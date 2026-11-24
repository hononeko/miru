package app.hononeko.miru.auth

import app.hononeko.miru.domain.error.MiruError
import arrow.core.Either

enum class MiruRole {
    ADMIN,
    USER,
}

enum class AuthSource {
    SESSION_COOKIE,
    API_KEY,
    DEV_BYPASS,
}

data class MiruPrincipal(
    val accountId: String,
    val username: String,
    val email: String? = null,
    val role: MiruRole,
    val linkedMediaUserIds: Set<String> = emptySet(),
    val authSource: AuthSource = AuthSource.SESSION_COOKIE,
    val authProvider: String = "LOCAL",
) {
    fun isAdmin(): Boolean = role == MiruRole.ADMIN

    fun scopedMediaUserIds(): Set<String>? = if (isAdmin()) null else linkedMediaUserIds

    fun canAccessMediaUser(mediaUserId: String): Boolean =
        isAdmin() || mediaUserId in linkedMediaUserIds

    fun requireAdmin(): Either<MiruError.Forbidden, Unit> =
        if (isAdmin()) {
            Either.Right(Unit)
        } else {
            Either.Left(MiruError.Forbidden("Administrator role is required"))
        }

    fun requireMediaUserAccess(mediaUserId: String): Either<MiruError.Forbidden, Unit> =
        if (canAccessMediaUser(mediaUserId)) {
            Either.Right(Unit)
        } else {
            Either.Left(MiruError.Forbidden("Access to media user '$mediaUserId' is forbidden"))
        }
}
