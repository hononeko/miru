package app.hononeko.miru.importer

import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.repository.MediaServerRepository
import app.hononeko.miru.persistence.repository.PlaybackHistoryRepository
import app.hononeko.miru.persistence.repository.UserRepository
import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID

private const val SECONDS_TIMESTAMP_THRESHOLD = 10_000_000_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val PERCENT_SCALE = 100.0
private const val DEFAULT_IMPORT_SERVER_MACHINE_ID = "tautulli-import"
private const val DRY_RUN_SERVER_ID = "dry-run-server"

/**
 * Imports historical users and playback sessions from a Tautulli SQLite database into Miru.
 */
class TautulliImporterService(
    private val mediaServerRepository: MediaServerRepository,
    private val userRepository: UserRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val fallbackBaseUrl: String = "http://localhost:32400",
    private val defaultWatchedThresholdPercent: Int = DEFAULT_TAUTULLI_WATCHED_THRESHOLD_PERCENT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val logger = LoggerFactory.getLogger(TautulliImporterService::class.java)

    suspend fun import(
        file: Path,
        options: TautulliImportOptions = TautulliImportOptions(watchedThresholdPercent = defaultWatchedThresholdPercent),
    ): Either<MiruError, TautulliImportReport> =
        either {
            val readResult = TautulliSqliteReader.read(file).bind()
            val warnings = readResult.warnings.toMutableList()
            var malformedRows = readResult.malformedRows
            val nowMs = clock()
            val thresholdPercent = options.watchedThresholdPercent.coerceIn(1, 100)
            val batchSize = options.batchSize.coerceAtLeast(1)

            val serverId = resolveServerId(options, nowMs).bind()

            val validatedRows = mutableListOf<ValidatedHistoryRow>()
            for (row in readResult.history) {
                val validated = validateHistoryRow(row, warnings)
                if (validated == null) {
                    malformedRows++
                } else {
                    validatedRows += validated
                }
            }

            val discoveredUsers = collectUsers(readResult.users, validatedRows, warnings)
            val (userIdByExternalId, usersUpserted) =
                upsertOrMapUsers(
                    dryRun = options.dryRun,
                    serverId = serverId,
                    users = discoveredUsers.values.toList(),
                    nowMs = nowMs,
                ).bind()

            val mergedRecords =
                mergeGroupedHistoryRows(
                    rows = validatedRows,
                    serverId = serverId,
                    userIdByExternalId = userIdByExternalId,
                    watchedThresholdPercent = thresholdPercent,
                )

            val (importedCount, skippedDuplicateCount) =
                processBatches(
                    dryRun = options.dryRun,
                    records = mergedRecords,
                    batchSize = batchSize,
                ).bind()

            logger.info(
                "Tautulli import completed (dryRun={}, serverId={}, users={}, rowsRead={}, imported={}, skipped={}, malformed={})",
                options.dryRun,
                serverId,
                discoveredUsers.size,
                readResult.historyRowsRead,
                importedCount,
                skippedDuplicateCount,
                malformedRows,
            )

            TautulliImportReport(
                dryRun = options.dryRun,
                serverId = serverId,
                usersDiscovered = discoveredUsers.size,
                usersUpserted = usersUpserted,
                historyRowsRead = readResult.historyRowsRead,
                historyImported = importedCount,
                historySkippedDuplicate = skippedDuplicateCount,
                malformedRows = malformedRows,
                warnings = warnings.take(MAX_TAUTULLI_IMPORT_WARNINGS),
            )
        }

    private suspend fun resolveServerId(
        options: TautulliImportOptions,
        nowMs: Long,
    ): Either<MiruError, String> =
        either {
            val requestedServerId = options.serverId?.trim()?.takeIf { it.isNotEmpty() }
            if (requestedServerId != null) {
                val server =
                    mediaServerRepository.findById(requestedServerId).bind()
                        ?: raise(MiruError.NotFound("MediaServer", requestedServerId))
                return@either server.id
            }

            val servers = mediaServerRepository.findAll().bind()
            val preferred =
                servers.firstOrNull { it.serverType.equals("plex", ignoreCase = true) }
                    ?: servers.firstOrNull()
            if (preferred != null) {
                return@either preferred.id
            }

            if (options.dryRun) {
                return@either DRY_RUN_SERVER_ID
            }

            val created =
                mediaServerRepository
                    .upsert(
                        MediaServerRecord(
                            id = idGenerator(),
                            name = "Tautulli Import",
                            serverType = "plex",
                            machineId = DEFAULT_IMPORT_SERVER_MACHINE_ID,
                            baseUrl = fallbackBaseUrl.ifBlank { "http://localhost:32400" },
                            createdAtMs = nowMs,
                            updatedAtMs = nowMs,
                        ),
                    ).bind()
            created.id
        }

    private data class DiscoveredUser(
        val externalUserId: String,
        val username: String,
        val displayName: String?,
        val email: String?,
        val thumbUrl: String?,
        val isAdmin: Boolean,
        val lastSeenAtMs: Long?,
    )

    private data class ValidatedHistoryRow(
        val row: TautulliHistoryRow,
        val importKey: String,
        val externalUserId: String,
        val ratingKey: String,
        val title: String,
        val startedAtMs: Long,
        val stoppedAtMs: Long,
        val pausedDurationSec: Int,
        val viewOffsetMs: Long,
        val durationMs: Long,
    )

    private fun validateHistoryRow(
        row: TautulliHistoryRow,
        warnings: MutableList<String>,
    ): ValidatedHistoryRow? {
        val ratingKey = row.ratingKey?.trim()?.takeIf { it.isNotEmpty() }
        val title = row.title?.trim()?.takeIf { it.isNotEmpty() } ?: row.fullTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (ratingKey == null || title == null) {
            warnings.addBoundedWarning("Skipped Tautulli row id=${row.id}: missing rating_key or title")
            return null
        }

        val startedAtMs = normalizeEpochMs(row.started)
        if (startedAtMs <= 0L) {
            warnings.addBoundedWarning("Skipped Tautulli row id=${row.id}: invalid or missing started timestamp")
            return null
        }

        val rawStopped = row.stopped
        val stoppedAtMs =
            if (rawStopped == null || rawStopped <= 0L) {
                startedAtMs
            } else {
                normalizeEpochMs(rawStopped)
            }
        if (stoppedAtMs < startedAtMs) {
            warnings.addBoundedWarning("Skipped Tautulli row id=${row.id}: stopped timestamp precedes started timestamp")
            return null
        }

        val refId = row.referenceId?.takeIf { it > 0L } ?: row.id
        val externalUserId =
            row.userId?.trim()?.takeIf { it.isNotEmpty() }
                ?: row.user?.trim()?.takeIf { it.isNotEmpty() }
                ?: "unknown"

        val pausedSec = (row.pausedCounter ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val viewOffsetMs = (row.viewOffset ?: 0L).coerceAtLeast(0L) * MILLIS_PER_SECOND
        val durationMs = (row.duration ?: 0L).coerceAtLeast(0L) * MILLIS_PER_SECOND

        return ValidatedHistoryRow(
            row = row,
            importKey = "tautulli:$refId",
            externalUserId = externalUserId,
            ratingKey = ratingKey,
            title = title,
            startedAtMs = startedAtMs,
            stoppedAtMs = stoppedAtMs,
            pausedDurationSec = pausedSec,
            viewOffsetMs = viewOffsetMs,
            durationMs = durationMs,
        )
    }

    private fun collectUsers(
        rawUsers: List<TautulliUserRow>,
        historyRows: List<ValidatedHistoryRow>,
        warnings: MutableList<String>,
    ): Map<String, DiscoveredUser> {
        val lastSeenByExternalId = linkedMapOf<String, Long>()
        for (history in historyRows) {
            val prev = lastSeenByExternalId[history.externalUserId]
            if (prev == null || history.stoppedAtMs > prev) {
                lastSeenByExternalId[history.externalUserId] = history.stoppedAtMs
            }
        }

        val discovered = linkedMapOf<String, DiscoveredUser>()
        for (user in rawUsers) {
            val externalId =
                user.userId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: user.username?.trim()?.takeIf { it.isNotEmpty() }
            if (externalId == null) {
                warnings.addBoundedWarning("Skipped Tautulli user without user_id or username")
                continue
            }
            val username =
                user.username?.trim()?.takeIf { it.isNotEmpty() }
                    ?: user.friendlyName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "user-$externalId"
            val displayName = user.friendlyName?.trim()?.takeIf { it.isNotEmpty() } ?: username
            discovered[externalId] =
                DiscoveredUser(
                    externalUserId = externalId,
                    username = username,
                    displayName = displayName,
                    email = user.email?.trim()?.takeIf { it.isNotEmpty() },
                    thumbUrl = user.thumb?.trim()?.takeIf { it.isNotEmpty() },
                    isAdmin = user.isAdmin,
                    lastSeenAtMs = lastSeenByExternalId[externalId],
                )
        }

        for (history in historyRows) {
            if (history.externalUserId !in discovered) {
                val fallbackName = history.row.user?.trim()?.takeIf { it.isNotEmpty() } ?: "user-${history.externalUserId}"
                discovered[history.externalUserId] =
                    DiscoveredUser(
                        externalUserId = history.externalUserId,
                        username = fallbackName,
                        displayName = fallbackName,
                        email = null,
                        thumbUrl = null,
                        isAdmin = false,
                        lastSeenAtMs = lastSeenByExternalId[history.externalUserId],
                    )
            }
        }
        return discovered
    }

    private suspend fun upsertOrMapUsers(
        dryRun: Boolean,
        serverId: String,
        users: List<DiscoveredUser>,
        nowMs: Long,
    ): Either<MiruError, Pair<Map<String, String>, Int>> =
        either {
            val idMap = linkedMapOf<String, String>()
            if (dryRun) {
                for (user in users) {
                    idMap[user.externalUserId] = "dry-run-user-${user.externalUserId}"
                }
                return@either idMap to 0
            }

            var upserted = 0
            for (user in users) {
                val existing = userRepository.findByExternalId(serverId, user.externalUserId).bind()
                val effectiveLastSeen =
                    listOfNotNull(existing?.lastSeenAtMs, user.lastSeenAtMs).maxOrNull()
                val saved =
                    userRepository
                        .upsert(
                            UserRecord(
                                id = existing?.id ?: idGenerator(),
                                serverId = serverId,
                                externalUserId = user.externalUserId,
                                username = user.username,
                                displayName = user.displayName,
                                email = user.email,
                                thumbUrl = user.thumbUrl,
                                isAdmin = user.isAdmin,
                                lastSeenAtMs = effectiveLastSeen,
                                createdAtMs = existing?.createdAtMs ?: nowMs,
                                updatedAtMs = nowMs,
                            ),
                        ).bind()
                idMap[user.externalUserId] = saved.id
                upserted++
            }
            idMap to upserted
        }

    private fun mergeGroupedHistoryRows(
        rows: List<ValidatedHistoryRow>,
        serverId: String,
        userIdByExternalId: Map<String, String>,
        watchedThresholdPercent: Int,
    ): List<PlaybackHistoryRecord> {
        val grouped = linkedMapOf<String, MutableList<ValidatedHistoryRow>>()
        for (row in rows) {
            grouped.getOrPut(row.importKey) { mutableListOf() }.add(row)
        }

        return grouped.values.map { group ->
            val latest = group.last()
            val startedAtMs = group.minOf { it.startedAtMs }
            val stoppedAtMs = group.maxOf { it.stoppedAtMs }
            val pausedDurationSec = group.sumOf { it.pausedDurationSec.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val viewOffsetMs = group.maxOf { it.viewOffsetMs }
            val durationMs = latest.durationMs.takeIf { it > 0L } ?: group.lastOrNull { it.durationMs > 0L }?.durationMs ?: 0L

            val watchedDurationSec =
                (((stoppedAtMs - startedAtMs) / MILLIS_PER_SECOND) - pausedDurationSec)
                    .coerceAtLeast(0L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            val completionPercent =
                if (durationMs > 0L) {
                    ((viewOffsetMs.toDouble() / durationMs.toDouble()) * PERCENT_SCALE).coerceIn(0.0, PERCENT_SCALE)
                } else {
                    0.0
                }
            val isWatched = completionPercent >= watchedThresholdPercent.toDouble()

            val raw = latest.row
            val videoDecision = normalizeDecisionOrNull(raw.videoDecision)
            val audioDecision = normalizeDecisionOrNull(raw.audioDecision)
            val streamDecision = resolveStreamDecision(raw.transcodeDecision, videoDecision, audioDecision)
            val hwTranscode = raw.transcodeHwDecoding == 1 || raw.transcodeHwEncoding == 1
            val miruUserId = userIdByExternalId.getValue(latest.externalUserId)

            PlaybackHistoryRecord(
                id = idGenerator(),
                serverId = serverId,
                userId = miruUserId,
                externalImportKey = latest.importKey,
                media =
                    MediaMetadataRecord(
                        ratingKey = latest.ratingKey,
                        mediaType = raw.mediaType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "unknown",
                        title = latest.title,
                        parentTitle = raw.parentTitle,
                        grandparentTitle = raw.grandparentTitle,
                        parentIndex = raw.parentMediaIndex,
                        mediaIndex = raw.mediaIndex,
                        year = raw.year,
                        thumbPath = raw.thumb,
                        librarySectionKey = raw.sectionId,
                        durationMs = durationMs,
                    ),
                viewOffsetMs = viewOffsetMs,
                watchedDurationSec = watchedDurationSec,
                pausedDurationSec = pausedDurationSec,
                completionPercent = completionPercent,
                isWatched = isWatched,
                streamTech =
                    StreamTechRecord(
                        streamDecision = streamDecision,
                        videoDecision = videoDecision,
                        audioDecision = audioDecision,
                        hwTranscode = hwTranscode,
                        videoCodec = raw.videoCodec ?: raw.streamVideoCodec,
                        audioCodec = raw.audioCodec ?: raw.streamAudioCodec,
                        videoResolution = raw.videoResolution ?: raw.streamVideoResolution,
                        bandwidthKbps = raw.bandwidth ?: raw.bitrate,
                    ),
                player =
                    PlayerRecord(
                        playerName = raw.player,
                        playerPlatform = raw.platform,
                        playerProduct = raw.product,
                        playerMachineId = raw.machineId,
                        ipAddress = raw.ipAddress,
                        location = raw.location?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                    ),
                startedAtMs = startedAtMs,
                stoppedAtMs = stoppedAtMs,
            )
        }
    }

    private suspend fun processBatches(
        dryRun: Boolean,
        records: List<PlaybackHistoryRecord>,
        batchSize: Int,
    ): Either<MiruError, Pair<Int, Int>> =
        either {
            var imported = 0
            var skippedDuplicate = 0

            val chunks = records.chunked(batchSize)
            chunks.forEachIndexed { index, chunk ->
                if (dryRun) {
                    val keys = chunk.mapNotNull { it.externalImportKey }.toSet()
                    val existing = playbackHistoryRepository.findExistingImportKeys(keys).bind()
                    val existingCount = chunk.count { it.externalImportKey in existing }
                    skippedDuplicate += existingCount
                    imported += (chunk.size - existingCount)
                } else {
                    val inserted = playbackHistoryRepository.insertBatchIfAbsentByImportKey(chunk).bind()
                    imported += inserted
                    skippedDuplicate += (chunk.size - inserted)
                    logger.info(
                        "Imported Tautulli history batch {}/{} (batchSize={}, inserted={}, skipped={})",
                        index + 1,
                        chunks.size,
                        chunk.size,
                        inserted,
                        chunk.size - inserted,
                    )
                }
            }
            imported to skippedDuplicate
        }

    private fun normalizeEpochMs(raw: Long?): Long {
        if (raw == null || raw <= 0L) return 0L
        return if (raw < SECONDS_TIMESTAMP_THRESHOLD) raw * MILLIS_PER_SECOND else raw
    }

    private fun normalizeDecisionOrNull(raw: String?): String? {
        val norm = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (norm) {
            "direct play", "directplay", "direct_play" -> "direct_play"
            "copy", "direct stream", "directstream", "direct_stream" -> "direct_stream"
            "transcode" -> "transcode"
            else -> norm
        }
    }

    private fun resolveStreamDecision(
        transcodeDecision: String?,
        videoDecision: String?,
        audioDecision: String?,
    ): String {
        val primary = normalizeDecisionOrNull(transcodeDecision)
        if (primary != null) return primary
        return when {
            videoDecision == "transcode" || audioDecision == "transcode" -> "transcode"
            videoDecision == "direct_stream" || audioDecision == "direct_stream" -> "direct_stream"
            else -> "direct_play"
        }
    }
}
