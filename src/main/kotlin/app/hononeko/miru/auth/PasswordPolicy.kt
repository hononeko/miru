package app.hononeko.miru.auth

/**
 * Shared password strength policy applied to local account creation, initial setup, and bootstrap env configuration.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 12

    fun validationError(password: String): String? {
        val missing = mutableListOf<String>()
        if (password.length < MIN_LENGTH) {
            missing += "at least $MIN_LENGTH characters"
        }
        if (password.none { it.isLowerCase() }) {
            missing += "at least 1 lowercase letter"
        }
        if (password.none { it.isUpperCase() }) {
            missing += "at least 1 uppercase letter"
        }
        if (password.none { it.isDigit() }) {
            missing += "at least 1 digit"
        }
        return if (missing.isEmpty()) null else "must contain ${missing.joinToString(", ")}"
    }
}
