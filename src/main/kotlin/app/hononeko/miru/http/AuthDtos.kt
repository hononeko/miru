package app.hononeko.miru.http

import app.hononeko.miru.auth.AccountWithLinks
import app.hononeko.miru.auth.AuthProvidersInfo
import app.hononeko.miru.auth.AuthSource
import app.hononeko.miru.auth.MediaUserLinkMode
import app.hononeko.miru.auth.MiruPrincipal
import app.hononeko.miru.auth.MiruRole
import app.hononeko.miru.persistence.model.ApiKeyRecord
import app.hononeko.miru.persistence.model.UserPreferencesRecord
import kotlinx.serialization.Serializable

@Serializable
data class AuthStatusResponse(
    val authMode: String,
    val setupRequired: Boolean,
    val localEnabled: Boolean? = null,
    val oidcEnabled: Boolean? = null,
    val oidcDisplayName: String? = null,
)

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
    val email: String? = null,
    val role: MiruRole,
    val linkedMediaUserIds: List<String>,
    val authSource: AuthSource,
    val avatarUrl: String? = null,
)

@Serializable
data class AccountResponse(
    val id: String,
    val username: String,
    val email: String? = null,
    val role: MiruRole,
    val disabled: Boolean,
    val authProvider: String,
    val linkedMediaUserIds: List<String>,
    val mediaUserLinkMode: MediaUserLinkMode,
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
    val role: MiruRole = MiruRole.USER,
    val linkedMediaUserIds: Set<String> = emptySet(),
) {
    override fun toString(): String =
        "CreateAccountRequest(username=$username, password=***, email=$email, role=$role, linkedMediaUserIds=$linkedMediaUserIds)"
}

@Serializable
data class SetLinkedMediaUsersRequest(
    val mediaUserIds: Set<String>,
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

@Serializable
data class UpdateUserPreferencesRequest(
    val theme: String = "system",
    val timeFormat: String = "24h",
    val dateFormat: String = "iso",
    val defaultStatsRange: String = "30d",
    val relativeTimestamps: Boolean = true,
    val analyticsMetric: String = "plays",
)

internal fun MiruPrincipal.toResponse(avatarUrl: String? = null): PrincipalResponse =
    PrincipalResponse(
        accountId = accountId,
        username = username,
        email = email,
        role = role,
        linkedMediaUserIds = linkedMediaUserIds.sorted(),
        authSource = authSource,
        avatarUrl = avatarUrl,
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

internal fun AccountWithLinks.toResponse(): AccountResponse =
    AccountResponse(
        id = account.id,
        username = account.username,
        email = account.email,
        role = if (account.role.equals("ADMIN", ignoreCase = true)) MiruRole.ADMIN else MiruRole.USER,
        disabled = account.disabled,
        authProvider = account.authProvider,
        linkedMediaUserIds = linkedMediaUserIds.sorted(),
        mediaUserLinkMode = MediaUserLinkMode.fromStored(account.mediaUserLinkMode),
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
