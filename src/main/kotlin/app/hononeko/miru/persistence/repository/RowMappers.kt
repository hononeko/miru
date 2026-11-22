package app.hononeko.miru.persistence.repository

import app.hononeko.miru.persistence.model.ActiveSessionRecord
import app.hononeko.miru.persistence.model.LibrarySectionRecord
import app.hononeko.miru.persistence.model.LibrarySnapshotRecord
import app.hononeko.miru.persistence.model.MediaItemCacheRecord
import app.hononeko.miru.persistence.model.MediaItemTechRecord
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.MediaServerRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.persistence.model.PlaybackHistoryStatsRow
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.model.UserRecord
import app.hononeko.miru.persistence.table.ActiveSessionsTable
import app.hononeko.miru.persistence.table.LibrarySectionsTable
import app.hononeko.miru.persistence.table.LibrarySnapshotsTable
import app.hononeko.miru.persistence.table.MediaItemsCacheTable
import app.hononeko.miru.persistence.table.MediaServersTable
import app.hononeko.miru.persistence.table.PlaybackHistoryTable
import app.hononeko.miru.persistence.table.UsersTable
import org.jetbrains.exposed.v1.core.ResultRow

internal fun ResultRow.toMediaServerRecord(): MediaServerRecord =
    MediaServerRecord(
        id = this[MediaServersTable.id],
        name = this[MediaServersTable.name],
        serverType = this[MediaServersTable.serverType],
        machineId = this[MediaServersTable.machineId],
        baseUrl = this[MediaServersTable.baseUrl],
        createdAtMs = this[MediaServersTable.createdAtMs],
        updatedAtMs = this[MediaServersTable.updatedAtMs],
    )

internal fun ResultRow.toUserRecord(): UserRecord =
    UserRecord(
        id = this[UsersTable.id],
        serverId = this[UsersTable.serverId],
        externalUserId = this[UsersTable.externalUserId],
        username = this[UsersTable.username],
        displayName = this[UsersTable.displayName],
        email = this[UsersTable.email],
        thumbUrl = this[UsersTable.thumbUrl],
        isAdmin = this[UsersTable.isAdmin],
        lastSeenAtMs = this[UsersTable.lastSeenAtMs],
        createdAtMs = this[UsersTable.createdAtMs],
        updatedAtMs = this[UsersTable.updatedAtMs],
    )

internal fun ResultRow.toActiveSessionRecord(): ActiveSessionRecord =
    ActiveSessionRecord(
        serverId = this[ActiveSessionsTable.serverId],
        sessionKey = this[ActiveSessionsTable.sessionKey],
        sessionId = this[ActiveSessionsTable.sessionId],
        userId = this[ActiveSessionsTable.userId],
        media =
            MediaMetadataRecord(
                ratingKey = this[ActiveSessionsTable.ratingKey],
                mediaType = this[ActiveSessionsTable.mediaType],
                title = this[ActiveSessionsTable.title],
                parentTitle = this[ActiveSessionsTable.parentTitle],
                grandparentTitle = this[ActiveSessionsTable.grandparentTitle],
                parentIndex = this[ActiveSessionsTable.parentIndex],
                mediaIndex = this[ActiveSessionsTable.mediaIndex],
                year = this[ActiveSessionsTable.year],
                thumbPath = this[ActiveSessionsTable.thumbPath],
                librarySectionKey = this[ActiveSessionsTable.librarySectionKey],
                durationMs = this[ActiveSessionsTable.durationMs],
            ),
        viewOffsetMs = this[ActiveSessionsTable.viewOffsetMs],
        state = this[ActiveSessionsTable.state],
        streamTech =
            StreamTechRecord(
                streamDecision = this[ActiveSessionsTable.streamDecision],
                videoDecision = this[ActiveSessionsTable.videoDecision],
                audioDecision = this[ActiveSessionsTable.audioDecision],
                hwTranscode = this[ActiveSessionsTable.hwTranscode],
                videoCodec = this[ActiveSessionsTable.videoCodec],
                audioCodec = this[ActiveSessionsTable.audioCodec],
                videoResolution = this[ActiveSessionsTable.videoResolution],
                bandwidthKbps = this[ActiveSessionsTable.bandwidthKbps],
            ),
        player =
            PlayerRecord(
                playerName = this[ActiveSessionsTable.playerName],
                playerPlatform = this[ActiveSessionsTable.playerPlatform],
                playerProduct = this[ActiveSessionsTable.playerProduct],
                playerMachineId = this[ActiveSessionsTable.playerMachineId],
                ipAddress = this[ActiveSessionsTable.ipAddress],
                location = this[ActiveSessionsTable.location],
            ),
        startedAtMs = this[ActiveSessionsTable.startedAtMs],
        lastSeenAtMs = this[ActiveSessionsTable.lastSeenAtMs],
        pausedDurationSec = this[ActiveSessionsTable.pausedDurationSec],
        missedPolls = this[ActiveSessionsTable.missedPolls],
    )

