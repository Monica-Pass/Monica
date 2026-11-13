package takagi.ru.monica.credentialexchange

import android.net.Uri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.data.CommonAccountPreferences
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.EncryptionHelper
import takagi.ru.monica.utils.SettingsManager
import java.io.File
import java.util.zip.ZipFile

class ZipConfigurationBackupTest {
    private val configOnly = BackupPreferences(includePasswords = false, includeAuthenticators = false,
        includeDocuments = false, includeBankCards = false, includePasskeys = false,
        includeImages = false, includeNotes = false, includeGeneratorHistory = false,
        includeTimeline = false, includeTrash = false, includeTrashAndHistory = false,
        includeWebDavConfig = true)
    private val secret = "Synthetic ZIP configuration password"

    @Test fun configOptInRequiresEncryptionAndDefaultArchiveHasNoSettings() = runBlocking {
        val f = TransferFixture()
        try {
            assertFalse(BackupPreferences().includeWebDavConfig)
            val before = f.context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
            for (password in listOf(null, "")) {
                assertTrue(f.model.prepareZipBackup(configOnly, password).isFailure)
            }
            assertEquals(before, f.context.cacheDir.listFiles().orEmpty().map { it.name }.toSet())
            val (archive, _) = f.model.prepareZipBackup(configOnly.copy(includeWebDavConfig = false,
                includePasswords = true), secret).getOrThrow()
            val plain = File(f.root, "default.zip")
            try {
                EncryptionHelper.decryptFile(archive, plain, secret, AppLocaleStringResolver(f.context)).getOrThrow()
                ZipFile(plain).use { zip -> assertFalse(zip.entries().asSequence().any { it.name.startsWith("monica_config/") }) }
            } finally { archive.delete() }
        } finally { f.close() }
    }

    @Test fun encryptedConfigOnlyRoundTripHonorsKeepAndRestoreChoices() = runBlocking {
        val f = AuthenticatedConfigurationFixture.create()
        val settings = SettingsManager(f.context)
        val original = settings.exportPageAdjustmentSettings()
        val accounts = CommonAccountPreferences(f.context)
        val originalAccount = accounts.commonAccountInfo.first()
        val autofill = AutofillPreferences(f.context)
        val originalBlacklist = autofill.blacklistPackages.first()
        val originalQuestions = f.security.exportSecurityQuestionsForBackup()
        try {
            settings.updateHapticFeedbackEnabled(true)
            accounts.setDefaultEmail("zip-config@example.invalid")
            autofill.setBlacklistPackages(setOf("invalid.zip.fixture"))
            f.security.setSecurityQuestions(1, "Synthetic answer one", 2, "Synthetic answer two")
            val expectedQuestions = f.security.exportSecurityQuestionsForBackup()
            val (archive, _) = f.model.prepareZipBackup(configOnly, secret).getOrThrow()
            val plain = File(f.root, "config.zip")
            try {
                assertTrue(EncryptionHelper.isEncryptedFile(archive))
                EncryptionHelper.decryptFile(archive, plain, secret, AppLocaleStringResolver(f.context)).getOrThrow()
                ZipFile(plain).use { zip ->
                    val names = zip.entries().asSequence().map { it.name }.toSet()
                    assertTrue(names.containsAll(listOf("monica_config/page_adjustment_settings.json",
                        "monica_config/security_questions.json", "monica_config/common_account.json",
                        "monica_config/autofill_blacklist.json")))
                    assertTrue(names.all { it == "database_export.json" || it.startsWith("monica_config/") })
                    val appSettings = zip.getInputStream(zip.getEntry("monica_config/page_adjustment_settings.json"))
                        .bufferedReader().use { it.readText() }
                    assertFalse(appSettings.contains("isPlusActivated"))
                    assertFalse(appSettings.contains("masterPassword"))
                    val questions = zip.getInputStream(zip.getEntry("monica_config/security_questions.json"))
                        .bufferedReader().use { it.readText() }
                    assertFalse(questions.contains("Synthetic answer"))
                    assertEquals(expectedQuestions!!.answer1Hash, JSONObject(questions).getString("answer1Hash"))
                }
                settings.updateHapticFeedbackEnabled(false)
                accounts.setDefaultEmail("keep-local@example.invalid")
                autofill.setBlacklistPackages(emptySet())
                f.security.clearSecurityQuestions()
                assertTrue(f.model.importZipBackup(Uri.fromFile(archive), "wrong", ImportDestination.Local, true).isFailure)
                assertFalse(settings.exportPageAdjustmentSettings().hapticFeedbackEnabled)
                assertEquals(0, f.model.importZipBackup(Uri.fromFile(archive), secret, ImportDestination.Local).getOrThrow())
                assertFalse(settings.exportPageAdjustmentSettings().hapticFeedbackEnabled)
                assertEquals("keep-local@example.invalid", accounts.commonAccountInfo.first().email)
                assertTrue(autofill.blacklistPackages.first().isEmpty())
                assertNull(f.security.exportSecurityQuestionsForBackup())
                assertEquals(0, f.model.importZipBackup(Uri.fromFile(archive), secret, ImportDestination.Local, true).getOrThrow())
                assertTrue(settings.exportPageAdjustmentSettings().hapticFeedbackEnabled)
                assertEquals("keep-local@example.invalid", accounts.commonAccountInfo.first().email)
                assertEquals(setOf("invalid.zip.fixture"), autofill.blacklistPackages.first())
                assertEquals(expectedQuestions, f.security.exportSecurityQuestionsForBackup())
                accounts.setDefaultEmail("")
                f.model.importZipBackup(Uri.fromFile(archive), secret, ImportDestination.Local, true).getOrThrow()
                assertEquals("zip-config@example.invalid", accounts.commonAccountInfo.first().email)
            } finally { archive.delete() }
        } finally {
            settings.importPageAdjustmentSettings(original)
            accounts.setDefaultEmail(originalAccount.email)
            autofill.setBlacklistPackages(originalBlacklist)
            if (originalQuestions == null) f.security.clearSecurityQuestions()
            else f.security.restoreSecurityQuestionsFromBackup(originalQuestions)
            f.close()
        }
    }

