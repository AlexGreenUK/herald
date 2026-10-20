package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.plugins.NextPromptApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** The reply the Next Prompt plugin suggests for [storedSessionId], shown over the composer. */
data class SuggestedReply(val storedSessionId: String, val text: String)

/**
 * The open chat's suggested next message, from the gateway's Next Prompt plugin when it has one. The plugin
 * stores a suggestion a second or two after each turn ends and drops it once the user sends their own
 * message, so after a turn this asks a few times, quickly at first; on opening a chat it asks once, for one
 * still waiting from before. Without the plugin, or with the setting off, nothing is asked and nothing shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NextPromptController(
    private val api: NextPromptApi?,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _suggestion = MutableStateFlow<SuggestedReply?>(null)
    val suggestion: StateFlow<SuggestedReply?> = _suggestion.asStateFlow()

    private var follow: Job? = null
    private var owner: Owner? = null

    /** Whether the plugin is there, per gateway and profile, and when that was learned (epoch ms). */
    private val installed = mutableMapOf<Owner, Pair<Boolean, Long>>()

    private data class Owner(val url: GatewayUrl, val profile: String?)

    /** What decides whether and how to ask: a new turn ending, another chat, the setting. */
    private data class Moment(val enabled: Boolean, val storedSessionId: String?, val running: Boolean, val historyLoaded: Boolean)

    /** Follows [states], the chat open in [profile] on [url]; null when no chat is open. */
    fun follow(url: GatewayUrl?, profile: String?, states: Flow<ChatState>?, enabled: Flow<Boolean>) {
        follow?.cancel()
        _suggestion.value = null
        val owner = url?.let { Owner(it, profile) }
        this.owner = owner
        if (api == null || owner == null || states == null) return
        follow = scope.launch {
            var ranBefore: String? = null
            combine(enabled, states) { on, chat -> Moment(on, chat.storedSessionId, chat.running, chat.historyLoaded) }
                .distinctUntilChanged()
                .collectLatest { moment ->
                    val id = moment.storedSessionId
                    // A turn ending in this chat, seen here: what follows it is worth waiting for.
                    val turnEnded = ranBefore != null && ranBefore == id && !moment.running
                    ranBefore = id?.takeIf { moment.running }
                    _suggestion.update { shown -> shown?.takeIf { it.storedSessionId == id && !moment.running && moment.enabled } }
                    if (!moment.enabled || id == null || moment.running || !moment.historyLoaded) return@collectLatest
                    if (!isInstalled(owner)) return@collectLatest
                    ask(owner, id, if (turnEnded) AFTER_TURN_MS else ON_OPEN_MS)
                }
        }
    }

    /** Asks the gateway after each wait in [waits] until it has a suggestion, or says why there is none. */
    private suspend fun ask(owner: Owner, id: String, waits: List<Long>) {
        for (wait in waits) {
            delay(wait)
            val raw = api?.state(owner.url, id, owner.profile)
            if ((raw as? ApiResult.Failed)?.status == 404) {
                // Listed but not served (a gateway that adds new plugins' routes only when it restarts): not there yet.
                installed[owner] = false to now()
                return
            }
            val state = (raw as? ApiResult.Success)?.value ?: continue
            val text = state.suggestion?.text?.trim()
            if (!text.isNullOrEmpty()) {
                _suggestion.value = SuggestedReply(id, text)
                return
            }
            if (state.error != null) return
        }
    }

    private suspend fun isInstalled(owner: Owner): Boolean {
        installed[owner]?.let { (known, at) -> if (now() - at < INSTALLED_FRESH_MS) return known }
        val result = api?.installed(owner.url, owner.profile) as? ApiResult.Success ?: return false
        installed[owner] = result.value to now()
        return result.value
    }

    /** The suggestion went into the composer; it's used up here and for every other client. */
    fun used() = drop()

    /** The user closed the suggestion. */
    fun dismiss() = drop()

    private fun drop() {
        val shown = _suggestion.value ?: return
        _suggestion.value = null
        val owner = owner ?: return
        scope.launch { api?.dismiss(owner.url, shown.storedSessionId, owner.profile) }
    }

    internal companion object {
        /** The plugin's answer is learned again after this long, so one installed meanwhile shows up. */
        const val INSTALLED_FRESH_MS = 5 * 60_000L

        /** One look on opening a chat: the suggestion may still be waiting from before. */
        val ON_OPEN_MS = listOf(0L)

        /** After a turn: every second for the first 12 s (the plugin usually takes 1–3 s), then every 4 s to a minute. */
        val AFTER_TURN_MS = List(12) { 1_000L } + List(12) { 4_000L }
    }
}