internal fun ResultRow.toPlaybackHistoryRecord(): PlaybackHistoryRecord =
    PlaybackHistoryRecord(
        id = this[PlaybackHistoryTable.id],
        serverId = this[PlaybackHistoryTable.serverId],
        userId = this[PlaybackHistoryTable.userId],
        externalImportKey = this[PlaybackHistoryTable.externalImportKey],
        media =
            MediaMetadataRecord(
                ratingKey = this[PlaybackHistoryTable.ratingKey],
                mediaType = this[PlaybackHistoryTable.mediaType],
                title = this[PlaybackHistoryTable.title],
                parentTitle = this[PlaybackHistoryTable.parentTitle],
                grandparentTitle = this[PlaybackHistoryTable.grandparentTitle],
                parentIndex = this[PlaybackHistoryTable.parentIndex],
                mediaIndex = this[PlaybackHistoryTable.mediaIndex],
                year = this[PlaybackHistoryTable.year],
                thumbPath = this[PlaybackHistoryTable.thumbPath],
                librarySectionKey = this[PlaybackHistoryTable.librarySectionKey],
                durationMs = this[PlaybackHistoryTable.durationMs],
            ),
        viewOffsetMs = this[PlaybackHistoryTable.viewOffsetMs],
        watchedDurationSec = this[PlaybackHistoryTable.watchedDurationSec],
        pausedDurationSec = this[PlaybackHistoryTable.pausedDurationSec],
        completionPercent = this[PlaybackHistoryTable.completionPercent],
        isWatched = this[PlaybackHistoryTable.isWatched],
        streamTech =
            StreamTechRecord(
                streamDecision = this[PlaybackHistoryTable.streamDecision],
                videoDecision = this[PlaybackHistoryTable.videoDecision],
                audioDecision = this[PlaybackHistoryTable.audioDecision],
                hwTranscode = this[PlaybackHistoryTable.hwTranscode],
                videoCodec = this[PlaybackHistoryTable.videoCodec],
                audioCodec = this[PlaybackHistoryTable.audioCodec],
                videoResolution = this[PlaybackHistoryTable.videoResolution],
                bandwidthKbps = this[PlaybackHistoryTable.bandwidthKbps],
            ),
        player =
            PlayerRecord(
                playerName = this[PlaybackHistoryTable.playerName],
                playerPlatform = this[PlaybackHistoryTable.playerPlatform],
                playerProduct = this[PlaybackHistoryTable.playerProduct],
                playerMachineId = this[PlaybackHistoryTable.playerMachineId],
                ipAddress = this[PlaybackHistoryTable.ipAddress],
                location = this[PlaybackHistoryTable.location],
            ),
        startedAtMs = this[PlaybackHistoryTable.startedAtMs],
        stoppedAtMs = this[PlaybackHistoryTable.stoppedAtMs],
    )

