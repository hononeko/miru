package app.hononeko.miru.http

import app.hononeko.miru.library.LibrarySyncMode
import app.hononeko.miru.library.LibrarySyncSummary
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.LibrarySnapshotRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.model.StaleMediaItemRecord
import app.hononeko.miru.service.LibraryRecentResult
import app.hononeko.miru.service.LibraryStaleResult
import app.hononeko.miru.service.LibraryStatsResult
import kotlinx.serialization.Serializable

@Serializable
data class LibrarySectionResponse(
    val id: String,
    val serverId: String,
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

@Serializable
data class LibraryStatsResponse(
    val section: LibrarySectionResponse,
    val byResolution: Map<String, Int>,
    val byVideoCodec: Map<String, Int>,
    val byAudioCodec: Map<String, Int>,
    val snapshots: List<LibrarySnapshotResponse>,
)

@Serializable
data class RecentMediaItemResponse(
    val ratingKey: String,
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
    val sectionId: String,
    val items: List<RecentMediaItemResponse>,
)

@Serializable
data class StaleMediaItemResponse(
    val ratingKey: String,
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
    val sectionId: String,
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

internal fun LibrarySectionRecord.toResponse(): LibrarySectionResponse =
    LibrarySectionResponse(
        id = sectionKey,
        serverId = serverId,
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

internal fun LibraryStatsResult.toResponse(): LibraryStatsResponse =
    LibraryStatsResponse(
        section = section.toResponse(),
        byResolution = aggregates.byResolution,
        byVideoCodec = aggregates.byVideoCodec,
        byAudioCodec = aggregates.byAudioCodec,
        snapshots = snapshots.map { it.toResponse() },
    )

internal fun MediaItemCacheRecord.toRecentResponse(): RecentMediaItemResponse =
    RecentMediaItemResponse(
        ratingKey = ratingKey,
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

internal fun LibraryRecentResult.toResponse(): RecentMediaListResponse =
    RecentMediaListResponse(
        sectionId = section.sectionKey,
        items = items.map { it.toRecentResponse() },
    )

internal fun StaleMediaItemRecord.toResponse(): StaleMediaItemResponse =
    StaleMediaItemResponse(
        ratingKey = item.ratingKey,
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

internal fun LibraryStaleResult.toResponse(): StaleMediaListResponse =
    StaleMediaListResponse(
        sectionId = section.sectionKey,
        unwatchedDays = unwatchedDays,
        sort = sort,
        totalStaleCount = report.totalStaleCount,
        totalReclaimableBytes = report.totalReclaimableBytes,
        neverPlayedCount = report.neverPlayedCount,
        items = report.items.map { it.toResponse() },
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
