package dev.hermeskotlin.core.capabilities

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillsHubApiTest {

    private val url = GatewayUrl.parse("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun sourcesCarryFeaturedAndInstalledForTheProfile() = runTest {
        var query: String? = null
        val api = SkillsHubApi(createHttpClient(MockEngine { request ->
            query = request.url.encodedQuery
            respond(
                """{"sources":[{"id":"official","label":"Official"}],"index_available":true,"featured":[{"name":"ascii-art","description":"ASCII art","source":"official","identifier":"official/creative/ascii-art","trust_level":"builtin","repo":null,"tags":["art"]}],"installed":{"official/creative/ascii-art":{"name":"ascii-art","trust_level":"builtin","scan_verdict":"safe"}}}""",
                HttpStatusCode.OK, json,
            )
        }))

        val sources = assertIs<ApiResult.Success<HubSources>>(api.sources(url, "ruby")).value

        assertTrue(query!!.contains("profile=ruby"))
        assertTrue(sources.featured.single().isOfficial)
        assertEquals(setOf("official/creative/ascii-art"), sources.installed.keys)
    }

    @Test
    fun officialUnwrapsSkillsWithCategory() = runTest {
        val api = SkillsHubApi(createHttpClient(MockEngine {
            respond("""{"skills":[{"name":"x","identifier":"official/research/x","category":"research","installed":true}]}""", HttpStatusCode.OK, json)
        }))

        val skill = assertIs<ApiResult.Success<List<HubSkill>>>(api.official(url, null)).value.single()

        assertEquals("research", skill.category)
        assertTrue(skill.installed)
    }

    @Test
    fun searchSendsQueryAndReadsTimeouts() = runTest {
        var query: String? = null
        val api = SkillsHubApi(createHttpClient(MockEngine { request ->
            query = request.url.encodedQuery
            respond(
                """{"results":[{"name":"pdf","source":"clawhub","identifier":"clawhub/org/pdf","trust_level":"community"}],"source_counts":{},"timed_out":["github"],"installed":{}}""",
                HttpStatusCode.OK, json,
            )
        }))

        val search = assertIs<ApiResult.Success<HubSearch>>(api.search(url, null, "pdf")).value

        val params = query!!.split("&")
        assertTrue("q=pdf" in params && "source=all" in params && "limit=20" in params)
        assertEquals(listOf("github"), search.timedOut)
        assertFalse(search.results.single().isOfficial)
    }

    @Test
    fun previewAndScanDecodeNullsAsDefaults() = runTest {
        val api = SkillsHubApi(createHttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("preview")) {
                respond("""{"name":"pdf","identifier":"clawhub/org/pdf","repo":null,"tags":null,"files":null}""", HttpStatusCode.OK, json)
            } else {
                respond(
                    """{"verdict":"caution","policy":"ask","findings":[{"severity":"high","category":"exfiltration","file":"run.sh","line":3,"description":"curl to remote host"}]}""",
                    HttpStatusCode.OK, json,
                )
            }
        }))

        val preview = assertIs<ApiResult.Success<HubPreview>>(api.preview(url, "clawhub/org/pdf")).value
        assertEquals(emptyList(), preview.tags)
        assertEquals(emptyList(), preview.files)

        val scan = assertIs<ApiResult.Success<HubScan>>(api.scan(url, "clawhub/org/pdf")).value
        assertEquals("ask", scan.policy)
        assertEquals(3, scan.findings.single().line)
    }

    @Test
    fun missingRouteIsAFailureNotACrash() = runTest {
        val api = SkillsHubApi(createHttpClient(MockEngine {
            respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, json)
        }))

        val failed = assertIs<ApiResult.Failed>(api.sources(url, null))
        assertEquals(404, failed.status)
    }
}
