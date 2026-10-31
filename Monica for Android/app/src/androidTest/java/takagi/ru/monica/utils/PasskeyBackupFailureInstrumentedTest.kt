package takagi.ru.monica.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.passkey.*
import takagi.ru.monica.security.SecurityManager
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

class PasskeyBackupFailureInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = BackupPreferences(includePasswords = true, includeAuthenticators = false,
        includeDocuments = false, includeBankCards = false, includePasskeys = true, includeGeneratorHistory = false,
        includeImages = false, includeNotes = false, includeTimeline = false, includeTrash = false,
        includeTrashAndHistory = false, includeWebDavConfig = false, includeLocalKeePass = false)

    @Test fun encryptedBackupIdentifiesUnavailableAndDeviceBoundKeysWithoutLosingHealthyKeys() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val alias = "backup-fixture-$suffix"
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val raw = Base64.getEncoder().encodeToString(pair.private.encoded)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
        val protected = PasskeyPrivateKeyStore.protectForStorage(context, "protected-$suffix", "example.test", "fixture", raw)
        fun entry(kind: String, material: String) = PasskeyEntry(credentialId = "$kind-$suffix", rpId = "example.test",
            rpName = "Fixture $kind", userId = "fixture", userName = "fixture", userDisplayName = "Fixture", publicKey = "fixture-public", privateKeyAlias = material)
        val fixtures = listOf(entry("raw", raw), entry("protected", protected),
            entry("missing", "monica-passkey-key-ref-v1:missing-$suffix"), entry("device", alias))
        val dao = PasswordDatabase.getDatabase(context).passkeyDao()
        val files = mutableListOf<java.io.File>()
        val server = MockWebServer()
        try {
            server.start()
            dao.insertAll(fixtures)
            val saved = dao.getAllPasskeysSync().filter { it.credentialId.endsWith(suffix) }.associateBy { it.credentialId.substringBefore('-') }
            val helper = WebDavHelper(context)
            val (file, report) = helper.createBackupZip(emptyList(), emptyList(), preferences,
                backupEncryptionPassword = "fixture-backup-password").getOrThrow()
            files += file
            assertFalse(report.success)
            assertTrue(report.successItems.passkeys >= 2)
            assertFalse(report.failedItems.any { it.id == saved.getValue("raw").id || it.id == saved.getValue("protected").id })
            assertEquals(context.getString(R.string.backup_passkey_device_bound), report.failedItems.single { it.id == saved.getValue("device").id }.reason)
            assertEquals(context.getString(R.string.backup_passkey_unavailable), report.failedItems.single { it.id == saved.getValue("missing").id }.reason)
            val error = runCatching { requireCompleteBackup(report, "incomplete") }.exceptionOrNull()
            assertTrue(error is IncompleteBackupException)
            assertSame(report, (error as IncompleteBackupException).report)
            assertFalse(error.message.orEmpty().contains("Fixture"))
            assertFalse(error.displayMessage(context).contains(raw))
            // Configure only this helper instance; never overwrite the shared emulator's settings.
            WebDavHelper::class.java.getDeclaredField("enableEncryption").apply { isAccessible = true }.setBoolean(helper, true)
            WebDavHelper::class.java.getDeclaredField("encryptionPassword").apply { isAccessible = true }.set(helper, "fixture-backup-password")
            val uploadError = helper.createAndUploadBackup(emptyList(), emptyList(), preferences).exceptionOrNull()
            assertTrue("The upload boundary must preserve item diagnostics", uploadError is IncompleteBackupException)
            assertTrue((uploadError as IncompleteBackupException).report.failedItems.any { it.id == saved.getValue("missing").id })
            val operations = mutableListOf<String>()
            val clientType = com.thegrizzlylabs.sardineandroid.Sardine::class.java
            val fakeClient = java.lang.reflect.Proxy.newProxyInstance(clientType.classLoader, arrayOf(clientType)) { _, method, args ->
                operations += method.name
                when (method.name) {
                    "exists" -> {
                        assertEquals(server.url("/fixture-$suffix/Monica_Backups").toString(), args!![0])
                        true
                    }
                    else -> error("Unexpected remote operation: ${method.name}")
                }
            }
            WebDavHelper::class.java.getDeclaredField("sardine").apply { isAccessible = true }.set(helper, fakeClient)
            WebDavHelper::class.java.getDeclaredField("serverUrl").apply { isAccessible = true }
                .set(helper, server.url("/fixture-$suffix").toString())
            val fullBackupTime = helper.getLastBackupTime()
            val stillIncomplete = helper.createAndUploadBackup(emptyList(), emptyList(), preferences,
                skippedPasskeys = report.failedItems.filter { it.id == saved.getValue("missing").id })
            assertTrue(stillIncomplete.exceptionOrNull() is IncompleteBackupException)
            assertTrue("New or unselected failures must still prevent upload", operations.isEmpty())
            assertEquals("Incomplete archives must not reach the real upload transport", 0, server.requestCount)
            val skipped = report.failedItems.filter { it.type == context.getString(R.string.backup_content_passkeys) }
            server.enqueue(MockResponse().setResponseCode(201))
            val partial = helper.createAndUploadBackup(emptyList(), emptyList(), preferences, skippedPasskeys = skipped).getOrThrow()
            assertTrue(partial.success)
            assertEquals(skipped, partial.skippedItems)
            val upload = requireNotNull(server.takeRequest(10, TimeUnit.SECONDS)) { "Partial archive was not uploaded" }
            val uploadedPath = requireNotNull(upload.path)
            val uploadedBytes = upload.body.readByteArray()
            assertEquals("PUT", upload.method)
            assertEquals("*", upload.getHeader("If-None-Match"))
            assertEquals(1, server.requestCount)
            assertTrue(uploadedPath.startsWith("/fixture-$suffix/Monica_Backups/monica_backup_"))
            assertFalse(uploadedPath.contains('?'))
            assertTrue(uploadedPath.contains("_partial_"))
            assertTrue(uploadedPath.endsWith("_permanent.enc.zip"))
            assertEquals(listOf("exists"), operations)
            assertEquals("Partial backup must not advance full-backup time", fullBackupTime, helper.getLastBackupTime())
            val partialFile = java.io.File(context.cacheDir, "fixture-partial-$suffix.enc.zip")
            files += partialFile
            partialFile.writeBytes(uploadedBytes)
            assertTrue("The real HTTP body must be an encrypted Monica archive", EncryptionHelper.hasEncryptedFileHeader(partialFile))
            val overwrite = helper.restoreFromBackupFile(partialFile, decryptPassword = "fixture-backup-password", overwrite = true)
            assertEquals(context.getString(R.string.passkey_partial_replace_blocked), overwrite.exceptionOrNull()?.message)
            val merge = helper.restoreFromBackupFile(partialFile, decryptPassword = "fixture-backup-password",
                overwrite = false, restoreMonicaConfig = false, importDataOnly = true).getOrThrow()
            assertFalse(merge.overwriteLocal)
            assertTrue(merge.report.warnings.contains(context.getString(R.string.passkey_partial_restore_warning)))
            val restoredFixtures = merge.content.passkeys.filter { it.credentialId.endsWith(suffix) }
            assertEquals(setOf("raw-$suffix", "protected-$suffix"), restoredFixtures.map { it.credentialId }.toSet())
            restoredFixtures.forEach {
                assertArrayEquals(pair.private.encoded, Base64.getDecoder().decode(
                    PasskeyPrivateKeyStore.resolve(context, it.privateKeyAlias)))
            }
            val withoutKeys = helper.createBackupZip(emptyList(), emptyList(), preferences.copy(includePasskeys = false),
                backupEncryptionPassword = "fixture-backup-password").getOrThrow()
            files += withoutKeys.first
            assertTrue(withoutKeys.second.success)
            assertEquals(0, withoutKeys.second.totalItems.passkeys)
            assertEquals(4, dao.getAllPasskeysSync().count { it.credentialId.endsWith(suffix) })
        } finally {
            try {
                fixtures.forEach { dao.deleteById(it.credentialId) }
                PasskeyPrivateKeyStore.removeIfProtectedReference(context, protected)
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
                files.distinct().forEach { it.delete() }
            } finally { server.shutdown() }
        }
    }

    @Test fun legacyExportFallbackMustStillBeValidatedAndEncrypted() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val raw = Base64.getEncoder().encodeToString(pair.private.encoded)
        val ready = PasskeyBackupPortabilityPolicy.prepareExport(true, "legacy-alias", { it },
            PasskeyPrivateKeySupport::exportPkcs8Base64, { raw }) as PasskeyBackupPortabilityPolicy.ExportDecision.Ready
        assertArrayEquals(pair.private.encoded, Base64.getDecoder().decode(ready.privateKeyMaterial))
        assertEquals(PasskeyBackupPortabilityPolicy.ExportDecision.PrivateKeyMissing,
            PasskeyBackupPortabilityPolicy.prepareExport(true, "legacy-alias", { it }, PasskeyPrivateKeySupport::exportPkcs8Base64, { "invalid" }))
        assertEquals(PasskeyBackupPortabilityPolicy.ExportDecision.EncryptionRequired,
            PasskeyBackupPortabilityPolicy.prepareExport(false, "legacy-alias", { error("must not resolve") }, { it }))
    }
}
