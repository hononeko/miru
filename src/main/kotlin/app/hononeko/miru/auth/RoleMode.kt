package app.hononeko.miru.auth

import kotlinx.serialization.Serializable

/**
 * Where an account's role comes from.
 *
 * [AUTO] is only meaningful for SSO (OIDC) accounts: every sign-in sets the role from the account's groups.
 * [MANUAL] accounts keep the role an admin set. Setting an account's role makes it [MANUAL]; local accounts
 * are always [MANUAL]. Either way, signing in with SSO takes a group that Miru accepts.
 */
@Serializable
enum class RoleMode {
    AUTO,
    MANUAL,
    ;

    companion object {
        /** Unknown stored values fall back to [MANUAL] so a bad row never lets a sign-in change the role. */
        fun fromStored(raw: String): RoleMode = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: MANUAL
    }
}
