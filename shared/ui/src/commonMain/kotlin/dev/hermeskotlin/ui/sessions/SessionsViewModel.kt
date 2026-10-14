package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.AuthUser
import dev.hermeskotlin.core.cache.OfflineCache
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.DraftStore
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.chat.Waiting
import dev.hermeskotlin.core.sessions.SeenChats
import dev.hermeskotlin.core.sessions.SeenStore
import kotlinx.coroutines.flow.combine
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.profiles.ProfileRoster
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.projects.FolderListing
import dev.hermeskotlin.core.projects.Project
import dev.hermeskotlin.core.projects.ProjectsApi
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.threads.ChatThread
import dev.hermeskotlin.core.threads.FreshChat
import dev.hermeskotlin.core.threads.ThreadsRepository
import dev.hermeskotlin.core.threads.movedPast
import dev.hermeskotlin.core.threads.ordered
import dev.hermeskotlin.core.threads.renamed
import dev.hermeskotlin.core.threads.withoutThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class SessionsUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val filter: SessionListFilter = SessionListFilter.Recent,
    /** Nothing loaded yet for this filter. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    /** The list itself failed to load. */
    val error: String? = null,
    /** When the rows shown were saved on this device (epoch ms): the gateway couldn't be read. Null once it has been. */
    val savedCopyAt: Long? = null,
    /** Results for the search box; null while it is empty. */
    val searchResults: List<SessionSummary>? = null,
    val searching: Boolean = false,
    /** One-off feedback for a failed row action, cleared by [SessionsViewModel.dismissMessage]. */
    val message: String? = null,
    val sessionExpired: Boolean = false,
    /** The last archive or unarchive, which "Undo" can take back for a few seconds. */
    val undo: ArchiveUndo? = null,
    /** The gateway's projects that have chats; empty when it has none or doesn't know projects. */
    val projects: List<Project> = emptyList(),
    /** The gateway answers `projects.*`, so a project can be made here even before there are any. */
    val canMakeProjects: Boolean = false,
    /** The project the recent list is narrowed to; null for every chat. */
    val project: Project? = null,
    /** [project]'s chats; null while they load (or with no project picked). */
    val projectSessions: List<SessionSummary>? = null,
) {
    /** The recent list as it shows: [project]'s chats, or every loaded chat. */
    val listed: List<SessionSummary> get() = if (project != null) projectSessions.orEmpty() else sessions
}

/**
 * [session] as it was before it was archived (or unarchived), where it sat in the list and in the search
 * results for [query], and the list it was in ([gateway], [profile], [filter]). [atMillis]: when the gateway
 * agreed, so the offer doesn't start over when the screen comes back.
 */
data class ArchiveUndo(
    val session: SessionSummary,
    val index: Int,
    val searchIndex: Int,
    /** The project the list was narrowed to, and where the row sat in it. */
    val projectId: String? = null,
    val projectIndex: Int = -1,
    val gateway: GatewayUrl? = null,
    val profile: String? = null,
    val filter: SessionListFilter = SessionListFilter.Recent,
    val query: String = "",
    val atMillis: Long = 0,
) {
    val message: String get() = if (session.archived) "Unarchived" else "Archived"

    /** What a screen reader says: which chat, too. */
    val spoken: String get() = "$message: ${session.displayTitle}"
}

/** The recent list narrowed to what's running, or to chats waiting on the user or with a reply not yet read. */
enum class AttentionFilter(val label: String) {
    All("All"),
    Running("Running"),
    NeedsAttention("Needs you"),
}

/** What a row says about its chat besides the title, in words. [running]: a turn is going right now. */
data class RowStatus(val waiting: Waiting? = null, val unread: Boolean = false, val running: Boolean = false) {
    val needsAttention: Boolean get() = waiting != null || unread
}

