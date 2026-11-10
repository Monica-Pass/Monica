package takagi.ru.monica.security

import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Independent Python hashlib/argon2-cffi vectors, not expected values derived by production helpers. */
class LocalPasswordKdfTest {
    private val vectors = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream(
        "/local-password-kdf-vectors.json")).bufferedReader().use { it.readText() })
        .jsonObject.getValue("vectors").jsonArray.map { it.jsonObject }
    private val password = "  中文🔑e\u0301 空格\t "
    private val mdk = ByteArray(32) { (it + 0x60).toByte() }
    private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()


    @Test fun malformedUtf16KeepsHistoricalJceOutcomeForBothPbkdf2Costs() {
        val salt = ByteArray(16) { it.toByte() }
        val cases = listOf("\uD800", "\uDC00", "prefix\uD800x")
        for ((index, password) in cases.withIndex()) {
            for (profile in listOf(PasswordKdf.LEGACY, PasswordKdf.PBKDF2_600K)) {
                val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, profile.iterations, 256)
                val expected = try {
                    runCatching { javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).encoded }
                } finally { spec.clearPassword() }
                val actual = runCatching { PasswordKdf.derive(password, salt, profile) }
                val label = "UTF-16 case $index at ${profile.iterations} iterations"
                try {
                    assertEquals(label, expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
                    if (expected.isSuccess) assertArrayEquals(label, expected.getOrThrow(), actual.getOrThrow())
                } finally { expected.getOrNull()?.fill(0); actual.getOrNull()?.fill(0) }
            }
        }
    }

    @Test fun allProfilesMatchIndependentUnicodeWhitespaceAndBothSaltLengthVectors() {
        vectors.forEach { v ->
            fun value(key: String) = v.getValue(key).jsonPrimitive.content
            val profile = PasswordKdf.fromMetadata(v.getValue("metadata").jsonPrimitive.contentOrNull)
            val actual = PasswordKdf.derive(value("password"), value("saltHex").hex(), profile)
            try { assertArrayEquals(value("id"), value("expectedKeyHex").hex(), actual) }
            finally { actual.fill(0) }
        }
    }

    @Test fun exactParserRejectsUnknownTruncatedNonStringAndUnboundedProfiles() {
        assertEquals(PasswordKdf.LEGACY, PasswordKdf.fromValues(emptyMap<String, Any>()))
        assertEquals(PasswordKdf.PBKDF2_600K, PasswordKdf.forAlgorithm(LocalPasswordAlgorithm.PBKDF2_SHA256))
        assertEquals(PasswordKdf.ARGON2ID, PasswordKdf.forAlgorithm(LocalPasswordAlgorithm.ARGON2ID))
        val invalid = listOf("", "1", "1|pbkdf2-sha256", "1|pbkdf2-sha256|100000",
            "1|pbkdf2-sha256|0", "1|pbkdf2-sha256|2147483647", "1|pbkdf2-sha256|0600000",
            "1|pbkdf2-sha256|600000 ", "2|pbkdf2-sha256|600000", "1|argon2id|65536|3",
            "1|argon2id|2147483647|3|1", "1|argon2id|65536|2147483647|1",
            "1|argon2id|65536|3|2147483647", "1|argon2id|0|0|0", "1|argon2d|65536|3|1")
        invalid.forEach { metadata ->
            assertTrue(metadata, runCatching { PasswordKdf.fromMetadata(metadata) }
                .exceptionOrNull() is IllegalArgumentException)
        }
        for (value in listOf<Any?>(null, 600000, true, setOf("1|pbkdf2-sha256|600000"))) {
            assertTrue(runCatching { PasswordKdf.fromValues(mapOf(PasswordKdf.METADATA_KEY to value)) }
                .exceptionOrNull() is IllegalArgumentException)
        }
        val forged = PasswordKdf.PBKDF2_600K.copy(iterations = Int.MAX_VALUE)
        assertTrue(runCatching { PasswordKdf.derive(password, ByteArray(16), forged) }
            .exceptionOrNull() is IllegalArgumentException)
        for (length in listOf(0, 15, 33)) assertTrue(runCatching {
            PasswordKdf.derive(password, ByteArray(length), PasswordKdf.PBKDF2_600K)
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun legacy100kVerifierAndWrapperRemainReadableWithoutMetadata() {
        val legacy = legacyCredentials()
        val before = legacy.toMap()
        assertTrue(LocalPasswordCredentials.verify(password, legacy))
        assertFalse(LocalPasswordCredentials.verify(password.trim(), legacy))
        val opened = LocalPasswordCredentials.unwrap(password, legacy)
        try { assertArrayEquals(mdk, opened) } finally { opened.fill(0) }
        assertEquals(before, legacy)
        assertFalse(legacy.containsKey(PasswordKdf.METADATA_KEY))
    }

    @Test fun bothNewProfilesRewrapTheSameMdkAndKeepExistingRecordCiphertextUsable() {
        val legacy = legacyCredentials()
        val originalCredentials = legacy.toMap()
        val originalMdk = mdk.copyOf()
        val recordNonce = ByteArray(12) { (it + 0x30).toByte() }
        val recordPlaintext = "Synthetic existing secret\u0000内容".toByteArray()
        val recordCiphertext = aes(Cipher.ENCRYPT_MODE, mdk, recordNonce, recordPlaintext)
        val originalCiphertext = recordCiphertext.copyOf()
        for (profile in listOf(PasswordKdf.PBKDF2_600K, PasswordKdf.ARGON2ID)) {
            val replacement = LocalPasswordCredentials.create(password, mdk, profile)
            assertEquals(profile.encoded, replacement[PasswordKdf.METADATA_KEY])
            assertTrue(LocalPasswordCredentials.verify(password, replacement))
            val opened = LocalPasswordCredentials.unwrap(password, replacement)
            try {
                assertArrayEquals(originalMdk, opened)
                assertArrayEquals(recordPlaintext, aes(Cipher.DECRYPT_MODE, opened, recordNonce, recordCiphertext))
            } finally { opened.fill(0) }
            assertArrayEquals(originalMdk, mdk)
            assertArrayEquals(originalCiphertext, recordCiphertext)
            assertEquals(originalCredentials, legacy)
        }
    }

    @Test fun aadMismatchWrongPasswordAndChangedMetadataRejectTheWrapper() {
        val profile = PasswordKdf.PBKDF2_600K
        val salt = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (0xa0 + it).toByte() }
        val key = PasswordKdf.derive(password, salt, profile)
        try {
            // Correct password/key/profile, but wrong authenticated context: must still fail.
            val wrongContext = nonce + aes(Cipher.ENCRYPT_MODE, key, nonce, mdk, "different-context".toByteArray())
            val fields = mapOf(PasswordKdf.METADATA_KEY to checkNotNull(profile.encoded),
                LocalPasswordCredentials.BLOB_SALT to PasswordKdf.hex(salt),
                LocalPasswordCredentials.BLOB to Base64.getEncoder().encodeToString(wrongContext))
            assertTrue(runCatching { LocalPasswordCredentials.unwrap(password, fields) }
                .exceptionOrNull() is AEADBadTagException)
        } finally { key.fill(0) }
        val valid = LocalPasswordCredentials.create(password, mdk, profile)
        assertTrue(runCatching { LocalPasswordCredentials.unwrap("wrong", valid) }
            .exceptionOrNull() is AEADBadTagException)
        assertTrue(runCatching { LocalPasswordCredentials.unwrap(password, valid - PasswordKdf.METADATA_KEY) }
            .exceptionOrNull() is AEADBadTagException)
        assertTrue(runCatching { LocalPasswordCredentials.unwrap(password, valid + (PasswordKdf.METADATA_KEY to "future")) }
            .exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun hashSuccessDoesNotConcealBrokenWrapperAndWrapperSuccessDoesNotReplaceHashVerification() {
        val valid = LocalPasswordCredentials.create(password, mdk, PasswordKdf.PBKDF2_600K)
        val before = valid.toMap()
        val raw = Base64.getDecoder().decode(valid.getValue(LocalPasswordCredentials.BLOB) as String)
        raw[raw.lastIndex] = (raw.last().toInt() xor 1).toByte()
        val corruptWrapper = valid + (LocalPasswordCredentials.BLOB to Base64.getEncoder().encodeToString(raw))
        assertTrue(LocalPasswordCredentials.verify(password, corruptWrapper))
        assertTrue(runCatching { LocalPasswordCredentials.unwrap(password, corruptWrapper) }
            .exceptionOrNull() is AEADBadTagException)
        val corruptHash = valid + (LocalPasswordCredentials.HASH to "00".repeat(32))
        assertFalse(LocalPasswordCredentials.verify(password, corruptHash))
        val opened = LocalPasswordCredentials.unwrap(password, corruptHash)
        try { assertArrayEquals(mdk, opened) } finally { opened.fill(0) }
        for (blob in listOf("", (valid.getValue(LocalPasswordCredentials.BLOB) as String).dropLast(1), "!".repeat(80))) {
            assertTrue(runCatching { LocalPasswordCredentials.unwrap(password,
                valid + (LocalPasswordCredentials.BLOB to blob)) }.isFailure)
        }
        for (hash in listOf("0", "zz".repeat(32))) assertTrue(runCatching {
            LocalPasswordCredentials.verify(password, valid + (LocalPasswordCredentials.HASH to hash))
        }.isFailure)
        assertEquals(before, valid)
    }

    @Test fun credentialSnapshotsDetectAllCredentialChangesButIgnoreUnrelatedSettings() {
        val original = legacyCredentials()
        val snapshot = LocalPasswordCredentials.snapshot(original)
        assertTrue(LocalPasswordCredentials.matches(original, snapshot))
        assertTrue(LocalPasswordCredentials.matches(original + ("theme" to "dark"), snapshot))
        for (key in LocalPasswordCredentials.keys) {
            assertFalse(key, LocalPasswordCredentials.matches(original + (key to "changed"), snapshot))
        }
    }

    @Test fun invalidMdkAndLegacyCreationAreRejectedBeforeReplacement() {
        for (size in listOf(0, 31, 33)) assertTrue(runCatching {
            LocalPasswordCredentials.create(password, ByteArray(size), PasswordKdf.PBKDF2_600K)
        }.isFailure)
        assertTrue(runCatching { LocalPasswordCredentials.create(password, mdk, PasswordKdf.LEGACY) }.isFailure)
    }

    private fun legacyCredentials(): Map<String, Any> {
        fun vector(size: Int) = vectors.single { it.getValue("id").jsonPrimitive.content == "unicode-$size-legacy" }
        val hash = vector(16)
        val wrapper = vector(32)
        val key = wrapper.getValue("expectedKeyHex").jsonPrimitive.content.hex()
        val nonce = ByteArray(12) { (it + 0xb0).toByte() }
        val encrypted = try { aes(Cipher.ENCRYPT_MODE, key, nonce, mdk) } finally { key.fill(0) }
        return mapOf(LocalPasswordCredentials.HASH to hash.getValue("expectedKeyHex").jsonPrimitive.content,
            LocalPasswordCredentials.HASH_SALT to hash.getValue("saltHex").jsonPrimitive.content,
            LocalPasswordCredentials.BLOB_SALT to wrapper.getValue("saltHex").jsonPrimitive.content,
            LocalPasswordCredentials.BLOB to Base64.getEncoder().encodeToString(nonce + encrypted))
    }

    private fun aes(mode: Int, key: ByteArray, nonce: ByteArray, bytes: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(bytes)
    }
}
