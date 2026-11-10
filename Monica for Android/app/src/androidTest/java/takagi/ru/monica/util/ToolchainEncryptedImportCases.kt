package takagi.ru.monica.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Real production file importer/decryptor calls. Returns parsed entries; does not persist a vault. */
internal class ToolchainEncryptedImportCases(private val context: Context) {
    private val manager = DataExportImportManager(context)
    private val fixture = ToolchainEncryptedImportFixtures

    fun aegisIndependentEncryptedBackupImportsAllFields() = runBlocking {
        withFixture(fixture.AEGIS) { uri ->
            val entries = manager.importEncryptedAegisJson(uri, fixture.PASSWORD).getOrThrow()
            assertEntry(entries, note = "Synthetic note")
            assertEquals("61eb9769-e610-4921-9a67-95d74c132601", entries.single().uuid)
        }
    }

    fun aegisWrongPasswordAndAuthenticatedCorruptionReturnNoEntries() = runBlocking {
        val inputs = listOf(fixture.AEGIS to fixture.WRONG_PASSWORD,
            fixture.AEGIS_BAD_SLOT_TAG to fixture.PASSWORD,
            fixture.AEGIS_BAD_DB_TAG to fixture.PASSWORD,
            fixture.AEGIS_TRUNCATED_DB to fixture.PASSWORD)
        for ((encoded, password) in inputs) withFixture(encoded) { uri ->
            val result = manager.importEncryptedAegisJson(uri, password)
            assertTrue("Aegis must fail before exposing imported entries", result.isFailure)
            assertEquals(null, result.getOrNull())
        }
    }

    fun stratumModernIndependentArgon2BackupImportsAllFields() = runBlocking {
        withFixture(fixture.STRATUM_MODERN) { uri ->
            assertEquals(StratumDecryptor.StratumFileType.MODERN_ENCRYPTED,
                manager.detectStratumFileType(uri).getOrThrow())
            assertTrue(manager.isStratumFileEncrypted(uri).getOrThrow())
            assertEntry(manager.importEncryptedStratum(uri, fixture.PASSWORD).getOrThrow())
        }
    }

    fun stratumModernWrongPasswordTagAndTruncationReturnNoEntries() = runBlocking {
        val truncated = Base64.getDecoder().decode(fixture.STRATUM_MODERN).copyOf(43)
        val inputs = listOf(fixture.STRATUM_MODERN to fixture.WRONG_PASSWORD,
            fixture.STRATUM_MODERN_BAD_TAG to fixture.PASSWORD,
            Base64.getEncoder().encodeToString(truncated) to fixture.PASSWORD)
        for ((encoded, password) in inputs) withFixture(encoded) { uri ->
            val result = manager.importEncryptedStratum(uri, password)
            assertTrue("Modern Stratum must fail before exposing imported entries", result.isFailure)
            assertEquals(null, result.getOrNull())
        }
    }

    fun stratumLegacyIndependentPbkdf2BackupAndWrongPassword() = runBlocking {
        withFixture(fixture.STRATUM_LEGACY) { uri ->
            assertEquals(StratumDecryptor.StratumFileType.LEGACY_ENCRYPTED,
                manager.detectStratumFileType(uri).getOrThrow())
            assertEntry(manager.importEncryptedStratum(uri, fixture.PASSWORD).getOrThrow())
            val wrong = manager.importEncryptedStratum(uri, fixture.WRONG_PASSWORD)
            assertTrue("This independently chosen wrong-password fixture must fail", wrong.isFailure)
            assertEquals(null, wrong.getOrNull())
        }
    }

    /** Documents the existing unauthenticated legacy format; it is not a corruption-rejection claim. */
    fun stratumLegacyCbcCannotAuthenticateAnIvWhitespaceChange() = runBlocking {
        val original = Base64.getDecoder().decode(fixture.STRATUM_LEGACY)
        val changed = Base64.getDecoder().decode(fixture.STRATUM_LEGACY_CHANGED_IV)
        val decryptor = StratumDecryptor()
        val a = decryptor.decrypt(original, fixture.PASSWORD)
        val b = decryptor.decrypt(changed, fixture.PASSWORD)
        assertFalse("CBC legacy has no MAC: altered IV changes plaintext", a == b)
        assertEquals('\t', b.first())
        withFixture(fixture.STRATUM_LEGACY_CHANGED_IV) { uri ->
            assertEntry(manager.importEncryptedStratum(uri, fixture.PASSWORD).getOrThrow())
        }
    }

    private fun assertEntry(entries: List<DataExportImportManager.AegisEntry>, note: String = "") {
        assertEquals(1, entries.size)
        val item = entries.single()
        assertEquals("Synthetic account", item.name)
        assertEquals("Monica test only", item.issuer)
        assertEquals(note, item.note)
        assertTrue("Synthetic OTP secret preserved (value suppressed)", item.secret == "JBSWY3DPEHPK3PXP")
        assertEquals("SHA256", item.algorithm)
        assertEquals(8, item.digits)
        assertEquals(45, item.period)
    }

    private suspend fun <T> withFixture(encoded: String, action: suspend (Uri) -> T): T {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "toolchain-crypto-").toFile()
        val file = File(directory, "synthetic-backup.bin")
        return try {
            file.writeBytes(Base64.getDecoder().decode(encoded))
            action(Uri.fromFile(file))
        } finally {
            // Only this test's exact files, never clear app data or remove a directory recursively.
            check(!file.exists() || file.delete())
            check(directory.delete())
        }
    }
}
