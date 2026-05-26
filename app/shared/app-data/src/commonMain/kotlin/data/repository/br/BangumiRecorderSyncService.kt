/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.br

import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionDao
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionDao
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectRelations
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.platform.currentTimeMillis
import kotlinx.coroutines.flow.firstOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class BangumiRecorderSyncService(
    private val repository: BangumiRecorderRepository,
    private val client: BangumiRecorderClient,
    private val subjectCollectionDao: SubjectCollectionDao,
    private val episodeCollectionDao: EpisodeCollectionDao,
    private val episodePlayHistoryRepository: EpisodePlayHistoryRepository,
    private val clock: Clock = Clock.System,
) {
    suspend fun syncActive(): BangumiRecorderSyncResult {
        val connection = repository.activeConnection.firstOrNullCompat()
            ?: return BangumiRecorderSyncResult(false, "未配置 Bangumi-Recorder")
        return sync(connection)
    }

    suspend fun sync(connection: BangumiRecorderConnection): BangumiRecorderSyncResult {
        val openToken = ensureOpenApiToken(connection)
        val since = if (connection.lastSyncAt > 0) {
            Instant.fromEpochMilliseconds(connection.lastSyncAt)
        } else {
            Instant.fromEpochMilliseconds(0)
        }

        val remote = client.incrementalSync(connection, openToken, since)
        val localProgressSeconds = episodePlayHistoryRepository.flow.firstOrNull()
            .orEmpty()
            .associate { it.episodeId to it.positionMillis.toProgressSeconds() }
        val localSubjects = subjectCollectionDao.allCollectedSync()
        val localSubjectsById = localSubjects.associateBy { it.subjectId }
        val localEpisodesBySubjectId = episodeCollectionDao.allSync().groupBy { it.subjectId }
        val localRecorders = localSubjects.associate {
            it.subjectId to buildRecorder(localEpisodesBySubjectId[it.subjectId].orEmpty(), localProgressSeconds)
        }
        val localRecords = localSubjects.map {
            it.toSyncRequestRecord(localRecorders[it.subjectId])
        }
        val authoritative = client.sync(connection, openToken, localRecords + remote.records.map { it.toSyncRequestRecord() })

        var applied = 0
        val recorderUpdates = mutableListOf<SyncRequestRecord>()
        for (record in authoritative.records) {
            val subjectId = record.bangumiId.toIntOrNull() ?: continue
            if (record.userStatus == null) continue
            val local = localSubjectsById[subjectId]
            if (local == null) {
                val item = runCatching { client.bangumi(connection, openToken, subjectId) }.getOrNull() ?: continue
                subjectCollectionDao.upsert(item.toSubjectEntity(record))
            } else if (local.collectionType != record.userStatus.toCollectionType()) {
                subjectCollectionDao.updateType(
                    subjectId = subjectId,
                    collectionType = record.userStatus.toCollectionType(),
                    lastUpdated = record.updatedAt.parseBangumiRecorderDateTime().toEpochMilliseconds(),
                )
            }
            applied++
            val localEpisodes = localEpisodesBySubjectId[subjectId].orEmpty()
            val recorder = localRecorders[subjectId]
            val updatedEpisodes = localEpisodes.any {
                it.selfCollectionType == UnifiedCollectionType.DONE || localProgressSeconds.containsKey(it.episodeId)
            } && syncEpisodes(connection, openToken, subjectId, localEpisodes, localProgressSeconds)
            if (recorder != null && updatedEpisodes) {
                recorderUpdates += SyncRequestRecord(
                    bangumiId = record.bangumiId,
                    recorder = recorder,
                    userStatus = record.userStatus,
                    updatedAt = clock.now().toBangumiRecorderDateTime(),
                )
            }
        }

        if (recorderUpdates.isNotEmpty()) {
            client.sync(connection, openToken, recorderUpdates)
        }

        val now = clock.now().toEpochMilliseconds()
        repository.updateLastSync(connection.id, now)
        return BangumiRecorderSyncResult(true, "已同步 $applied 条记录", applied)
    }

    suspend fun pushEpisode(subjectId: Int, episodeOrdinal: Int, watched: Boolean, progressSeconds: Int? = null) {
        val connection = repository.activeConnection.firstOrNullCompat() ?: return
        val openToken = ensureOpenApiToken(connection)
        client.updateEpisode(connection, openToken, subjectId, episodeOrdinal, watched, progressSeconds)
    }

    private suspend fun syncEpisodes(
        connection: BangumiRecorderConnection,
        token: String,
        subjectId: Int,
        localEpisodes: List<EpisodeCollectionEntity>,
        localProgressSeconds: Map<Int, Int>,
    ): Boolean {
        var updated = false
        val remoteEpisodes = runCatching { client.episodes(connection, token, subjectId) }.getOrNull().orEmpty()
        for (episode in localEpisodes) {
            val ordinal = episode.sortNumber.toInt().takeIf { it > 0 } ?: continue
            val remote = remoteEpisodes.firstOrNull { it.ordinal == ordinal }
            val watched = episode.selfCollectionType == UnifiedCollectionType.DONE
            val progressSeconds = localProgressSeconds[episode.episodeId]
            if (remote?.watched != watched || progressSeconds != null && remote.progressSeconds != progressSeconds) {
                client.updateEpisode(connection, token, subjectId, ordinal, watched, progressSeconds)
                updated = true
            }
        }
        return updated
    }

    private suspend fun ensureOpenApiToken(connection: BangumiRecorderConnection): String {
        return when (val auth = connection.auth) {
            is BangumiRecorderAuth.ApiToken -> auth.token
            is BangumiRecorderAuth.Jwt -> {
                val jwt = if (auth.expiresAt - clock.now().toEpochMilliseconds() < 1.days.inWholeMilliseconds) {
                    client.login(connection.serverUrl, auth.username, auth.password)
                } else {
                    auth.token
                }
                val apiToken = auth.apiToken ?: client.createApiToken(connection.serverUrl, jwt)
                if (jwt != auth.token || apiToken != auth.apiToken) {
                    repository.updateJwtToken(
                        connection.id,
                        jwt,
                        apiToken,
                        clock.now().plus(7.days).toEpochMilliseconds(),
                    )
                }
                apiToken
            }
        }
    }

    private fun buildRecorder(
        episodes: List<EpisodeCollectionEntity>,
        progressSecondsByEpisodeId: Map<Int, Int>,
    ): String? {
        val maxDoneOrdinal = episodes.asSequence()
            .filter { it.selfCollectionType == UnifiedCollectionType.DONE }
            .mapNotNull { it.sortNumber.toInt().takeIf { ordinal -> ordinal > 0 } }
            .maxOrNull()
            ?: 0
        val maxProgressOrdinal = episodes.asSequence()
            .filter { progressSecondsByEpisodeId.containsKey(it.episodeId) }
            .mapNotNull { it.sortNumber.toInt().takeIf { ordinal -> ordinal > 0 } }
            .maxOrNull()
            ?: 0
        val currentOrdinal = maxOf(maxDoneOrdinal, maxProgressOrdinal)
        val maxProgressSeconds = episodes.asSequence()
            .mapNotNull { progressSecondsByEpisodeId[it.episodeId] }
            .maxOrNull()
            ?: 0
        if (currentOrdinal == 0 && maxProgressSeconds == 0) return null
        return "$currentOrdinal|${maxProgressSeconds.toProgressTime()}"
    }
}