/**
 * Stored-session browser: list (REST, paged), search, and row actions. Refetches when the gateway
 * broadcasts `sessions.changed` / `session.title`, and after each reconnect (events may have been missed).
 * Marks chats waiting on the user ([AttentionTracker]) and ones with a reply not yet read ([SeenStore]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModel(
    private val api: SessionsApi,
    private val auth: AuthApi,
    private val connection: GatewayConnection,
    private val lastChats: LastChatStore,
    private val profiles: ProfilesApi,
    attention: AttentionTracker,
    private val seenStore: SeenStore,
    private val drafts: DraftStore,
    private val projectsApi: ProjectsApi,
    /** The threads, shared with the open chat so `/new` there starts its thread fresh. */
    private val threadsRepo: ThreadsRepository,
    /** The list as last read, shown while the gateway can't be. */
    private val cache: OfflineCache? = null,
) : ViewModel() {

    /** Stamps an archive's undo offer, so it runs out on time even if the screen goes and comes back. */
    internal var clock: Clock = Clock.System

    val connectionState: StateFlow<ConnectionState> = connection.state

    /** The profile's threads, in their order. */
    val threads: StateFlow<List<ChatThread>> = threadsRepo.threads

    private val bound = MutableStateFlow<Pair<GatewayUrl, String?>?>(null)

    private val _attentionFilter = MutableStateFlow(AttentionFilter.All)
    val attentionFilter: StateFlow<AttentionFilter> = _attentionFilter.asStateFlow()

    private val seen: StateFlow<SeenChats?> = bound
        .flatMapLatest { scope -> scope?.let { (url, profile) -> seenStore.seen(url, profile) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** How far [session] had been read before it is opened, when it has new messages since; read before [markSeen]. */
    fun readUpTo(session: SessionSummary): Double? = seen.value?.readUpTo(session)

    private val waiting = attention.waiting
    private val running = attention.running
    private val requestsByChat = attention.requestsByChat

    fun setAttentionFilter(filter: AttentionFilter) {
        _attentionFilter.value = filter
    }

    /** The user has looked at [session] as it is now: its reply is read. */
    fun markSeen(session: SessionSummary) {
        val (url, profile) = bound.value ?: return
        viewModelScope.launch { seenStore.markSeen(url, session, profile) }
    }

    private val _user = MutableStateFlow<AuthUser?>(null)
    val user: StateFlow<AuthUser?> = _user.asStateFlow()

    private val _roster = MutableStateFlow<ProfileRoster?>(null)

    /** The gateway's profiles; null until loaded (or when the gateway doesn't list them). */
    val roster: StateFlow<ProfileRoster?> = _roster.asStateFlow()

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    /** Whether the list is on screen ([setVisible]): the sidebar stays composed while closed. */
    private val visible = MutableStateFlow(false)

    /**
     * Each listed chat's status by id; chats with nothing to say are left out. Only listed chats get one:
     * the gateway's live list spans every profile. Worked out only while the list shows, so the gateway is
     * asked for live statuses only then; hidden, the last statuses stand.
     */
    val statuses: StateFlow<Map<String, RowStatus>> = visible.flatMapLatest { shown ->
        if (!shown) return@flatMapLatest emptyFlow()
        combine(_state, waiting, seen, running) { state, waiting, seen, running ->
            (state.sessions + state.searchResults.orEmpty() + state.projectSessions.orEmpty()).mapNotNull { session ->
                RowStatus(waiting[session.id], seen?.isUnread(session) == true, running[session.id] == true)
                    .takeIf { it.needsAttention || it.running }?.let { session.id to it }
            }.toMap()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptyMap())

    /** Whether the chat is on screen with the app in the foreground ([setChatShown]). */
    private val chatShown = MutableStateFlow(false)

    /** Every id the chat on screen goes by: it never counts as another chat. */
    private val openIds = MutableStateFlow<Set<String>>(emptySet())

    /** The bots' chats, which the list doesn't have (they're hidden sessions); waiting and running are filled in here. */
    private val botChats = MutableStateFlow<List<OtherChatRow>>(emptyList())

    /**
     * The list has been read since the chat came on screen, the gateway or profile changed, or the link
     * came back; until then the watch only takes stock, so what the read brings in isn't news.
     */
    private val freshList = MutableStateFlow(false)

    private val watch = OtherChatsWatch()

    /**
     * The recent list as last read, for the other chats. It stands while the archive shows or a list is being
     * read again: neither means the chats went away, and taking them out would drop what was news about them.
     */
    private val recentRows = MutableStateFlow<List<SessionSummary>>(emptyList())

    private val _others = MutableStateFlow(OtherChats())

    /** The chats other than the one on screen, as last worked out while the chat showed. */
    val others: StateFlow<OtherChats> = _others.asStateFlow()

    private val _badge = MutableStateFlow(SessionsBadge())

    /** What the Sessions button shows: chats waiting on the user and replies that are news, not every unread chat. */
    val badge: StateFlow<SessionsBadge> = _badge.asStateFlow()

    private val _banner = MutableStateFlow<OtherChatEvent?>(null)

    /** What newly happened in another chat, for the banner over the chat; null when there's nothing to say. */
    val banner: StateFlow<OtherChatEvent?> = _banner.asStateFlow()

    private val _otherChatEvents = MutableSharedFlow<OtherChatEvent>(extraBufferCapacity = 4)

    /** Each thing that newly happens in another chat, once, e.g. to buzz when one starts waiting. */
    val otherChatEvents: SharedFlow<OtherChatEvent> = _otherChatEvents.asSharedFlow()

    /**
     * The chat is on screen with the app in the foreground, or no longer is. Only then are the other chats
     * watched: that collects the live statuses, so the gateway's live list is polled while the chat shows,
     * not only while the sidebar does (and not at all in the background). Coming back takes stock afresh, so
     * what happened while away shows on the badge but never as a burst of banners.
     */
    fun setChatShown(shown: Boolean) {
        if (chatShown.value == shown) return
        if (shown) watch.reset() else watch.left(clock.now().toEpochMilliseconds() / 1000.0)
        freshList.value = false
        // The banner stays: a rotation passes through here, and its own timer takes away one gone stale meanwhile.
        chatShown.value = shown
        // The list may be old by now; the watch waits for this read before anything counts as new.
        if (shown) refreshQuietly()
    }

    /**
     * The chat on screen now goes by [ids] (a bot's chat by both of its ids); its banner, if any, goes. It leaves
     * the other chats at once, not after they settle, so the badge and Next don't send the user back to it.
     */
    fun setOpenChat(ids: Set<String>) {
        openIds.value = ids
        _banner.update { event -> event?.takeUnless { it.chatId in ids } }
        val others = OtherChats(_others.value.chats.filterKeys { it !in ids })
        _others.value = others
        _badge.value = watch.badge(others)
    }

    /** The bots' chats, from the roster, for the badge and the banner. */
    fun setBotChats(rows: List<OtherChatRow>) {
        botChats.value = rows
    }

    /**
     * Takes away the banner for [event]. Matched by chat and time, not by value: the preview fills in after the
     * banner shows, so a caller may still hold the event from before it did.
     */
    fun dismissBanner(event: OtherChatEvent) {
        _banner.update { if (it != null && it.chatId == event.chatId && it.atMillis == event.atMillis) null else it }
    }

    /** The next other chat needing the user: requests oldest first, then replies oldest first; null when none does. */
    fun nextChat(): OtherChat? = watch.queue(_others.value).firstOrNull { it.id !in openIds.value }

    /**
     * How far the listed chat [id] had been read, taken before it counts as read now, as a sidebar tap does,
     * for opening it from elsewhere (the banner, Next). Null when it isn't listed or has nothing new.
     */
    fun openListed(id: String): Double? {
        val state = _state.value
        // The recent rows too: the sidebar may show the archive, and the chat is in the recent list.
        val session = (state.sessions + state.projectSessions.orEmpty() + recentRows.value).firstOrNull { it.id == id } ?: return null
        val readUpTo = readUpTo(session)
        markSeen(session)
        return readUpTo
    }

    /** Whether a reconnect (or coming back) has settled: connected, the list read again, and a moment for the live statuses. */
    private val settled = combine(chatShown, connection.state.map { it is ConnectionState.Connected }.distinctUntilChanged(), bound) { shown, connected, _ ->
        shown && connected
    }.flatMapLatest { live ->
        flow {
            emit(false)
            if (!live) return@flow
            freshList.first { it }
            delay(SETTLE_MS)
            emit(true)
        }
    }

    @OptIn(FlowPreview::class)
    private fun watchOtherChats() {
        viewModelScope.launch {
            // Only the recent list, once read: the archive showing, or the list emptied while it is read again
            // (a filter changed back), shouldn't make every chat leave and come back.
            _state.collect { state ->
                if (state.filter == SessionListFilter.Recent && !state.loading) {
                    recentRows.value = (state.sessions + state.projectSessions.orEmpty()).distinctBy { it.id }
                }
            }
        }
        viewModelScope.launch {
            chatShown.flatMapLatest { shown ->
                if (!shown) return@flatMapLatest emptyFlow()
                val listed = combine(recentRows, seen, waiting, running) { sessions, seen, waiting, running ->
                    Listed(
                        sessions.map { s ->
                            OtherChatRow(OtherChat(s.id, s.displayTitle, waiting[s.id], seen?.isUnread(s) == true, running[s.id] == true, s.activityAt))
                        },
                        waiting,
                        running,
                    )
                }
                combine(listed, botChats, openIds, settled) { listed, bots, open, settled ->
                    val botRows = bots.map { row ->
                        row.copy(
                            chat = row.chat.copy(
                                waiting = row.ids.firstNotNullOfOrNull { listed.waiting[it] },
                                running = row.ids.any { listed.running[it] == true },
                            ),
                        )
                    }
                    OtherChats.of(listed.rows + botRows, open) to settled
                }
                // The list and the live statuses move separately (a turn's end bumps the list before the poll
                // says it stopped): let a change settle, so a turn just starting doesn't read as a reply.
                .debounce(OTHERS_SETTLE_MS)
            }.collect { (others, settled) ->
                _others.value = others
                val event = watch.update(others, clock.now().toEpochMilliseconds(), settled)
                _badge.value = watch.badge(others)
                // A banner for something since dealt with (answered, read, archived) goes.
                _banner.update { shown -> shown?.takeIf { others.stillTrue(it) } }
                if (event != null) {
                    previewJob?.cancel()
                    // What is asked is already here; a reply's text is read from the gateway just after.
                    val shown = if (event.waiting) event.copy(preview = previewOf(requestsByChat.value[event.chatId])) else event
                    _banner.value = shown
                    _otherChatEvents.tryEmit(shown)
                    if (!shown.waiting) fillReplyPreview(shown)
                }
            }
        }
    }

    /**
     * Reads the newest row of the chat [event] is about and puts the start of its reply into the banner, if that
     * banner still shows. A Bot Chat lives in its bot's profile. Failing, the banner keeps just its title.
     */
    private fun fillReplyPreview(event: OtherChatEvent) {
        val url = gateway?.gatewayUrl ?: return
        val profile = event.bot ?: this.profile
        // One read at a time: a newer banner replaces the one this was for, and another gateway's banner
        // must never get the last one's words (two banners can share a chat id and a millisecond).
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val page = api.messages(url, event.chatId, limit = PREVIEW_ROWS, profile = profile) as? ApiResult.Success ?: return@launch
            val reply = page.value.messages.lastOrNull { it.role == "assistant" && !it.isHidden && it.text.isNotBlank() }
            val preview = previewOf(reply?.text) ?: return@launch
            _banner.update { shown -> if (shown == event) shown.copy(preview = preview) else shown }
        }
    }

    /** The read filling in the banner's preview, if one is under way. */
    private var previewJob: Job? = null

    private class Listed(val rows: List<OtherChatRow>, val waiting: Map<String, Waiting>, val running: Map<String, Boolean>)

    /** The list is on screen (the sidebar open or docked), or no longer is. */
    fun setVisible(shown: Boolean) {
        visible.value = shown
        if (shown && _state.value.filter == SessionListFilter.Recent) loadProjects()
    }

    val query = TextFieldState()

    private var gateway: SavedGateway? = null

    /** The profile whose sessions are listed; null is the gateway's launch profile. */
    private var profile: String? = null
    private var loadJob: Job? = null

    /** When the rows showing were saved on the device (epoch ms); null once the gateway's list replaced them. */
    private var savedShownAt: Long? = null

    /** Sessions with unsent text in their composer, marked "Draft" in the list. */
    val draftChats: StateFlow<Set<String>> = bound
        .flatMapLatest { scope -> scope?.let { (url, profile) -> drafts.chatsWithDrafts(url, profile) } ?: flowOf(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        observeServerChanges()
        observeSearch()
        watchOtherChats()
    }

    /** Idempotent: binds to [gateway] and [profile] and loads the first page once. */
    fun bind(gateway: SavedGateway, profile: String? = null) {
        if (this.gateway == gateway && this.profile == profile) return
        val newGateway = this.gateway != gateway
        this.gateway = gateway
        this.profile = profile
        bound.value = gateway.gatewayUrl to profile
        threadsRepo.bind(gateway.gatewayUrl, profile)
        _state.value = SessionsUiState()
        savedShownAt = null
        // The last list's projects mean nothing here; ask afresh.
        projectsJob?.cancel()
        projectSessionsJob?.cancel()
        projectsAskedAt = 0
        projectsAgain = false
        selectAfterLoad = null
        projectChangesMissing = false
        // Another gateway or profile: its chats are other chats, and what was said about the last ones goes.
        watch.clear()
        freshList.value = false
        _others.value = OtherChats()
        _badge.value = SessionsBadge()
        _banner.value = null
        previewJob?.cancel()
        recentJob?.cancel()
        recentRows.value = emptyList()
        // Bots are the gateway's, not the profile's: the roster doesn't change with the profile, so nothing would
        // hand them back after a profile switch. Another gateway's roster replaces them as it loads.
        if (newGateway) botChats.value = emptyList()
        load(refresh = false)
        if (newGateway) {
            _user.value = null
            _roster.value = null
            viewModelScope.launch {
                (auth.me(gateway.gatewayUrl) as? ApiResult.Success)?.let { _user.value = it.value }
            }
            refreshProfiles()
        }
    }

    /** Refetches the profile list, e.g. when the account sheet opens (one may have been added on the host). */
    fun refreshProfiles() {
        val url = gateway?.gatewayUrl ?: return
        viewModelScope.launch {
            (profiles.roster(url) as? ApiResult.Success)?.let { _roster.value = it.value }
        }
    }

    fun refresh() = load(refresh = true)

    /** Background refetch without the spinner, e.g. when the list becomes visible again. */
    fun refreshQuietly() = load(refresh = false)

    fun setFilter(filter: SessionListFilter) {
        if (filter == _state.value.filter) return
        // The picked project stays for the way back; the archive isn't split by project.
        savedShownAt = null
        _state.update {
            SessionsUiState(
                filter = filter,
                projects = it.projects,
                canMakeProjects = it.canMakeProjects,
                project = it.project,
                projectSessions = it.projectSessions,
            )
        }
        load(refresh = false)
    }

    /** Narrows the recent list to [project]'s chats, or shows every chat again with null. */
    fun selectProject(project: Project?) {
        if (project?.id == _state.value.project?.id) return
        _state.update { it.copy(project = project, projectSessions = null) }
        loadProjectSessions()
    }

    /**
     * Makes a project named [name] over [folder] and shows its chats; [onDone] runs once it's made, or with the
     * gateway's reason it wasn't (a folder another project has, say), so a form can stay open on the error.
     */
    fun createProject(name: String, folder: String?, onDone: (error: String?) -> Unit) {
        val scope = bound.value
        viewModelScope.launch {
            var id: String? = null
            val error = projectCall { id = projectsApi.create(scope?.second, name, folder) }
            onDone(error)
            if (error == null && bound.value == scope) {
                // Saving a folder as a project gives it a new id, so the picked chip would drop without this.
                selectAfterLoad = id
                loadProjects(force = true)
            }
        }
    }

    /** Renames a project the user made; the chip shows the new name at once and goes back if the gateway says no. */
    fun renameProject(project: Project, name: String) {
        val scope = bound.value
        val label = name.trim()
        _state.update { it.withProjectLabel(project.id, label) }
        viewModelScope.launch {
            val error = projectCall { projectsApi.rename(scope?.second, project.id, name) }
            // Put the old name back here: the tree below isn't asked while the gateway is away.
            if (error != null) _state.update { it.withProjectLabel(project.id, project.label, unless = label).copy(message = error) }
            if (bound.value == scope) loadProjects(force = true)
        }
    }

    /** Deletes a project the user made. Its chats stay; the list shows every chat again if it was narrowed to it. */
    fun deleteProject(project: Project) {
        val scope = bound.value
        viewModelScope.launch {
            val error = projectCall { projectsApi.delete(scope?.second, project.id) }
            if (error != null) {
                _state.update { it.copy(message = error) }
                return@launch
            }
            _state.update { state ->
                val wasOpen = state.project?.id == project.id
                state.copy(
                    projects = state.projects.filterNot { it.id == project.id },
                    project = state.project.takeUnless { wasOpen },
                    projectSessions = state.projectSessions.takeUnless { wasOpen },
                )
            }
            if (bound.value == scope) loadProjects(force = true)
        }
    }

    /** The folders in [dir] on the gateway's machine starting with [prefix], for picking a project's folder; throws when it can't ask. */
    suspend fun projectFolders(dir: String, prefix: String): FolderListing =
        projectsApi.folders(bound.value?.second, dir, prefix)

    /** [id]'s chip and the picked project renamed to [label]; with [unless], only while they still show that. */
    private fun SessionsUiState.withProjectLabel(id: String, label: String, unless: String? = null): SessionsUiState {
        fun Project.relabeled() = if (this.id == id && (unless == null || this.label == unless)) copy(label = label) else this
        return copy(projects = projects.map { it.relabeled() }, project = project?.relabeled())
    }

    /** Runs a project change; null when it went through, else what to tell the user. */
    private suspend fun projectCall(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: RpcException) {
        if (e.code == METHOD_NOT_FOUND) {
            // The tree answers but changes don't: stop offering them.
            projectChangesMissing = true
            _state.update { it.copy(canMakeProjects = false) }
            "This gateway can't change projects."
        } else {
            e.message?.takeIf { it.isNotBlank() } ?: "Couldn't change the project."
        }
    } catch (e: Exception) {
        e.message?.takeIf { it.isNotBlank() } ?: "Couldn't change the project."
    }

    /** A project just made, to pick once the tree shows it. */
    private var selectAfterLoad: String? = null

    /** The gateway turned down a project change as unknown, so New project, Rename and Delete stay hidden. */
    private var projectChangesMissing = false

    private var projectsJob: Job? = null
    private var projectSessionsJob: Job? = null

    /** When the projects were last asked for (epoch ms), to ask at most every [PROJECTS_EVERY_MS] on changes. */
    private var projectsAskedAt = 0L

    /** A change came in while the projects were being asked for; ask once more when that answer is in. */
    private var projectsAgain = false

    /**
     * The project list, again, since no event says a chat moved between projects. The gateway groups the
     * newest sessions each time, so this is asked only while the list shows, at most every
     * [PROJECTS_EVERY_MS] for a change, and at once on connect, on opening the list, or with [force].
     */
    private fun loadProjects(force: Boolean = false) {
        if (connection.state.value !is ConnectionState.Connected) return
        if (!force && !visible.value && _state.value.projects.isNotEmpty()) return
        val now = getTimeMillis()
        if (!force && now - projectsAskedAt < PROJECTS_EVERY_MS) return
        if (projectsJob?.isActive == true) {
            projectsAgain = true
            return
        }
        val scope = bound.value
        projectsAskedAt = now
        projectsJob = viewModelScope.launch {
            var supported = true
            val projects = try {
                projectsApi.projects(scope?.second)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcException) {
                // A gateway that doesn't know projects: no chips. Any other failure keeps what shows.
                supported = e.code != METHOD_NOT_FOUND
                if (e.code == METHOD_NOT_FOUND) emptyList() else null
            } catch (_: Exception) {
                null
            }
            if (projects != null && bound.value == scope) {
                // Only worth a filter when some chats are in a project, not all in Home.
                val shown = projects.takeIf { list -> list.any { !it.isNoProject } }.orEmpty()
                val made = selectAfterLoad
                _state.update { state ->
                    val picked = made?.let { id -> shown.firstOrNull { it.id == id } }
                        ?: state.project?.let { p -> shown.firstOrNull { it.id == p.id } }
                    state.copy(
                        projects = shown,
                        canMakeProjects = supported && !projectChangesMissing,
                        project = picked,
                        projectSessions = state.projectSessions.takeIf { picked != null && picked.id == state.project?.id },
                    )
                }
                // An older answer may not have the new project yet; the next one will.
                if (made != null && _state.value.project?.id == made) selectAfterLoad = null
                loadProjectSessions()
            }
            if (projectsAgain) {
                projectsAgain = false
                loadProjects(force = true)
            }
        }
    }

    private fun loadProjectSessions() {
        val project = _state.value.project ?: return
        val scope = bound.value
        // A newer ask replaces an older one, so a late answer can't bring back a row since archived.
        projectSessionsJob?.cancel()
        projectSessionsJob = viewModelScope.launch {
            val rows = try {
                projectsApi.sessions(scope?.second, project.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (bound.value == scope && it.project?.id == project.id) {
                        it.copy(projectSessions = it.projectSessions ?: emptyList(), message = e.message)
                    } else {
                        it
                    }
                }
                return@launch
            }
            _state.update { state ->
                if (bound.value != scope || state.project?.id != project.id) return@update state
                // The project's rows don't say which chats are pinned; the main list (pinned ones included) does.
                val pinned = state.sessions.filter { it.pinned }.mapTo(HashSet()) { it.id }
                state.copy(projectSessions = rows.map { if (it.id in pinned) it.copy(pinned = true) else it })
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        val url = gateway?.gatewayUrl ?: return
        if (!current.canLoadMore || current.loadingMore || loadJob?.isActive == true) return
        _state.update { it.copy(loadingMore = true) }
        loadJob = viewModelScope.launch {
            val result = api.list(url, offset = current.sessions.size, filter = current.filter, profile = profile)
            _state.update { state ->
                when (result) {
                    is ApiResult.Success -> {
                        val known = state.sessions.mapTo(HashSet()) { it.id }
                        val fresh = result.value.sessions.filter { it.id !in known }
                        val merged = state.sessions + fresh
                        // A page with nothing new means the server's total counts rows the list never returns.
                        state.copy(sessions = merged, loadingMore = false, canLoadMore = fresh.isNotEmpty() && merged.size < result.value.total)
                    }
                    else -> state.copy(loadingMore = false, message = result.errorMessage, sessionExpired = result.isExpired)
                }
            }
        }
    }

    fun retryConnection() = connection.retry()

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Clears the expiry flag once a screen has acted on it; the view model outlives the screen, so
     *  leaving it set would bounce the next mount after a fresh sign-in. */
    fun consumeSessionExpired() = _state.update { it.copy(sessionExpired = false) }

    fun rename(session: SessionSummary, title: String) = mutate(
        apply = { list -> list.map { if (it.id == session.id) it.copy(title = title.ifBlank { null }) else it } },
        call = { url, profile -> api.rename(url, session.id, title.trim(), profile) },
    )

    fun togglePinned(session: SessionSummary) = mutate(
        apply = { list -> list.map { if (it.id == session.id) it.copy(pinned = !session.pinned) else it } },
        call = { url, profile -> api.setPinned(url, session.id, !session.pinned, profile) },
    )

    /**
     * Archiving moves the row to the other filter, so it leaves the current list either way. Once the
     * gateway agrees, [SessionsUiState.undo] offers to put it back.
     */
    fun toggleArchived(session: SessionSummary) {
        val before = _state.value
        val index = before.sessions.indexOfFirst { it.id == session.id }
        val searchIndex = before.searchResults?.indexOfFirst { it.id == session.id } ?: -1
        val projectIndex = before.projectSessions?.indexOfFirst { it.id == session.id } ?: -1
        // A chat in no list (opened from a notification, or from the archive and then this list) is only
        // a guess as to whether it's archived; undoing that guess could unarchive a chat the user archived.
        val undo = if (index < 0 && searchIndex < 0 && projectIndex < 0) null else ArchiveUndo(
            session = session,
            index = index,
            searchIndex = searchIndex,
            projectId = before.project?.id,
            projectIndex = projectIndex,
            gateway = gateway?.gatewayUrl,
            profile = profile,
            filter = before.filter,
            query = query.text.toString(),
            atMillis = clock.now().toEpochMilliseconds(),
        )
        mutate(
            apply = { list -> list.filterNot { it.id == session.id } },
            call = { url, profile -> api.setArchived(url, session.id, !session.archived, profile) },
            // Not when the list moved on (another profile, gateway or filter) while the gateway answered.
            onSuccess = { if (undo != null && isCurrent(undo)) _state.update { it.copy(undo = undo) } },
        )
    }

    /** Takes the last archive or unarchive back: the row returns to where it was. */
    fun undoArchive() {
        val undo = _state.value.undo ?: return
        _state.update { it.copy(undo = null) }
        if (!isCurrent(undo)) return
        val session = undo.session
        // A reload that started before the undo would bring back the list without the row.
        loadJob?.cancel()
        val sameSearch = query.text.toString() == undo.query
        val sameProject = _state.value.project?.id == undo.projectId
        mutate(
            apply = { list -> if (list.any { it.id == session.id }) list else list.withRowAt(session, undo.index) },
            applySearch = { list ->
                if (!sameSearch || undo.searchIndex < 0 || list.any { it.id == session.id }) list else list.withRowAt(session, undo.searchIndex)
            },
            applyProject = { list ->
                if (!sameProject || undo.projectIndex < 0 || list.any { it.id == session.id }) list else list.withRowAt(session, undo.projectIndex)
            },
            call = { url, profile -> api.setArchived(url, session.id, session.archived, profile) },
        )
    }

    private fun isCurrent(undo: ArchiveUndo): Boolean =
        undo.gateway == gateway?.gatewayUrl && undo.profile == profile && undo.filter == _state.value.filter

    fun dismissUndo() = _state.update { it.copy(undo = null) }

    private fun List<SessionSummary>.withRowAt(row: SessionSummary, index: Int): List<SessionSummary> =
        if (index < 0) this else toMutableList().apply { add(index.coerceAtMost(size), row) }

    fun delete(session: SessionSummary) = mutate(
        apply = { list -> list.filterNot { it.id == session.id } },
        call = { url, profile ->
            api.delete(url, session.id, profile).also {
                if (it is ApiResult.Success) {
                    lastChats.forget(url, session.id, profile)
                    cache?.forgetChat(url, profile, session.id)
                }
            }
        },
    )

    /** Makes [session] a thread at the end of the Threads section; its chat is pinned so the gateway keeps it. */
    fun makeThread(session: SessionSummary) = threadAction("Couldn't make a thread.") {
        threadsRepo.makeThread(session)
        _state.update { state ->
            fun List<SessionSummary>.pinnedRow() = map { if (it.id == session.id) it.copy(pinned = true) else it }
            state.copy(sessions = state.sessions.pinnedRow(), projectSessions = state.projectSessions?.pinnedRow())
        }
    }

    /** Takes [thread] out of the Threads section; its chat stays, as a pinned chat. */
    fun removeThread(thread: ChatThread) = threadAction("Couldn't remove the thread.") {
        threadsRepo.change { it.withoutThread(thread.id) }
    }

    /** Names [thread] [name]; its chat is retitled to match. */
    fun renameThread(thread: ChatThread, name: String) = threadAction("Couldn't rename the thread.") {
        threadsRepo.rename(thread, name.trim())
        load(refresh = false)
    }

    /** Moves [thread] one place up or down, past [neighbour]: the thread next to it in the section as shown. */
    fun moveThread(thread: ChatThread, neighbour: ChatThread) = threadAction("Couldn't move the thread.") {
        threadsRepo.change { it.movedPast(thread.id, neighbour.id) }
    }

    /** Saves the threads in the order of [ids], as a drag left them. */
    fun reorderThreads(ids: List<String>) = threadAction("Couldn't save the order.") {
        threadsRepo.change { it.ordered(ids) }
    }

    /**
     * Starts [thread] fresh: its chat is filed away in the archive under the thread's name and the time, and a new
     * one takes its place, which [onOpened] opens.
     */
    fun startFresh(thread: ChatThread, onOpened: (FreshChat) -> Unit) = threadAction("Couldn't start the thread fresh.") {
        val fresh = threadsRepo.startFresh(thread)
        onOpened(fresh)
        load(refresh = false)
    }

    /** Runs a thread change; a failure is said in the list's message, [fallback] when it has no words of its own. */
    private fun threadAction(fallback: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message?.takeIf { m -> m.isNotBlank() } ?: fallback) }
            }
        }
    }

    /** Optimistic row update: apply locally (list, search results, project), call the server, roll back on failure. */
    private fun mutate(
        apply: (List<SessionSummary>) -> List<SessionSummary>,
        call: suspend (GatewayUrl, String?) -> ApiResult<Unit>,
        applySearch: (List<SessionSummary>) -> List<SessionSummary> = apply,
        applyProject: (List<SessionSummary>) -> List<SessionSummary> = apply,
        onSuccess: () -> Unit = {},
    ) {
        val url = gateway?.gatewayUrl ?: return
        val profile = profile
        val before = _state.value
        _state.update {
            it.copy(
                sessions = apply(it.sessions),
                searchResults = it.searchResults?.let(applySearch),
                projectSessions = it.projectSessions?.let(applyProject),
            )
        }
        viewModelScope.launch {
            val result = call(url, profile)
            if (result is ApiResult.Success) {
                onSuccess()
            } else {
                _state.update {
                    it.copy(
                        sessions = before.sessions,
                        searchResults = before.searchResults,
                        projectSessions = before.projectSessions.takeIf { _ -> it.project?.id == before.project?.id } ?: it.projectSessions,
                        message = result.errorMessage,
                        sessionExpired = result.isExpired,
                    )
                }
            }
        }
    }

    private fun load(refresh: Boolean) {
        val url = gateway?.gatewayUrl ?: return
        val profile = profile
        val filter = _state.value.filter
        // This may cancel a page fetch mid-flight, which would otherwise leave its spinner up for good.
        loadJob?.cancel()
        _state.update { (if (refresh) it.copy(refreshing = true) else it.copy(loading = it.sessions.isEmpty())).copy(loadingMore = false) }
        // Pull to refresh asks at once; a change from the gateway waits its turn.
        if (filter == SessionListFilter.Recent) loadProjects(force = refresh)
        loadJob = viewModelScope.launch {
            // Nothing showing yet: the list as last read, while the gateway is asked.
            val saved = if (_state.value.sessions.isEmpty()) cache?.savedList(url, profile, filter) else null
            if (saved != null) {
                _state.update { state ->
                    if (state.filter != filter || state.sessions.isNotEmpty() || bound.value != url to profile) {
                        state
                    } else {
                        savedShownAt = saved.savedAt
                        state.copy(sessions = saved.sessions, loading = false, canLoadMore = false)
                    }
                }
            }
            // Keep however many rows are already showing so a background refetch doesn't truncate the list.
            val limit = _state.value.sessions.size.coerceIn(SessionsApi.PAGE_SIZE, 100)
            val result = api.list(url, limit = limit, filter = filter, profile = profile)
            if (result is ApiResult.Success) cache?.saveList(url, profile, filter, result.value.sessions)
            if (result is ApiResult.Success && filter == SessionListFilter.Recent) {
                threadsRepo.noteRows(url, profile, result.value.sessions)
                // Threads come over the socket; another device may have changed them.
                if (connection.state.value is ConnectionState.Connected) viewModelScope.launch { threadsRepo.refresh(force = refresh) }
            }
            _state.update { state ->
                if (state.filter != filter) return@update state
                if (result is ApiResult.Success) savedShownAt = null
                when (result) {
                    is ApiResult.Success -> state.copy(
                        sessions = result.value.sessions,
                        loading = false,
                        refreshing = false,
                        error = null,
                        canLoadMore = result.value.sessions.size < result.value.total,
                        savedCopyAt = null,
                    )
                    else -> state.copy(
                        loading = false,
                        refreshing = false,
                        // A failed background refresh keeps the rows; only an empty list shows the error.
                        error = result.errorMessage.takeIf { state.sessions.isEmpty() },
                        message = result.errorMessage.takeIf { state.sessions.isNotEmpty() && refresh },
                        sessionExpired = result.isExpired,
                        savedCopyAt = savedShownAt,
                    )
                }
            }
            if (result is ApiResult.Success) freshList.value = true
        }
        loadRecentForOthers(url, profile, filter)
    }

    private var recentJob: Job? = null

    /**
     * While the sidebar shows another list (the archive), the recent chats are read too, on the same triggers as
     * the list (a change on the gateway, a reconnect, the chat coming back), so a reply in one still reaches the
     * badge, the banner and Next. Only while the chat shows: nothing is read for this in the background. Only the
     * plain recent list; a picked project's chats wait for Recent to show again. The sidebar's state is untouched.
     */
    private fun loadRecentForOthers(url: GatewayUrl, profile: String?, filter: SessionListFilter) {
        recentJob?.cancel()
        if (filter == SessionListFilter.Recent || !chatShown.value) return
        recentJob = viewModelScope.launch {
            val limit = recentRows.value.size.coerceIn(SessionsApi.PAGE_SIZE, 100)
            val result = api.list(url, limit = limit, filter = SessionListFilter.Recent, profile = profile)
            if (result !is ApiResult.Success || bound.value != url to profile) return@launch
            // Recent may have come back meanwhile; then its own read speaks for the recent chats.
            if (_state.value.filter == SessionListFilter.Recent) return@launch
            recentRows.value = result.value.sessions
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeServerChanges() {
        viewModelScope.launch {
            connection.events
                .filter { it.type in REFRESH_EVENTS }
                .debounce(400)
                .collect { load(refresh = false) }
        }
        // Projects come over the socket, which may connect after the list (REST) has loaded.
        viewModelScope.launch {
            connection.state.map { it is ConnectionState.Connected }.distinctUntilChanged().filter { it }.collect {
                if (gateway != null && _state.value.filter == SessionListFilter.Recent) loadProjects(force = true)
                if (gateway != null) launch { threadsRepo.refresh(force = true) }
            }
        }
        viewModelScope.launch {
            var wasConnected = true
            connection.state.collect { state ->
                val connected = state is ConnectionState.Connected
                if (!connected) freshList.value = false
                if (connected && !wasConnected) load(refresh = false)
                wasConnected = connected
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            snapshotFlow { query.text.toString().trim() }
                .distinctUntilChanged()
                .map { it.takeIf { q -> q.length >= MIN_QUERY } }
                .debounce { if (it == null) 0 else 300 }
                .collectLatest { q ->
                    val url = gateway?.gatewayUrl
                    if (q == null || url == null) {
                        _state.update { it.copy(searchResults = null, searching = false) }
                        return@collectLatest
                    }
                    _state.update { it.copy(searching = true) }
                    val result = api.search(url, q, profile = profile)
                    _state.update {
                        when (result) {
                            is ApiResult.Success -> it.copy(searchResults = result.value, searching = false)
                            else -> it.copy(
                                searchResults = emptyList(),
                                searching = false,
                                message = result.errorMessage,
                                sessionExpired = result.isExpired,
                            )
                        }
                    }
                }
        }
    }

    private companion object {
        /** Rows read for a reply's preview: the newest few, as tool rows can follow the reply's text. */
        const val PREVIEW_ROWS = 6

        val REFRESH_EVENTS = setOf("sessions.changed", "session.title")
        const val MIN_QUERY = 2

        /** Brief gaps (a screen rotating) keep the statuses collected. */
        const val STOP_AFTER_MS = 5_000L

        /** After a reconnect's list read, how long the live statuses get to catch up before changes count as news. */
        const val SETTLE_MS = 3_000L

        /** How long the other chats must hold still before a change in them counts. */
        const val OTHERS_SETTLE_MS = 1_500L

        /** How often a change on the gateway may ask for the projects again; a turn changes sessions every 2 s. */
        const val PROJECTS_EVERY_MS = 30_000L

        /** JSON-RPC's "method not found": a gateway from before projects. */
        const val METHOD_NOT_FOUND = -32601
    }

    private fun getTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
}

private val ApiResult<*>.isExpired: Boolean get() = this == ApiResult.SessionExpired
