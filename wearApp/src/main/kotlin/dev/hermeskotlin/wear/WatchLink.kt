package dev.hermeskotlin.wear

import android.content.Context
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import dev.hermeskotlin.wearlink.ChatList
import dev.hermeskotlin.wearlink.PhoneStatus
import dev.hermeskotlin.wearlink.RecordedAsk
import dev.hermeskotlin.wearlink.ReplyPhase
import dev.hermeskotlin.wearlink.ReplySpeech
import dev.hermeskotlin.wearlink.ReplyUpdate
import dev.hermeskotlin.wearlink.WatchAsk
import dev.hermeskotlin.wearlink.WatchChat
import dev.hermeskotlin.wearlink.WatchStop
import dev.hermeskotlin.wearlink.WearCapabilities
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

/** One ask and where its turn stands: [asked] is what was typed or recognized here (null until the phone transcribes). */
data class WatchTurn(val askId: String, val asked: String?, val update: ReplyUpdate) {
    val running: Boolean get() = update.phase != ReplyPhase.Done && update.phase != ReplyPhase.Failed
    val said: String? get() = asked ?: update.heard
}

/** How the phone stands, for what the watch can offer. */
enum class PhoneState {
    Unknown,

    /** No phone is connected to the watch. */
    NotConnected,

    /** A phone is connected, but without Herald (or with one too old to answer). */
    NoHerald,

    /** Herald is there, signed in to no gateway. */
    SignedOut,

    /** Herald is signed in, but not connected to its gateway right now; an ask waits for it a little. */
    Reconnecting,

    /** Herald gave up on its gateway (a setting or the server is wrong); asking won't help until that's fixed. */
    Unreachable,

    Ready,
}

/**
 * The watch's side of the companion: it sends what's asked to Herald on the phone, and turns what comes back into
 * [turn], [chats] and [phone]. It lives as long as the app, so updates that arrive with the screen off (through
 * [WatchListenerService]) are there when it's looked at again.
 *
 * In a debug build on a watch with no phone at all (an emulator not paired), [FakePhone] answers instead.
 */
