package takagi.ru.monica.autofill_ng

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.KeePassDatabaseSourceType
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.SettingsManager

/** Host drives a real force-stop and cold Activity/password UI between these independent phases. */
class KeePassAutofillColdProcessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val marker by lazy { context.getSharedPreferences("issue179_cold_fixture", 0) }
    private val credentials get() = Credentials.from(EncryptedValue.fromString(KDBX_PASSWORD))

    // No @Before or @After: verification must not create a new vault, unlock it, or replace this fixture.
    @Test fun prepareColdPickerFixture() = runBlocking {
        checkOwnedUser()
        check(!marker.contains("id")) { "Previous cold fixture needs explicit verification/cleanup" }
        val security = SecurityManager(context)
        if (!security.isMasterPasswordSet()) check(security.setMasterPassword(MASTER_PASSWORD))
        check(security.unlockVaultWithPassword(MASTER_PASSWORD)) { "Existing vault is not this synthetic test vault" }
        val settings = SettingsManager(context)
        val preferences = AutofillPreferences(context)
        val oldSettings = settings.settingsFlow.first()
        val oldSource = preferences.v2DefaultSourceFilter.first()
        val oldKeePass = preferences.v2DefaultKeepassDatabaseId.first()
        val oldBitwarden = preferences.v2DefaultBitwardenVaultId.first()
        val uri = Uri.parse("content://${instrumentation.context.packageName}.kdbx-delete/delete-${UUID.randomUUID()}.kdbx")
        val created = KeePassDatabase.Ver4x.create("ColdIssue179Root", Meta(generator = "Issue179 cold fixture"), credentials)
        val seed = when (val kdf = created.header.kdfParameters) {
            is KdfParameters.Aes -> kdf.seed
            is KdfParameters.Argon2 -> kdf.salt
        }
        val native = created.copy(header = created.header.copy(kdfParameters = KdfParameters.Argon2(
            variant = KdfParameters.Argon2.Variant.Argon2d, salt = seed, parallelism = 2U,
            memory = 32uL * 1024uL * 1024uL, iterations = 8U, version = 0x13U,
            secretKey = null, associatedData = null))).modifyParentGroup {
                copy(groups = listOf(Group(uuid = UUID.randomUUID(), name = "Cold native folder", entries = listOf(
                    Entry(uuid = UUID.randomUUID(), fields = EntryFields.of(
                        "Title" to EntryValue.Plain(TITLE),
                        "UserName" to EntryValue.Plain("C2|cold-native-account"),
                        "Password" to EntryValue.Encrypted(EncryptedValue.fromString("MDK|cold-native-password")),
                        "URL" to EntryValue.Plain("https://cold.issue179.example.test"),
                        "App Package Name" to EntryValue.Plain(instrumentation.context.packageName),
                    ))))))
            }
        var ownedId = 0L
        try {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { native.encode(it) }
            val sha = sha256(bytes(uri))
            val database = PasswordDatabase.getDatabase(context)
            ownedId = database.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(
                name = "Issue179 cold native vault", filePath = uri.toString(),
                storageLocation = KeePassStorageLocation.EXTERNAL,
                sourceType = KeePassDatabaseSourceType.LOCAL_DOCUMENT_URI,
                encryptedPassword = security.encryptData(KDBX_PASSWORD)))
            assertTrue(database.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(ownedId).isEmpty())
            check(marker.edit().putLong("id", ownedId).putString("uri", uri.toString())
                .putString("sha256", sha).putString("title", TITLE).putString("package", context.packageName)
                .putString("testPackage", instrumentation.context.packageName)
                .putInt("pid", android.os.Process.myPid()).putInt("user", android.os.Process.myUid() / 100000)
                .putBoolean("oldAuth", oldSettings.autofillAuthRequired)
                .putBoolean("oldKeep", oldSettings.autofillKeepUnlocked)
                .putBoolean("oldBiometric", oldSettings.biometricEnabled)
                .putBoolean("oldScreenshot", oldSettings.screenshotProtectionEnabled)
                .putInt("oldTimeout", oldSettings.autoLockMinutes)
                .putString("oldSource", oldSource.name)
                .putLong("oldKeePass", oldKeePass ?: -1L).putLong("oldBitwarden", oldBitwarden ?: -1L).commit())
            settings.updateBiometricEnabled(false)
            settings.updateAutofillAuthRequired(true)
            settings.updateAutofillKeepUnlocked(false)
            settings.updateScreenshotProtectionEnabled(false)
            settings.updateAutoLockMinutes(5)
            preferences.setV2DefaultSourceFilter(AutofillPreferences.AutofillDefaultSourceFilter.KEEPASS)
            preferences.setV2DefaultKeepassDatabaseId(ownedId)
            preferences.setV2DefaultBitwardenVaultId(null)
            val receipt = JSONObject().put("phase", "prepared").put("applicationId", context.packageName)
                .put("testPackage", instrumentation.context.packageName).put("user", android.os.Process.myUid() / 100000)
                .put("pid", android.os.Process.myPid()).put("databaseId", ownedId).put("uri", uri.toString())
                .put("sourceSha256", sha).put("expectedTitle", TITLE)
                .put("activity", "takagi.ru.monica.autofill_ng.AutofillPickerActivityV2")
                .put("manualExtra", "extra_manual_mode").put("targetExtra", "extra_manual_target_package")
            writeReceipt("cold-prepared.json", receipt)
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "ISSUE179_COLD_PREPARED $receipt\n") })
        } catch (error: Throwable) {
            if (marker.contains("id")) cleanupOwnedFixture()
            else {
                if (ownedId != 0L) PasswordDatabase.getDatabase(context).localKeePassDatabaseDao().deleteDatabaseById(ownedId)
                context.contentResolver.delete(uri, null, null)
            }
            throw error
        } finally {
            SessionManager.markLocked()
        }
    }

    @Test fun verifyColdPickerFixtureAndCleanup() = runBlocking {
        checkOwnedUser()
        check(marker.contains("id")) { "Run prepareColdPickerFixture first, then actual cold picker UI" }
        check(marker.getString("package", null) == context.packageName)
        check(marker.getInt("user", -1) == android.os.Process.myUid() / 100000)
        val id = marker.getLong("id", 0L)
        val uri = Uri.parse(requireNotNull(marker.getString("uri", null)))
        val preparedPid = marker.getInt("pid", -1)
        // The host records the separate real Activity PID before instrumentation restarts it.
        val coldUiPid = requireNotNull(InstrumentationRegistry.getArguments().getString("issue179ColdUiPid"))
            .toInt().also { require(it > 0) }
        try {
            assertNotEquals("Cold Activity must differ from phase1 instrumentation", preparedPid, coldUiPid)
            assertNotEquals("Verification must run after a real process boundary", preparedPid, android.os.Process.myPid())
            assertFalse("Verification must not unlock the main app", SessionManager.isUnlocked.value)
            val database = PasswordDatabase.getDatabase(context)
            assertEquals(uri.toString(), requireNotNull(database.localKeePassDatabaseDao().getDatabaseById(id)).filePath)
            assertTrue("Cold picker must not create a Room mirror", database.passwordEntryDao()
                .getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
            val bytes = bytes(uri)
            assertEquals(marker.getString("sha256", null), sha256(bytes))
            val reopened = bytes.inputStream().use { KeePassDatabase.decode(it, credentials) }
            assertEquals(TITLE, reopened.content.group.groups.single().entries.single().fields.getValue("Title").content)
            val receipt = JSONObject().put("phase", "verified").put("applicationId", context.packageName)
                .put("preparedPid", preparedPid).put("hostObservedColdUiPid", coldUiPid)
                .put("verificationPid", android.os.Process.myPid()).put("databaseId", id)
                .put("sourceSha256", sha256(bytes)).put("roomMirrorCount", 0).put("mainSessionUnlocked", false)
                .put("scope", "Storage verification only; host separately asserts the cold password prompt and native title")
            writeReceipt("cold-verified.json", receipt)
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "ISSUE179_COLD_VERIFIED $receipt\n") })
        } finally {
            cleanupOwnedFixture()
        }
    }

    private fun checkOwnedUser() {
        val user = android.os.Process.myUid() / 100000
        check(user > 0 && InstrumentationRegistry.getArguments().getString("issue179User") == user.toString()) {
            "Run only in this task's explicitly owned secondary Android user"
        }
    }

    private suspend fun cleanupOwnedFixture() {
        val id = marker.getLong("id", 0L)
        val value = marker.getString("uri", null)
        check(id > 0 && value != null && marker.getString("package", null) == context.packageName)
        val uri = Uri.parse(value)
        check(uri.authority == "${instrumentation.context.packageName}.kdbx-delete" &&
            uri.lastPathSegment?.matches(Regex("delete-[a-f0-9-]+\\.kdbx")) == true)
        val database = PasswordDatabase.getDatabase(context)
        val registration = database.localKeePassDatabaseDao().getDatabaseById(id)
        check(registration == null || registration.filePath == value) { "Owned fixture registration changed; preserve it" }
        KeePassKdbxService.invalidateProcessCache(id)
        database.localKeePassDatabaseDao().deleteDatabaseById(id)
        context.contentResolver.delete(uri, null, null)
        val settings = SettingsManager(context)
        settings.updateBiometricEnabled(marker.getBoolean("oldBiometric", false))
        settings.updateAutofillAuthRequired(marker.getBoolean("oldAuth", true))
        settings.updateAutofillKeepUnlocked(marker.getBoolean("oldKeep", false))
        settings.updateScreenshotProtectionEnabled(marker.getBoolean("oldScreenshot", true))
        settings.updateAutoLockMinutes(marker.getInt("oldTimeout", 5))
        val preferences = AutofillPreferences(context)
        preferences.setV2DefaultSourceFilter(AutofillPreferences.AutofillDefaultSourceFilter.valueOf(
            requireNotNull(marker.getString("oldSource", null))))
        preferences.setV2DefaultKeepassDatabaseId(marker.getLong("oldKeePass", -1).takeIf { it >= 0 })
        preferences.setV2DefaultBitwardenVaultId(marker.getLong("oldBitwarden", -1).takeIf { it >= 0 })
        check(marker.edit().clear().commit())
    }

    private fun bytes(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun writeReceipt(name: String, receipt: JSONObject) {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "issue179")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, name).writeText(receipt.toString(2))
    }

    companion object {
        private const val MASTER_PASSWORD = "Monica-Issue179-Test-2026!"
        private const val KDBX_PASSWORD = "Synthetic issue179 cold KDBX password"
        private const val TITLE = "Issue179 cold original native login"
    }
}
