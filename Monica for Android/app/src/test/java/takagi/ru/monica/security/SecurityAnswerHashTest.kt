package takagi.ru.monica.security

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.*
import org.junit.Test

class SecurityAnswerHashTest {
    @Test fun saltedNewEnrollmentMatchesIndependentPbkdfVerifier() {
        val encoded = SecurityAnswerHash.create("  EXAMPLE 回答  ")
        assertTrue(SecurityAnswerHash.isValid(encoded))
        assertFalse(SecurityAnswerHash.isLegacy(encoded))
        val pieces = encoded.split('$')
        val spec = PBEKeySpec("monica-security-answer-v2:example 回答".toCharArray(),
            PasswordKdf.unhex(pieces[2], 16), 600_000, 256)
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        assertEquals(PasswordKdf.hex(expected), pieces[3])
        assertTrue(SecurityAnswerHash.verify("example 回答", encoded))
        assertFalse(SecurityAnswerHash.verify("other", encoded))
        assertNotEquals(encoded, SecurityAnswerHash.create("example 回答"))
        spec.clearPassword(); expected.fill(0)
    }
    @Test fun legacyBackupIsComparedToActualAnswerAndNotRehashedAsAnswer() {
        val legacy = PasswordKdf.hex(MessageDigest.getInstance("SHA-256").digest("legacy answer".toByteArray()))
        assertTrue(SecurityAnswerHash.verify("  Legacy Answer  ", legacy))
        assertFalse(SecurityAnswerHash.verify(legacy, legacy))
        assertEquals(legacy, SecurityAnswerHash.canonical(legacy.uppercase()))
    }
    @Test fun invalidVersionsAndUnboundedCostsFailClosed() {
        for (value in listOf("", "sq2$" + "1$" + "a".repeat(32) + "$" + "b".repeat(64),
            "sq3$" + "600000$" + "a".repeat(32) + "$" + "b".repeat(64),
            "sq2$" + "2147483647$" + "a".repeat(32) + "$" + "b".repeat(64))) {
            assertFalse(SecurityAnswerHash.isValid(value))
            assertFalse(SecurityAnswerHash.verify("answer", value))
        }
    }
}
