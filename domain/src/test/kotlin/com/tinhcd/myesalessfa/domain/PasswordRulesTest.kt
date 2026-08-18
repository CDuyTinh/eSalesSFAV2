package com.tinhcd.myesalessfa.domain

import com.tinhcd.myesalessfa.domain.model.PasswordChange
import com.tinhcd.myesalessfa.domain.model.PasswordRule
import com.tinhcd.myesalessfa.domain.model.PasswordRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordRulesTest {

    @Test
    fun `a password meeting every rule has none unmet`() {
        assertTrue(PasswordRules.unmet("Matkhau1@").isEmpty())
        assertTrue(PasswordRules.isValid("Matkhau1@"))
    }

    @Test
    fun `each rule is reported on its own`() {
        // Not a single boolean: the screen ticks them off one by one, so a rep
        // never has to guess which of five they broke.
        assertEquals(setOf(PasswordRule.LENGTH), PasswordRules.unmet("Ab1@c"))
        assertEquals(setOf(PasswordRule.UPPERCASE), PasswordRules.unmet("matkhau1@"))
        assertEquals(setOf(PasswordRule.LOWERCASE), PasswordRules.unmet("MATKHAU1@"))
        assertEquals(setOf(PasswordRule.DIGIT), PasswordRules.unmet("Matkhauu@"))
        assertEquals(setOf(PasswordRule.SPECIAL), PasswordRules.unmet("Matkhau12"))
    }

    @Test
    fun `an empty password breaks all five`() {
        assertEquals(PasswordRule.entries.toSet(), PasswordRules.unmet(""))
    }

    @Test
    fun `a Vietnamese letter is a letter, not a special character`() {
        // \p{L} covers đ and the toned vowels. Counting them as punctuation would
        // pass a password with no special character at all.
        assertTrue(PasswordRule.SPECIAL in PasswordRules.unmet("Đường12a"))
        assertTrue(PasswordRules.isValid("Đường12a!"))
    }

    @Test
    fun `a space is not a special character`() {
        // Otherwise a trailing space typed by the keyboard would satisfy the rule
        // invisibly.
        assertTrue(PasswordRule.SPECIAL in PasswordRules.unmet("Matkhau1 "))
    }

    // -------------------------------------------------------------------------
    // The form as a whole
    // -------------------------------------------------------------------------

    private val good = PasswordChange(current = "Cukhau1@", new = "Moikhau1@", confirm = "Moikhau1@")

    @Test
    fun `a complete form can be submitted`() {
        assertTrue(good.canSubmit)
    }

    @Test
    fun `the current password is required`() {
        // The auth backend would not ask for it. This app does, because the
        // session alone is not proof the person holding the phone is the rep.
        assertFalse(good.copy(current = "").canSubmit)
    }

    @Test
    fun `the confirmation has to match`() {
        val mismatched = good.copy(confirm = "Moikhau1")

        assertTrue(mismatched.confirmMismatch)
        assertFalse(mismatched.canSubmit)
    }

    @Test
    fun `an untouched confirmation is not yet a mismatch`() {
        // Nothing should turn red before the rep has typed in the field.
        assertFalse(good.copy(confirm = "").confirmMismatch)
    }

    @Test
    fun `the new password cannot be the current one`() {
        val same = PasswordChange(current = "Matkhau1@", new = "Matkhau1@", confirm = "Matkhau1@")

        assertTrue(same.sameAsCurrent)
        assertFalse(same.canSubmit)
    }

    @Test
    fun `a weak new password blocks submission`() {
        assertFalse(good.copy(new = "matkhau", confirm = "matkhau").canSubmit)
    }
}
