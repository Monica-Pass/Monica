package takagi.ru.monica.bitwarden

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.api.BitwardenEnvironment
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentDraft
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentPreset
import takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver
import takagi.ru.monica.bitwarden.api.BitwardenHeaderDraft
import takagi.ru.monica.bitwarden.api.BitwardenTlsConfig
import takagi.ru.monica.bitwarden.api.BitwardenTransportSecretStore
import takagi.ru.monica.bitwarden.api.BitwardenVaultEnvironmentStore
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenPendingOperation
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.RecoverableBitwardenSettings
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.EncryptionHelper
import takagi.ru.monica.utils.WebDavHelper

/** Actual configuration writer/parser and repository publication; no remote account is contacted. */
@RunWith(AndroidJUnit4::class)
class BitwardenTransportBackupInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun encryptedZipRoundTripRestoresLockedAccountWithFreshLocalBinding(): Unit = runBlocking {
        withFixture { f ->
            val source = f.createSource()
            assertTrue(source.isLocked)
            val serialized = f.exportEntry()
            val text = serialized.toString()
            listOf(HEADER_SECRET, TLS_SECRET, source.transportSecretRef!!, source.transportRevision,
                source.encryptedAccessToken!!, source.encryptedEncKey!!).forEach {
                assertFalse("ZIP entry must not contain transport secrets, raw credentials or device bindings", text.contains(it))
            }
            assertEquals(0L, serialized.optLong("id", 0L))
            assertTrue(serialized.has("transport"))
            val originalEnvironment = f.resolve(source)
            f.remove(source)
            f.restore(serialized)
            val restored = f.row()
            assertNotEquals(source.id, restored.id)
            assertNotEquals(source.transportSecretRef, restored.transportSecretRef)
            assertNotEquals(source.transportRevision, restored.transportRevision)
            assertEquals(source.encryptedAccessToken, restored.encryptedAccessToken)
            assertEquals(source.encryptedEncKey, restored.encryptedEncKey)
            assertEquals(source.eventsUrl, restored.eventsUrl)
            assertTrue(restored.isLocked)
            assertFalse(f.repository.isVaultUnlocked(restored.id))
            assertEquals(originalEnvironment.fingerprint, f.resolve(restored).fingerprint)
            assertEquals(HEADER_SECRET, f.resolve(restored).headers.single().value)
            assertEquals(TLS_SECRET, f.resolve(restored).tls.clientCertPassword)
        }
    }

    @Test fun restoringExistingAccountPreservesNewerConfigurationRecordsAndQueueButLocksRuntime(): Unit = runBlocking {
        withFixture { f ->
            val source = f.createSource()
            val oldBackup = f.exportEntry()
            val newer = f.environment("$BASE/new-api/")
            val binding = f.secrets.stage(source.id, newer).also { f.references += it.reference }
            f.dao.update(source.copy(apiUrl = newer.api.toString(), transportSecretRef = binding.reference,
                transportRevision = binding.revision, displayName = "Newer synthetic local configuration"))
            assertTrue(f.repository.unlock(source.id, MASTER_PASSWORD) is BitwardenRepository.UnlockResult.Success)
            val before = f.row()
            val entryId = f.database.passwordEntryDao().insert(PasswordEntry(title = "Synthetic pending backup record",
                website = "https://entry.example.invalid", username = "synthetic-user", password = "synthetic-stored-ciphertext",
                bitwardenVaultId = source.id, bitwardenCipherId = UUID.randomUUID().toString(), bitwardenLocalModified = true))
            val record = f.database.passwordEntryDao().getPasswordEntryById(entryId)
            f.database.bitwardenPendingOperationDao().insert(BitwardenPendingOperation(vaultId = source.id, entryId = entryId,
                operationType = "UPDATE", targetType = "CIPHER", payloadJson = "{\"syntheticSealedPayload\":\"unchanged\"}"))
            val queue = f.database.bitwardenPendingOperationDao().getPendingOperationsByVault(source.id)
            val selected = f.preferences.getLong("active_vault_id", -1L)
            f.restore(oldBackup)
            val after = f.row()
            assertEquals(before.copy(isLocked = after.isLocked, updatedAt = after.updatedAt), after)
            assertTrue(after.isLocked)
            assertFalse(f.repository.isVaultUnlocked(source.id))
            assertEquals(newer.fingerprint, f.resolve(after).fingerprint)
            assertEquals(record, f.database.passwordEntryDao().getPasswordEntryById(entryId))
            assertEquals(queue, f.database.bitwardenPendingOperationDao().getPendingOperationsByVault(source.id))
            assertEquals(selected, f.preferences.getLong("active_vault_id", -1L))
            assertEquals(1, f.rows().size)
        }
    }

    @Test fun legacyZipUsesItsRealCustomBaseAndNeverAllocatesTheSourceDeviceId(): Unit = runBlocking {
        withFixture { f ->
            val legacy = JSONObject().put("id", 987654321L).put("email", f.email).put("serverUrl", BASE)
                .put("identityUrl", "").put("apiUrl", "").put("isConnected", false)
            f.restore(legacy)
            val inherited = f.row()
            assertNotEquals(987654321L, inherited.id)
            assertEquals("$BASE/api/", inherited.apiUrl)
            assertEquals("$BASE/identity/", inherited.identityUrl)
            assertNull(inherited.transportSecretRef)
            assertNull(inherited.environmentPreset)
            assertEquals("", inherited.transportRevision)
            assertTrue(inherited.isLocked)
            f.remove(inherited)
            legacy.put("apiUrl", "https://api.example.invalid/Case/").put("identityUrl", "https://identity.example.invalid/Auth/")
            f.restore(legacy)
            val explicit = f.row()
            assertEquals("https://api.example.invalid/Case/", explicit.apiUrl)
            assertEquals("https://identity.example.invalid/Auth/", explicit.identityUrl)
            f.remove(explicit)
            legacy.put("serverUrl", "")
            val warnings = f.restore(legacy)
            assertTrue(f.rows().isEmpty())
            assertTrue("A rejected missing Base must be visible in the restore report", warnings.isNotEmpty())
        }
    }

    @Test fun tamperedModernZipCannotPublishOrFallBackToLegacyTransport(): Unit = runBlocking {
        withFixture { f ->
            val source = f.createSource()
            val backup = f.exportEntry()
            f.remove(source)
            backup.put("identityUrl", "https://unapproved.example.invalid/identity/")
            val warnings = f.restore(backup)
            assertTrue(f.rows().isEmpty())
            assertTrue("Fingerprint rejection must be visible in the restore report", warnings.isNotEmpty())
            assertFalse(f.repository.isVaultUnlocked(source.id))
        }
    }

    @Test fun secondRestoredAccountMarkedDefaultWinsTheBatchPreference(): Unit = runBlocking {
        withFixture { f ->
            val secondEmail = f.ownEmail("preferred")
            f.restore(listOf(f.legacyEntry(f.email), f.legacyEntry(secondEmail, preferred = true)))
            val restored = f.ownedRows()
            val preferred = restored.single { it.email == secondEmail }
            assertEquals(2, restored.size)
            assertEquals(preferred.id, f.dao.getDefaultVault()?.id)
            assertEquals(preferred.id, f.preferences.getLong("active_vault_id", -1L))
            assertTrue(restored.all { it.isLocked && !f.repository.isVaultUnlocked(it.id) })
        }
    }

    @Test fun batchWithoutPreferredAccountChoosesItsFirstSuccessfulNewAccount(): Unit = runBlocking {
        withFixture { f ->
            val invalid = f.legacyEntry(f.ownEmail("invalid")).put("serverUrl", "")
            f.restore(listOf(invalid, f.legacyEntry(f.email), f.legacyEntry(f.ownEmail("later"))))
            val firstSuccessful = f.row()
            assertEquals(2, f.ownedRows().size)
            assertEquals(firstSuccessful.id, f.dao.getDefaultVault()?.id)
            assertEquals(firstSuccessful.id, f.preferences.getLong("active_vault_id", -1L))
        }
    }

    @Test fun batchPreservesDifferentPreexistingDefaultAndActiveSelections(): Unit = runBlocking {
        withFixture { f ->
            val originalDefault = f.createSource()
            val otherEmail = f.ownEmail("already-active")
            f.restore(f.legacyEntry(otherEmail))
            val originalActive = f.ownedRows().single { it.email == otherEmail }
            f.repository.setActiveVault(originalActive.id)
            f.restore(listOf(f.legacyEntry(f.ownEmail("new-first")),
                f.legacyEntry(f.ownEmail("new-preferred"), preferred = true)))
            assertEquals(4, f.ownedRows().size)
            assertEquals(originalDefault.id, f.dao.getDefaultVault()?.id)
            assertEquals(originalActive.id, f.preferences.getLong("active_vault_id", -1L))
            assertEquals(originalDefault, f.row())
        }
    }

    @Test fun manualSelectionBetweenRestoreAndFinishPreventsLateAutomaticSelection(): Unit = runBlocking {
        withFixture { f ->
            val generation = SessionManager.lockGeneration.value
            val selection = f.repository.beginVaultConfigurationRestoreSelection(generation)
            val environment = f.environment()
            fun candidate(email: String) = BitwardenVault(email = email, canonicalEmail = email,
                serverUrl = environment.base.toString(), apiUrl = environment.api.toString(),
                identityUrl = environment.identity.toString(), isLocked = true, isConnected = false)
            val first = requireNotNull(f.repository.restoreVaultConfigurationFromBackup(
                candidate(f.email), environment, generation, selection))
            val second = requireNotNull(f.repository.restoreVaultConfigurationFromBackup(
                candidate(f.ownEmail("late-preferred")), environment, generation, selection))
            assertNull("Individual batch publications must defer default selection", f.dao.getDefaultVault())
            assertTrue(f.preferences.getLong("active_vault_id", -1L) <= 0)
            f.repository.setActiveVault(first)
            f.repository.finishVaultConfigurationRestoreSelection(selection, second)
            assertEquals(first, f.preferences.getLong("active_vault_id", -1L))
            assertNull("A manual selection must revoke automatic default publication as well", f.dao.getDefaultVault())
            assertTrue(f.ownedRows().all { it.isLocked })
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) = withContext(Dispatchers.IO) {
        val f = Fixture()
        var failure: Throwable? = null
        try { f.initialize(); block(f) }
        catch (error: Throwable) { failure = error; throw error }
        finally { try { f.close() } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup } }
    }

    private inner class Fixture {
        val repository = BitwardenRepository.getInstance(context)
        val database = PasswordDatabase.getDatabase(context)
        val dao = database.bitwardenVaultDao()
        val email = "transport-backup-${UUID.randomUUID()}@example.invalid"
        private val ownedEmails = mutableSetOf(email)
        val references = mutableSetOf<String>()
        val preferences = RecoverableBitwardenSettings.open(context)
        private val security = SecurityManager(context)
        private val helper = WebDavHelper(context)
        private val strings = AppLocaleStringResolver(context)
        private val originalUnlocked = SessionManager.isUnlocked.value
        private val previousPrefs = listOf("active_vault_id", "last_sync_time").associateWith {
            preferences.contains(it) to preferences.getLong(it, 0L)
        }
        private val directory = File(context.cacheDir, "transport-backup-${UUID.randomUUID()}").apply { mkdirs() }
        private var initialized = false
        val secrets = BitwardenTransportSecretStore(security::getProtectedString, security::putProtectedString, security::removeProtectedString)

        suspend fun initialize() {
            check(dao.getAllVaults().isEmpty()) { "Run ZIP configuration tests only in an empty dedicated synthetic Android user" }
            // Isolate the empty-fixture selection baseline; restore the exact previous preference in close().
            check(preferences.edit().remove("active_vault_id").commit())
            instrumentation.runOnMainSync { SessionManager.markUnlocked() }
            initialized = true
        }

        fun environment(api: String = "$BASE/api/"): BitwardenEnvironment = requireNotNull(BitwardenEnvironmentResolver.resolve(
            BitwardenEnvironmentDraft(BitwardenEnvironmentPreset.CUSTOM, BASE, "$BASE/web/", api, "$BASE/identity/",
                "$BASE/notifications/", listOf(BitwardenHeaderDraft("X-Synthetic-Proxy", HEADER_SECRET)),
                BitwardenTlsConfig(certificateAlias = "display-metadata-only", clientCertPassword = TLS_SECRET)),
            UUID.randomUUID().toString()).value)

        suspend fun createSource(): BitwardenVault {
            val env = environment()
            val master = BitwardenCrypto.deriveMasterKeyPbkdf2(MASTER_PASSWORD, email, 100)
            fun stored(value: String) = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
            fun storedBytes(bytes: ByteArray) = stored(Base64.encodeToString(bytes, Base64.NO_WRAP))
            try {
                val candidate = BitwardenVault(email = email, canonicalEmail = email, userId = UUID.randomUUID().toString(),
                    serverUrl = env.base.toString(), apiUrl = env.api.toString(), identityUrl = env.identity.toString(),
                    eventsUrl = "$BASE/existing-events/", encryptedMasterKey = storedBytes(master),
                    encryptedEncKey = storedBytes(ByteArray(32) { 23 }), encryptedMacKey = storedBytes(ByteArray(32) { 47 }),
                    encryptedAccessToken = stored("synthetic-portable-access-token"), encryptedRefreshToken = stored("synthetic-portable-refresh-token"),
                    accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000, kdfIterations = 100,
                    isConnected = true, isLocked = true, syncEnabled = false)
                assertNotNull(repository.restoreVaultConfigurationFromBackup(candidate, env, SessionManager.lockGeneration.value))
                return row().also { references += requireNotNull(it.transportSecretRef) }
            } finally { master.fill(0) }
        }

        suspend fun exportEntry(): JSONObject {
            val bytes = ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { helper.writeFileExportConfiguration(it, BACKUP_PASSWORD) }
                output.toByteArray()
            }
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == ENTRY_NAME) {
                        val all = JSONObject(zip.readBytes().toString(Charsets.UTF_8)).getJSONArray("vaults")
                        return (0 until all.length()).map { all.getJSONObject(it) }.single { it.getString("email") == email }
                    }
                    entry = zip.nextEntry
                }
            }
            error("Configuration writer omitted the selected synthetic Bitwarden account")
        }

        suspend fun restore(entry: JSONObject): List<String> = restore(listOf(entry))

        suspend fun restore(entries: List<JSONObject>): List<String> {
            val plain = File(directory, "${UUID.randomUUID()}.zip")
            val encrypted = File(directory, "${UUID.randomUUID()}.enc.zip")
            ZipOutputStream(plain.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(ENTRY_NAME))
                zip.write(JSONObject().put("vaults", JSONArray(entries)).toString().toByteArray())
                zip.closeEntry()
            }
            EncryptionHelper.encryptFile(plain, encrypted, BACKUP_PASSWORD, strings).getOrThrow()
            plain.delete()
            val result = helper.restoreFromBackupFile(encrypted, BACKUP_PASSWORD, restoreMonicaConfig = true, importDataOnly = true).getOrThrow()
            result.content.portableAttachments?.close()
            ownedRows().mapNotNull { it.transportSecretRef }.forEach { references += it }
            return result.report.warnings
        }

        suspend fun rows() = dao.getAllVaults().filter { it.email == email }
        suspend fun ownedRows() = dao.getAllVaults().filter { it.email in ownedEmails }
        suspend fun row() = rows().single()
        fun ownEmail(suffix: String) = email.replace("@", "-$suffix@").also { ownedEmails += it }
        fun legacyEntry(accountEmail: String, preferred: Boolean = false) = JSONObject()
            .put("email", accountEmail).put("serverUrl", BASE).put("apiUrl", "")
            .put("identityUrl", "").put("isDefault", preferred).put("isConnected", false)
        fun resolve(vault: BitwardenVault) = BitwardenVaultEnvironmentStore(secrets).resolve(vault)
        suspend fun remove(vault: BitwardenVault) {
            check(vault.email in ownedEmails && vault.serverUrl.trimEnd('/') == BASE)
            vault.transportSecretRef?.let { references += it }
            check(repository.logout(vault.id))
        }

        suspend fun close() {
            try {
                if (initialized) ownedRows().forEach { remove(it) }
                references.forEach(security::removeProtectedString)
            } finally {
                if (initialized) {
                    val edit = preferences.edit()
                    previousPrefs.forEach { (name, old) -> if (old.first) edit.putLong(name, old.second) else edit.remove(name) }
                    check(edit.commit())
                    instrumentation.runOnMainSync { if (originalUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked() }
                }
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile && directory.name.startsWith("transport-backup-"))
                directory.deleteRecursively()
            }
        }
    }

    private companion object {
        const val BASE = "https://transport-backup.example.invalid/Case"
        const val MASTER_PASSWORD = "Synthetic configuration master password"
        const val BACKUP_PASSWORD = "Synthetic encrypted backup password"
        const val HEADER_SECRET = "synthetic-portable-proxy-secret"
        const val TLS_SECRET = "  synthetic-preserved-client-password  "
        const val ENTRY_NAME = "monica_config/bitwarden_vaults.json"
    }
}
