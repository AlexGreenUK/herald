package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.LiveCall
import dev.hermeskotlin.core.wear.CallChat
import dev.hermeskotlin.core.wear.WatchOutbox
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.core.wear.contextPercentOf
import dev.hermeskotlin.core.wear.modelOf
import dev.hermeskotlin.wearlink.CallEnd
import dev.hermeskotlin.wearlink.CallEvents
import dev.hermeskotlin.wearlink.CallPhase
import dev.hermeskotlin.wearlink.CallStart
import dev.hermeskotlin.wearlink.CallUpdate
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * Herald's side of a live voice call held by the watch. The watch makes the WebRTC call (its microphone and
 * speaker, straight to the voice model); this trades its offer for the model's answer through the gateway and
 * runs the conversation with [LiveConversation], as for a call on the phone: what the model sends on the call's
 * event channel comes from the watch, what Herald sends it goes back for the watch to send. Requests run as
 * Hermes turns in the watch's chat. One call at a time; a new one ends the last.
 *
 * Every message is handled on one thread, in the order it came, so a hang-up right after a start finds its call.
 * A watch that goes quiet (out of range, its app gone) ends the call here too: it says it's there every few seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchCalls(
    private val relay: WatchRelay,
    private val audio: AudioApi,
    private val outbox: WatchOutbox,
    appScope: CoroutineScope,
) {
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]) + serial)
    private var running: Job? = null
    private var call: WatchHeldCall? = null

    /** The watch [node] starts a call. Busy at once, before the caller returns, so the app may keep itself up. */
    fun start(node: String, start: CallStart) {
        relay.callStarted()
        scope.launch {
            val previous = running
            val held = WatchHeldCall(start.id, node, start.offerSdp)
            // Before waiting on the last call: the watch's events and hang-up for this one find it meanwhile.
            call = held
            // Atomic, so it runs even when cancelled at once, and the call it counted is always let go.
            val job = scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    previous?.cancelAndJoin()
                    run(node, start, held)
                } finally {
                    if (call === held) call = null
                }
            }
            job.invokeOnCompletion { relay.callEnded() }
            running = job
        }
    }

    /** The voice model's events of a call as the watch got them, or (empty) that the watch is still there. */
    fun events(events: CallEvents) {
        scope.launch { call?.takeIf { it.id == events.callId }?.received(events) }
    }

    /** Hung up on the watch. */
    fun end(end: CallEnd) {
        scope.launch { call?.takeIf { it.id == end.callId }?.hungUp() }
    }

    private suspend fun run(node: String, start: CallStart, held: WatchHeldCall) {
        if (held.over) return
        var seq = 0
        var last = CallUpdate(start.id, CallPhase.Connecting, chatId = start.chatId)
        val telling = Mutex()
        suspend fun tell(update: CallUpdate) {
            telling.withLock {
                last = update.copy(seq = ++seq)
                held.sent(outbox.sendSafely(node, WearPaths.CALL_UPDATE, WearJson.encode(last)))
            }
        }
        suspend fun end(error: String?) = tell(last.copy(phase = CallPhase.Ended, answerSdp = null, error = error, thinking = false, working = null))

        tell(last)
        val chat = when (val found = relay.chatForCall(start.chatId)) {
            is CallChat.Failed -> return end(found.message)
            is CallChat.Ready -> found
        }
        val status = audio.voiceLiveStatus(chat.gateway, chat.profile)
        if (status !is ApiResult.Success || !status.value.live) return end(NOT_LIVE)
        if (held.over) return

        val voice = MutableStateFlow(VoiceChatState(VoicePhase.Connecting, live = true))
        val conversation = LiveConversation(held, chat.session, voice)
        var answer: String? = null
        held.onAnswer = { sdp ->
            answer = sdp
            tell(last.copy(phase = CallPhase.Live, answerSdp = sdp))
        }
        // What the watch shows of the call, a few times a second at most: the captions, Hermes at work, the chat.
        // The answer goes again with each until the watch's first events say it arrived.
        val forward = scope.launch {
            var heldChat: String? = null
            combine(voice, chat.session.state) { v, s ->
                CallView(v.caption, v.captionIsVoice, v.phase == VoicePhase.Thinking, v.working, v.error, s.storedSessionId, s.title, modelOf(s), contextPercentOf(s))
            }.distinctUntilChanged().conflate().collect { view ->
                view.chatId?.takeIf { it != heldChat }?.let {
                    heldChat = it
                    relay.callChat(it)
                }
                if (last.phase == CallPhase.Live) {
                    tell(
                        last.copy(
                            answerSdp = answer.takeUnless { held.heard },
                            caption = view.caption,
                            captionIsVoice = view.captionIsVoice,
                            thinking = view.thinking,
                            working = view.working,
                            error = view.error,
                            chatId = view.chatId ?: last.chatId,
                            title = view.title ?: last.title,
                            model = view.model ?: last.model,
                            contextPercent = view.contextPercent ?: last.contextPercent,
                        ),
                    )
                }
                delay(UPDATE_EVERY_MS)
            }
        }
        val watching = scope.launch { held.watch() }
        val error = try {
            conversation.run {
                held.connect { offer ->
                    when (val result = audio.startVoiceLive(chat.gateway, offer, conversation.history(), chat.profile)) {
                        is ApiResult.Success -> result.value.ifBlank { error("GPT-Live sent no answer.") }
                        else -> error(liveFailure(result))
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                watching.cancel()
                forward.cancelAndJoin()
            }
        }
        // Hung up on the watch, or the watch is gone: nothing to tell. Otherwise ("stop" said, the call dropped) it's told.
        if (!held.over) end(error)
    }

    /** The watch's call as [LiveConversation] sees it: the model's events come from the watch, and go back to it. */
    private inner class WatchHeldCall(val id: String, private val node: String, private val offer: String) : LiveCall {
        var onAnswer: (suspend (String) -> Unit)? = null

        /** Hung up on the watch, or the watch stopped answering. */
        var over = false
            private set

        /** Something came from the watch since the answer went: it has it. */
        var heard = false
            private set

        private val incoming = Channel<String>(Channel.UNLIMITED)
        override val events: ReceiveChannel<String> = incoming
        private var lastIn = 0
        private val early = mutableMapOf<Int, CallEvents>()
        private var heardAt = TimeSource.Monotonic.markNow()
        private var failedSends = 0
        private val outgoing = Channel<String>(Channel.UNLIMITED)

        override suspend fun connect(answer: suspend (offerSdp: String) -> String) {
            val sdp = answer(offer)
            onAnswer?.invoke(sdp)
            scope.launch { sendBatches() }
        }

        /** A batch from the watch, put back in order if it overtook another. */
        fun received(batch: CallEvents) {
            heardAt = TimeSource.Monotonic.markNow()
            heard = true
            if (batch.seq <= lastIn) return
            early[batch.seq] = batch
            // One missing for a while: what came after it goes on without it.
            if (early.size > MAX_EARLY) lastIn = early.keys.min() - 1
            while (true) {
                val next = early.remove(lastIn + 1) ?: break
                lastIn = next.seq
                next.events.forEach { incoming.trySend(it) }
            }
        }

        fun hungUp() {
            over = true
            incoming.close()
        }

        /** Ends the call once the watch has said nothing for a while, or messages to it keep failing. */
        suspend fun watch() {
            while (true) {
                delay(WATCH_CHECK_MS)
                if (heardAt.elapsedNow().inWholeMilliseconds > WATCH_QUIET_MS || failedSends >= MAX_FAILED_SENDS) {
                    hungUp()
                    return
                }
            }
        }

        fun sent(ok: Boolean) {
            failedSends = if (ok) 0 else failedSends + 1
        }

        override fun send(event: String): Boolean = outgoing.trySend(event).isSuccess

        /** What Herald says to the model, gathered a moment so a reply's sentences go to the watch together. */
        private suspend fun sendBatches() {
            var seq = 0
            while (true) {
                val first = outgoing.receiveCatching().getOrNull() ?: return
                delay(SEND_GATHER_MS)
                val batch = mutableListOf(first)
                while (true) batch += outgoing.tryReceive().getOrNull() ?: break
                sent(outbox.sendSafely(node, WearPaths.CALL_SEND, WearJson.encode(CallEvents(id, ++seq, batch))))
            }
        }

        // The watch shows its own call: it knows when the voice talks and how loud you are.
        override fun speaking(): Boolean = false

        override fun micLevel(): Float = 0f

        // Muting is the watch's: it turns its microphone off and tells the model itself.
        override fun setMuted(muted: Boolean) = Unit

        override fun close() {
            incoming.close()
            // The close command LiveConversation sends last still goes, then the sender stops.
            outgoing.close()
        }
    }

    private data class CallView(
        val caption: String?,
        val captionIsVoice: Boolean,
        val thinking: Boolean,
        val working: String?,
        val error: String?,
        val chatId: String?,
        val title: String?,
        val model: String?,
        val contextPercent: Int?,
    )

    private suspend fun WatchOutbox.sendSafely(node: String, path: String, data: ByteArray): Boolean = try {
        send(node, path, data)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    companion object {
        const val NOT_LIVE = "Live voice isn't set up on your gateway any more. Tap to ask one question at a time."
        private const val UPDATE_EVERY_MS = 250L
        private const val SEND_GATHER_MS = 60L
        private const val WATCH_CHECK_MS = 5_000L

        /** The watch says it's there every 10 s; three missed and it's gone. */
        private const val WATCH_QUIET_MS = 30_000L
        private const val MAX_FAILED_SENDS = 3
        private const val MAX_EARLY = 4
    }
}
