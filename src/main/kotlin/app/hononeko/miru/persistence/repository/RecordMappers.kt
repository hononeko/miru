package app.hononeko.miru.persistence.repository

import app.hononeko.miru.persistence.DatabaseDialect
import app.hononeko.miru.persistence.getDialectBoolean
import app.hononeko.miru.persistence.getNullableInt
import app.hononeko.miru.persistence.getNullableString
import app.hononeko.miru.persistence.model.MediaMetadataRecord
import app.hononeko.miru.persistence.model.PlayerRecord
import app.hononeko.miru.persistence.model.StreamTechRecord
import app.hononeko.miru.persistence.setDialectBoolean
import app.hononeko.miru.persistence.setNullableInt
import app.hononeko.miru.persistence.setNullableString
import java.sql.PreparedStatement
import java.sql.ResultSet

internal fun PreparedStatement.bindMediaMetadata(
    startIndex: Int,
    media: MediaMetadataRecord,
): Int {
    var idx = startIndex
    setString(idx++, media.ratingKey)
    setString(idx++, media.mediaType)
    setString(idx++, media.title)
    setNullableString(idx++, media.parentTitle)
    setNullableString(idx++, media.grandparentTitle)
    setNullableInt(idx++, media.parentIndex)
    setNullableInt(idx++, media.mediaIndex)
    setNullableInt(idx++, media.year)
    setNullableString(idx++, media.thumbPath)
    setNullableString(idx++, media.librarySectionKey)
    setLong(idx++, media.durationMs)
    return idx
}

internal fun PreparedStatement.bindStreamTech(
    startIndex: Int,
    streamTech: StreamTechRecord,
    dialect: DatabaseDialect,
): Int {
    var idx = startIndex
    setString(idx++, streamTech.streamDecision)
    setNullableString(idx++, streamTech.videoDecision)
    setNullableString(idx++, streamTech.audioDecision)
    setDialectBoolean(idx++, streamTech.hwTranscode, dialect)
    setNullableString(idx++, streamTech.videoCodec)
    setNullableString(idx++, streamTech.audioCodec)
    setNullableString(idx++, streamTech.videoResolution)
    setNullableInt(idx++, streamTech.bandwidthKbps)
    return idx
}

internal fun PreparedStatement.bindPlayer(
    startIndex: Int,
    player: PlayerRecord,
): Int {
    var idx = startIndex
    setNullableString(idx++, player.playerName)
    setNullableString(idx++, player.playerPlatform)
    setNullableString(idx++, player.playerProduct)
    setNullableString(idx++, player.playerMachineId)
    setNullableString(idx++, player.ipAddress)
    setNullableString(idx++, player.location)
    return idx
}

internal fun ResultSet.readMediaMetadata(): MediaMetadataRecord =
    MediaMetadataRecord(
        ratingKey = getString("rating_key"),
        mediaType = getString("media_type"),
        title = getString("title"),
        parentTitle = getNullableString("parent_title"),
        grandparentTitle = getNullableString("grandparent_title"),
        parentIndex = getNullableInt("parent_index"),
        mediaIndex = getNullableInt("media_index"),
        year = getNullableInt("year"),
        thumbPath = getNullableString("thumb_path"),
        librarySectionKey = getNullableString("library_section_key"),
        durationMs = getLong("duration_ms"),
    )

internal fun ResultSet.readStreamTech(dialect: DatabaseDialect): StreamTechRecord =
    StreamTechRecord(
        streamDecision = getString("stream_decision"),
        videoDecision = getNullableString("video_decision"),
        audioDecision = getNullableString("audio_decision"),
        hwTranscode = getDialectBoolean("hw_transcode", dialect),
        videoCodec = getNullableString("video_codec"),
        audioCodec = getNullableString("audio_codec"),
        videoResolution = getNullableString("video_resolution"),
        bandwidthKbps = getNullableInt("bandwidth_kbps"),
    )

internal fun ResultSet.readPlayer(): PlayerRecord =
    PlayerRecord(
        playerName = getNullableString("player_name"),
        playerPlatform = getNullableString("player_platform"),
        playerProduct = getNullableString("player_product"),
        playerMachineId = getNullableString("player_machine_id"),
        ipAddress = getNullableString("ip_address"),
        location = getNullableString("location"),
    )
