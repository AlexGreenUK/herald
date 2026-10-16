package dev.hermeskotlin.core.media

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.takeFrom
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray

class LinkPreview(val url: String, val title: String, val description: String, val image: ByteArray?) {
    val domain: String get() = Url(url).host
}

internal class LinkMetadata(val title: String, val description: String, val image: String?)
internal expect fun linkMetadata(bytes: ByteArray, url: String): LinkMetadata?

/** The first three distinct webpage destinations in rendered Markdown order, excluding images and code. */
expect fun replyWebLinks(text: String): List<String>

internal fun webUrl(value: String): Url? = runCatching { Url(value) }.getOrNull()?.takeIf {
    it.protocol.name in setOf("https", "http") && it.host.isNotBlank() && it.user.isNullOrEmpty() && it.password.isNullOrEmpty()
}

/** Webpage cards use only the credential-free outside client, with bounded downloads and parallelism. */
class LinkPreviews(private val web: HttpClient) {
    private val permits = Semaphore(2)
    private val cacheLock = Mutex()
    private val cache = linkedMapOf<String, LinkPreview?>()

    suspend fun load(url: String): LinkPreview? = permits.withPermit {
        cacheLock.withLock { if (cache.containsKey(url)) return@withPermit cache[url] }
        val card = try {
            val page = fetch(url, 256 * 1024, "text/html", "application/xhtml+xml", prefix = true)
            page?.let { (pageUrl, bytes) ->
                val meta = withContext(Dispatchers.Default) { linkMetadata(bytes, pageUrl) } ?: return@let null
                val image = try {
                    withTimeoutOrNull(4_000) { meta.image?.let { fetch(it, 2 * 1024 * 1024, "image/")?.second } }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                LinkPreview(url, meta.title, meta.description, image)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        cacheLock.withLock {
            if (cache.size >= 32) cache.remove(cache.keys.first())
            cache[url] = card
        }
        card
    }

    private suspend fun fetch(source: String, limit: Int, vararg types: String, prefix: Boolean = false): Pair<String, ByteArray>? {
        var url = webUrl(source) ?: return null
        var partial: Pair<String, ByteArray>? = null
        return try {
            withTimeoutOrNull(6_000) {
                repeat(4) {
                    var redirect: Url? = null
                    val result = web.prepareGet(url) {
                        header(HttpHeaders.Accept, types.joinToString(", ") { if (it.endsWith('/')) "${it}*" else it })
                    }.execute { response ->
                        if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                            redirect = response.headers[HttpHeaders.Location]?.let { location ->
                                webUrl(URLBuilder(url).apply { takeFrom(location) }.buildString())
                            }
                            return@execute null
                        }
                        val type = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').lowercase()
                        if (response.status.value !in 200..299 || types.none { if (it.endsWith('/')) type.startsWith(it) else type == it } ||
                            (!prefix && (response.contentLength() ?: 0) > limit)) return@execute null
                        val channel = response.bodyAsChannel()
                        var bytes = channel.readRemaining(limit.toLong() + if (prefix) 0 else 1).readByteArray()
                        if (prefix && bytes.size == limit) {
                            partial = url.toString() to bytes
                            val meta = withContext(Dispatchers.Default) { linkMetadata(bytes, url.toString()) }
                            if (meta != null && meta.image == null) {
                                bytes += channel.readRemaining((2 * 1024 * 1024 - bytes.size).toLong()).readByteArray()
                            }
                        }
                        if (bytes.size > limit && !prefix) null else url.toString() to bytes
                    }
                    if (result != null) return@withTimeoutOrNull result
                    url = redirect ?: return@withTimeoutOrNull null
                }
                null
            } ?: partial
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            partial ?: throw e
        }
    }
}
