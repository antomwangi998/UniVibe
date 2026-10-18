package com.univibe.app.auth

import com.univibe.app.domain.auth.AuthValidators
import com.univibe.app.domain.auth.PasswordStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AuthValidatorsTest {
    @Test fun emailAcceptsNormalAddresses() {
        assertNull(AuthValidators.email("aisha.njoki@students.lakeside.example"))
        assertNull(AuthValidators.email("  a+b@x.co  "))
    }

    @Test fun emailRejectsBadAddresses() {
        listOf("", "plain", "a@b", "a@@b.com", "a b@c.com", "@c.com").forEach {
            assertNotNull("should reject '$it'", AuthValidators.email(it))
        }
    }

    @Test fun nameRequired() {
        assertNotNull(AuthValidators.fullName("   "))
        assertNotNull(AuthValidators.fullName("A"))
        assertNull(AuthValidators.fullName("Aisha Njoki"))
    }

    @Test fun admissionNumberIsOptional() {
        assertNull(AuthValidators.admissionNumber(""))
        assertNull(AuthValidators.admissionNumber("SC211/0042/2023"))
        assertNotNull(AuthValidators.admissionNumber("!!"))
    }

    @Test fun passwordPolicy() {
        assertNotNull(AuthValidators.password(""))
        assertNotNull(AuthValidators.password("short1"))
        assertNotNull(AuthValidators.password("onlyletters"))
        assertNotNull(AuthValidators.password("12345678"))
        assertNotNull(AuthValidators.password("Password123"))
        assertNull(AuthValidators.password("Campus2026!"))
    }

    @Test fun confirmPasswordMustMatch() {
        assertNotNull(AuthValidators.confirmPassword("abc12345", ""))
        assertNotNull(AuthValidators.confirmPassword("abc12345", "abc12346"))
        assertNull(AuthValidators.confirmPassword("abc12345", "abc12345"))
    }

    @Test fun strengthMeterLevels() {
        assertEquals(PasswordStrength.Empty, AuthValidators.passwordStrength(""))
        assertEquals(PasswordStrength.Weak, AuthValidators.passwordStrength("abc"))
        assertEquals(PasswordStrength.Weak, AuthValidators.passwordStrength("password123"))
        assertEquals(PasswordStrength.Fair, AuthValidators.passwordStrength("Abcdef12"))
        assertEquals(PasswordStrength.Good, AuthValidators.passwordStrength("Abcdef12!"))
        assertEquals(PasswordStrength.Strong, AuthValidators.passwordStrength("Abcdef12!xyz"))
    }

    @Test fun codeMustBeSixDigits() {
        assertNull(AuthValidators.verificationCode("123456"))
        assertNotNull(AuthValidators.verificationCode("12345"))
        assertNotNull(AuthValidators.verificationCode("12a456"))
    }

    @Test fun maskEmailHidesLocalPart() {
        assertEquals("a***@x.com", AuthValidators.maskEmail("alice@x.com"))
    }
}
