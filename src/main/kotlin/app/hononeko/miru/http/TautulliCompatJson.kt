package app.hononeko.miru.http

import app.hononeko.miru.persistence.model.PlaybackHistoryRecord
import app.hononeko.miru.service.TautulliHistory
import app.hononeko.miru.service.TautulliHistoryRow
import app.hononeko.miru.service.TautulliHomeStat
import app.hononeko.miru.service.TautulliMediaStat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.roundToInt

/**
 * The version of Tautulli whose API this follows. Tools compare it with the version they need: Kometa, for example, only
 * filters by library section from 2.12 on.
 */
internal const val TAUTULLI_COMPAT_VERSION = "v2.19.1"

private const val MILLIS_PER_SECOND = 1_000L
private const val LIVE_TV = "live_tv"
private const val QUARTER = 0.25

internal fun tautulliEnvelope(
    result: String,
    message: String?,
    data: JsonElement,
): JsonObject =
    buildJsonObject {
        put(
            "response",
            buildJsonObject {
                put("result", result)
                put("message", message)
                put("data", data)
            },
        )
    }

internal fun tautulliInfoJson(appVersion: String): JsonObject =
    buildJsonObject {
        put("tautulli_install_type", "miru")
        put("tautulli_version", TAUTULLI_COMPAT_VERSION)
        put("tautulli_branch", "miru")
        put("tautulli_commit", "")
        put("tautulli_platform", "Miru")
        put("tautulli_platform_release", appVersion)
        put("tautulli_platform_version", appVersion)
        put("tautulli_platform_linux_distro", "")
        put("tautulli_platform_device_name", "Miru")
        put("tautulli_python_version", "")
    }

internal fun List<TautulliHomeStat>.toTautulliJson(byDuration: Boolean): JsonArray =
    buildJsonArray {
        for (homeStat in this@toTautulliJson) {
            add(
                buildJsonObject {
                    put("stat_id", homeStat.stat.id)
                    if (!homeStat.stat.popular) {
                        put("stat_type", if (byDuration) "total_duration" else "total_plays")
                    }
                    put("stat_title", homeStat.stat.title)
                    putJsonArray("rows") {
                        homeStat.rows.forEach { row -> add(row.toTautulliJson(popular = homeStat.stat.popular)) }
                    }
                },
            )
        }
    }

private fun TautulliMediaStat.toTautulliJson(popular: Boolean): JsonObject =
    buildJsonObject {
        put("title", title)
        put("year", year)
        put("total_plays", plays)
        if (popular) {
            put("users_watched", users)
        } else {
            put("total_duration", durationSec)
            put("users_watched", "")
        }
        put("rating_key", tautulliId(ratingKey))
        put("grandparent_rating_key", if (mediaType == "movie") JsonPrimitive("") else tautulliId(ratingKey))
        put("last_play", lastStartedAtMs / MILLIS_PER_SECOND)
        put("grandparent_thumb", "")
        put("thumb", thumbPath.orEmpty())
        put("art", "")
        put("section_id", tautulliId(sectionKey))
        put("media_type", mediaType)
        put("content_rating", "")
        put("rating", "")
        putJsonArray("labels") { }
        put("user", "")
        put("friendly_name", "")
        put("platform", "")
        put("live", 0)
        put("guid", "")
        put("row_id", "")
    }

internal fun TautulliHistory.toTautulliJson(
    draw: Int,
    watchedThresholdPercent: Int,
): JsonObject =
    buildJsonObject {
        put("recordsFiltered", filteredCount)
        put("recordsTotal", totalCount)
        put("draw", draw)
        putJsonArray("data") {
            rows.forEach { row -> add(row.toTautulliJson(watchedThresholdPercent)) }
        }
    }

private fun TautulliHistoryRow.toTautulliJson(watchedThresholdPercent: Int): JsonObject {
    val media = play.media
    val isLive = media.mediaType == LIVE_TV
    val startedAtSec = play.startedAtMs / MILLIS_PER_SECOND
    val username = user?.username.orEmpty()
    return buildJsonObject {
        put("reference_id", play.id)
        put("row_id", play.id)
        put("id", play.id)
        put("date", startedAtSec)
        put("started", startedAtSec)
        put("stopped", play.stoppedAtMs / MILLIS_PER_SECOND)
        put("duration", play.watchedDurationSec)
        put("play_duration", play.watchedDurationSec)
        put("paused_counter", play.pausedDurationSec)
        put("user_id", tautulliId(user?.externalUserId))
        put("user", username)
        put("friendly_name", user?.displayName?.takeIf { it.isNotBlank() } ?: username)
        put("user_thumb", "")
        put("user_is_archived", 0)
        put("library_is_archived", 0)
        put("platform", play.player.playerPlatform.orEmpty())
        put("product", play.player.playerProduct.orEmpty())
        put("player", play.player.playerName.orEmpty())
        put("ip_address", play.player.ipAddress.orEmpty())
        put("live", if (isLive) 1 else 0)
        put("machine_id", play.player.playerMachineId.orEmpty())
        put("location", play.player.location.orEmpty())
        put("secure", JsonNull)
        put("relayed", JsonNull)
        put("media_type", if (isLive) "episode" else media.mediaType)
        put("rating_key", tautulliId(media.ratingKey))
        put("parent_rating_key", "")
        put("grandparent_rating_key", grandparentRatingKey?.let(::tautulliId) ?: JsonPrimitive(""))
        put("full_title", listOfNotNull(media.grandparentTitle, media.title).joinToString(" - "))
        put("title", media.title)
        put("parent_title", media.parentTitle.orEmpty())
        put("grandparent_title", media.grandparentTitle.orEmpty())
        put("original_title", "")
        put("year", media.year)
        put("media_index", media.mediaIndex)
        put("parent_media_index", media.parentIndex)
        put("thumb", media.thumbPath.orEmpty())
        put("originally_available_at", "")
        put("guid", "")
        put("transcode_decision", tautulliTranscodeDecision(play.streamTech.streamDecision))
        put("percent_complete", if (isLive) 100 else play.completionPercent.roundToInt())
        put("watched_status", play.tautulliWatchedStatus(watchedThresholdPercent))
        // Miru keeps a resumed play as one
        put("group_count", 1)
        put("group_ids", play.id)
        put("state", JsonNull)
        put("session_key", JsonNull)
    }
}

/** Tautulli's IDs are numbers, and tools compare them as such. */
internal fun tautulliId(raw: String?): JsonElement =
    when {
        raw.isNullOrBlank() -> JsonNull
        else -> raw.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)
    }

private fun tautulliTranscodeDecision(streamDecision: String): String =
    when (streamDecision) {
        "direct_play" -> "direct play"
        "direct_stream" -> "copy"
        else -> streamDecision
    }

/** 1 when watched, else how many quarters of the way to the threshold the play got. */
private fun PlaybackHistoryRecord.tautulliWatchedStatus(watchedThresholdPercent: Int): JsonPrimitive {
    if (isWatched || media.mediaType == LIVE_TV) return JsonPrimitive(1)
    val quarters = (completionPercent / (watchedThresholdPercent * QUARTER)).toInt().coerceIn(0, 3)
    return if (quarters == 0) JsonPrimitive(0) else JsonPrimitive(quarters * QUARTER)
}
