package takagi.ru.monica.utils

import javax.crypto.AEADBadTagException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.BackupEncryptionFormat
import takagi.ru.monica.data.BackupPreferences

class PortableBackupCryptoTest {
    private val vectors = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream(
        "/portable-backup-encryption-vectors.json")).bufferedReader().use { it.readText() })
        .jsonObject.getValue("vectors").jsonArray.map { it.jsonObject }
    private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val v2 get() = vectors.first { it.getValue("id").jsonPrimitive.content == "unicode-v2" }
    private val archive get() = v2.getValue("envelopeHex").jsonPrimitive.content.hex()
    private val password get() = v2.getValue("password").jsonPrimitive.content

    @Test fun pythonVectorsMatchBothFormatsIncludingUnicodeAndWhitespace() {
        vectors.forEach { v ->
            fun value(key: String) = v.getValue(key).jsonPrimitive.content
            val format = if (v.getValue("version").jsonPrimitive.int == 1)
                BackupEncryptionFormat.COMPATIBLE_V1 else BackupEncryptionFormat.ENHANCED_V2
            val expected = value("envelopeHex").hex()
            val plain = value("plaintextHex").hex()
            assertArrayEquals(value("id"), expected, PortableBackupCrypto.encryptWithParameters(
                plain, value("password"), format, value("saltHex").hex(), value("nonceHex").hex()))
            assertArrayEquals(value("id"), plain, PortableBackupCrypto.decrypt(expected, value("password")))
        }
    }

    @Test fun defaultWriterAndPreferencesStayCompatibleAndUseFreshRandomness() {
        assertEquals(BackupEncryptionFormat.COMPATIBLE_V1, BackupPreferences().encryptionFormat)
        val plain = "synthetic ZIP".toByteArray()
        val one = PortableBackupCrypto.encrypt(plain, password)
        val two = PortableBackupCrypto.encrypt(plain, password)
        assertEquals("MONICA_ENC_V1", one.copyOfRange(0, 13).toString(Charsets.US_ASCII))
        assertFalse(one.contentEquals(two))
        assertArrayEquals(plain, PortableBackupCrypto.decrypt(one, password))
    }

    @Test fun unsupportedHeaderParametersAreRejectedBeforeAnyDecryption() {
        // Includes KDF, cipher, flags, every byte of cost and both declared lengths.
        for (index in 13..23) {
            val corrupt = archive.also { it[index] = (it[index].toInt() xor 0x40).toByte() }
            assertTrue("header byte $index", runCatching {
                PortableBackupCrypto.decrypt(corrupt, password)
            }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun truncatedHeadersAndTagsAreRejected() {
        for (length in 0 until PortableBackupCrypto.V2_HEADER_SIZE + 16) {
            assertTrue("truncated length $length", runCatching {
                PortableBackupCrypto.decrypt(archive.copyOf(length), password)
            }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching {
            PortableBackupCrypto.decrypt(archive.copyOf(archive.size - 1), password)
        }.isFailure)
    }

    @Test fun wrongPasswordSaltNonceCiphertextAndTagFailAuthentication() {
        assertTrue(runCatching { PortableBackupCrypto.decrypt(archive, password.trim()) }
            .exceptionOrNull() is AEADBadTagException)
        for (index in listOf(24, 56, 68, archive.lastIndex)) {
            val corrupt = archive.also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertTrue("tampered byte $index", runCatching {
                PortableBackupCrypto.decrypt(corrupt, password)
            }.exceptionOrNull() is AEADBadTagException)
        }
    }

    @Test fun unknownVersionAndDowngradeNeverFallBackToAnotherFormat() {
        val unknown = archive.also { it[12] = '9'.code.toByte() }
        assertTrue(runCatching { PortableBackupCrypto.decrypt(unknown, password) }
            .exceptionOrNull() is IllegalArgumentException)
        val downgrade = archive.also { it[12] = '1'.code.toByte() }
        assertTrue(runCatching { PortableBackupCrypto.decrypt(downgrade, password) }
            .exceptionOrNull() is AEADBadTagException)
    }

    @Test fun enhancedPasswordsAreNotNormalizedAndRejectMalformedUtf16() {
        val composed = vectors.first { it.getValue("id").jsonPrimitive.content == "composed-v2" }
            .getValue("envelopeHex").jsonPrimitive.content.hex()
        assertTrue(runCatching { PortableBackupCrypto.decrypt(composed, "e\u0301") }
            .exceptionOrNull() is AEADBadTagException)
        for (invalid in listOf("\uD800", "\uDC00", "\uD800A")) {
            assertTrue(runCatching { PortableBackupCrypto.encrypt(byteArrayOf(), invalid,
                BackupEncryptionFormat.ENHANCED_V2) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
