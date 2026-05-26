/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.br

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.him188.ani.utils.ktor.ScopedHttpClient
import kotlin.time.Instant

class BangumiRecorderClient(
    private val httpClient: ScopedHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun login(serverUrl: String, username: String, password: String): String {
        return httpClient.use { post("${serverUrl.trimEnd('/')}/api/v2/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        } }.decodeApiResponse<JsonObject>()["token"]!!.jsonPrimitive.content
    }

    suspend fun createApiToken(serverUrl: String, jwt: String): String {
        return httpClient.use { post("${serverUrl.trimEnd('/')}/api/v2/me/token") {
            bearerAuth(jwt)
        } }.decodeApiResponse<JsonObject>()["api_token"]!!.jsonPrimitive.content
    }

    suspend fun test(connection: BangumiRecorderConnection): Boolean {
        return runCatching {
            when (val auth = connection.auth) {
                is BangumiRecorderAuth.ApiToken -> openGet<JsonElement>(connection, "/api/v2/open/records", auth.token)
                is BangumiRecorderAuth.Jwt -> httpClient.use { get("${connection.serverUrl}/api/v2/me") {
                    bearerAuth(auth.token)
                } }.decodeApiResponse<JsonElement>()
            }
        }.isSuccess
    }

    suspend fun incrementalSync(connection: BangumiRecorderConnection, token: String, since: Instant): SyncResponseData {
        val records = openGet<List<SyncResponseRecord>>(connection, "/api/v2/open/sync", token) {
            parameter("since", since.toBangumiRecorderDateTime())
        }
        return SyncResponseData(records = records)
    }

    suspend fun sync(connection: BangumiRecorderConnection, token: String, records: List<SyncRequestRecord>): SyncResponseData {
        return httpClient.use { post("${connection.serverUrl}/api/v2/open/sync") {
            parameter("token", token)
            contentType(ContentType.Application.Json)
            setBody(SyncRequest(records))
        } }.decodeApiResponse<SyncResponseData>()
    }

    suspend fun bangumi(connection: BangumiRecorderConnection, token: String, id: Int): BangumiRecorderBangumiItem {
        return openGet(connection, "/api/v2/open/bangumi/$id", token)
    }

    suspend fun episodes(connection: BangumiRecorderConnection, token: String, id: Int): List<BangumiRecorderEpisodeItem> {
        return openGet(connection, "/api/v2/open/episodes/$id", token)
    }

    suspend fun updateEpisode(
        connection: BangumiRecorderConnection,
        token: String,
        bangumiId: Int,
        ordinal: Int,
        watched: Boolean,
        progressSeconds: Int? = null,
        durationSeconds: Int? = null,
    ): BangumiRecorderEpisodeItem {
        return httpClient.use { patch("${connection.serverUrl}/api/v2/open/episodes/$bangumiId/$ordinal") {
            parameter("token", token)
            contentType(ContentType.Application.Json)
            setBody(EpisodePatch(watched, progressSeconds, durationSeconds))
        } }.decodeApiResponse<BangumiRecorderEpisodeItem>()
    }

    private suspend inline fun <reified T> openGet(
        connection: BangumiRecorderConnection,
        path: String,
        token: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        return httpClient.use { get("${connection.serverUrl}$path") {
            parameter("token", token)
            block()
        } }.decodeApiResponse<T>()
    }

    private suspend inline fun <reified T> HttpResponse.decodeApiResponse(): T {
        return json.decodeFromString<ApiResponse<T>>(bodyAsText()).dataOrThrow()
    }
}

fun Instant.toBangumiRecorderDateTime(): String {
    return toString().removeSuffix("Z").substringBefore('.')
}

@Serializable
private data class LoginRequest(val username: String, val password: String)

@Serializable
private data class ApiResponse<T>(val status: Int, val data: T? = null, val message: String? = null) {
    fun dataOrThrow(): T = if (status == 0 && data != null) data else error(message ?: "Bangumi-Recorder request failed")
}

@Serializable
private data class SyncRequest(val records: List<SyncRequestRecord>)

@Serializable
data class SyncRequestRecord(
    @SerialName("bangumi_id") val bangumiId: String,
    val recorder: String? = null,
    @SerialName("user_status") val userStatus: Int? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class SyncResponseData(
    val records: List<SyncResponseRecord> = emptyList(),
    val deleted: List<String> = emptyList(),
)

@Serializable
data class SyncResponseRecord(
    @SerialName("bangumi_id") val bangumiId: String,
    val recorder: String? = null,
    @SerialName("user_status") val userStatus: Int? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class BangumiRecorderBangumiItem(
    @SerialName("bangumi_id") val bangumiId: String,
    val title: String,
    @SerialName("cover_url") val coverUrl: String = "",
    val episodes: Int = 0,
    val description: String = "",
)

@Serializable
data class BangumiRecorderEpisodeItem(
    val ordinal: Int,
    val title: String? = null,
    @SerialName("name_cn") val nameCn: String? = null,
    val watched: Boolean,
    @SerialName("progress_seconds") val progressSeconds: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
)

@Serializable
private data class EpisodePatch(
    val watched: Boolean,
    @SerialName("progress_seconds") val progressSeconds: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
)
