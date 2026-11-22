package app.hononeko.miru.importer

import kotlinx.serialization.Serializable

const val DEFAULT_TAUTULLI_WATCHED_THRESHOLD_PERCENT = 85
const val DEFAULT_TAUTULLI_BATCH_SIZE = 500
const val MAX_TAUTULLI_IMPORT_WARNINGS = 50

data class TautulliImportOptions(
    val dryRun: Boolean = false,
    val serverId: String? = null,
    val watchedThresholdPercent: Int = DEFAULT_TAUTULLI_WATCHED_THRESHOLD_PERCENT,
    val batchSize: Int = DEFAULT_TAUTULLI_BATCH_SIZE,
)

@Serializable
data class TautulliImportReport(
    val dryRun: Boolean,
    val serverId: String,
    val usersDiscovered: Int,
    val usersUpserted: Int,
    val historyRowsRead: Int,
    val historyImported: Int,
    val historySkippedDuplicate: Int,
    val malformedRows: Int,
    val warnings: List<String>,
)

internal data class TautulliUserRow(
    val userId: String?,
    val username: String?,
    val friendlyName: String?,
    val email: String?,
    val thumb: String?,
    val isAdmin: Boolean,
)

internal data class TautulliHistoryRow(
    val id: Long,
    val referenceId: Long?,
    val started: Long?,
    val stopped: Long?,
    val pausedCounter: Long?,
    val viewOffset: Long?,
    val userId: String?,
    val user: String?,
    val ratingKey: String?,
    val mediaType: String?,
    val sectionId: String?,
    val player: String?,
    val product: String?,
    val platform: String?,
    val machineId: String?,
    val ipAddress: String?,
    val location: String?,
    val title: String?,
    val parentTitle: String?,
    val grandparentTitle: String?,
    val parentMediaIndex: Int?,
    val mediaIndex: Int?,
    val fullTitle: String?,
    val year: Int?,
    val thumb: String?,
    val duration: Long?,
    val transcodeDecision: String?,
    val videoDecision: String?,
    val audioDecision: String?,
    val transcodeHwDecoding: Int?,
    val transcodeHwEncoding: Int?,
    val videoCodec: String?,
    val audioCodec: String?,
    val videoResolution: String?,
    val streamVideoResolution: String?,
    val streamVideoCodec: String?,
    val streamAudioCodec: String?,
    val bandwidth: Int?,
    val bitrate: Int?,
)

internal data class TautulliReadResult(
    val users: List<TautulliUserRow>,
    val history: List<TautulliHistoryRow>,
    val historyRowsRead: Int,
    val malformedRows: Int,
    val warnings: List<String>,
)

internal fun MutableList<String>.addBoundedWarning(message: String) {
    if (size < MAX_TAUTULLI_IMPORT_WARNINGS) {
        add(message)
    }
}
