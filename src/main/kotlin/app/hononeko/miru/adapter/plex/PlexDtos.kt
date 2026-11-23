package app.hononeko.miru.adapter.plex

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Accepts Plex booleans encoded as JSON booleans (`true`), integers (`1`/`0`), or strings (`"1"`/`"0"`).
 */
object FlexibleBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Boolean {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive ?: return false
        return primitive.booleanOrNull
            ?: when (primitive.content.trim().lowercase()) {
                "true", "1" -> true
                else -> false
            }
    }

    override fun serialize(
        encoder: Encoder,
        value: Boolean,
    ) {
        encoder.encodeBoolean(value)
    }
}

/**
 * Accepts Plex identifiers encoded as either JSON strings (`"1"`) or numbers (`1`).
 */
object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        return (element as? JsonPrimitive)?.content ?: element.toString()
    }

    override fun serialize(
        encoder: Encoder,
        value: String,
    ) {
        encoder.encodeString(value)
    }
}

typealias FlexibleBoolean =
    @Serializable(with = FlexibleBooleanSerializer::class)
    Boolean

typealias FlexibleString =
    @Serializable(with = FlexibleStringSerializer::class)
    String

@Serializable
data class PlexServerInfoResponse(
    @SerialName("MediaContainer")
    val mediaContainer: PlexServerInfoContainer,
)

@Serializable
data class PlexServerInfoContainer(
    val machineIdentifier: String? = null,
    val friendlyName: String? = null,
    val version: String? = null,
    val platform: String? = null,
    val claimed: FlexibleBoolean? = null,
)

@Serializable
data class PlexSessionsResponse(
    @SerialName("MediaContainer")
    val mediaContainer: PlexSessionsContainer,
)

@Serializable
data class PlexSessionsContainer(
    val size: Int? = null,
    @SerialName("Metadata")
    val metadata: List<PlexMetadataDto>? = null,
)

@Serializable
data class PlexLibrarySectionsResponse(
    @SerialName("MediaContainer")
    val mediaContainer: PlexLibrarySectionsContainer,
)

@Serializable
data class PlexLibrarySectionsContainer(
    val size: Int? = null,
    @SerialName("Directory")
    val directory: List<PlexDirectoryDto>? = null,
)

@Serializable
data class PlexDirectoryDto(
    val key: FlexibleString? = null,
    val title: String? = null,
    val type: String? = null,
    val uuid: String? = null,
    val count: Int? = null,
)

@Serializable
data class PlexLibraryItemsResponse(
    @SerialName("MediaContainer")
    val mediaContainer: PlexLibraryItemsContainer,
)

@Serializable
data class PlexLibraryItemsContainer(
    val size: Int? = null,
    val totalSize: Int? = null,
    val offset: Int? = null,
    @SerialName("Metadata")
    val metadata: List<PlexMetadataDto>? = null,
)

@Serializable
data class PlexTagDto(
    val id: FlexibleString? = null,
    val tag: String? = null,
)

@Serializable
class PlexMetadataDto {
    var ratingKey: FlexibleString? = null
    var sessionKey: FlexibleString? = null
    var type: String? = null
    var title: String? = null
    var parentTitle: String? = null
    var grandparentTitle: String? = null
    var parentRatingKey: FlexibleString? = null
    var grandparentRatingKey: FlexibleString? = null
    var parentIndex: Int? = null
    var index: Int? = null
    var year: Int? = null
    var originallyAvailableAt: String? = null
    var duration: Long? = null
    var viewOffset: Long? = null
    var contentRating: String? = null
    var studio: String? = null
    var summary: String? = null
    var thumb: String? = null
    var parentThumb: String? = null
    var grandparentThumb: String? = null
    var art: String? = null
    var librarySectionID: FlexibleString? = null
    var librarySectionTitle: String? = null
    var addedAt: Long? = null
    var childCount: Int? = null
    var leafCount: Int? = null
    var viewedLeafCount: Int? = null
    var live: FlexibleBoolean? = null
    var channelIdentifier: String? = null
    var channelCallSign: String? = null
    var channelTitle: String? = null

