package app.hononeko.miru.adapter.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class JellyfinSystemInfoDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("OperatingSystem") val operatingSystem: String? = null,
    @SerialName("ProductName") val productName: String? = null,
)

@Serializable
data class JellyfinSessionDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("UserId") val userId: String? = null,
    @SerialName("UserName") val userName: String? = null,
    @SerialName("UserPrimaryImageTag") val userPrimaryImageTag: String? = null,
    @SerialName("Client") val client: String? = null,
    @SerialName("DeviceName") val deviceName: String? = null,
    @SerialName("DeviceId") val deviceId: String? = null,
    @SerialName("ApplicationVersion") val applicationVersion: String? = null,
    @SerialName("RemoteEndPoint") val remoteEndPoint: String? = null,
    @SerialName("NowPlayingItem") val nowPlayingItem: JellyfinBaseItemDto? = null,
    @SerialName("PlayState") val playState: JellyfinPlayStateDto? = null,
    @SerialName("TranscodingInfo") val transcodingInfo: JellyfinTranscodingInfoDto? = null,
    @SerialName("LastActivityDate") val lastActivityDate: String? = null,
)

@Serializable
data class JellyfinPlayStateDto(
    @SerialName("PositionTicks") val positionTicks: Long? = null,
    @SerialName("IsPaused") val isPaused: Boolean? = null,
    @SerialName("PlayMethod") val playMethod: String? = null,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
)

@Serializable
data class JellyfinTranscodingInfoDto(
    @SerialName("AudioCodec") val audioCodec: String? = null,
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("IsVideoDirect") val isVideoDirect: Boolean? = null,
    @SerialName("IsAudioDirect") val isAudioDirect: Boolean? = null,
    @SerialName("Bitrate") val bitrate: Int? = null,
    @SerialName("CompletionPercentage") val completionPercentage: Double? = null,
    @SerialName("TranscodeReasons") val transcodeReasons: List<String>? = null,
    @SerialName("HardwareAccelerationType") val hardwareAccelerationType: String? = null,
    @SerialName("Framerate") val framerate: Double? = null,
    @SerialName("TranscodingFramerate") val transcodingFramerate: Double? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
)

@Serializable
data class JellyfinBaseItemDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("SeasonName") val seasonName: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("AlbumArtists") val albumArtists: List<JellyfinNameIdPairDto>? = null,
    @SerialName("Artists") val artists: List<String>? = null,
    @SerialName("ArtistItems") val artistItems: List<JellyfinNameIdPairDto>? = null,
    @SerialName("ParentId") val parentId: String? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("Studios") val studios: List<JellyfinNameIdPairDto>? = null,
    @SerialName("People") val people: List<JellyfinPersonDto>? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("BackdropImageTags") val backdropImageTags: List<String>? = null,
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String>? = null,
    @SerialName("MediaSources") val mediaSources: List<JellyfinMediaSourceDto>? = null,
    @SerialName("MediaStreams") val mediaStreams: List<JellyfinMediaStreamDto>? = null,
    @SerialName("ChannelName") val channelName: String? = null,
    @SerialName("ChannelNumber") val channelNumber: String? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("RecursiveItemCount") val recursiveItemCount: Int? = null,
    @SerialName("UserData") val userData: JellyfinUserItemDataDto? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
)

@Serializable
data class JellyfinNameIdPairDto(
    @SerialName("Name") val name: String? = null,
    @SerialName("Id") val id: String? = null,
)

@Serializable
data class JellyfinPersonDto(
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
)

@Serializable
data class JellyfinUserItemDataDto(
    @SerialName("Played") val played: Boolean? = null,
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
)

@Serializable
data class JellyfinMediaSourceDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Bitrate") val bitrate: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("MediaStreams") val mediaStreams: List<JellyfinMediaStreamDto>? = null,
)

@Serializable
data class JellyfinMediaStreamDto(
    @SerialName("Type") val type: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Profile") val profile: String? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("BitRate") val bitRate: Int? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean? = null,
    @SerialName("Index") val index: Int? = null,
)

@Serializable
data class JellyfinUserDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
    @SerialName("LastActivityDate") val lastActivityDate: String? = null,
    @SerialName("Policy") val policy: JellyfinUserPolicyDto? = null,
)

@Serializable
data class JellyfinUserPolicyDto(
    @SerialName("IsAdministrator") val isAdministrator: Boolean? = null,
    @SerialName("EnableAllFolders") val enableAllFolders: Boolean? = null,
    @SerialName("EnabledFolders") val enabledFolders: List<String>? = null,
)

@Serializable
data class JellyfinVirtualFolderDto(
    @SerialName("Name") val name: String? = null,
    @SerialName("ItemId") val itemId: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("Locations") val locations: List<String>? = null,
)

@Serializable
data class JellyfinItemsQueryResponse(
    @SerialName("Items") val items: List<JellyfinBaseItemDto>? = null,
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
    @SerialName("StartIndex") val startIndex: Int? = null,
)

@Serializable
data class JellyfinMessageRequest(
    @SerialName("Header") val header: String,
    @SerialName("Text") val text: String,
    @SerialName("TimeoutMs") val timeoutMs: Int = 5_000,
)

@Serializable
data class JellyfinWsInboundFrame(
    @SerialName("MessageType") val messageType: String? = null,
    @SerialName("MessageId") val messageId: String? = null,
    @SerialName("Data") val data: JsonElement? = null,
)

@Serializable
data class JellyfinWsOutboundFrame(
    @SerialName("MessageType") val messageType: String,
    @SerialName("Data") val data: String? = null,
)

@Serializable
data class JellyfinLibraryChangedDto(
    @SerialName("CollectionFolders") val collectionFolders: List<String>? = null,
    @SerialName("FoldersAddedTo") val foldersAddedTo: List<String>? = null,
    @SerialName("FoldersRemovedFrom") val foldersRemovedFrom: List<String>? = null,
    @SerialName("FoldersUpdated") val foldersUpdated: List<String>? = null,
    @SerialName("ItemsAdded") val itemsAdded: List<String>? = null,
    @SerialName("ItemsUpdated") val itemsUpdated: List<String>? = null,
    @SerialName("ItemsRemoved") val itemsRemoved: List<String>? = null,
)
