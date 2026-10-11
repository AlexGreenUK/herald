package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.chat.toolInProgress
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.speakableText
import dev.hermeskotlin.core.voice.speechChunks
import dev.hermeskotlin.wearlink.ChatList
import dev.hermeskotlin.wearlink.PhoneStatus
import dev.hermeskotlin.wearlink.ReplyPhase
import dev.hermeskotlin.wearlink.ReplySpeech
import dev.hermeskotlin.wearlink.ReplyUpdate
import dev.hermeskotlin.wearlink.WatchAsk
import dev.hermeskotlin.wearlink.WatchChat
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import dev.hermeskotlin.wearlink.clipForWatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** How the relay reaches the watch: the platform's Data Layer, or a fake in tests. */
interface WatchOutbox {
    /** Sends a message on [path] to the watch [node]; false when it didn't go. */
    suspend fun send(node: String, path: String, data: ByteArray): Boolean

    /** Sends [data] on a channel at [path] to [node], for what's too large for a message; false when it didn't go. */
    suspend fun stream(node: String, path: String, data: ByteArray): Boolean
}

/**
 * Herald's side of the watch companion: what the watch asks goes to Hermes from here, over the phone's gateway
 * connection and sign-in, and the turn goes back to the watch as it runs ([ReplyUpdate]s), then read aloud in the
 * gateway's voice. The watch's chat runs in its own [ChatSession], never the one open in the app, so talking
 * on the watch doesn't move the phone off its chat; [ChatHost.sideChats] keeps its replies out of the phone's
 * notifications, since the watch shows them itself.
 *
 * One ask is followed at a time: a new one takes over the updates and the voice, and its words reach the
 * running turn the way a prompt sent mid-turn does.
 */
