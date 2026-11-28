package app.hononeko.miru.persistence.model

/** Plays that stopped within one UTC hour, numbered from the epoch (hour 0 starts at 1970-01-01T00:00Z). */
data class HourlyPlayBucket(
    val utcHour: Long,
    val playCount: Long,
    val watchDurationSec: Long,
)

/**
 * Plays that share every column the client breakdowns read. [client] describes them all, except that its
 * [ClientStatsRow.watchedDurationSec] belongs to no single play: [watchDurationSec] is their total.
 */
data class ClientStatsGroup(
    val client: ClientStatsRow,
    val playCount: Long,
    val watchDurationSec: Long,
)