    @Test fun configOptInKeepsSelectedDatabaseRecordsSeparate() = runBlocking {
        val f = TransferFixture()
        try {
            val source = f.mdbx()
            f.importer.importExchange(f.decoded("selected"), source)
            f.importer.importExchange(f.decoded("excluded"), ImportDestination.Local)
            val (archive, _) = f.model.prepareZipBackup(configOnly.copy(includePasswords = true), secret, source).getOrThrow()
            val plain = File(f.root, "selected.zip")
            try {
                EncryptionHelper.decryptFile(archive, plain, secret, AppLocaleStringResolver(f.context)).getOrThrow()
                ZipFile(plain).use { zip ->
                    val records = zip.entries().asSequence().filter { it.name.startsWith("passwords/") }
                        .map { zip.getInputStream(it).bufferedReader().use { reader -> JSONObject(reader.readText()) } }.toList()
                    assertEquals(1, records.size)
                    assertEquals("${f.prefix}-selected", records.single().getString("title"))
                    assertNotNull(zip.getEntry("monica_config/page_adjustment_settings.json"))
                    assertFalse(zip.entries().asSequence().any { it.name.startsWith("keepass/") || it.name.startsWith("passkeys/") })
                }
            } finally { archive.delete() }
        } finally { f.close() }
    }

    @Test fun encryptedConnectionBackupRestoresBitwardenLockedWithoutWebDavSetup() = runBlocking {
        val f = TransferFixture()
        val settings = SettingsManager(f.context)
        val original = settings.exportPageAdjustmentSettings()
        try {
            val source = f.bitwarden()
            val before = checkNotNull(f.db.bitwardenVaultDao().getVaultById(source.databaseId))
            val (archive, _) = f.model.prepareZipBackup(configOnly, secret).getOrThrow()
            val plain = File(f.root, "connections.zip")
            try {
                EncryptionHelper.decryptFile(archive, plain, secret, AppLocaleStringResolver(f.context)).getOrThrow()
                ZipFile(plain).use { zip ->
                    val payload = zip.getInputStream(checkNotNull(zip.getEntry("monica_config/bitwarden_vaults.json")))
                        .bufferedReader().use { it.readText() }
                    assertFalse(payload.contains(f.accessToken))
                    assertFalse(payload.contains(checkNotNull(before.encryptedAccessToken)))
                }
                f.model.importZipBackup(Uri.fromFile(archive), secret, ImportDestination.Local, true).getOrThrow()
                val restored = checkNotNull(f.db.bitwardenVaultDao().getVaultById(source.databaseId))
                assertEquals(before.encryptedAccessToken, restored.encryptedAccessToken)
                assertEquals(before.encryptedEncKey, restored.encryptedEncKey)
                assertTrue(restored.isLocked)
                assertFalse(takagi.ru.monica.bitwarden.repository.BitwardenRepository.getInstance(f.context)
                    .isVaultUnlocked(source.databaseId))
                assertTrue(f.importedPasswords(ImportDestination.Local).isEmpty())
            } finally { archive.delete() }
        } finally { settings.importPageAdjustmentSettings(original); f.close() }
    }

