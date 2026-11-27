package app.hononeko.miru.persistence.model

data class AuthAccountRecord(
    val id: String,
    val username: String,
    val usernameNormalized: String,
    val email: String? = null,
    val passwordHash: String,
    val role: String,
    val disabled: Boolean = false,
    val authProvider: String = "LOCAL",
    val externalSubject: String? = null,
    val mediaUserLinkMode: String = "MANUAL",
    /** Whether an SSO sign-in sets [role] from the account's groups (AUTO) or keeps it (MANUAL). */
    val roleMode: String = "MANUAL",
    /** Whether the IdP marked [email] verified at the last SSO sign-in; null until recorded. */
    val emailVerified: Boolean? = null,
    val displayName: String? = null,
    val avatarBytes: ByteArray? = null,
    val avatarContentType: String? = null,
    val avatarSourceServerId: String? = null,
    val avatarUpdatedAtMs: Long? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    val hasCustomAvatar: Boolean
        get() = avatarUpdatedAtMs != null || avatarBytes != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthAccountRecord) return false
        return id == other.id &&
            username == other.username &&
            usernameNormalized == other.usernameNormalized &&
            email == other.email &&
            passwordHash == other.passwordHash &&
            role == other.role &&
            disabled == other.disabled &&
            authProvider == other.authProvider &&
            externalSubject == other.externalSubject &&
            mediaUserLinkMode == other.mediaUserLinkMode &&
            roleMode == other.roleMode &&
            emailVerified == other.emailVerified &&
            displayName == other.displayName &&
            avatarBytes.contentEquals(other.avatarBytes) &&
            avatarContentType == other.avatarContentType &&
            avatarSourceServerId == other.avatarSourceServerId &&
            avatarUpdatedAtMs == other.avatarUpdatedAtMs &&
            createdAtMs == other.createdAtMs &&
            updatedAtMs == other.updatedAtMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + usernameNormalized.hashCode()
        result = 31 * result + (email?.hashCode() ?: 0)
        result = 31 * result + passwordHash.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + disabled.hashCode()
        result = 31 * result + authProvider.hashCode()
        result = 31 * result + (externalSubject?.hashCode() ?: 0)
        result = 31 * result + mediaUserLinkMode.hashCode()
        result = 31 * result + roleMode.hashCode()
        result = 31 * result + (emailVerified?.hashCode() ?: 0)
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + (avatarBytes?.contentHashCode() ?: 0)
        result = 31 * result + (avatarContentType?.hashCode() ?: 0)
        result = 31 * result + (avatarSourceServerId?.hashCode() ?: 0)
        result = 31 * result + (avatarUpdatedAtMs?.hashCode() ?: 0)
        result = 31 * result + createdAtMs.hashCode()
        result = 31 * result + updatedAtMs.hashCode()
        return result
    }

    override fun toString(): String =
        "AuthAccountRecord(id=$id, username=$username, usernameNormalized=$usernameNormalized, " +
            "email=$email, passwordHash=***, role=$role, disabled=$disabled, " +
            "authProvider=$authProvider, externalSubject=$externalSubject, " +
            "mediaUserLinkMode=$mediaUserLinkMode, roleMode=$roleMode, emailVerified=$emailVerified, " +
            "displayName=$displayName, avatarBytes=${avatarBytes?.let { "<${it.size} bytes>" } ?: "null"}, " +
            "avatarContentType=$avatarContentType, avatarSourceServerId=$avatarSourceServerId, " +
            "avatarUpdatedAtMs=$avatarUpdatedAtMs, createdAtMs=$createdAtMs, updatedAtMs=$updatedAtMs)"
}

data class AuthAccountProfileRecord(
    val id: String,
    val username: String,
    val displayName: String? = null,
    val avatarContentType: String? = null,
    val avatarSourceServerId: String? = null,
    val avatarUpdatedAtMs: Long? = null,
) {
    val hasCustomAvatar: Boolean
        get() = avatarUpdatedAtMs != null
}

