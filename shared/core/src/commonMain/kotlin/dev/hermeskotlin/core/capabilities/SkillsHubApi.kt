package dev.hermeskotlin.core.capabilities

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class OfficialResponse(val skills: List<HubSkill> = emptyList())

/** The skills hub through the dashboard routes: browse, search, preview and scan. */
class SkillsHubApi(
    private val client: HttpClient,
    private val pollIntervalMs: Long = 1_000,
) {

    suspend fun sources(url: GatewayUrl, profile: String?): ApiResult<HubSources> = apiCall {
        client.get(url.resolve("api/skills/hub/sources")) { profile(profile) }
    }.map { it.decode<HubSources>() }

    suspend fun official(url: GatewayUrl, profile: String?): ApiResult<List<HubSkill>> = apiCall {
        client.get(url.resolve("api/skills/hub/official")) { profile(profile) }
    }.map { it.decode<OfficialResponse>().skills }

    suspend fun search(url: GatewayUrl, profile: String?, query: String, limit: Int = 20): ApiResult<HubSearch> = apiCall {
        client.get(url.resolve("api/skills/hub/search")) {
            profile(profile)
            parameter("q", query)
            parameter("source", "all")
            parameter("limit", limit)
        }
    }.map { it.decode<HubSearch>() }

    suspend fun preview(url: GatewayUrl, identifier: String): ApiResult<HubPreview> = apiCall {
        client.get(url.resolve("api/skills/hub/preview")) { parameter("identifier", identifier) }
    }.map { it.decode<HubPreview>() }

    suspend fun scan(url: GatewayUrl, identifier: String): ApiResult<HubScan> = apiCall {
        client.get(url.resolve("api/skills/hub/scan")) { parameter("identifier", identifier) }
    }.map { it.decode<HubScan>() }

    private fun HttpRequestBuilder.profile(profile: String?) {
        profile?.let { parameter("profile", it) }
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = lenientJson.decodeFromString<T>(bodyAsText())

    private companion object {
        /** The gateway writes `null` for what it doesn't have (a preview's tags, files); that reads as the field's default. */
        val lenientJson = Json(HermesJson) { coerceInputValues = true }
    }
}
