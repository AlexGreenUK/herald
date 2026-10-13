package dev.hermeskotlin.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.inbox.ChipMode
import dev.hermeskotlin.core.inbox.Follow
import dev.hermeskotlin.core.inbox.Following
import dev.hermeskotlin.core.inbox.InboxApi
import dev.hermeskotlin.core.inbox.InboxGroups
import dev.hermeskotlin.core.inbox.InboxLayout
import dev.hermeskotlin.core.inbox.InboxRun
import dev.hermeskotlin.core.inbox.InboxStore
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushSetup
import dev.hermeskotlin.core.push.PushStatus
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** What the Inbox list shows: every run, only the failed ones, one job's, or one of the user's groups. */
sealed interface InboxFilter {
    data object All : InboxFilter
    data object Failed : InboxFilter
    data class Job(val bot: String, val job: String) : InboxFilter

    /** A group the user made; [name] null is "Other", the jobs in no group. */
    data class Group(val name: String?) : InboxFilter
}

/**
 * A job's chip: what it filters to, the name it shows ([label]: the user's, else the job's own), the job's own
 * name ([own]), and how many of its runs are unread.
 */
data class JobChip(val filter: InboxFilter.Job, val label: String, val runs: Int, val unread: Int, val own: String = label) {
    val renamed: Boolean get() = label != own
}

/** A group's chip: what it filters to, its name, and how many of its runs are unread. */
data class GroupChip(val filter: InboxFilter.Group, val label: String, val runs: Int, val unread: Int)

/**
 * A chip per group the user made, in their order, then "Other" for the runs of jobs in no group. Groups with no
 * runs in the Inbox are left out, and "Other" only shows when there is a group to tell it from.
 */
fun groupChips(runs: List<InboxRun>, groups: InboxGroups, isUnread: (InboxRun) -> Boolean): List<GroupChip> {
    val byGroup = runs.groupBy { groups.of(it.bot, it.job) }
    val made = groups.names.mapNotNull { name ->
        byGroup[name]?.let { GroupChip(InboxFilter.Group(name), name, it.size, it.count(isUnread)) }
    }
    val other = byGroup[null]?.takeIf { made.isNotEmpty() }?.let { GroupChip(InboxFilter.Group(null), "Other", it.size, it.count(isUnread)) }
    return made + listOfNotNull(other)
}

/**
 * One chip per job with runs in the Inbox, the job with the newest run first, so the job that just reported is
 * at hand. Each job is named by its newest run's label, the name it goes by now.
 */
fun jobChips(runs: List<InboxRun>, groups: InboxGroups = InboxGroups(), isUnread: (InboxRun) -> Boolean): List<JobChip> =
    runs.groupBy { it.bot to it.job }
        .map { (id, ofJob) ->
            val newest = ofJob.maxBy { it.at }
            JobChip(InboxFilter.Job(id.first, id.second), groups.nameOf(newest), ofJob.size, ofJob.count(isUnread), own = newest.title)
        }
        .sortedByDescending { chip -> runs.first { it.bot == chip.filter.bot && it.job == chip.filter.job }.at }

/**
 * A folder on the Inbox's front page: a job's reports (or, split by group, a group's), its name, how many and how
 * many unread, and the newest one, whose time and opening words the folder shows.
 */
data class InboxFolder(val filter: InboxFilter, val label: String, val runs: Int, val unread: Int, val newest: InboxRun, val failed: Int)

/**
 * The Inbox's folders: per group when the chips split by group and there are groups (in the user's order, then
 * Other), else per job; either way the folder that reported last comes first, so what's new is on top.
 */
fun inboxFolders(runs: List<InboxRun>, groups: InboxGroups, isUnread: (InboxRun) -> Boolean): List<InboxFolder> {
    val byGroup = groups.mode == ChipMode.Groups && groupChips(runs, groups, isUnread).isNotEmpty()
    val folders = if (byGroup) {
        groupChips(runs, groups, isUnread).map { chip ->
            val ofGroup = runs.filtered(chip.filter, groups)
            InboxFolder(chip.filter, chip.label, chip.runs, chip.unread, ofGroup.maxBy { it.at }, ofGroup.count { it.failed })
        }
    } else {
        jobChips(runs, groups, isUnread).map { chip ->
            val ofJob = runs.filtered(chip.filter)
            InboxFolder(chip.filter, chip.label, chip.runs, chip.unread, ofJob.maxBy { it.at }, ofJob.count { it.failed })
        }
    }
    return folders.sortedByDescending { it.newest.at }
}

/** The runs [filter] keeps, in their order. */
fun List<InboxRun>.filtered(filter: InboxFilter, groups: InboxGroups = InboxGroups()): List<InboxRun> = when (filter) {
    InboxFilter.All -> this
    InboxFilter.Failed -> filter { it.failed }
    is InboxFilter.Job -> filter { it.bot == filter.bot && it.job == filter.job }
    is InboxFilter.Group -> filter { groups.of(it.bot, it.job) == filter.name }
}

