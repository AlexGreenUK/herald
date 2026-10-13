package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.core.voice.Recording
import dev.hermeskotlin.core.voice.SpeechPlayer
import dev.hermeskotlin.core.voice.SpeechStream
import dev.hermeskotlin.core.voice.SpeechStreamEvent
import dev.hermeskotlin.core.voice.SpokenAudio
import dev.hermeskotlin.core.voice.VoiceActivity
import dev.hermeskotlin.core.voice.VoiceRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamedSpeechTest {

    private val gateway = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** The gateway's socket: creates the chat and takes prompts; the test plays the turn's events. */
    private class Socket : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }
        val submits = MutableStateFlow<List<JsonObject>>(emptyList())
        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"] ?: return
            val result = when (message["method"]?.jsonPrimitive?.contentOrNull) {
                "session.create" -> """{"session_id":"rt9","stored_session_id":"stored-9","message_count":0,"messages":[],"info":{}}"""
                "prompt.submit" -> """{"status":"streaming"}""".also { submits.update { it + message } }
                else -> "{}"
            }
            inbound.trySend("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) = Unit

        fun event(type: String, payload: String = "{}") {
            inbound.trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"rt9","payload":$payload}}""")
        }
    }

    /** Hears one request, then listens for good. */
    private class OneRequest : VoiceRecorder {
        var recordings = 0
        override suspend fun record(activity: VoiceActivity, onLevel: (Float) -> Unit, onSpeech: () -> Unit): Recording {
            if (recordings++ > 0) awaitCancellation()
            onSpeech()
            return Recording(byteArrayOf(1, 2, 3), "audio/wav", heardSpeech = true)
        }

        override fun finish() = Unit
    }

    private class Player : SpeechPlayer {
        val clips = mutableListOf<SpokenAudio>()
        val streamed = MutableStateFlow<List<ByteArray>>(emptyList())
        var rate = 0
        override suspend fun play(audio: SpokenAudio) {
            clips += audio
        }

        override suspend fun playPcm(sampleRate: Int, chunks: ReceiveChannel<ByteArray>, onSound: (Boolean) -> Unit) {
            rate = sampleRate
            onSound(true)
            for (chunk in chunks) streamed.update { it + chunk }
            onSound(false)
        }
    }

    private class Stream : SpeechStream {
        val gateway = Channel<SpeechStreamEvent>(Channel.UNLIMITED)
        val appended = MutableStateFlow("")
        var finished = false
        var closed = false
        // Like the gateway's socket, the answers end with End or Fallback.
        override val events: Flow<SpeechStreamEvent> = gateway.receiveAsFlow()
            .transformWhile { emit(it); it != SpeechStreamEvent.End && it != SpeechStreamEvent.Fallback }

        override suspend fun append(text: String) = appended.update { it + text }

        override suspend fun finish() {
            finished = true
        }

        override suspend fun close() {
            closed = true
            gateway.close()
        }
    }

    private class Setup(val socket: Socket, val voice: VoiceController, val player: Player, val spoken: MutableList<String>)

    /** A chained voice chat on a chat whose gateway voices replies through [stream], or can't when it's null. */
    private suspend fun TestScope.chat(stream: Stream?): Setup {
        val spoken = mutableListOf<String>()
        val client = createHttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                    "/api/audio/transcribe" -> respond("""{"ok":true,"transcript":"How was your day?"}""", HttpStatusCode.OK, json)
                    "/api/audio/speak" -> {
                        spoken += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject["text"]!!.jsonPrimitive.content
                        respond("""{"ok":true,"data_url":"data:audio/mpeg;base64,AAAA","mime_type":"audio/mpeg"}""", HttpStatusCode.OK, json)
                    }
                    else -> respond("""{"ok":true}""", HttpStatusCode.OK, json)
                }
            },
            PersistentCookiesStorage(InMemoryKeyValueStore()),
        )
        val socket = Socket()
        val connection = GatewayConnection(AuthApi(client, PersistentCookiesStorage(InMemoryKeyValueStore())), { _, _ -> socket }, backgroundScope)
        connection.start(gateway)
        connection.state.first { it is ConnectionState.Connected }
        val session = ChatSession(gateway, null, null, connection, SessionsApi(client), backgroundScope)
        session.start()
        val player = Player()
        val voice = VoiceController(AudioApi(client), OneRequest(), player, backgroundScope, backgroundScope, speechStreams = { _, _ -> stream })
        voice.startChat(session, gateway, "rin")
        socket.submits.first { it.isNotEmpty() }
        return Setup(socket, voice, player, spoken)
    }

    @Test
    fun aReplyIsVoicedWhileItIsStillBeingWritten() = runTest {
        val stream = Stream()
        val setup = chat(stream)

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"It was good. "}""")
        // The first sentence reaches the gateway while the reply is still being written.
        stream.appended.first { it == "It was good. " }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0, 2, 0)))
        setup.player.streamed.first { it.size == 1 }
        setup.voice.chat.first { it.phase == VoicePhase.Speaking }
        assertEquals(24_000, setup.player.rate)

        // Code isn't read out, nor an attached file.
        setup.socket.event("message.delta", """{"text":"Here:\n```kotlin\nval x = 1\n```\nMEDIA:/tmp/day.png\nAnd you?"}""")
        setup.socket.event("message.complete", """{"text":"It was good. Here:\n```kotlin\nval x = 1\n```\nMEDIA:/tmp/day.png\nAnd you?","status":"complete"}""")
        stream.appended.first { it.endsWith("And you?") }
        assertEquals("It was good. Here:\n\nAnd you?", stream.appended.value)
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(3, 0)))
        stream.gateway.send(SpeechStreamEvent.End)

        setup.voice.chat.first { it.phase == VoicePhase.Listening }
        assertTrue(stream.finished)
        assertEquals(2, setup.player.streamed.value.size)
        // Voiced once, as it streamed: never again from the finished reply.
        assertTrue(setup.spoken.isEmpty())
        assertTrue(setup.player.clips.isEmpty())
        assertTrue(stream.closed)
        setup.voice.stopChat()
    }

    @Test
    fun aGatewayThatMadeNoAudioReadsTheFinishedReply() = runTest {
        val stream = Stream()
        val setup = chat(stream)

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"All good."}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Fallback)
        setup.socket.event("message.complete", """{"text":"All good.","status":"complete"}""")

        setup.voice.chat.first { it.phase == VoicePhase.Listening && setup.spoken.isNotEmpty() }
        assertEquals(listOf("All good."), setup.spoken)
        assertEquals(1, setup.player.clips.size)
        setup.voice.stopChat()
    }

    @Test
    fun withoutTheStreamTheFinishedReplyIsReadOut() = runTest {
        val setup = chat(stream = null)

        setup.socket.event("message.start")
        setup.socket.event("message.complete", """{"text":"Fine, thanks.","status":"complete"}""")

        setup.voice.chat.first { it.phase == VoicePhase.Listening && setup.spoken.isNotEmpty() }
        assertEquals(listOf("Fine, thanks."), setup.spoken)
        setup.voice.stopChat()
    }

    @Test
    fun skippingStopsTheVoiceAndDoesntReadTheReplyAgain() = runTest {
        val stream = Stream()
        val setup = chat(stream)

        setup.socket.event("message.start")
        setup.socket.event("message.delta", """{"text":"A long story begins. "}""")
        stream.appended.first { it.isNotEmpty() }
        stream.gateway.send(SpeechStreamEvent.Start(sampleRate = 24_000))
        stream.gateway.send(SpeechStreamEvent.Audio(byteArrayOf(1, 0)))
        setup.voice.chat.first { it.phase == VoicePhase.Speaking }

        setup.voice.skipSpeech()
        setup.socket.event("message.complete", """{"text":"A long story begins. It goes on.","status":"complete"}""")

        setup.voice.chat.first { it.phase == VoicePhase.Listening }
        assertTrue(stream.closed)
        assertTrue(setup.spoken.isEmpty())
        assertFalse(setup.player.clips.isNotEmpty())
        setup.voice.stopChat()
    }
}
