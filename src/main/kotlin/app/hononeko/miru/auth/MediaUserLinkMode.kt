package app.hononeko.miru.auth

import kotlinx.serialization.Serializable

/**
 * How an account's linked media users are maintained.
 *
 * [AUTO] is only meaningful for SSO (OIDC) accounts: Miru keeps the account's links in sync with the media
 * users that share its verified email when its sign-in email or verification changes and after each media
 * user sync. [MANUAL] accounts keep exactly the links an admin set. Editing an account's links makes it
 * [MANUAL]; local accounts are always [MANUAL].
 */
@Serializable
enum class MediaUserLinkMode {
    AUTO,
    MANUAL,
    ;

    companion object {
        /** Unknown stored values fall back to [MANUAL] so a bad row never triggers automatic relinking. */
        fun fromStored(raw: String): MediaUserLinkMode = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: MANUAL
    }
}
