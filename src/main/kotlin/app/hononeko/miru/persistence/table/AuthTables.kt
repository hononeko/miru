package app.hononeko.miru.persistence.table

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object AuthAccountsTable : Table("auth_accounts") {
    val id = text("id")
    val username = text("username")
    val usernameNormalized = text("username_normalized").uniqueIndex()
    val email = text("email").nullable()
    val passwordHash = text("password_hash")
    val role = text("role")
    val disabled = bool("disabled").default(false)
    val authProvider = text("auth_provider").default("LOCAL")
    val externalSubject = text("external_subject").nullable()
    val createdAtMs = long("created_at_ms")
    val updatedAtMs = long("updated_at_ms")

    override val primaryKey = PrimaryKey(id)
}

object AuthAccountMediaUsersTable : Table("auth_account_media_users") {
    val accountId = text("account_id").references(AuthAccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val mediaUserId = text("media_user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val createdAtMs = long("created_at_ms")

    override val primaryKey = PrimaryKey(accountId, mediaUserId)

    init {
        index(isUnique = false, mediaUserId)
    }
}

object AuthSessionsTable : Table("auth_sessions") {
    val id = text("id")
    val accountId = text("account_id").references(AuthAccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val tokenHash = text("token_hash").uniqueIndex()
    val createdAtMs = long("created_at_ms")
    val expiresAtMs = long("expires_at_ms")
    val lastSeenAtMs = long("last_seen_at_ms")

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, accountId)
        index(isUnique = false, expiresAtMs)
    }
}

object ApiKeysTable : Table("api_keys") {
    val id = text("id")
    val accountId = text("account_id").references(AuthAccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val name = text("name")
    val keyPrefix = text("key_prefix")
    val keyHash = text("key_hash").uniqueIndex()
    val role = text("role")
    val lastUsedAtMs = long("last_used_at_ms").nullable()
    val expiresAtMs = long("expires_at_ms").nullable()
    val revokedAtMs = long("revoked_at_ms").nullable()
    val createdAtMs = long("created_at_ms")

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, accountId)
    }
}

object UserPreferencesTable : Table("user_preferences") {
    val accountId = text("account_id").references(AuthAccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val theme = text("theme").default("system")
    val timeFormat = text("time_format").default("24h")
    val dateFormat = text("date_format").default("iso")
    val defaultStatsRange = text("default_stats_range").default("30d")
    val relativeTimestamps = bool("relative_timestamps").default(true)
    val analyticsMetric = text("analytics_metric").default("plays")
    val updatedAtMs = long("updated_at_ms")

    override val primaryKey = PrimaryKey(accountId)
}
