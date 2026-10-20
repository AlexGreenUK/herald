package dev.hermeskotlin.core.plugins

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class NextPromptApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(engine: MockEngine) = createHttpClient(engine).let { NextPromptApi(it, PluginsApi(it)) }

    @Test
    fun readsTheChatsSuggestionWithItsProfile() = runTest {
        var request: String? = null
        val api = api(MockEngine {
            request = "${it.method.value} ${it.url.encodedPath}?${it.url.encodedQuery}"
            respond(
                """{"suggestion":{"text":"Commit the parser fix","session_id":"s1","timestamp":1790000000.5},"error":null}""",
                HttpStatusCode.OK, json,
            )
        })

        val state = assertIs<ApiResult.Success<NextPromptState>>(api.state(url, "s1", "work")).value

        assertEquals("GET /api/plugins/next-prompt/suggestion?session_id=s1&profile=work", request)
        assertEquals(NextPrompt("Commit the parser fix", "s1", 1790000000.5), state.suggestion)
        assertNull(state.error)
    }

    @Test
    fun readsNoSuggestionAndAFailure() = runTest {
        val empty = api(MockEngine { respond("""{"suggestion":null,"error":null}""", HttpStatusCode.OK, json) })
        assertEquals(NextPromptState(), assertIs<ApiResult.Success<NextPromptState>>(empty.state(url, "s1", null)).value)

        val failed = api(MockEngine {
            respond("""{"suggestion":null,"error":{"kind":"rate_limit","session_id":"s1","timestamp":1.0}}""", HttpStatusCode.OK, json)
        })
        val state = assertIs<ApiResult.Success<NextPromptState>>(failed.state(url, "s1", null)).value
        assertNull(state.suggestion)
        assertEquals("rate_limit", state.error?.kind)
    }

    @Test
    fun dismissPostsTheChat() = runTest {
        var request: String? = null
        val api = api(MockEngine {
            request = "${it.method.value} ${it.url.encodedPath}?${it.url.encodedQuery}"
            respond("""{"ok":true}""", HttpStatusCode.OK, json)
        })

        assertIs<ApiResult.Success<Unit>>(api.dismiss(url, "s1", null))
        assertEquals("${HttpMethod.Post.value} /api/plugins/next-prompt/dismiss?session_id=s1", request)
    }

    @Test
    fun installedWhenTheGatewayListsThePluginEvenWithItsTabHidden() = runTest {
        val with = api(MockEngine {
            respond(
                """[{"name":"example-board","tab":{"path":"/example-board"}},
                    {"name":"next-prompt","label":"Next Prompt","version":"0.3.1","tab":{"path":"/next-prompt","position":"end","hidden":true}}]""",
                HttpStatusCode.OK, json,
            )
        })
        assertEquals(true, assertIs<ApiResult.Success<Boolean>>(with.installed(url, null)).value)

        val without = api(MockEngine { respond("""[{"name":"example-board"}]""", HttpStatusCode.OK, json) })
        assertEquals(false, assertIs<ApiResult.Success<Boolean>>(without.installed(url, null)).value)
    }
}