class WatchLink(
    private val context: Context,
    private val store: WatchStore,
    private val speech: SpeechOut,
    private val scope: CoroutineScope,
) {
    private val messages = Wearable.getMessageClient(context)
    private val channels = Wearable.getChannelClient(context)
    private val capabilities = Wearable.getCapabilityClient(context)
    private val nodes = Wearable.getNodeClient(context)
    private val fake = if (BuildConfig.DEBUG) FakePhone(scope) { path, data -> onMessage(path, data) } else null

    private val _turn = MutableStateFlow<WatchTurn?>(null)
    val turn: StateFlow<WatchTurn?> = _turn.asStateFlow()

    private val _chats = MutableStateFlow<ChatList?>(null)
    val chats: StateFlow<ChatList?> = _chats.asStateFlow()

    private val _phone = MutableStateFlow(PhoneState.Unknown)
    val phone: StateFlow<PhoneState> = _phone.asStateFlow()

    private var watchdog: Job? = null

    /** The ask whose voice was stopped here: its clips still arriving are dropped. */
    @Volatile private var silenced: String? = null

    /** Sends what was said or typed; with [audio], the phone has the gateway transcribe it first. */
    fun ask(text: String?, audio: ByteArray? = null, audioType: String? = null) {
        speech.stop()
        val id = UUID.randomUUID().toString()
        val chat = store.chat.value
        val ask = WatchAsk(id, text.orEmpty().trim(), chat.id, speak = store.speak.value && speech.canPlay(), audioType = audioType)
        _turn.value = WatchTurn(id, text?.trim(), ReplyUpdate(id, if (audio != null) ReplyPhase.Transcribing else ReplyPhase.Sending, chatId = chat.id, title = chat.title))
        scope.launch {
            val sent = if (audio != null) stream(WearPaths.recordingPath(id), RecordedAsk.write(ask, audio)) else send(WearPaths.ASK, WearJson.encode(ask))
            if (sent != null) fail(id, sent)
        }
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(ANSWER_WITHIN_MS)
            val now = _turn.value
            if (now != null && now.askId == id && now.update.seq == 0 && now.running) {
                _phone.value = PhoneState.NoHerald
                fail(id, NO_HERALD)
            }
        }
    }

    /** Stops the running turn, and the voice reading a reply. */
    fun stop() {
        quiet()
        val now = _turn.value ?: return
        if (!now.running) return
        // The phone stops telling once it stops the turn: what came so far stays as the reply.
        _turn.update { it?.takeIf { t -> t.askId == now.askId }?.copy(update = now.update.copy(phase = ReplyPhase.Done, activity = null)) ?: it }
    }

    /**
     * Silences the reply: what plays stops, and clips still on their way are dropped. The phone is told while it
     * may still be making them, so it stops (and lets its connection go).
     */
    fun quiet() {
        speech.stop()
        val now = _turn.value ?: return
        silenced = now.askId
        if (now.running || now.update.speech == ReplySpeech.Coming) {
            scope.launch { send(WearPaths.STOP, WearJson.encode(WatchStop(now.askId))) }
        }
    }

    fun newChat() {
        speech.stop()
        store.setChat(null, null)
        _turn.value = null
    }

    fun pickChat(chat: WatchChat) {
        speech.stop()
        store.setChat(chat.id, chat.title)
        _turn.value = null
    }

    fun refreshChats() {
        scope.launch {
            send(WearPaths.CHATS, ByteArray(0))?.let { _chats.value = ChatList(error = it) }
        }
    }

    fun refreshPhone() {
        scope.launch { send(WearPaths.STATUS, ByteArray(0)) }
    }

    /** A message from the phone, from [WatchListenerService] or the fake phone. */
    fun onMessage(path: String, data: ByteArray) {
        when (path) {
            WearPaths.REPLY -> WearJson.decode<ReplyUpdate>(data)?.let(::onReply)
            WearPaths.CHATS -> WearJson.decode<ChatList>(data)?.let { _chats.value = it }
            WearPaths.STATUS -> WearJson.decode<PhoneStatus>(data)?.let {
                _phone.value = when {
                    !it.signedIn -> PhoneState.SignedOut
                    it.connected -> PhoneState.Ready
                    it.failed -> PhoneState.Unreachable
                    else -> PhoneState.Reconnecting
                }
            }
        }
    }

    /** A clip of the reply's voice. */
    fun onSpeech(path: String, bytes: ByteArray) {
        val clip = WearPaths.parseSpeech(path) ?: return
        val now = _turn.value ?: return
        if (clip.askId != now.askId || clip.askId == silenced || !store.speak.value) return
        scope.launch(Dispatchers.Main) { speech.clip(clip.askId, clip.index, clip.count, clip.mimeType, bytes) }
    }

    private fun onReply(update: ReplyUpdate) {
        var taken = false
        // Atomic: this runs off the main thread, where an ask() in between must not be written over.
        _turn.update { now ->
            taken = false
            // Only the ask on screen, and never an older update over a newer one.
            if (now == null || update.askId != now.askId || update.seq <= now.update.seq) return@update now
            if (!now.running && update.phase != ReplyPhase.Done) return@update now
            taken = true
            now.copy(update = update)
        }
        if (!taken) return
        _phone.value = PhoneState.Ready
        update.chatId?.let { store.adoptChat(it, update.title ?: store.chat.value.title) }
        store.setStats(update.model, update.contextPercent)
        if (update.phase == ReplyPhase.Done) {
            if (update.text.isNotBlank()) {
                store.lastReply = update.text
                TileService.getUpdater(context).requestUpdate(AskTileService::class.java)
            }
            if (update.speech == ReplySpeech.Unavailable && update.askId != silenced && store.speak.value && update.text.isNotBlank()) {
                scope.launch(Dispatchers.Main) { speech.say(update.askId, spokenText(update.text)) }
            }
        }
    }

    private fun fail(askId: String, message: String) {
        _turn.update { now ->
            if (now == null || now.askId != askId || !now.running) now else now.copy(update = now.update.copy(phase = ReplyPhase.Failed, error = message, activity = null))
        }
    }

    /** Sends a message to Herald on the phone; null when it went, else what to tell the person. */
    private suspend fun send(path: String, data: ByteArray): String? = reach(path, data) { node ->
        messages.sendMessage(node, path, data).await()
    }

    private suspend fun stream(path: String, data: ByteArray): String? = reach(path, data) { node ->
        val channel = channels.openChannel(node, path).await()
        try {
            val output = channels.getOutputStream(channel).await()
            withContext(Dispatchers.IO) { output.use { it.write(data) } }
        } finally {
            channels.close(channel)
        }
    }

    /** Runs [block] with Herald's phone node; [path] and [data] go to the fake phone when it stands in. */
    private suspend fun reach(path: String, data: ByteArray, block: suspend (String) -> Unit): String? {
        return try {
            val phone = capabilities.getCapability(WearCapabilities.PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes
            // A phone's capabilities can reach the watch minutes after Herald is installed or updated there: until
            // then the connected phone is tried anyway, and one without Herald never answers (ANSWER_WITHIN_MS).
            val connected = if (phone.isEmpty()) nodes.connectedNodes.await() else emptyList()
            val node = phone.firstOrNull { it.isNearby } ?: phone.firstOrNull() ?: connected.firstOrNull { it.isNearby } ?: connected.firstOrNull()
            if (node == null) {
                if (fake != null) {
                    _phone.value = PhoneState.Ready
                    fake.receive(path, data)
                    return null
                }
                _phone.value = PhoneState.NotConnected
                return NOT_CONNECTED
            }
            block(node.id)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "Couldn't reach your phone."
        }
    }

    companion object {
        const val NOT_CONNECTED = "Your phone isn't connected."
        const val NO_HERALD = "Herald on your phone didn't answer. Open it there, or update it."
        private const val ANSWER_WITHIN_MS = 30_000L
    }
}

/** A reply as it should sound in the watch's own voice: the words, without the markdown around them. */
internal fun spokenText(markdown: String): String = markdown
    .replace(Regex("```[\\s\\S]*?(?:```|$)"), "")
    .replace(Regex("""\[([^\]]+)\]\(([^)]+)\)"""), "$1")
    .replace(Regex("`([^`]+)`"), "$1")
    .replace(Regex("""\bhttps?://\S+""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("[*_~>#|]"), "")
    .replace(Regex("""\s+"""), " ")
    .trim()