/** Why the Inbox can't fetch from the gateway: it then shows what it kept, with this said once. */
enum class InboxBlock {
    /** Notifications anywhere is off: the Inbox goes by this phone's push registration. */
    PushOff,

    /** The gateway's herald-push plugin is older than the Inbox needs. */
    PluginOld,
}

data class InboxUiState(
    val runs: List<InboxRun> = emptyList(),
    val read: Set<String> = emptySet(),
    val since: Long = 0,
    val groups: InboxGroups = InboxGroups(),
    /** The groups page: make, rename and remove groups, and put jobs in them. */
    val grouping: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val block: InboxBlock? = null,
    val filter: InboxFilter = InboxFilter.All,
    /** The run open in full; null shows the list. */
    val open: InboxRun? = null,
    val openLoading: Boolean = false,
    /** The open run's text is only the start: the gateway couldn't be asked for the rest. */
    val openPartial: Boolean = false,
    /** The follow page: every job with how it reaches the Inbox. */
    val choosing: Boolean = false,
    val jobs: List<CronJob> = emptyList(),
    val jobsLoading: Boolean = false,
    val following: Following = Following(),
    val saving: Boolean = false,
    val message: String? = null,
) {
    fun isUnread(run: InboxRun): Boolean = run.key !in read && run.at > since
    val unread: Int get() = runs.count(::isUnread)
    val chips: List<JobChip> get() = jobChips(runs, groups, ::isUnread)
    val folders: List<InboxFolder> get() = inboxFolders(runs, groups, ::isUnread)

    /** The front page shows folders, rather than a folder or a filter opened from it. */
    val atFolders: Boolean get() = groups.layout == InboxLayout.Folders && shownFilter == InboxFilter.All

    /** The name [run]'s job shows by: the user's, else its own. */
    fun nameOf(run: InboxRun): String = groups.nameOf(run)
    val groupChips: List<GroupChip> get() = groupChips(runs, groups, ::isUnread)
    val failed: Int get() = runs.count { it.failed }

    /**
     * The filter in force: one that no longer fits (its job left the Inbox, its group was removed, or the chips
     * now split the other way) shows everything again.
     */
    val shownFilter: InboxFilter get() = filter.takeIf { f ->
        when (f) {
            InboxFilter.All, InboxFilter.Failed -> true
            is InboxFilter.Job -> groups.mode == ChipMode.Jobs && runs.any { it.bot == f.bot && it.job == f.job }
            is InboxFilter.Group -> groups.mode == ChipMode.Groups && groupChips.any { it.filter == f }
        }
    } ?: InboxFilter.All
    val shown: List<InboxRun> get() = runs.filtered(shownFilter, groups)

    /** Every job with runs in the Inbox, for the groups page: its chip and the group it's in. */
    val groupableJobs: List<Pair<JobChip, String?>> get() = chips.map { it to groups.of(it.filter.bot, it.filter.job) }
}

/**
 * The Inbox: finished runs of the cron jobs this phone follows, newest first. The list is the gateway's
 * (`/herald-push cron inbox`), kept on the phone with the runs that came by push, so it reads offline too.
 * Following a job, and whether it notifies, is set per job on the gateway for this phone.
 */
