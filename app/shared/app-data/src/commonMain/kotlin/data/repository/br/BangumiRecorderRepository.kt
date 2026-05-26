/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.br

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class BangumiRecorderRepository(
    private val dataStore: DataStore<BangumiRecorderSave>,
) {
    val save: Flow<BangumiRecorderSave> = dataStore.data
    val activeConnection: Flow<BangumiRecorderConnection?> = save.map { store ->
        store.activeId?.let { activeId -> store.connections.firstOrNull { it.id == activeId } }
    }

    suspend fun upsert(connection: BangumiRecorderConnection, activate: Boolean = true) {
        dataStore.updateData { store ->
            val normalized = connection.copy(serverUrl = connection.serverUrl.trimEnd('/'))
            store.copy(
                connections = store.connections.filterNot { it.id == normalized.id } + normalized,
                activeId = if (activate) normalized.id else store.activeId ?: normalized.id,
            )
        }
    }

    suspend fun remove(id: String) {
        dataStore.updateData { store ->
            val rest = store.connections.filterNot { it.id == id }
            store.copy(connections = rest, activeId = store.activeId.takeIf { it != id } ?: rest.firstOrNull()?.id)
        }
    }

    suspend fun setActive(id: String) {
        dataStore.updateData { store ->
            if (store.connections.any { it.id == id }) store.copy(activeId = id) else store
        }
    }

    suspend fun updateLastSync(id: String, lastSyncAt: Long) {
        dataStore.updateData { store ->
            store.copy(connections = store.connections.map { connection ->
                if (connection.id == id) connection.copy(lastSyncAt = lastSyncAt) else connection
            })
        }
    }

    suspend fun updateJwtToken(id: String, token: String, apiToken: String?, expiresAt: Long) {
        dataStore.updateData { store ->
            store.copy(connections = store.connections.map { connection ->
                if (connection.id == id && connection.auth is BangumiRecorderAuth.Jwt) {
                    connection.copy(auth = connection.auth.copy(token = token, apiToken = apiToken, expiresAt = expiresAt))
                } else {
                    connection
                }
            })
        }
    }

    suspend fun snapshot(): BangumiRecorderSave = dataStore.data.first()
}

@Serializable
data class BangumiRecorderSave(
    val connections: List<BangumiRecorderConnection> = emptyList(),
    val activeId: String? = null,
) {
    companion object {
        val Initial = BangumiRecorderSave()
    }
}

@Serializable
data class BangumiRecorderConnection(
    val id: String,
    val name: String,
    val serverUrl: String,
    val auth: BangumiRecorderAuth,
    val lastSyncAt: Long = 0,
)

@Serializable
sealed interface BangumiRecorderAuth {
    @Serializable
    @SerialName("api_token")
    data class ApiToken(val token: String) : BangumiRecorderAuth

    @Serializable
    @SerialName("jwt")
    data class Jwt(
        val username: String,
        val password: String,
        val token: String,
        val apiToken: String? = null,
        val expiresAt: Long = Clock.System.now().plus(7.days).toEpochMilliseconds(),
    ) : BangumiRecorderAuth
}
