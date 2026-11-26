package app.hononeko.miru.http

import app.hononeko.miru.library.LibrarySyncMode
import app.hononeko.miru.library.LibrarySyncSummary
import app.hononeko.miru.persistence.model.LibraryGrowthDayRecord
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.LibrarySnapshotRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.StaleMediaItemRecord
import app.hononeko.miru.service.LibraryRecentResult
import app.hononeko.miru.service.LibraryStaleResult
import app.hononeko.miru.service.LibraryStatsResult
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class LibrarySectionResponse(
    val id: String,
    val serverId: String,
    val serverName: String? = null,
    val serverType: String? = null,
    val sectionKey: String,
    val title: String,
    val sectionType: String,
    val itemCount: Int,
    val childCount: Int,
    val leafCount: Int,
    val totalSizeBytes: Long,
    val totalDurationMs: Long,
    val lastSyncedAtMs: Long? = null,
    val updatedAtMs: Long,
)

@Serializable
data class LibraryListResponse(
    val libraries: List<LibrarySectionResponse>,
)

@Serializable
data class LibrarySnapshotResponse(
    val id: String,
    val capturedAtMs: Long,
    val itemCount: Int,
    val childCount: Int,
    val leafCount: Int,
    val totalSizeBytes: Long,
    val totalDurationMs: Long,
)

/** Items (movies, episodes or tracks) added and removed on one UTC day. */
@Serializable
data class LibraryGrowthDayResponse(
    val date: String,
    val added: Long,
    val removed: Long,
    val sizeDeltaBytes: Long,
)

@Serializable
data class LibraryStatsResponse(
    val section: LibrarySectionResponse,
    val byResolution: Map<String, Int>,
    val byVideoCodec: Map<String, Int>,
    val byAudioCodec: Map<String, Int>,
    val snapshots: List<LibrarySnapshotResponse>,
    /** Days with additions or removals, oldest first. */
    val growth: List<LibraryGrowthDayResponse>,
)

@Serializable
data class RecentMediaItemResponse(
    val ratingKey: String,
    val serverId: String? = null,
    val serverName: String? = null,
    val serverType: String? = null,
    val sectionKey: String,
    val mediaType: String,
    val title: String,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val year: Int? = null,
    val thumbUrl: String? = null,
    val durationMs: Long? = null,
    val fileSizeBytes: Long? = null,
    val videoResolution: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val addedAtMs: Long,
)

@Serializable
data class RecentMediaListResponse(
    val sectionKey: String,
    val items: List<RecentMediaItemResponse>,
)

@Serializable
data class StaleMediaItemResponse(
    val ratingKey: String,
    val serverId: String? = null,
    val serverName: String? = null,
    val serverType: String? = null,
    val sectionKey: String,
    val mediaType: String,
    val title: String,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val year: Int? = null,
    val thumbUrl: String? = null,
    val durationMs: Long? = null,
    val fileSizeBytes: Long? = null,
    val videoResolution: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val addedAtMs: Long,
    val lastPlayedAtMs: Long? = null,
    val playCount: Long,
)

@Serializable
data class StaleMediaListResponse(
    val sectionKey: String,
    val unwatchedDays: Int,
    val sort: String,
    val totalStaleCount: Int,
    val totalReclaimableBytes: Long,
    val neverPlayedCount: Int,
    val items: List<StaleMediaItemResponse>,
)

@Serializable
data class LibrarySyncResponse(
    val mode: LibrarySyncMode,
    val syncedSections: Int,
    val failedSections: Int,
    val totalItemsSynced: Int,
    val snapshotsCreated: Int,
    val prunedItems: Int,
    val syncedAtMs: Long,
)

internal fun LibrarySectionRecord.toResponse(server: MediaServerRecord? = null): LibrarySectionResponse =
    LibrarySectionResponse(
        id = sectionKey,
        serverId = serverId,
        serverName = server?.name?.normalizedDisplayName(),
        serverType = server?.serverType?.lowercase(),
        sectionKey = sectionKey,
        title = title,
        sectionType = sectionType,
        itemCount = itemCount,
        childCount = childCount,
        leafCount = leafCount,
        totalSizeBytes = totalSizeBytes,
        totalDurationMs = totalDurationMs,
        lastSyncedAtMs = lastSyncedAtMs,
        updatedAtMs = updatedAtMs,
    )