data class BangumiRecorderSyncResult(
    val success: Boolean,
    val message: String,
    val appliedRecords: Int = 0,
)

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstOrNullCompat(): T? = firstOrNull()

private fun SubjectCollectionEntity.toSyncRequestRecord(recorder: String? = null): SyncRequestRecord {
    return SyncRequestRecord(
        bangumiId = subjectId.toString(),
        recorder = recorder,
        userStatus = collectionType.toBrStatus(),
        updatedAt = Instant.fromEpochMilliseconds(lastUpdated).toBangumiRecorderDateTime(),
    )
}

private fun Long.toProgressSeconds(): Int {
    return (this / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
}

private fun Int.toProgressTime(): String {
    val minutes = this / 60
    val seconds = this % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun SyncResponseRecord.toSyncRequestRecord(): SyncRequestRecord {
    return SyncRequestRecord(bangumiId, recorder, userStatus, updatedAt)
}

private fun UnifiedCollectionType.toBrStatus(): Int? = when (this) {
    UnifiedCollectionType.WISH -> 0
    UnifiedCollectionType.DOING -> 1
    UnifiedCollectionType.DONE -> 2
    UnifiedCollectionType.ON_HOLD -> 3
    UnifiedCollectionType.DROPPED -> 4
    UnifiedCollectionType.NOT_COLLECTED -> null
}

private fun Int.toCollectionType(): UnifiedCollectionType = when (this) {
    0 -> UnifiedCollectionType.WISH
    1 -> UnifiedCollectionType.DOING
    2 -> UnifiedCollectionType.DONE
    3 -> UnifiedCollectionType.ON_HOLD
    4 -> UnifiedCollectionType.DROPPED
    else -> UnifiedCollectionType.NOT_COLLECTED
}

private fun BangumiRecorderBangumiItem.toSubjectEntity(record: SyncResponseRecord): SubjectCollectionEntity {
    val subjectId = bangumiId.toInt()
    return SubjectCollectionEntity(
        subjectId = subjectId,
        name = title,
        nameCn = title,
        summary = description,
        nsfw = false,
        imageLarge = coverUrl,
        totalEpisodes = episodes,
        airDate = PackedDate.Invalid,
        aliases = emptyList(),
        tags = emptyList(),
        collectionStats = SubjectCollectionStats.Zero,
        ratingInfo = RatingInfo.Empty,
        completeDate = PackedDate.Invalid,
        selfRatingInfo = SelfRatingInfo.Empty,
        collectionType = record.userStatus?.toCollectionType() ?: UnifiedCollectionType.NOT_COLLECTED,
        recurrence = null,
        relations = SubjectRelations.Empty,
        lastUpdated = record.updatedAt.parseBangumiRecorderDateTime().toEpochMilliseconds(),
        lastFetched = currentTimeMillis(),
        cachedStaffUpdated = 0,
        cachedCharactersUpdated = 0,
    )
}

private fun String.parseBangumiRecorderDateTime(): Instant {
    return if (endsWith("Z")) Instant.parse(this) else Instant.parse("${this}Z")
}
