package takagi.ru.monica.credentialexchange

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.BackupEncryptionFormat
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.EncryptionHelper
import takagi.ru.monica.utils.PortableBackupCrypto
import takagi.ru.monica.utils.WebDavHelper

class PortableBackupFormatInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val strings = AppLocaleStringResolver(context)
    private val password = "  中文🔑e\u0301 空格  "
    private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun vectors() = JSONObject(instrumentation.context.assets
        .open("portable-backup-encryption-vectors.json").bufferedReader().use { it.readText() }).getJSONArray("vectors")

    @Test fun androidProviderMatchesAllPythonVectorsAndReadsBothFormats() {
        val vectors = vectors()
        for (index in 0 until vectors.length()) {
            val v = vectors.getJSONObject(index)
            val format = if (v.getInt("version") == 1) BackupEncryptionFormat.COMPATIBLE_V1 else BackupEncryptionFormat.ENHANCED_V2
            val plain = v.getString("plaintextHex").hex()
            val encrypted = v.getString("envelopeHex").hex()
            assertArrayEquals(v.getString("id"), encrypted, PortableBackupCrypto.encryptWithParameters(
                plain, v.getString("password"), format, v.getString("saltHex").hex(), v.getString("nonceHex").hex()))
            assertArrayEquals(plain, PortableBackupCrypto.decrypt(encrypted, v.getString("password")))
        }
    }

    @Test fun fileApiPreservesDestinationOnWrongPasswordCorruptionAndUnknownVersion() {
        val root = File(context.cacheDir, "backup-format-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "source.zip").apply { writeBytes("PK synthetic ZIP".toByteArray()) }
            val encrypted = File(root, "archive.bin")
            val decoded = File(root, "decoded.zip")
            for (format in BackupEncryptionFormat.entries) {
                EncryptionHelper.encryptFile(source, encrypted, password, strings, format).getOrThrow()
                assertTrue(EncryptionHelper.hasEncryptedFileHeader(encrypted))
                assertTrue(EncryptionHelper.isEncryptedFile(encrypted))
                EncryptionHelper.decryptFile(encrypted, decoded, password, strings).getOrThrow()
                assertArrayEquals(source.readBytes(), decoded.readBytes())
            }
            val original = encrypted.readBytes()
            val sentinel = "preserve existing destination".toByteArray()
            decoded.writeBytes(sentinel)
            assertTrue(EncryptionHelper.decryptFile(encrypted, decoded, "wrong", strings).isFailure)
            assertArrayEquals(sentinel, decoded.readBytes())
            for (bytes in listOf(original.copyOf(67), original.copyOf(original.size - 1),
                original.copyOf().also { it[12] = '9'.code.toByte() },
                original.copyOf().also { it[16] = 0x7f })) {
                encrypted.writeBytes(bytes)
                assertTrue(EncryptionHelper.hasEncryptedFileHeader(ByteArrayInputStream(bytes)))
                assertTrue(EncryptionHelper.decryptFile(encrypted, decoded, password, strings).isFailure)
                assertArrayEquals(sentinel, decoded.readBytes())
            }
            assertArrayEquals("PK synthetic ZIP".toByteArray(), source.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun unversionedConfigurationStringsRemainCompatibleWith100000Iterations() {
        val vector = vectors().let { values -> (0 until values.length()).map(values::getJSONObject)
            .single { it.getString("id") == "unicode-v1" } }
        val envelope = vector.getString("envelopeHex").hex()
        fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val old = listOf(envelope.copyOfRange(13, 45), envelope.copyOfRange(45, 57), envelope.copyOfRange(57, envelope.size))
            .joinToString(":", transform = ::encode)
        val plain = vector.getString("plaintextHex").hex().toString(Charsets.UTF_8)
        assertEquals(plain, EncryptionHelper.decryptString(old, password, strings))
        val current = EncryptionHelper.encryptString(plain, password, strings).split(':')
        assertEquals(3, current.size)
        val currentEnvelope = "MONICA_ENC_V1".toByteArray() + current.flatMap {
            Base64.decode(it, Base64.NO_WRAP).toList()
        }.toByteArray()
        assertArrayEquals(plain.toByteArray(), PortableBackupCrypto.decrypt(currentEnvelope, password))
    }

    @Test fun manualZipAndSharedCloudArchiveHonorExplicitFormatAndPersistCloudChoice() = runBlocking {
        val f = TransferFixture()
        val helper = WebDavHelper(f.context)
        val originalPreferences = helper.getBackupPreferences()
        val originalEncryption = helper.getEncryptionConfig()
        val preferences = BackupPreferences(includePasswords = true, includeAuthenticators = false,
            includeDocuments = false, includeBankCards = false, includePasskeys = false,
            includeGeneratorHistory = false, includeImages = false, includeNotes = false,
            includeTimeline = false, includeTrash = false, includeTrashAndHistory = false)
        try {
            assertEquals(BackupEncryptionFormat.COMPATIBLE_V1, preferences.encryptionFormat)
            helper.setEncryptionConfig(true, password)
            for (format in BackupEncryptionFormat.entries) {
                val selected = preferences.copy(encryptionFormat = format)
                helper.saveBackupPreferences(selected)
                val reopened = WebDavHelper(f.context)
                assertEquals(format, reopened.getBackupPreferences().encryptionFormat)
                // This is the common archive path used by WebDAV and ordinary-edition OneDrive.
                val cloud = reopened.createBackupZip(emptyList(), emptyList()).getOrThrow().first
                val manual = f.model.prepareZipBackup(selected, password).getOrThrow().first
                try {
                    for ((index, archive) in listOf(cloud, manual).withIndex()) {
                        val expected = if (format == BackupEncryptionFormat.COMPATIBLE_V1) "MONICA_ENC_V1" else "MONICA_ENC_V2"
                        assertEquals(expected, archive.readBytes().copyOfRange(0, 13).toString(Charsets.US_ASCII))
                        val decoded = File(f.root, "decoded-$index.zip")
                        EncryptionHelper.decryptFile(archive, decoded, password, strings).getOrThrow()
                        ZipFile(decoded).use { assertTrue(it.entries().hasMoreElements()) }
                    }
                } finally { cloud.delete(); manual.delete() }
            }
        } finally {
            try {
                helper.saveBackupPreferences(originalPreferences)
                helper.setEncryptionConfig(originalEncryption.enabled, originalEncryption.password)
            } finally { f.close() }
        }
    }
}
