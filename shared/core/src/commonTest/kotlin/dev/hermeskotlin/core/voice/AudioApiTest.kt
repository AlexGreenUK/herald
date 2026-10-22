package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioApiTest {

    @Test
    fun aTranscriptionMayBeSilentAsLongAsItMayTake() = runTest {
        var timeouts: HttpTimeoutConfig? = null
        val api = AudioApi(createHttpClient(MockEngine { request ->
            timeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
            respond("""{"transcript":"hi"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }))

        api.transcribe(GatewayUrl.parse("https://hermes.example.ts.net"), byteArrayOf(1), "audio/wav", null)

        assertEquals(timeouts?.requestTimeoutMillis, timeouts?.socketTimeoutMillis)
    }
}
