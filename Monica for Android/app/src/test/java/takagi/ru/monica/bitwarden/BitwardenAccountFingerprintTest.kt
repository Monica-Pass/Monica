package takagi.ru.monica.bitwarden

import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.crypto.BitwardenAccountFingerprint

class BitwardenAccountFingerprintTest {
    private val wordFile = listOf(File("src/main/res/raw/bitwarden_fingerprint_words.txt"),
        File("app/src/main/res/raw/bitwarden_fingerprint_words.txt")).first { it.isFile }
    private val words = wordFile.readLines()
    private val publicKey = Base64.getDecoder().decode(PUBLIC_KEY)

    @Test fun matchesOfficialSdkAccountFingerprintVector() {
        assertEquals("turban-deftly-anime-chatroom-unselfish",
            BitwardenAccountFingerprint.phrase(USER_ID, publicKey, words))
    }

    @Test fun packagedWordListMatchesEffOrderIncludingHyphenatedWords() {
        assertEquals(7776, words.size)
        assertEquals(7776, words.toSet().size)
        assertTrue(words.containsAll(listOf("drop-down", "felt-tip", "t-shirt", "yo-yo")))
        val normalized = (words.joinToString("\n") + "\n").toByteArray()
        assertEquals("6d557f0693958fb5e650b68b5bee585eb82cf4da32965505c789e924743bc522",
            MessageDigest.getInstance("SHA-256").digest(normalized).joinToString("") { "%02x".format(it) })
    }

    @Test fun differentAccountAndDifferentPublicKeyHaveDifferentPhrases() {
        val first = BitwardenAccountFingerprint.phrase(USER_ID, publicKey, words)
        assertNotEquals(first, BitwardenAccountFingerprint.phrase("another-account", publicKey, words))
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded
        assertNotEquals(first, BitwardenAccountFingerprint.phrase(USER_ID, other, words))
    }

    @Test fun rejectsMalformedOrMissingIdentityInsteadOfGeneratingANewOne() {
        for (bytes in listOf(byteArrayOf(), ByteArray(294), publicKey.copyOf(40))) {
            assertTrue(runCatching { BitwardenAccountFingerprint.phrase(USER_ID, bytes, words) }.isFailure)
        }
        assertTrue(runCatching { BitwardenAccountFingerprint.phrase(" ", publicKey, words) }.isFailure)
    }

    @Test fun rejectsShortOrDuplicatedProtocolWordList() {
        assertTrue(runCatching { BitwardenAccountFingerprint.phrase(USER_ID, publicKey, words.take(100)) }.isFailure)
        assertTrue(runCatching { BitwardenAccountFingerprint.phrase(USER_ID, publicKey, List(7776) { "same" }) }.isFailure)
    }

    companion object {
        const val USER_ID = "a09726a0-9590-49d1-a5f5-afe300b6a515"
        // Public test identity from bitwarden-sdk 8aa7955, fingerprint.rs; no private key.
        const val PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuyYs8W7NWf0Zv35Ueco93730dtRKi4Jhc6Snar+86drE+ruSfaCWMcbgsAoAj2Pm6KAzaJrTIVCqBERQ23OncpzjfcGAeyf+v3w/gSw/EjihMJ4AG5ICY4hLFYcGdgwa+7is+TVO0i6PEWjKQa3l2+mQo2XY7pg2ngHDMssV4gxSqq+qoBX3+FBhewCYdOV+3cebwsAzz7HwoFTxKViwNW8crbHonhZPhZgfIAzEkzo5MvzQg5azhLKW6vuPfaOQFC5HqPykVnh8OPzO0uzUi3+97CguAu4N2CgwVYXltZuw2fGamdVw3kjbxQPbOE1tL0j7gyTwYKkfUl2m8gMh1QIDAQAB"
    }
}
