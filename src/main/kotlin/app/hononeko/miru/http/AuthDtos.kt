package app.hononeko.miru.http

import app.hononeko.miru.auth.AccountWithLinks
import app.hononeko.miru.auth.AuthProvidersInfo
import app.hononeko.miru.auth.AuthSource
import app.hononeko.miru.auth.MediaUserLinkMode
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.auth.RoleMode
import app.hononeko.miru.persistence.model.ApiKeyRecord
import app.hononeko.miru.persistence.model.UserPreferencesRecord
import kotlinx.serialization.Serializable

@Serializable
data class AuthProvidersResponse(
    val authMode: String,
    val localEnabled: Boolean,
    val oidcEnabled: Boolean,
    val oidcDisplayName: String,
    val oidcLoginUrl: String? = null,
    val oidcLogoutUrl: String? = null,
    val setupRequired: Boolean,
)

@Serializable
data class SetupAdminRequest(
    val username: String,
    val password: String,
    val email: String? = null,
) {
    override fun toString(): String =
        "SetupAdminRequest(username=$username, password=***, email=$email)"
}

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
) {
    override fun toString(): String =
        "LoginRequest(username=$username, password=***)"
}

@Serializable
data class PrincipalResponse(
    val accountId: String,
    val username: String,
    /** The account's own display name, else the best one of its linked media users. */
    val displayName: String? = null,
    val email: String? = null,
    val role: MiruRole,
    val linkedMediaUserIds: List<String>,
    val authSource: AuthSource,
    val authProvider: String = "LOCAL",
    val avatarUrl: String? = null,
    val avatarSourceServerId: String? = null,
    /** Whether [displayName] is the account's own, rather than one taken from a linked media user. */
    val hasCustomDisplayName: Boolean = false,
    val hasCustomAvatar: Boolean = false,
)

/** A person's own profile change. Importing from a linked server is an admin action (`UpdateAccountRequest`). */
@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val clearDisplayName: Boolean = false,
    val clearAvatar: Boolean = false,
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
) {
    override fun toString(): String =
        "ChangePasswordRequest(currentPassword=***, newPassword=***)"
}

@Serializable
data class UpdateAccountRequest(
    val role: String? = null,
    val roleMode: RoleMode? = null,
    val disabled: Boolean? = null,
    val password: String? = null,
    val displayName: String? = null,
    val clearDisplayName: Boolean = false,
    val clearAvatar: Boolean = false,
    val importDisplayNameFromServerId: String? = null,
    val importAvatarFromServerId: String? = null,
) {
    override fun toString(): String =
        "UpdateAccountRequest(role=$role, roleMode=$roleMode, disabled=$disabled, " +
            "password=${if (password != null) "***" else "null"}, displayName=$displayName, " +
            "clearDisplayName=$clearDisplayName, clearAvatar=$clearAvatar, " +
            "importDisplayNameFromServerId=$importDisplayNameFromServerId, " +
            "importAvatarFromServerId=$importAvatarFromServerId)"
}

