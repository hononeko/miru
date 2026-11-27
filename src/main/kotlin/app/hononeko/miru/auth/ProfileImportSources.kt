package app.hononeko.miru.auth

import app.hononeko.miru.artwork.ArtworkProxyService

/** The media server access that importing a display name or avatar from a linked server needs. */
class ProfileImportSources(
    /** Fetches a linked media user's avatar from its server; avatar imports fail without it. */
    val artworkProxyService: ArtworkProxyService? = null,
    /**
     * Re-syncs one media server's users before an import, so it sees names and avatars changed on the
     * server since the last scheduled sync. Best effort: a failed sync leaves the stored users to import from.
     */
    val syncServerUsers: suspend (serverId: String) -> Unit = {},
)