    @Test fun webDavCredentialsRestoreOnlyWithOptInAndNeverReplaceExistingConnection() = runBlocking {
        val secret = "   " // A non-empty password must be used verbatim, including whitespace.
        val f = TransferFixture()
        val helper = takagi.ru.monica.utils.WebDavHelper(f.context)
        val settings = SettingsManager(f.context)
        val original = settings.exportPageAdjustmentSettings()
        check(!helper.isConfigured()) { "Run only in the dedicated empty test user" }
        try {
            helper.configure("https://zip-backup.example.invalid", "synthetic-user", "synthetic-webdav-password")
            val (archive, _) = f.model.prepareZipBackup(configOnly, secret).getOrThrow()
            try {
                helper.clearConfig()
                f.model.importZipBackup(Uri.fromFile(archive), secret, ImportDestination.Local).getOrThrow()
                assertFalse(takagi.ru.monica.utils.WebDavHelper(f.context).isConfigured())
                f.model.importZipBackup(Uri.fromFile(archive), secret, ImportDestination.Local, true).getOrThrow()
                val restored = takagi.ru.monica.utils.WebDavHelper(f.context)
                assertEquals("synthetic-webdav-password", restored.getCurrentPasswordForEdit())
                restored.configure("https://keep-local.example.invalid", "local-user", "local-password")
                f.model.importZipBackup(Uri.fromFile(archive), secret, ImportDestination.Local, true).getOrThrow()
                assertEquals("local-password", takagi.ru.monica.utils.WebDavHelper(f.context).getCurrentPasswordForEdit())
            } finally { archive.delete() }
        } finally { helper.clearConfig(); settings.importPageAdjustmentSettings(original); f.close() }
    }

    /** Authenticate only private namespaced settings; retain the application's original credentials. */
    private class AuthenticatedConfigurationFixture private constructor(private val transfer: TransferFixture) {
        private val base = transfer.context
        private val prefix = "zip-config-auth-${java.util.UUID.randomUUID()}-"
        private val names = linkedSetOf<String>()
        val root = File(transfer.root, "authenticated-config").apply { check(mkdirs()) }
        private val originalStores = (setOf(takagi.ru.monica.security.SecurePreferencesStore.MONICA,
            takagi.ru.monica.security.SecurePreferencesStore.BITWARDEN) + listOfNotNull(
            takagi.ru.monica.security.LocalVaultRecovery(base).activeStore()?.first)).associateWith {
            base.getSharedPreferences(it, android.content.Context.MODE_PRIVATE).all.toMap()
        }
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
                dataDir = root.absolutePath
            }
            override fun getFilesDir() = File(root, "files").apply { check(isDirectory || mkdirs()) }
            override fun getCacheDir() = File(root, "cache").apply { check(isDirectory || mkdirs()) }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                names += name
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        lateinit var security: takagi.ru.monica.security.SecurityManager
            private set
        lateinit var model: takagi.ru.monica.viewmodel.DataExportImportViewModel
            private set

        private suspend fun prepare() {
            assertSame(context, context.applicationContext)
            assertEquals(root.absolutePath, context.applicationContext.applicationInfo.dataDir)
            val startup = takagi.ru.monica.security.SecureStorageStartup.prepareWithRetry(context)
            assertTrue(startup is takagi.ru.monica.security.SecureStartupResult.Ready)
            security = (startup as takagi.ru.monica.security.SecureStartupResult.Ready).manager
            val password = "Synthetic ZIP configuration authentication 327!"
            takagi.ru.monica.security.SessionManager.markLocked()
            takagi.ru.monica.security.SecurityManager.clearRuntimeUnlockCache()
            assertFalse("Only a fresh private synthetic store may be enrolled", security.isMasterPasswordSet())
            assertTrue(security.setMasterPassword(password))
            takagi.ru.monica.security.SecurityManager.clearRuntimeUnlockCache()
            assertTrue("Security questions require actual persisted-password authentication",
                security.verifyMasterPassword(password))
            takagi.ru.monica.security.SessionManager.markUnlocked()
            assertTrue(takagi.ru.monica.security.SecurityManager.hasRuntimeUnlockCache())
            model = takagi.ru.monica.viewmodel.DataExportImportViewModel(transfer.secureItems, transfer.passwords, context)
        }

        suspend fun close() {
            if (::model.isInitialized) model.viewModelScope.cancel()
            takagi.ru.monica.security.SessionManager.markLocked()
            takagi.ru.monica.security.SecurityManager.clearRuntimeUnlockCache()
            takagi.ru.monica.security.PreparedSecureStore.invalidate()
            names.forEach { base.deleteSharedPreferences(prefix + it) }
            check(root.canonicalFile.parentFile == transfer.root.canonicalFile && root.name == "authenticated-config")
            root.deleteRecursively()
            originalStores.forEach { (name, values) ->
                assertEquals("The original application credential preferences must remain unchanged",
                    values, base.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).all)
            }
            assertTrue(takagi.ru.monica.security.SecureStorageStartup.prepareWithRetry(base) is
                takagi.ru.monica.security.SecureStartupResult.Ready)
            transfer.close()
        }

        companion object {
            suspend fun create(): AuthenticatedConfigurationFixture {
                val fixture = AuthenticatedConfigurationFixture(TransferFixture())
                try { fixture.prepare(); return fixture }
                catch (error: Throwable) {
                    try { fixture.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                    throw error
                }
            }
        }
    }
}