internal fun ResultRow.toPlaybackHistoryStatsRow(): PlaybackHistoryStatsRow =
    PlaybackHistoryStatsRow(
        userId = this[PlaybackHistoryTable.userId],
        mediaType = this[PlaybackHistoryTable.mediaType],
        ratingKey = this[PlaybackHistoryTable.ratingKey],
        title = this[PlaybackHistoryTable.title],
        parentTitle = this[PlaybackHistoryTable.parentTitle],
        grandparentTitle = this[PlaybackHistoryTable.grandparentTitle],
        thumbPath = this[PlaybackHistoryTable.thumbPath],
        librarySectionKey = this[PlaybackHistoryTable.librarySectionKey],
        watchedDurationSec = this[PlaybackHistoryTable.watchedDurationSec],
        streamDecision = this[PlaybackHistoryTable.streamDecision],
        hwTranscode = this[PlaybackHistoryTable.hwTranscode],
        playerPlatform = this[PlaybackHistoryTable.playerPlatform],
        playerProduct = this[PlaybackHistoryTable.playerProduct],
        stoppedAtMs = this[PlaybackHistoryTable.stoppedAtMs],
    )

internal fun ResultRow.toLibrarySectionRecord(): LibrarySectionRecord =
    LibrarySectionRecord(
        serverId = this[LibrarySectionsTable.serverId],
        sectionKey = this[LibrarySectionsTable.sectionKey],
        title = this[LibrarySectionsTable.title],
        sectionType = this[LibrarySectionsTable.sectionType],
        itemCount = this[LibrarySectionsTable.itemCount],
        childCount = this[LibrarySectionsTable.childCount],
        leafCount = this[LibrarySectionsTable.leafCount],
        totalSizeBytes = this[LibrarySectionsTable.totalSizeBytes],
        totalDurationMs = this[LibrarySectionsTable.totalDurationMs],
        lastSyncedAtMs = this[LibrarySectionsTable.lastSyncedAtMs],
        updatedAtMs = this[LibrarySectionsTable.updatedAtMs],
    )

internal fun ResultRow.toLibrarySnapshotRecord(): LibrarySnapshotRecord =
    LibrarySnapshotRecord(
        id = this[LibrarySnapshotsTable.id],
        serverId = this[LibrarySnapshotsTable.serverId],
        sectionKey = this[LibrarySnapshotsTable.sectionKey],
        capturedAtMs = this[LibrarySnapshotsTable.capturedAtMs],
        itemCount = this[LibrarySnapshotsTable.itemCount],
        childCount = this[LibrarySnapshotsTable.childCount],
        leafCount = this[LibrarySnapshotsTable.leafCount],
        totalSizeBytes = this[LibrarySnapshotsTable.totalSizeBytes],
        totalDurationMs = this[LibrarySnapshotsTable.totalDurationMs],
        aggregatesJson = this[LibrarySnapshotsTable.aggregatesJson],
    )

internal fun ResultRow.toMediaItemCacheRecord(): MediaItemCacheRecord =
    MediaItemCacheRecord(
        serverId = this[MediaItemsCacheTable.serverId],
        ratingKey = this[MediaItemsCacheTable.ratingKey],
        sectionKey = this[MediaItemsCacheTable.sectionKey],
        mediaType = this[MediaItemsCacheTable.mediaType],
        title = this[MediaItemsCacheTable.title],
        parentTitle = this[MediaItemsCacheTable.parentTitle],
        grandparentTitle = this[MediaItemsCacheTable.grandparentTitle],
        year = this[MediaItemsCacheTable.year],
        thumbPath = this[MediaItemsCacheTable.thumbPath],
        durationMs = this[MediaItemsCacheTable.durationMs],
        tech =
            MediaItemTechRecord(
                fileSizeBytes = this[MediaItemsCacheTable.fileSizeBytes],
                videoResolution = this[MediaItemsCacheTable.videoResolution],
                videoCodec = this[MediaItemsCacheTable.videoCodec],
                audioCodec = this[MediaItemsCacheTable.audioCodec],
            ),
        addedAtMs = this[MediaItemsCacheTable.addedAtMs],
        updatedAtMs = this[MediaItemsCacheTable.updatedAtMs],
    )
