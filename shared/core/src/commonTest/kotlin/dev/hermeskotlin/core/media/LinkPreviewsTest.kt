package dev.hermeskotlin.core.media

import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinkPreviewsTest {
    private val html = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")

    @Test fun selectsThreeDistinctLinksOutsideCodeAndImages() {
        val text = """
            `https://code.example` ![image](https://image.example/a.png)
            [https://label.example](https://one.example/a_(b)) https://one.example/a_(b).
            ```
            https://fenced.example
            ```
            <https://two.example> https://three.example?q=one&x=two. https://four.example
        """.trimIndent()
        assertEquals(listOf("https://one.example/a_(b)", "https://two.example", "https://three.example?q=one&x=two"), replyWebLinks(text))
    }

    @Test fun followsRenderedMarkdownDestinationsAndIgnoresCodeAndReferenceImages() {
        assertEquals(emptyList(), replyWebLinks("    https://code.example"))
        assertEquals(emptyList(), replyWebLinks("[https://label.test](mailto:a@example.com)"))
        assertEquals(emptyList(), replyWebLinks("![alt][pic]\n\n[pic]: https://images.example/a.png"))
        assertEquals(listOf("https://e.test/a)"), replyWebLinks("[x](<https://e.test/a)>)"))
        assertEquals(listOf("https://e.test"), replyWebLinks("[https://e.test](https://e.test)?prose"))
        assertEquals(listOf("https://e.test/a?x=1&y=2"), replyWebLinks("[x](https://e.test/a?x=1&amp;y=2)"))
        assertEquals(listOf("https://second.example", "https://first.example"), replyWebLinks(
            "[second][b] [first][a]\n\n[a]: https://first.example\n[b]: https://second.example",
        ))
    }

    @Test fun redirectsAndParsesMetadataWithRelativeThumbnail() = runBlocking {
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/start" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/article"))
                "/photo.png" -> respond("picture", headers = headersOf(HttpHeaders.ContentType, "image/png"))
                else -> respond("""<TITLE>Fallback</TITLE><META content='Cats &amp; Dogs' property='og:title'>
                    <meta name='description' content='A useful article'><meta property='og:image' content='/photo.png'>""", headers = html)
            }
        }))
        val card = assertNotNull(api.load("https://example.com/start"))
        assertEquals("Cats & Dogs", card.title)
        assertEquals("A useful article", card.description)
        assertEquals("picture", card.image?.decodeToString())
        assertEquals("https://example.com/start", card.url)
    }

    @Test fun failedImageKeepsTitleAndDescription() = runBlocking {
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/bad") respond("missing", HttpStatusCode.NotFound)
            else respond("""<title>Page title</title><meta name='twitter:description' content='Details'><meta name='twitter:image' content='/bad'>""", headers = html)
        }))
        val card = assertNotNull(api.load("https://example.com"))
        assertEquals("Page title", card.title)
        assertEquals("Details", card.description)
        assertNull(card.image)
    }

    @Test fun thumbnailNetworkFailureKeepsTheCard() = runBlocking {
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/broken") error("connection reset")
            respond("""<title>Still useful</title><meta property='og:image' content='/broken'>""", headers = html)
        }))
        assertEquals("Still useful", api.load("https://example.com")?.title)
    }

    @Test fun skipsNonHtmlMissingTitlesAndUnsafeUrls() = runBlocking {
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/json" -> respond("<title>Not HTML</title>", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                "/redirect" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "file:///private"))
                else -> respond("<p>No title</p>", headers = html)
            }
        }))
        for (url in listOf("https://example.com/json", "https://example.com/redirect", "https://example.com/empty", "file:///private", "https://user:password@example.com")) assertNull(api.load(url))
    }

    @Test fun largePagesUseOnlyABoundedPrefix() = runBlocking {
        val padding = " ".repeat(262_144)
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            val body = if (request.url.encodedPath == "/late") padding + "<title>Too late</title>"
                else "<title>Large page</title>" + padding
            val headers = if (request.url.encodedPath == "/declared")
                headersOf(HttpHeaders.ContentType to listOf("text/html"), HttpHeaders.ContentLength to listOf(body.length.toString())) else html
            respond(body, headers = headers)
        }))
        assertEquals("Large page", api.load("https://example.com/chunked")?.title)
        assertEquals("Large page", api.load("https://example.com/declared")?.title)
        assertNull(api.load("https://example.com/late"))
    }

    @Test fun boundsThumbnailBodiesWithoutRelyingOnContentLength() = runBlocking {
        val api = LinkPreviews(createHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/image") respond("x".repeat(2_097_153), headers = headersOf(HttpHeaders.ContentType, "image/png"))
            else respond("""<title>Small</title><meta property='og:image' content='/image'>""", headers = html)
        }))
        assertNull(assertNotNull(api.load("https://example.com/small")).image)
    }

    @Test fun limitsParallelLoadsAndCachesFailures() = runBlocking {
        val lock = Mutex()
        var active = 0
        var peak = 0
        var calls = 0
        val api = LinkPreviews(createHttpClient(MockEngine {
            lock.withLock {
                calls++
                active++
                peak = maxOf(peak, active)
            }
            delay(10)
            lock.withLock { active-- }
            respond("", HttpStatusCode.NotFound)
        }))
        (1..3).map { async { api.load("https://example.com/$it") } }.awaitAll()
        assertEquals(2, peak)
        assertNull(api.load("https://example.com/1"))
        assertEquals(3, calls)
    }

    @Test fun cancellationIsNotCachedAsFailure() = runBlocking {
        var cancelled = true
        val api = LinkPreviews(createHttpClient(MockEngine {
            if (cancelled) throw CancellationException("left chat")
            respond("<title>Back</title>", headers = html)
        }))
        assertFailsWith<CancellationException> { api.load("https://example.com") }
        cancelled = false
        assertEquals("Back", api.load("https://example.com")?.title)
    }
}