@OptIn(ExperimentalTime::class)
class InboxViewModel(
    private val api: InboxApi,
    private val store: InboxStore,
    private val cron: CronApi,
    private val push: PushSetup,
    private val connection: GatewayConnection,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    private var gateway: SavedGateway? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            store.state.collect { kept -> _state.update { it.copy(runs = kept.runs, read = kept.read, since = kept.since, groups = kept.groups) } }
        }
        // A run finished on the gateway: cron output arrives without an event of its own, so a
        // sessions change (an agent run is a session) is the nudge to look again.
        viewModelScope.launch {
            connection.events.filter { it.type == "sessions.changed" || it.type == "cron.changed" }.collect {
                if (gateway != null && _state.value.open == null && !_state.value.choosing) load(quiet = true)
            }
        }
        viewModelScope.launch {
            combine(push.status, settings.settings) { status, prefs -> status.state to (prefs?.pushAnywhere == true) }
                .collect { (status, on) ->
                    val block = when {
                        !on || status == PushStatus.State.Off -> InboxBlock.PushOff
                        status == PushStatus.State.NotInstalled -> InboxBlock.PushOff
                        else -> null
                    }
                    val was = _state.value.block
                    _state.update { it.copy(block = block ?: it.block.takeIf { b -> b == InboxBlock.PluginOld }) }
                    if (was == InboxBlock.PushOff && block == null) load()
                }
        }
    }

    fun bind(gateway: SavedGateway) {
        if (this.gateway == gateway) return
        this.gateway = gateway
        viewModelScope.launch {
            store.bind(gateway.gatewayUrl.value, Clock.System.now().epochSeconds)
            load()
        }
    }

    fun refresh() = load()

    fun setFilter(filter: InboxFilter) = _state.update { it.copy(filter = filter) }

    /** Opens the Inbox on folders, or on every report in one list. */
    fun setLayout(layout: InboxLayout) {
        _state.update { it.copy(filter = InboxFilter.All) }
        editGroups { it.copy(layout = layout) }
    }

    /** Splits the chips by job or by the user's groups. */
    fun setChipMode(mode: ChipMode) {
        _state.update { it.copy(filter = InboxFilter.All) }
        editGroups { it.copy(mode = mode) }
    }

    fun openGroups() = _state.update { it.copy(grouping = true) }

    fun closeGroups() = _state.update { it.copy(grouping = false) }

    fun addGroup(name: String) = editGroups { it.add(name) }

    fun renameGroup(from: String, to: String) = editGroups { it.rename(from, to) }

    fun removeGroup(name: String) = editGroups { it.remove(name) }

    fun assignGroup(job: InboxFilter.Job, group: String?) = editGroups { it.assign(job.bot, job.job, group) }

    /** Calls [chip]'s job [name] on this phone; blank puts its own name back. */
    fun renameJob(chip: JobChip, name: String) = editGroups { it.renameJob(chip.filter.bot, chip.filter.job, name, chip.own) }

    private fun editGroups(change: (InboxGroups) -> InboxGroups) {
        viewModelScope.launch { store.editGroups(change) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun load(quiet: Boolean = false) {
        if (gateway == null) return
        loadJob?.cancel()
        if (!quiet) _state.update { it.copy(loading = it.runs.isEmpty(), error = null) }
        loadJob = viewModelScope.launch {
            if (!ready()) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val result = runCatching { api.runs() }
            result.onSuccess { runs ->
                store.replace(runs)
                _state.update { it.copy(loading = false, error = null, block = null) }
            }.onFailure { e ->
                val old = e.message?.contains("too old") == true
                _state.update {
                    it.copy(
                        loading = false,
                        block = if (old) InboxBlock.PluginOld else it.block,
                        error = if (old || it.runs.isNotEmpty() || quiet) null else e.message ?: "Couldn't load the Inbox.",
                    )
                }
            }
        }
    }

    /** Whether the gateway can be asked now: connected, with Notifications anywhere on. */
    private suspend fun ready(): Boolean {
        if (connection.state.value !is ConnectionState.Connected) return false
        val prefs = settings.settings.first { it != null }
        if (prefs?.pushAnywhere != true) {
            _state.update { it.copy(block = InboxBlock.PushOff) }
            return false
        }
        return true
    }

    fun open(run: InboxRun) {
        _state.update { it.copy(open = run, openLoading = true, openPartial = false) }
        viewModelScope.launch {
            store.markRead(run)
            val full = if (ready()) runCatching { api.read(run) }.getOrNull() else null
            if (full != null) store.add(full)
            _state.update { state ->
                if (state.open?.key != run.key) return@update state
                state.copy(open = full ?: run, openLoading = false, openPartial = full == null)
            }
        }
    }

    /** Opens the run [key] names ([InboxRun.key]), from a notification; it came by push, so it's kept. */
    fun openKey(key: String) {
        viewModelScope.launch {
            val run = store.state.first { kept -> kept.runs.any { it.key == key } || kept.runs.isNotEmpty() }
                .runs.firstOrNull { it.key == key } ?: return@launch
            _state.update { it.copy(choosing = false) }
            open(run)
        }
    }

    fun closeRun() = _state.update { it.copy(open = null, openLoading = false) }

    fun markAllRead() {
        viewModelScope.launch { store.markAllRead() }
    }

    /** Opens the follow page: every job, and how each reaches the Inbox now. */
    fun chooseJobs() {
        val url = gateway?.gatewayUrl ?: return
        _state.update { it.copy(choosing = true, jobsLoading = it.jobs.isEmpty()) }
        viewModelScope.launch {
            val jobs = cron.jobs(url)
            val following = if (ready()) runCatching { api.following() }.getOrNull() else null
            _state.update {
                it.copy(
                    jobs = (jobs as? ApiResult.Success)?.value?.sortedBy { job -> job.displayName.lowercase() } ?: it.jobs,
                    jobsLoading = false,
                    following = following ?: it.following,
                    message = if (jobs !is ApiResult.Success) "Couldn't load the jobs." else it.message,
                )
            }
        }
    }

    fun closeJobs() = _state.update { it.copy(choosing = false) }

    fun setFollow(job: CronJob, follow: Follow) = save(_state.value.following.with(job.id, follow))

    fun setFailures(on: Boolean) = save(_state.value.following.copy(failures = on))

    private fun save(next: Following) {
        val before = _state.value.following
        _state.update { it.copy(following = next, saving = true) }
        viewModelScope.launch {
            val saved = if (ready()) runCatching { api.setFollowing(next) } else Result.failure(IllegalStateException("Not connected"))
            saved.onSuccess { following ->
                _state.update { it.copy(following = following, saving = false) }
                load(quiet = true)
            }.onFailure { e ->
                _state.update { it.copy(following = before, saving = false, message = e.message ?: "Couldn't save that.") }
            }
        }
    }

    companion object {
        /** The plugin the Inbox needs, for the message that asks to update it. */
        const val NEEDS_PLUGIN = InboxApi.MIN_PLUGIN_VERSION
        const val PLUGIN_NOW = PushApi.PLUGIN_VERSION
    }
}