class WatchRelay(
    private val gateways: GatewayRepository,
    private val auth: AuthApi,
    private val profiles: ProfileStore,
    private val connection: GatewayConnection,
    private val sessions: SessionsApi,
    private val audio: AudioApi,
    private val host: ChatHost,
    appScope: CoroutineScope,
    private val outbox: WatchOutbox,
) {
    private val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]))

    private val _busy = MutableStateFlow(false)

    /** A watch ask is being answered or read aloud; the app keeps its connection up meanwhile. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _session = MutableStateFlow<ChatSession?>(null)

    /** The watch's chat, for answering its approvals and questions from the phone. */
    val session: StateFlow<ChatSession?> = _session.asStateFlow()

    private var sessionGateway: GatewayUrl? = null
    private var sessionProfile: String? = null
    private var sessionScope: CoroutineScope? = null
    private var turn: Job? = null
    private var turnAskId: String? = null
    private val turnLock = Mutex()

    /** A typed or transcribed ask from the watch [node]; [recording] is its audio when it was spoken. */
    fun ask(node: String, ask: WatchAsk, recording: ByteArray? = null) {
        // Busy at once, before the caller returns: that is when Android still lets the app keep itself up.
        _busy.value = true
        scope.launch {
            turnLock.withLock {
                turn?.cancel()
                turnAskId = ask.id
                _busy.value = true
                turn = scope.launch {
                    try {
                        answer(node, ask, recording)
                    } finally {
                        if (turnAskId == ask.id) _busy.value = false
                    }
                }
            }
        }
    }

    /** The watch asked to stop: the running turn ends and the voice goes quiet. */
    fun stop(askId: String) {
        scope.launch {
            turnLock.withLock {
                if (askId != turnAskId) return@withLock
                turn?.cancel()
                _busy.value = false
            }
            _session.value?.takeIf { it.state.value.running }?.interrupt()
        }
    }

    fun status(node: String) {
        scope.launch {
            val saved = gateways.current()
            val signedIn = saved != null && auth.hasStoredSession(saved.gatewayUrl)
            val status = PhoneStatus(signedIn, signedIn && connection.state.value is ConnectionState.Connected)
            outbox.sendSafely(node, WearPaths.STATUS, WearJson.encode(status))
        }
    }

    /** The recent chats for the watch [node] to pick from, bot chats left out (they talk on their own). */
    fun chats(node: String) {
        scope.launch {
            val list = run {
                val saved = gateways.current() ?: return@run ChatList(error = SIGNED_OUT)
                if (!auth.hasStoredSession(saved.gatewayUrl)) return@run ChatList(error = SIGNED_OUT)
                when (val result = sessions.list(saved.gatewayUrl, limit = CHAT_LIST_SIZE, profile = profiles.get(saved.gatewayUrl))) {
                    is ApiResult.Success -> ChatList(
                        result.value.sessions
                            .filter { !it.archived && it.title != BotsApi.BOT_CHAT_TITLE }
                            .map { WatchChat(it.id, it.displayTitle.take(MAX_TITLE), it.activityAt) },
                    )
                    else -> ChatList(error = result.errorMessage ?: "Couldn't read your chats.")
                }
            }
            outbox.sendSafely(node, WearPaths.CHATS, WearJson.encode(list))
        }
    }

    fun close() {
        _session.value?.let { closeSession(it) }
        scope.cancel()
    }

    private suspend fun answer(node: String, ask: WatchAsk, recording: ByteArray?) {
        var seq = 0
        var last = ReplyUpdate(ask.id, ReplyPhase.Sending, chatId = ask.chatId)
        suspend fun tell(update: ReplyUpdate) {
            last = update.copy(seq = ++seq)
            outbox.sendSafely(node, WearPaths.REPLY, WearJson.encode(last))
        }
        suspend fun fail(message: String) = tell(last.copy(phase = ReplyPhase.Failed, error = message, activity = null))

        val saved = gateways.current()
        if (saved == null || !auth.hasStoredSession(saved.gatewayUrl)) return fail(SIGNED_OUT)
        val gateway = saved.gatewayUrl
        val profile = profiles.get(gateway)
        connection.start(gateway)

        var text = ask.text.trim()
        if (recording != null) {
            tell(last.copy(phase = ReplyPhase.Transcribing))
            val heard = when (val result = audio.transcribe(gateway, recording, ask.audioType ?: "audio/wav", profile)) {
                is ApiResult.Success -> result.value.trim()
                else -> return fail(result.errorMessage ?: "Couldn't write down what you said.")
            }
            if (heard.isEmpty()) return fail(NOTHING_HEARD)
            text = heard
            last = last.copy(heard = heard)
        }
        if (text.isEmpty()) return fail(NOTHING_HEARD)

        tell(last.copy(phase = ReplyPhase.Sending))
        if (withTimeoutOrNull(CONNECT_MS) { connection.state.first { it is ConnectionState.Connected } } == null) {
            connection.retry()
            return fail("Herald can't reach your gateway right now.")
        }
        val session = sessionFor(gateway, profile, ask.chatId)
        val sentAt = session.state.value.messages.size
        if (!session.send(text)) return fail(session.state.value.error ?: "Couldn't send that.")
        session.state.value.storedSessionId?.let(host::holdSideChat)

        // The reply as it streams, a few times a second at most: each update is a whole message to the watch.
        val streaming = scope.launch {
            session.state.map { state -> progressOf(state, sentAt) }.distinctUntilChanged().conflate().collect { progress ->
                tell(last.copy(phase = progress.phase, chatId = progress.chatId, title = progress.title, text = progress.text, activity = progress.activity))
                delay(UPDATE_EVERY_MS)
            }
        }
        try {
            withTimeoutOrNull(TURN_START_MS) { session.state.first { it.running } }
            session.state.first { !it.running }
        } finally {
            streaming.cancel()
        }

        val state = session.state.value
        state.storedSessionId?.let(host::holdSideChat)
        val reply = replyAfter(state, sentAt)
        val done = last.copy(chatId = state.storedSessionId ?: last.chatId, title = state.title, activity = null)
        if (reply?.outcome == TurnOutcome.Error) {
            return tell(done.copy(phase = ReplyPhase.Failed, text = clipForWatch(reply.text), error = reply.error ?: "The turn failed."))
        }
        val replyText = reply?.text.orEmpty()
        val spoken = if (ask.speak && reply?.outcome != TurnOutcome.Interrupted) speakableText(replyText) else ""
        tell(done.copy(phase = ReplyPhase.Done, text = clipForWatch(replyText), speech = if (spoken.isEmpty()) ReplySpeech.Off else ReplySpeech.Coming))
        if (spoken.isNotEmpty() && !speak(node, ask.id, spoken, gateway, profile)) {
            tell(last.copy(speech = ReplySpeech.Unavailable))
        }
    }

    /**
     * Voices [text] in the gateway's voice, clip by clip, each sent as soon as it's made so the first plays
     * while the rest are made. False when not even the first could be made or sent: the watch reads it itself.
     */
    private suspend fun speak(node: String, askId: String, text: String, gateway: GatewayUrl, profile: String?): Boolean {
        audio.ttsLease(gateway, true, profile)
        try {
            val chunks = speechChunks(text)
            chunks.forEachIndexed { index, chunk ->
                val clip = when (val result = audio.speak(gateway, chunk, profile)) {
                    is ApiResult.Success -> result.value
                    else -> return index > 0
                }
                if (!outbox.streamSafely(node, WearPaths.speechPath(askId, index, chunks.size, clip.mimeType), clip.bytes)) return index > 0
            }
            return true
        } finally {
            audio.ttsLease(gateway, false, profile)
        }
    }

    /** The watch's chat [chatId] (null: a new one), kept from the last ask when it's the same. */
    private fun sessionFor(gateway: GatewayUrl, profile: String?, chatId: String?): ChatSession {
        val current = _session.value
        if (current != null && chatId != null && sessionGateway == gateway && sessionProfile == profile &&
            current.state.value.storedSessionId == chatId
        ) {
            return current
        }
        current?.let { closeSession(it) }
        val childScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        sessionScope = childScope
        sessionGateway = gateway
        sessionProfile = profile
        chatId?.let(host::holdSideChat)
        return ChatSession(gateway, chatId, null, connection, sessions, childScope, profile).also {
            it.start()
            _session.value = it
        }
    }

    private fun closeSession(session: ChatSession) {
        session.state.value.storedSessionId?.let(host::releaseSideChat)
        session.stop()
        sessionScope?.cancel()
        sessionScope = null
        _session.update { if (it === session) null else it }
    }

    companion object {
        const val SIGNED_OUT = "Sign in to Herald on your phone first."
        const val NOTHING_HEARD = "I didn't catch that."
        private const val CHAT_LIST_SIZE = 15
        private const val MAX_TITLE = 80
        private const val CONNECT_MS = 10_000L
        private const val TURN_START_MS = 15_000L
        private const val UPDATE_EVERY_MS = 400L
    }
}