data class AuthAccountAvatarRecord(
    val accountId: String,
    val bytes: ByteArray,
    val contentType: String,
    val sourceServerId: String? = null,
    val updatedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthAccountAvatarRecord) return false
        return accountId == other.accountId &&
            bytes.contentEquals(other.bytes) &&
            contentType == other.contentType &&
            sourceServerId == other.sourceServerId &&
            updatedAtMs == other.updatedAtMs
    }

    override fun hashCode(): Int {
        var result = accountId.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (sourceServerId?.hashCode() ?: 0)
        result = 31 * result + updatedAtMs.hashCode()
        return result
    }

    override fun toString(): String =
        "AuthAccountAvatarRecord(accountId=$accountId, bytes=<${bytes.size} bytes>, " +
            "contentType=$contentType, sourceServerId=$sourceServerId, updatedAtMs=$updatedAtMs)"
}

sealed interface DisplayNameMutation {
    data object Keep : DisplayNameMutation

    data class Set(
        val displayName: String?,
    ) : DisplayNameMutation
}

sealed interface AvatarMutation {
    data object Keep : AvatarMutation

    data object Clear : AvatarMutation

    data class Set(
        val bytes: ByteArray,
        val contentType: String,
        val sourceServerId: String? = null,
        val avatarUpdatedAtMs: Long,
    ) : AvatarMutation {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Set) return false
            return bytes.contentEquals(other.bytes) &&
                contentType == other.contentType &&
                sourceServerId == other.sourceServerId &&
                avatarUpdatedAtMs == other.avatarUpdatedAtMs
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + contentType.hashCode()
            result = 31 * result + (sourceServerId?.hashCode() ?: 0)
            result = 31 * result + avatarUpdatedAtMs.hashCode()
            return result
        }

        override fun toString(): String =
            "Set(bytes=<${bytes.size} bytes>, contentType=$contentType, " +
                "sourceServerId=$sourceServerId, avatarUpdatedAtMs=$avatarUpdatedAtMs)"
    }
}

data class AuthSessionRecord(
    val id: String,
    val accountId: String,
    val tokenHash: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val lastSeenAtMs: Long,
) {
    override fun toString(): String =
        "AuthSessionRecord(id=$id, accountId=$accountId, tokenHash=***, " +
            "createdAtMs=$createdAtMs, expiresAtMs=$expiresAtMs, lastSeenAtMs=$lastSeenAtMs)"
}

data class ApiKeyRecord(
    val id: String,
    val accountId: String,
    val name: String,
    val keyPrefix: String,
    val keyHash: String,
    val role: String,
    val lastUsedAtMs: Long? = null,
    val expiresAtMs: Long? = null,
    val revokedAtMs: Long? = null,
    val createdAtMs: Long,
) {
    override fun toString(): String =
        "ApiKeyRecord(id=$id, accountId=$accountId, name=$name, keyPrefix=$keyPrefix, " +
            "keyHash=***, role=$role, lastUsedAtMs=$lastUsedAtMs, " +
            "expiresAtMs=$expiresAtMs, revokedAtMs=$revokedAtMs, createdAtMs=$createdAtMs)"
}

data class UserPreferencesRecord(
    val accountId: String,
    val theme: String = "system",
    val timeFormat: String = "24h",
    val dateFormat: String = "iso",
    val defaultStatsRange: String = "30d",
    val relativeTimestamps: Boolean = true,
    val analyticsMetric: String = "plays",
    val updatedAtMs: Long,
)

data class SessionAuthContext(
    val session: AuthSessionRecord,
    val account: AuthAccountRecord,
    val linkedMediaUserIds: Set<String>,
)

data class ApiKeyAuthContext(
    val apiKey: ApiKeyRecord,
    val account: AuthAccountRecord,
    val linkedMediaUserIds: Set<String>,
)