internal fun LibrarySnapshotRecord.toResponse(): LibrarySnapshotResponse =
    LibrarySnapshotResponse(
        id = id,
        capturedAtMs = capturedAtMs,
        itemCount = itemCount,
        childCount = childCount,
        leafCount = leafCount,
        totalSizeBytes = totalSizeBytes,
        totalDurationMs = totalDurationMs,
    )

internal fun LibraryGrowthDayRecord.toResponse(): LibraryGrowthDayResponse =
    LibraryGrowthDayResponse(
        date = LocalDate.ofEpochDay(epochDay).toString(),
        added = added,
        removed = removed,
        sizeDeltaBytes = sizeDeltaBytes,
    )

internal fun LibraryStatsResult.toResponse(server: MediaServerRecord? = null): LibraryStatsResponse =
    LibraryStatsResponse(
        section = section.toResponse(server),
        byResolution = aggregates.byResolution,
        byVideoCodec = aggregates.byVideoCodec,
        byAudioCodec = aggregates.byAudioCodec,
        snapshots = snapshots.map { it.toResponse() },
        growth = growth.map { it.toResponse() },
    )

internal fun MediaItemCacheRecord.toRecentResponse(server: MediaServerRecord? = null): RecentMediaItemResponse =
    RecentMediaItemResponse(
        ratingKey = ratingKey,
        serverId = serverId,
        serverName = server?.name?.normalizedDisplayName(),
        serverType = server?.serverType?.lowercase(),
        sectionKey = sectionKey,
        mediaType = mediaType,
        title = title,
        parentTitle = parentTitle,
        grandparentTitle = grandparentTitle,
        year = year,
        thumbUrl = buildThumbUrl(ratingKey, thumbPath),
        durationMs = durationMs,
        fileSizeBytes = tech.fileSizeBytes,
        videoResolution = tech.videoResolution,
        videoCodec = tech.videoCodec,
        audioCodec = tech.audioCodec,
        addedAtMs = addedAtMs,
    )

internal fun LibraryRecentResult.toResponse(server: MediaServerRecord? = null): RecentMediaListResponse =
    RecentMediaListResponse(
        sectionKey = section.sectionKey,
        items = items.map { it.toRecentResponse(server) },
    )

internal fun StaleMediaItemRecord.toResponse(server: MediaServerRecord? = null): StaleMediaItemResponse =
    StaleMediaItemResponse(
        ratingKey = item.ratingKey,
        serverId = item.serverId,
        serverName = server?.name?.normalizedDisplayName(),
        serverType = server?.serverType?.lowercase(),
        sectionKey = item.sectionKey,
        mediaType = item.mediaType,
        title = item.title,
        parentTitle = item.parentTitle,
        grandparentTitle = item.grandparentTitle,
        year = item.year,
        thumbUrl = buildThumbUrl(item.ratingKey, item.thumbPath),
        durationMs = item.durationMs,
        fileSizeBytes = item.tech.fileSizeBytes,
        videoResolution = item.tech.videoResolution,
        videoCodec = item.tech.videoCodec,
        audioCodec = item.tech.audioCodec,
        addedAtMs = item.addedAtMs,
        lastPlayedAtMs = lastPlayedAtMs,
        playCount = playCount,
    )

internal fun LibraryStaleResult.toResponse(server: MediaServerRecord? = null): StaleMediaListResponse =
    StaleMediaListResponse(
        sectionKey = section.sectionKey,
        unwatchedDays = unwatchedDays,
        sort = sort,
        totalStaleCount = report.totalStaleCount,
        totalReclaimableBytes = report.totalReclaimableBytes,
        neverPlayedCount = report.neverPlayedCount,
        items = report.items.map { it.toResponse(server) },
    )

internal fun LibrarySyncSummary.toResponse(): LibrarySyncResponse =
    LibrarySyncResponse(
        mode = mode,
        syncedSections = syncedSections,
        failedSections = failedSections,
        totalItemsSynced = totalItemsSynced,
        snapshotsCreated = snapshotsCreated,
        prunedItems = prunedItems,
        syncedAtMs = syncedAtMs,
    )
