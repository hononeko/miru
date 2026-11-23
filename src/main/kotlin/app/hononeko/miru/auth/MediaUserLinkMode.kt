package app.hononeko.miru.auth

import kotlinx.serialization.Serializable

/**
 * How an account's linked media users are maintained.
 *
 * [AUTO] is only meaningful for SSO (OIDC) accounts: on every sign-in Miru replaces the account's
 * links with the media users that share the account's verified email. [MANUAL] accounts keep exactly
 * the links an admin set. Editing an account's links makes it [MANUAL]; local accounts are always [MANUAL].
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
