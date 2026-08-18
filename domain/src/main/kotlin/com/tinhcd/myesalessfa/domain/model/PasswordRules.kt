package com.tinhcd.myesalessfa.domain.model

/**
 * One thing a password has to be, and how to say so.
 *
 * Modelled as a list rather than a boolean because the screen shows all of them
 * at once with ticks against the ones met. A rep told only "mật khẩu không hợp
 * lệ" has to guess which rule they broke, and on a phone keyboard that is several
 * attempts.
 */
enum class PasswordRule(val label: String) {
    LENGTH("Ít nhất 8 ký tự"),
    LOWERCASE("Có chữ thường"),
    UPPERCASE("Có chữ hoa"),
    DIGIT("Có chữ số"),
    SPECIAL("Có ký tự đặc biệt"),
}

/**
 * The rules the app this replaces applied, kept as they were.
 *
 * Deliberately not tightened. A rep whose password already satisfies the old app
 * would otherwise be told it is no longer good enough on the day they migrate,
 * for a reason nobody warned them about.
 */
object PasswordRules {

    const val MIN_LENGTH = 8

    /** Anything that is not a letter, a digit or whitespace. */
    private val SPECIAL = Regex("[^\\p{L}\\p{N}\\s]")

    fun unmet(password: String): Set<PasswordRule> = buildSet {
        if (password.length < MIN_LENGTH) add(PasswordRule.LENGTH)
        if (password.none { it.isLowerCase() }) add(PasswordRule.LOWERCASE)
        if (password.none { it.isUpperCase() }) add(PasswordRule.UPPERCASE)
        if (password.none { it.isDigit() }) add(PasswordRule.DIGIT)
        if (!SPECIAL.containsMatchIn(password)) add(PasswordRule.SPECIAL)
    }

    fun isValid(password: String): Boolean = unmet(password).isEmpty()
}

/**
 * A password change being filled in.
 *
 * The current password is required by this app even though the auth backend does
 * not ask for it. Without it, anyone holding an unlocked phone could lock the rep
 * out of their own account — the session is already there, and changing the
 * password is the one action that would make it permanent.
 */
data class PasswordChange(
    val current: String = "",
    val new: String = "",
    val confirm: String = "",
) {
    val unmet: Set<PasswordRule> get() = PasswordRules.unmet(new)

    val confirmMismatch: Boolean get() = confirm.isNotEmpty() && confirm != new

    /**
     * Refused here rather than left to the server, which would accept it: setting
     * a password to the one already in use is almost always a rep who thinks they
     * changed it and has not.
     */
    val sameAsCurrent: Boolean get() = new.isNotEmpty() && new == current

    val canSubmit: Boolean
        get() = current.isNotEmpty() &&
            unmet.isEmpty() &&
            confirm == new &&
            !sameAsCurrent
}