/** The turn as the watch shows it while it runs. */
internal data class TurnProgress(val phase: ReplyPhase, val chatId: String?, val title: String?, val text: String, val activity: String?)

/** Where the turn after the [sentAt]th message stands in [state]. */
internal fun progressOf(state: ChatState, sentAt: Int): TurnProgress {
    val reply = replyAfter(state, sentAt)
    val waiting = state.inputRequests.isNotEmpty()
    val tool = reply?.tools?.lastOrNull { it.running }
    val activity = when {
        waiting -> "Waiting for you on your phone"
        tool != null -> toolInProgress(tool.name)
        else -> state.status?.takeIf { it.isNotBlank() }
    }
    val phase = when {
        waiting -> ReplyPhase.WaitingOnPhone
        state.running || reply != null -> ReplyPhase.Working
        else -> ReplyPhase.Sending
    }
    return TurnProgress(phase, state.storedSessionId, state.title, clipForWatch(reply?.text.orEmpty()), activity)
}

/** The reply to the prompt sent after the [sentAt]th message: the last one past it. */
internal fun replyAfter(state: ChatState, sentAt: Int): ChatMessage.Assistant? =
    state.messages.drop(sentAt).lastOrNull { it is ChatMessage.Assistant } as? ChatMessage.Assistant

/** Catches what a send to the watch may throw, so one lost message doesn't end the turn's updates. */
internal suspend fun WatchOutbox.sendSafely(node: String, path: String, data: ByteArray): Boolean = safely { send(node, path, data) }

internal suspend fun WatchOutbox.streamSafely(node: String, path: String, data: ByteArray): Boolean = safely { stream(node, path, data) }

private inline fun safely(block: () -> Boolean): Boolean = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}
