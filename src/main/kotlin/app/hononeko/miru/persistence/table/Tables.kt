package app.hononeko.miru.persistence.table

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object MediaServersTable : Table("media_servers") {
    val id = text("id")
    val name = text("name")
    val serverType = text("server_type")
    val machineId = text("machine_id").uniqueIndex()
    val baseUrl = text("base_url")
    val encryptedToken = text("encrypted_token").nullable()
    val clientIdentifier = text("client_identifier").nullable()
    val createdAtMs = long("created_at_ms")
    val updatedAtMs = long("updated_at_ms")

    override val primaryKey = PrimaryKey(id)
}

object UsersTable : Table("users") {
    val id = text("id")
    val serverId = text("server_id").references(MediaServersTable.id, onDelete = ReferenceOption.CASCADE)
    val externalUserId = text("external_user_id")
    val username = text("username")
    val displayName = text("display_name").nullable()
    val email = text("email").nullable()
    val thumbUrl = text("thumb_url").nullable()
    val isAdmin = bool("is_admin").default(false)
    val lastSeenAtMs = long("last_seen_at_ms").nullable()
    val createdAtMs = long("created_at_ms")
    val updatedAtMs = long("updated_at_ms")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(serverId, externalUserId)
    }
}

object ActiveSessionsTable : Table("active_sessions") {
    val serverId = text("server_id").references(MediaServersTable.id, onDelete = ReferenceOption.CASCADE)
    val sessionKey = text("session_key")
    val ratingKey = text("rating_key")
    val sessionId = text("session_id").nullable()
    val userId = text("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val mediaType = text("media_type")
    val title = text("title")
    val parentTitle = text("parent_title").nullable()
    val grandparentTitle = text("grandparent_title").nullable()
    val parentIndex = integer("parent_index").nullable()
    val mediaIndex = integer("media_index").nullable()
    val year = integer("year").nullable()
    val thumbPath = text("thumb_path").nullable()
    val librarySectionKey = text("library_section_key").nullable()
    val durationMs = long("duration_ms")
    val viewOffsetMs = long("view_offset_ms")
    val state = text("state")
    val streamDecision = text("stream_decision")
    val videoDecision = text("video_decision").nullable()
    val audioDecision = text("audio_decision").nullable()
    val hwTranscode = bool("hw_transcode").default(false)
    val videoCodec = text("video_codec").nullable()
    val audioCodec = text("audio_codec").nullable()
    val videoResolution = text("video_resolution").nullable()
    val bandwidthKbps = integer("bandwidth_kbps").nullable()
    val transcodeVideoCodec = text("transcode_video_codec").nullable()
    val transcodeAudioCodec = text("transcode_audio_codec").nullable()
    val container = text("container").nullable()
    val transcodeContainer = text("transcode_container").nullable()
    val subtitleDecision = text("subtitle_decision").nullable()
    val playerName = text("player_name").nullable()
    val playerPlatform = text("player_platform").nullable()
    val playerProduct = text("player_product").nullable()
    val playerMachineId = text("player_machine_id").nullable()
    val ipAddress = text("ip_address").nullable()
    val location = text("location").nullable()
    val startedAtMs = long("started_at_ms")
    val lastSeenAtMs = long("last_seen_at_ms")
    val pausedDurationSec = integer("paused_duration_sec").default(0)
    val missedPolls = integer("missed_polls").default(0)

    override val primaryKey = PrimaryKey(serverId, sessionKey, ratingKey)
}

object PlaybackHistoryTable : Table("playback_history") {
    val id = text("id")
    val serverId = text("server_id").references(MediaServersTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = text("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val externalImportKey = text("external_import_key").nullable().uniqueIndex()
    val ratingKey = text("rating_key")
    val mediaType = text("media_type")
    val title = text("title")
    val parentTitle = text("parent_title").nullable()
    val grandparentTitle = text("grandparent_title").nullable()
    val parentIndex = integer("parent_index").nullable()
    val mediaIndex = integer("media_index").nullable()
    val year = integer("year").nullable()
    val thumbPath = text("thumb_path").nullable()
    val librarySectionKey = text("library_section_key").nullable()
    val durationMs = long("duration_ms")
    val viewOffsetMs = long("view_offset_ms")
    val watchedDurationSec = integer("watched_duration_sec")
    val pausedDurationSec = integer("paused_duration_sec").default(0)
    val completionPercent = double("completion_percent")
    val isWatched = bool("is_watched").default(false)
    val streamDecision = text("stream_decision")
    val videoDecision = text("video_decision").nullable()
    val audioDecision = text("audio_decision").nullable()
    val hwTranscode = bool("hw_transcode").default(false)
    val videoCodec = text("video_codec").nullable()
    val audioCodec = text("audio_codec").nullable()
    val videoResolution = text("video_resolution").nullable()
    val bandwidthKbps = integer("bandwidth_kbps").nullable()
    val transcodeVideoCodec = text("transcode_video_codec").nullable()
    val transcodeAudioCodec = text("transcode_audio_codec").nullable()
    val container = text("container").nullable()
    val transcodeContainer = text("transcode_container").nullable()
    val subtitleDecision = text("subtitle_decision").nullable()
    val playerName = text("player_name").nullable()
    val playerPlatform = text("player_platform").nullable()
    val playerProduct = text("player_product").nullable()
    val playerMachineId = text("player_machine_id").nullable()
    val ipAddress = text("ip_address").nullable()
    val location = text("location").nullable()
    val startedAtMs = long("started_at_ms")
    val stoppedAtMs = long("stopped_at_ms")

    override val primaryKey = PrimaryKey(id)
}

object LibrarySectionsTable : Table("library_sections") {
    val serverId = text("server_id").references(MediaServersTable.id, onDelete = ReferenceOption.CASCADE)
    val sectionKey = text("section_key")
    val title = text("title")
    val sectionType = text("section_type")
    val itemCount = integer("item_count").default(0)
    val childCount = integer("child_count").default(0)
    val leafCount = integer("leaf_count").default(0)
    val totalSizeBytes = long("total_size_bytes").default(0L)
    val totalDurationMs = long("total_duration_ms").default(0L)
    val lastSyncedAtMs = long("last_synced_at_ms").nullable()
    val updatedAtMs = long("updated_at_ms")

    override val primaryKey = PrimaryKey(serverId, sectionKey)
}

object LibrarySnapshotsTable : Table("library_snapshots") {
    val id = text("id")
    val serverId = text("server_id")
    val sectionKey = text("section_key")
    val capturedAtMs = long("captured_at_ms")
    val itemCount = integer("item_count")
    val childCount = integer("child_count")
    val leafCount = integer("leaf_count")
    val totalSizeBytes = long("total_size_bytes")
    val totalDurationMs = long("total_duration_ms")
    val aggregatesJson = text("aggregates_json").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        foreignKey(
            serverId,
            sectionKey,
            target = LibrarySectionsTable.primaryKey,
            onDelete = ReferenceOption.CASCADE,
        )
    }
}

object MediaItemsCacheTable : Table("media_items_cache") {
    val serverId = text("server_id").references(MediaServersTable.id, onDelete = ReferenceOption.CASCADE)
    val ratingKey = text("rating_key")
    val sectionKey = text("section_key")
    val mediaType = text("media_type")
    val title = text("title")
    val parentTitle = text("parent_title").nullable()
    val grandparentTitle = text("grandparent_title").nullable()
    val year = integer("year").nullable()
    val thumbPath = text("thumb_path").nullable()
    val durationMs = long("duration_ms").nullable()
    val fileSizeBytes = long("file_size_bytes").nullable()
    val videoResolution = text("video_resolution").nullable()
    val videoCodec = text("video_codec").nullable()
    val audioCodec = text("audio_codec").nullable()
    val addedAtMs = long("added_at_ms")
    val updatedAtMs = long("updated_at_ms")

    override val primaryKey = PrimaryKey(serverId, ratingKey)
}

object UserLibraryAccessTable : Table("user_library_access") {
    val serverId = text("server_id").references(MediaServersTable.id, onDelete = ReferenceOption.CASCADE)
    val externalUserId = text("external_user_id")
    val allLibraries = bool("all_libraries").default(false)
    val sectionKeysJson = text("section_keys_json").default("[]")
    val updatedAtMs = long("updated_at_ms")

    override val primaryKey = PrimaryKey(serverId, externalUserId)
}
