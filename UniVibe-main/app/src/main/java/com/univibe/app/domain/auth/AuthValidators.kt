package com.univibe.app.domain.auth

enum class PasswordStrength(val label: String, val level: Int) {
    Empty("", 0), Weak("Weak", 1), Fair("Fair", 2), Good("Good", 3), Strong("Strong", 4)
}

/**
 * Client-side convenience checks for fast feedback. They are NOT security: the backend
 * must repeat every rule. Each function returns an error message or null when valid.
 */
object AuthValidators {
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")
    private val ADMISSION = Regex("^[A-Za-z0-9/_-]{3,24}$")
    private val COMMON = setOf("password", "password1", "password123", "12345678", "123456789", "qwerty123", "iloveyou", "welcome1", "letmein123")
    const val MIN_PASSWORD = 8
    const val CODE_LENGTH = 6

    fun fullName(value: String): String? {
        val v = value.trim()
        return when {
            v.isEmpty() -> "Enter your full name."
            v.length < 2 -> "Your name looks too short."
            v.length > 80 -> "Your name is too long."
            else -> null
        }
    }

    fun email(value: String): String? {
        val v = value.trim()
        return when {
            v.isEmpty() -> "Enter your email address."
            v.length > 254 || !EMAIL.matches(v) -> "Enter a valid email address."
            else -> null
        }
    }

    /** Optional: blank is fine. */
    fun admissionNumber(value: String): String? {
        val v = value.trim()
        return if (v.isEmpty() || ADMISSION.matches(v)) null else "Use 3–24 letters, numbers, / or -."
    }

    fun password(value: String): String? = when {
        value.isEmpty() -> "Create a password."
        value.length < MIN_PASSWORD -> "Use at least $MIN_PASSWORD characters."
        value.none { it.isLetter() } || value.none { it.isDigit() } -> "Include at least one letter and one number."
        value.lowercase() in COMMON -> "That password is too common."
        else -> null
    }

    fun confirmPassword(password: String, confirm: String): String? = when {
        confirm.isEmpty() -> "Confirm your password."
        password != confirm -> "Passwords don't match."
        else -> null
    }

    fun verificationCode(value: String): String? =
        if (value.length == CODE_LENGTH && value.all { it.isDigit() }) null else "Enter the $CODE_LENGTH-digit code."

    fun passwordStrength(value: String): PasswordStrength {
        if (value.isEmpty()) return PasswordStrength.Empty
        if (value.length < MIN_PASSWORD || value.lowercase() in COMMON) return PasswordStrength.Weak
        var points = 1
        if (value.length >= 12) points++
        if (value.any { it.isUpperCase() } && value.any { it.isLowerCase() }) points++
        if (value.any { it.isDigit() }) points++
        if (value.any { !it.isLetterOrDigit() }) points++
        return when {
            points >= 5 -> PasswordStrength.Strong
            points == 4 -> PasswordStrength.Good
            points == 3 -> PasswordStrength.Fair
            else -> PasswordStrength.Weak
        }
    }

    fun maskEmail(email: String): String {
        val local = email.substringBefore('@')
        val domain = email.substringAfter('@', "")
        return if (domain.isEmpty()) email else "${local.take(1)}***@$domain"
    }
}