    @SerialName("Genre")
    var genre: List<PlexTagDto>? = null

    @SerialName("Director")
    var director: List<PlexTagDto>? = null

    @SerialName("Media")
    var media: List<PlexMediaDto>? = null

    @SerialName("User")
    var user: PlexSessionUserDto? = null

    @SerialName("Player")
    var player: PlexPlayerDto? = null

    @SerialName("Session")
    var session: PlexSessionDto? = null

    @SerialName("TranscodeSession")
    var transcodeSession: PlexTranscodeSessionDto? = null
}

@Serializable
data class PlexMediaDto(
    val id: FlexibleString? = null,
    val duration: Long? = null,
    val bitrate: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoResolution: String? = null,
    val videoProfile: String? = null,
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val selected: FlexibleBoolean? = null,
    @SerialName("Part")
    val part: List<PlexPartDto>? = null,
)

@Serializable
data class PlexPartDto(
    val id: FlexibleString? = null,
    val key: String? = null,
    val duration: Long? = null,
    val size: Long? = null,
    val container: String? = null,
    val decision: String? = null,
    val selected: FlexibleBoolean? = null,
    @SerialName("Stream")
    val stream: List<PlexStreamDto>? = null,
)

@Serializable
data class PlexStreamDto(
    val id: FlexibleString? = null,
    val streamType: Int? = null,
    val codec: String? = null,
    val profile: String? = null,
    val decision: String? = null,
    val selected: FlexibleBoolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Int? = null,
    val channels: Int? = null,
    val language: String? = null,
    val languageCode: String? = null,
    val languageTag: String? = null,
    val title: String? = null,
    val displayTitle: String? = null,
)

@Serializable
data class PlexSessionUserDto(
    val id: FlexibleString? = null,
    val title: String? = null,
    val thumb: String? = null,
)

@Serializable
data class PlexPlayerDto(
    val machineIdentifier: String? = null,
    val product: String? = null,
    val platform: String? = null,
    val device: String? = null,
    val title: String? = null,
    val address: String? = null,
    val local: FlexibleBoolean? = null,
    val state: String? = null,
)

@Serializable
data class PlexSessionDto(
    val id: String? = null,
    val bandwidth: Int? = null,
    val location: String? = null,
)

@Serializable
data class PlexTranscodeSessionDto(
    val sourceVideoCodec: String? = null,
    val sourceAudioCodec: String? = null,
    val videoDecision: String? = null,
    val audioDecision: String? = null,
    val subtitleDecision: String? = null,
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val transcodeHwRequested: FlexibleBoolean? = null,
    val transcodeHwFullPipeline: FlexibleBoolean? = null,
    val transcodeHwDecoding: String? = null,
    val transcodeHwEncoding: String? = null,
    val progress: Double? = null,
    val speed: Double? = null,
)

@Serializable
data class PlexAccountsResponse(
    @SerialName("MediaContainer")
    val mediaContainer: PlexAccountsContainer,
)

@Serializable
data class PlexAccountsContainer(
    val size: Int? = null,
    @SerialName("Account")
    val account: List<PlexAccountDto>? = null,
)

@Serializable
data class PlexAccountDto(
    val id: Long,
    val name: String? = null,
    val thumb: String? = null,
)

@Serializable
data class PlexTvUserDto(
    val id: Long,
    val uuid: String? = null,
    val username: String? = null,
    val title: String? = null,
    val email: String? = null,
    val thumb: String? = null,
)

@Serializable
data class PlexTvFriendDto(
    val id: Long,
    val uuid: String? = null,
    val username: String? = null,
    val title: String? = null,
    val email: String? = null,
    val thumb: String? = null,
)