@Serializable
data class AccountResponse(
    val id: String,
    val username: String,
    val displayName: String? = null,
    val email: String? = null,
    val role: MiruRole,
    val roleMode: RoleMode,
    val disabled: Boolean,
    val authProvider: String,
    val linkedMediaUserIds: List<String>,
    val mediaUserLinkMode: MediaUserLinkMode,
    val emailVerified: Boolean? = null,
    val avatarUrl: String? = null,
    val avatarSourceServerId: String? = null,
    val hasCustomAvatar: Boolean = false,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Serializable
data class AccountListResponse(
    val accounts: List<AccountResponse>,
    val emailLinkingEnabled: Boolean,
)

@Serializable
data class CreateAccountRequest(
    val username: String,
    val password: String,
    val email: String? = null,
    val displayName: String? = null,
    val role: MiruRole = MiruRole.USER,
    val linkedMediaUserIds: Set<String> = emptySet(),
) {
    override fun toString(): String =
        "CreateAccountRequest(username=$username, password=***, email=$email, displayName=$displayName, role=$role, linkedMediaUserIds=$linkedMediaUserIds)"
}

@Serializable
data class SetLinkedMediaUsersRequest(
    val linkedMediaUserIds: Set<String>,
)

@Serializable
data class SetMediaUserLinkModeRequest(
    val mode: MediaUserLinkMode,
)

@Serializable
data class CreateApiKeyRequest(
    val accountId: String? = null,
    val name: String,
    val role: MiruRole = MiruRole.ADMIN,
    val expiresAtMs: Long? = null,
    val expiresIn: String? = null,
)

@Serializable
data class ApiKeyResponse(
    val id: String,
    val accountId: String,
    val name: String,
    val keyPrefix: String,
    val role: MiruRole,
    val lastUsedAtMs: Long? = null,
    val expiresAtMs: Long? = null,
    val revokedAtMs: Long? = null,
    val createdAtMs: Long,
)

@Serializable
data class ApiKeyListResponse(
    val apiKeys: List<ApiKeyResponse>,
)

@Serializable
data class CreateApiKeyResponse(
    val apiKey: ApiKeyResponse,
    val rawKey: String,
) {
    override fun toString(): String =
        "CreateApiKeyResponse(apiKey=$apiKey, rawKey=***)"
}

@Serializable
data class UserPreferencesResponse(
    val theme: String,
    val timeFormat: String,
    val dateFormat: String,
    val defaultStatsRange: String,
    val relativeTimestamps: Boolean,
    val analyticsMetric: String = "plays",
    val updatedAtMs: Long? = null,
)

/** Omitted preferences keep their value, so that a client that does not know one yet cannot reset it. */
@Serializable
data class UpdateUserPreferencesRequest(
    val theme: String? = null,
    val timeFormat: String? = null,
    val dateFormat: String? = null,
    val defaultStatsRange: String? = null,
    val relativeTimestamps: Boolean? = null,
    val analyticsMetric: String? = null,
)

internal fun MiruPrincipal.toResponse(
    avatarUrl: String? = null,
    resolvedDisplayName: String? = displayName.normalizedDisplayName(),
): PrincipalResponse =
    PrincipalResponse(
        accountId = accountId,
        username = username,
        displayName = resolvedDisplayName,
        email = email,
        role = role,
        linkedMediaUserIds = linkedMediaUserIds.sorted(),
        authSource = authSource,
        authProvider = authProvider,
        avatarUrl = avatarUrl,
        avatarSourceServerId = avatarSourceServerId,
        hasCustomDisplayName = displayName.normalizedDisplayName() != null,
        hasCustomAvatar = hasCustomAvatar,
    )

internal fun AuthProvidersInfo.toResponse(): AuthProvidersResponse =
    AuthProvidersResponse(
        authMode = authMode,
        localEnabled = localEnabled,
        oidcEnabled = oidcEnabled,
        oidcDisplayName = oidcDisplayName,
        oidcLoginUrl = oidcLoginUrl,
        oidcLogoutUrl = oidcLogoutUrl,
        setupRequired = setupRequired,
    )

internal fun AccountWithLinks.toResponse(avatarUrl: String?): AccountResponse =
    AccountResponse(
        id = account.id,
        username = account.username,
        displayName = account.displayName.normalizedDisplayName(),
        email = account.email,
        role = if (account.role.equals("ADMIN", ignoreCase = true)) MiruRole.ADMIN else MiruRole.USER,
        roleMode = RoleMode.fromStored(account.roleMode),
        disabled = account.disabled,
        authProvider = account.authProvider,
        linkedMediaUserIds = linkedMediaUserIds.sorted(),
        mediaUserLinkMode = MediaUserLinkMode.fromStored(account.mediaUserLinkMode),
        emailVerified = account.emailVerified,
        avatarUrl = avatarUrl,
        avatarSourceServerId = account.avatarSourceServerId,
        hasCustomAvatar = account.hasCustomAvatar,
        createdAtMs = account.createdAtMs,
        updatedAtMs = account.updatedAtMs,
    )

internal fun ApiKeyRecord.toResponse(): ApiKeyResponse =
    ApiKeyResponse(
        id = id,
        accountId = accountId,
        name = name,
        keyPrefix = keyPrefix,
        role = if (role.equals("ADMIN", ignoreCase = true)) MiruRole.ADMIN else MiruRole.USER,
        lastUsedAtMs = lastUsedAtMs,
        expiresAtMs = expiresAtMs,
        revokedAtMs = revokedAtMs,
        createdAtMs = createdAtMs,
    )

internal fun UserPreferencesRecord.toResponse(): UserPreferencesResponse =
    UserPreferencesResponse(
        theme = theme,
        timeFormat = timeFormat,
        dateFormat = dateFormat,
        defaultStatsRange = defaultStatsRange,
        relativeTimestamps = relativeTimestamps,
        analyticsMetric = analyticsMetric,
        updatedAtMs = updatedAtMs,
    )
