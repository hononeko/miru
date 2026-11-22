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
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    override fun toString(): String =
        "AuthAccountRecord(id=$id, username=$username, usernameNormalized=$usernameNormalized, " +
            "email=$email, passwordHash=***, role=$role, disabled=$disabled, " +
            "authProvider=$authProvider, externalSubject=$externalSubject, " +
            "createdAtMs=$createdAtMs, updatedAtMs=$updatedAtMs)"
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
