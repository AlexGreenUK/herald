package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the gateway sends back while it voices a reply. */
sealed interface SpeechStreamEvent {
    /** Audio follows: 16-bit little-endian PCM at [sampleRate]. */
    data class Start(val sampleRate: Int, val channels: Int = 1) : SpeechStreamEvent

    class Audio(val pcm: ByteArray) : SpeechStreamEvent

    /** Everything said has been sent. */
    data object End : SpeechStreamEvent

    /** The gateway made no audio at all; the reply should be voiced the old way. */
    data object Fallback : SpeechStreamEvent
}

/**
 * One reply's speech, voiced while it is still being written (`/api/audio/speak-stream`, Desktop's live
 * read-aloud): the reply's text goes in as it arrives, the gateway cuts it into sentences and sends each
 * one's audio as soon as it's ready, so the first words play long before the reply is finished.
 */
interface SpeechStream {
    /** The gateway's answers; ends after [SpeechStreamEvent.End] or [SpeechStreamEvent.Fallback], or when the socket drops. */
    val events: Flow<SpeechStreamEvent>

    /** More of the reply, as written; the gateway drops Markdown and emoji sentence by sentence. */
    suspend fun append(text: String)

    /** The reply is complete: what's left is voiced, then [SpeechStreamEvent.End]. */
    suspend fun finish()

    /** Stops at once: nothing more is voiced. */
    suspend fun close()
}

/** Opens [SpeechStream]s; null when this gateway can't (an older one, or no socket). */
fun interface SpeechStreams {
    suspend fun open(url: GatewayUrl, profile: String?): SpeechStream?
}

/**
 * [SpeechStreams] over the gateway's socket. The socket takes a fresh single-use ticket (`?ticket=`, valid
 * 30 seconds), as Desktop's sibling sockets do, and `?profile=`, so the profile's own voice speaks.
 */
class GatewaySpeechStreams(private val client: HttpClient, private val auth: AuthApi) : SpeechStreams {

    override suspend fun open(url: GatewayUrl, profile: String?): SpeechStream? {
        val ticket = (auth.mintWsTicket(url) as? ApiResult.Success)?.value?.ticket ?: return null
        val query = buildString {
            append("ticket=").append(ticket.encodeURLParameter())
            profile?.let { append("&profile=").append(it.encodeURLParameter()) }
        }
        val session = try {
            client.webSocketSession(url.siblingSocketUrl("api/audio/speak-stream") + "?" + query)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return KtorSpeechStream(session)
    }
}

private class KtorSpeechStream(private val session: DefaultClientWebSocketSession) : SpeechStream {

    override val events: Flow<SpeechStreamEvent> = flow {
        try {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Binary -> emit(SpeechStreamEvent.Audio(frame.readBytes()))
                    is Frame.Text -> {
                        val event = parseSpeechFrame(frame.readText()) ?: continue
                        emit(event)
                        if (event == SpeechStreamEvent.End || event == SpeechStreamEvent.Fallback) return@flow
                    }
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A dropped socket ends the speech with what was said.
        }
    }

    override suspend fun append(text: String) {
        if (text.isNotEmpty()) sendQuietly(buildJsonObject { put("text", text) })
    }

    override suspend fun finish() = sendQuietly(buildJsonObject { put("done", true) })

    override suspend fun close() {
        sendQuietly(buildJsonObject { put("stop", true) })
        runCatching { session.close() }
    }

    /** A socket already gone has nothing to say back; the events end on their own. */
    private suspend fun sendQuietly(frame: JsonObject) {
        try {
            session.send(Frame.Text(frame.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}

/** The gateway's text frames: `start` with the audio's rate, `end`, `fallback`; anything else is ignored. */
internal fun parseSpeechFrame(text: String): SpeechStreamEvent? {
    val json = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    return when (json["type"]?.jsonPrimitive?.contentOrNull) {
        "start" -> SpeechStreamEvent.Start(
            sampleRate = json["sample_rate"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: 24_000,
            channels = json["channels"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: 1,
        )
        "end" -> SpeechStreamEvent.End
        "fallback" -> SpeechStreamEvent.Fallback
        else -> null
    }
}

private val FENCE = Regex("```")
private val MEDIA_LINE = Regex("""(?m)^[ \t]*MEDIA:\S+[ \t]*\r?\n""")
private val MEDIA_INLINE = Regex("""(?<!\S)MEDIA:\S+?(?=[.,;:!?)\]]*(?:\s|$))""")
private const val MEDIA = "MEDIA:"

/**
 * The part of a reply still being written that can be voiced so far. Code blocks are left out, and an open
 * one holds back everything after its start until it closes, so code is never read out; a file the agent
 * attaches (`MEDIA:` on a line of its own or among the words) is left out too. What could still turn into
 * either (a backtick or two at the end, a last line or word starting like `MEDIA:`) waits for the next
 * words, so the result only ever grows as the reply does and the new part can be sent as it comes. Once the
 * reply is [complete], nothing is waiting for more: what was held back is voiced.
 */
fun speakableSoFar(reply: String, complete: Boolean = false): String {
    val ticks = reply.takeLastWhile { it == '`' }.length
    val text = if (ticks in 1..2 && !complete) reply.dropLast(ticks) else reply
    val out = StringBuilder()
    var start = 0
    var inCode = false
    for (match in FENCE.findAll(text)) {
        if (!inCode) out.append(text, start, match.range.first)
        inCode = !inCode
        start = match.range.last + 1
    }
    if (!inCode) out.append(text, start, text.length)
    if (!complete) {
        val lastLine = out.substring(out.lastIndexOf("\n") + 1).trimStart()
        if (lastLine.isNotEmpty() && (MEDIA.startsWith(lastLine) || lastLine.startsWith(MEDIA))) {
            out.setLength(out.lastIndexOf("\n") + 1)
        }
        val lastWord = out.takeLastWhile { !it.isWhitespace() }
        if (lastWord.isNotEmpty() && (MEDIA.startsWith(lastWord) || lastWord.startsWith(MEDIA))) {
            out.setLength(out.length - lastWord.length)
        }
    }
    return out.toString().replace(MEDIA_LINE, "").replace(MEDIA_INLINE, "")
}
