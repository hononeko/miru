package app.hononeko.miru.library

import kotlinx.serialization.Serializable

@Serializable
data class LibraryAggregates(
    val byResolution: Map<String, Int> = emptyMap(),
    val byVideoCodec: Map<String, Int> = emptyMap(),
    val byAudioCodec: Map<String, Int> = emptyMap(),
)

@Serializable
enum class LibrarySyncMode {
    FAST,
    DEEP,
}

@Serializable
data class LibrarySyncSummary(
    val mode: LibrarySyncMode,
    val syncedSections: Int,
    val failedSections: Int = 0,
    val totalItemsSynced: Int,
    val snapshotsCreated: Int,
    val prunedItems: Int,
    val syncedAtMs: Long,
)

object LibraryNormalizer {
    private const val UNKNOWN = "Unknown"

    fun normalizeResolution(raw: String?): String {
        val cleaned = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return UNKNOWN
        return when {
            cleaned == "unknown" -> UNKNOWN
            cleaned == "4k" || cleaned.startsWith("2160") -> "4K"
            cleaned.startsWith("1080") -> "1080p"
            cleaned.startsWith("720") -> "720p"
            cleaned == "sd" || cleaned.startsWith("480") || cleaned.startsWith("576") -> "SD"
            else -> raw.trim()
        }
    }

    fun normalizeCodec(raw: String?): String {
        val cleaned = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return UNKNOWN
        return when (cleaned) {
            "unknown" -> UNKNOWN
            "hevc", "h265", "h.265" -> "HEVC"
            "h264", "avc", "avc1", "h.264" -> "H.264"
            "av1", "av01" -> "AV1"
            else -> cleaned.uppercase()
        }
    }
}
