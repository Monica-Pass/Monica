package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.MasterKey
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey

class LocalVaultRecoveryInstrumentedTest {
    private class Fixture : AutoCloseable {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "local-recovery-test-${UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        private val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
                dataDir = root.absolutePath
            }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names.add(name)
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        val recovery = LocalVaultRecovery(context)
        val password = "Synthetic-local-vault-316!"
        val manager: SecurityManager
        init { SecurityManager.clearRuntimeUnlockCache(); manager = SecurityManager(context) }
        fun enroll() { assertTrue(manager.setMasterPassword(password)) }
        val raw get() = context.getSharedPreferences(SecurePreferencesStore.MONICA, Context.MODE_PRIVATE)
        val envelope get() = File(root, "files/local-vault-recovery/recovery.json")
        fun damageOriginal() {
            assertTrue(raw.edit().remove("__androidx_security_crypto_encrypted_prefs_value_keyset__").commit())
            SecurityManager.clearRuntimeUnlockCache()
        }
        fun reopened() = SecurityManager(context)
        override fun close() {
            SecurityManager.clearRuntimeUnlockCache()
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            names.filter { it.startsWith("monica_recovered_") }.forEach {
                val alias = it.replace("monica_recovered_", "monica_recovered_key_")
                if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
            }
            names.forEach { base.deleteSharedPreferences(prefix + it) }
            check(root.parentFile == base.cacheDir && root.name.startsWith("local-recovery-test-"))
            root.deleteRecursively()
        }
    }

    @Test fun offlineRecoveryPreservesPasswordOtpAttachmentKeyAndNewPasskeyPreferences() {
        Fixture().use { f ->
            f.enroll()
            val password = f.manager.encryptData("synthetic-login-password")
            val otp = f.manager.encryptDataLegacyCompat("JBSWY3DPEHPK3PXP")
            val cek = ByteArray(32) { it.toByte() }
            val wrapped = takagi.ru.monica.attachments.storage.AttachmentKeyVault(f.manager).wrap(cek)
            // Created after initial enrollment: snapshot must be kept up to date.
            f.manager.putProtectedString("passkey_private_key_v1_synthetic", "synthetic-pkcs8")
            f.manager.putProtectedString("new-cloud-setting", "synthetic-unsynced-setting")
            f.damageOriginal()
            val original = f.raw.all.toMap()
            assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Blocked)
            assertTrue(f.recovery.recover(f.password))
            assertEquals(original, f.raw.all)
            val restored = f.reopened()
            assertTrue(restored.unlockVaultWithPassword(f.password))
            assertEquals("synthetic-login-password", restored.decryptData(password))
            assertEquals("JBSWY3DPEHPK3PXP", restored.decryptData(otp))
            assertArrayEquals(cek, takagi.ru.monica.attachments.storage.AttachmentKeyVault(restored).unwrap(wrapped))
            assertEquals("synthetic-pkcs8", restored.getProtectedString("passkey_private_key_v1_synthetic"))
            assertEquals("synthetic-unsynced-setting", restored.getProtectedString("new-cloud-setting"))
            assertFalse(restored.isBiometricEnabled())
        }
    }

    @Test fun wrongPasswordAndDamagedEnvelopeNeverModifyOriginalsOrActivateAnotherStore() {
        Fixture().use { f ->
            f.enroll(); f.damageOriginal()
            val original = f.raw.all.toMap()
            val bytes = f.envelope.readBytes()
            assertFalse(f.recovery.recover("wrong-password"))
            assertArrayEquals(bytes, f.envelope.readBytes())
            assertNull(f.recovery.activeStore())
            val doc = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
            val cipher = Base64.decode(doc.getString("snapshot"), Base64.NO_WRAP)
            cipher[cipher.lastIndex] = (cipher.last().toInt() xor 1).toByte()
            doc.put("snapshot", Base64.encodeToString(cipher, Base64.NO_WRAP))
            f.envelope.writeText(doc.toString())
            assertFalse(f.recovery.recover(f.password))
            assertEquals(original, f.raw.all)
            assertNull(f.recovery.activeStore())
        }
    }

    @Test fun passwordRotationRevokesOldPasswordWithoutReencryptingLocalItems() {
        Fixture().use { f ->
            f.enroll()
            val ciphertext = f.manager.encryptData("unchanged-local-content")
            val newPassword = "New-synthetic-password-316!"
            assertTrue(f.manager.resetMasterPassword(f.password, newPassword))
            assertFalse(f.recovery.recover(f.password))
            f.damageOriginal()
            assertTrue(f.recovery.recover(newPassword))
            val restored = f.reopened()
            assertFalse(restored.unlockVaultWithPassword(f.password))
            assertTrue(restored.unlockVaultWithPassword(newPassword))
            assertEquals("unchanged-local-content", restored.decryptData(ciphertext))
        }
    }

    @Test fun interruptedCredentialMirrorBlocksOldPasswordAndRecoversNewPassword() {
        Fixture().use { f ->
            f.enroll()
            val oldMirror = f.raw.all.toMap()
            val newPassword = "After-interrupted-commit-316!"
            assertTrue(f.manager.resetMasterPassword(f.password, newPassword))
            val editor = f.raw.edit().clear()
            oldMirror.forEach { (k, v) -> LocalVaultRecovery.put(editor, k, v) }
            assertTrue(editor.commit())
            val result = SecureStorageStartup.prepare(f.context) as SecureStartupResult.Blocked
            assertEquals("RECOVERY_COMMIT_PENDING", result.failure.reason)
            assertFalse(f.recovery.recover(f.password))
            assertTrue(f.recovery.recover(newPassword))
            assertTrue(f.reopened().unlockVaultWithPassword(newPassword))
        }
    }

    @Test fun legacyStoreStillContainsReadableCredentialsAndNewProtectedValues() {
        Fixture().use { f ->
            f.enroll()
            f.manager.putProtectedString("compatibility-fixture", "preserved")
            val legacy = SecurePreferencesStore.openRaw(f.context, SecurePreferencesStore.MONICA, MasterKey.DEFAULT_MASTER_KEY_ALIAS).preferences
            assertTrue(legacy.contains("master_password_hash"))
            assertTrue(legacy.contains("mdk_password_blob"))
            assertEquals("preserved", legacy.getString("compatibility-fixture", null))
            assertNull(f.recovery.activeStore())
            val rawEnvelope = f.envelope.readText()
            assertFalse(rawEnvelope.contains(f.password))
            assertFalse(rawEnvelope.contains("compatibility-fixture"))
            assertFalse(rawEnvelope.contains("preserved"))
        }
    }

    @Test fun changingPasswordInLegacyClientRevokesStaleRecoveryUntilPasswordUnlock() {
        Fixture().use { f ->
            f.enroll()
            val legacy = SecurePreferencesStore.openRaw(f.context, SecurePreferencesStore.MONICA, MasterKey.DEFAULT_MASTER_KEY_ALIAS).preferences
            // Model a legacy credential edit without corrupting the actual verifier.
            val blob = legacy.getString("mdk_password_blob", null)!!
            assertTrue(legacy.edit().putString("mdk_password_blob", blob + "\n").commit())
            f.reopened()
            assertFalse(f.recovery.available())
            assertFalse(f.recovery.recover(f.password))
            assertTrue(legacy.edit().putString("mdk_password_blob", blob).commit())
            assertTrue(f.reopened().unlockVaultWithPassword(f.password))
            assertTrue(f.recovery.available())
        }
    }

    @Test fun missingMdkDuringPasswordChangeDoesNotGenerateReplacementOrChangeVerifier() {
        Fixture().use { f ->
            f.enroll()
            val legacy = SecurePreferencesStore.openRaw(f.context, SecurePreferencesStore.MONICA, MasterKey.DEFAULT_MASTER_KEY_ALIAS).preferences
            legacy.edit().remove("mdk_keystore_blob").commit()
            SecurityManager.clearRuntimeUnlockCache()
            val before = legacy.all.toMap()
            assertFalse(f.manager.setMasterPassword("Cannot-replace-lost-key-316!"))
            assertEquals(before, legacy.all)
        }
    }

    @Test fun concurrentProtectedWritesRemainRecoverable() {
        Fixture().use { f ->
            f.enroll()
            val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
            try {
                (0 until 12).map { index -> pool.submit { f.reopened().putProtectedString("item-$index", "value-$index") } }
                    .forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            } finally { pool.shutdownNow() }
            f.damageOriginal()
            assertTrue(f.recovery.recover(f.password))
            val recovered = f.reopened()
            repeat(12) { assertEquals("value-$it", recovered.getProtectedString("item-$it")) }
        }
    }

    @Test fun staleManagerCannotWriteAfterRecoverySwitchesStores() {
        Fixture().use { f ->
            f.enroll()
            val stale = f.manager
            f.damageOriginal()
            assertTrue(f.recovery.recover(f.password))
            assertTrue(runCatching { stale.putProtectedString("stale-write", "must-not-be-saved") }.isFailure)
            val current = f.reopened()
            assertNull(current.getProtectedString("stale-write"))
            current.putProtectedString("current-write", "preserved")
            assertEquals("preserved", f.reopened().getProtectedString("current-write"))
        }
    }

    @Test fun upgradeAfterLegacyProtectedWriteRefreshesRecoverySnapshot() {
        Fixture().use { f ->
            f.enroll()
            val legacy = SecurePreferencesStore.openRaw(f.context, SecurePreferencesStore.MONICA, MasterKey.DEFAULT_MASTER_KEY_ALIAS).preferences
            assertTrue(legacy.edit().putString("created-on-old-apk", "must-be-recovered").commit())
            assertTrue(f.reopened().unlockVaultWithPassword(f.password))
            f.damageOriginal()
            assertTrue(f.recovery.recover(f.password))
            assertEquals("must-be-recovered", f.reopened().getProtectedString("created-on-old-apk"))
        }
    }

    @Test fun recoveredGenerationCanSurviveLosingItsOwnKeystoreKey() {
        Fixture().use { f ->
            f.enroll(); f.damageOriginal()
            assertTrue(f.recovery.recover(f.password))
            val first = f.recovery.activeStore()!!
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(first.second) }
            assertTrue(SecureStorageStartup.prepare(f.context) is SecureStartupResult.Blocked)
            assertTrue(f.recovery.recover(f.password))
            assertNotEquals(first, f.recovery.activeStore())
            assertTrue(f.reopened().unlockVaultWithPassword(f.password))
        }
    }

    @Test fun failedRecoveryWriteDoesNotSetInitialPassword() {
        Fixture().use { f ->
            File(f.root, "files").writeText("fixture prevents directory creation")
            assertFalse(f.manager.setMasterPassword(f.password))
            assertFalse(f.manager.isMasterPasswordSet())
            assertFalse(f.manager.isVaultRuntimeUnlocked())
        }
    }

    @Test fun migrationPreservesUnreadableFieldsAndSyncMetadata() = runBlocking {
        Fixture().use { f ->
            f.enroll()
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = keystore.getKey("monica_data_key_v2_compat", null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val legacy = "C2|" + Base64.encodeToString(cipher.iv + cipher.doFinal("legacy-otp-value".toByteArray()), Base64.NO_WRAP)
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(f.context)
                .name(null).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE secure_items(id INTEGER PRIMARY KEY, itemData TEXT, updatedAt INTEGER, bitwarden_local_modified INTEGER)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
            try {
                val db = helper.writableDatabase
                db.execSQL("INSERT INTO secure_items VALUES(1, ?, 12345, 0)", arrayOf<Any>(legacy))
                db.execSQL("INSERT INTO secure_items VALUES(2, 'C2|damaged', 54321, 1)")
                val nested = org.json.JSONObject().put("secret", legacy).put("unknown", org.json.JSONArray(listOf("unchanged", 7))).toString()
                db.execSQL("INSERT INTO secure_items VALUES(3, ?, 67890, 0)", arrayOf<Any>(nested))
                db.execSQL("INSERT INTO secure_items VALUES(4, ?, 67890, 0)", arrayOf<Any>(f.manager.encryptData(nested)))
                assertEquals(3, PortableLocalCipherMigration.migrate(db, f.manager, PortableLocalCipherMigration.vaultColumns))
                db.query("SELECT itemData, updatedAt, bitwarden_local_modified FROM secure_items ORDER BY id").use {
                    assertTrue(it.moveToFirst()); assertTrue(it.getString(0).startsWith("MDK|"))
                    assertEquals("legacy-otp-value", f.manager.decryptData(it.getString(0)))
                    assertEquals(12345L, it.getLong(1)); assertEquals(0, it.getInt(2))
                    assertTrue(it.moveToNext()); assertEquals("C2|damaged", it.getString(0))
                    assertEquals(54321L, it.getLong(1)); assertEquals(1, it.getInt(2))
                    repeat(2) { _ ->
                        assertTrue(it.moveToNext())
                        val recovered = org.json.JSONObject(f.manager.decryptData(it.getString(0)))
                        assertEquals("legacy-otp-value", f.manager.decryptData(recovered.getString("secret")))
                        assertEquals("[\"unchanged\",7]", recovered.getJSONArray("unknown").toString())
                    }
                }
                assertEquals(0, PortableLocalCipherMigration.migrate(db, f.manager, PortableLocalCipherMigration.vaultColumns))
            } finally { helper.close() }
        }
    }

    @Test fun interruptedLargeMigrationResumesWithoutLosingRows() = runBlocking {
        Fixture().use { f ->
            f.enroll()
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("monica_data_key_v2_compat", null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val old = "C2|" + Base64.encodeToString(cipher.iv + cipher.doFinal("stress fixture secret".toByteArray()), Base64.NO_WRAP)
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(f.context)
                .name(null).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE secure_items(id INTEGER PRIMARY KEY, itemData TEXT, updatedAt INTEGER)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
            try {
                val db = helper.writableDatabase
                db.beginTransaction()
                try { repeat(1024) { db.execSQL("INSERT INTO secure_items VALUES(?, ?, 123)", arrayOf<Any>(it + 1, old)) }; db.setTransactionSuccessful() }
                finally { db.endTransaction() }
                val job = kotlinx.coroutines.CoroutineScope(coroutineContext).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    PortableLocalCipherMigration.migrate(db, f.manager, PortableLocalCipherMigration.vaultColumns)
                }
                job.cancel(); job.join()
                val first = db.query("SELECT COUNT(*) FROM secure_items WHERE itemData LIKE 'MDK|%'").use { it.moveToFirst(); it.getInt(0) }
                assertTrue("Cancellation must leave a resumable partially migrated batch", first in 1..1023)
                assertEquals(1024 - first, PortableLocalCipherMigration.migrate(db, f.manager, PortableLocalCipherMigration.vaultColumns))
                db.query("SELECT itemData,updatedAt FROM secure_items").use {
                    var count = 0
                    while (it.moveToNext()) { assertEquals("stress fixture secret", f.manager.decryptData(it.getString(0))); assertEquals(123L, it.getLong(1)); count++ }
                    assertEquals(1024, count)
                }
            } finally { helper.close() }
        }
    }

    @Test fun failedConfirmedRecoveryDoesNotRewriteRecoveryEnvelope() {
        Fixture().use { f ->
            f.enroll(); f.damageOriginal()
            val beforeEnvelope = f.envelope.readBytes()
            val beforeOriginal = f.raw.all.toMap()
            val denied = object : ContextWrapper(f.context) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val prefs = f.context.getSharedPreferences(name, mode)
                    if (!name.startsWith("monica_recovered_")) return prefs
                    return object : SharedPreferences by prefs {
                        override fun edit(): SharedPreferences.Editor {
                            val editor = prefs.edit()
                            return object : SharedPreferences.Editor by editor {
                                private var keysetOnly = true
                                override fun commit() = keysetOnly && editor.commit()
                                override fun putString(k: String?, v: String?) = apply {
                                    if (k?.startsWith("__androidx_security_crypto_") != true) keysetOnly = false
                                    editor.putString(k, v)
                                }
                                override fun putBoolean(k: String?, v: Boolean) = apply { editor.putBoolean(k, v) }
                                override fun putInt(k: String?, v: Int) = apply { editor.putInt(k, v) }
                                override fun putLong(k: String?, v: Long) = apply { editor.putLong(k, v) }
                                override fun remove(k: String?) = apply { editor.remove(k) }
                            }
                        }
                    }
                }
            }
            assertFalse(LocalVaultRecovery(denied).recover(f.password))
            assertArrayEquals("A failed new-store write must preserve the original envelope", beforeEnvelope, f.envelope.readBytes())
            assertEquals(beforeOriginal, f.raw.all)
            assertNull(f.recovery.activeStore())
        }
    }

    @Test fun passwordUnlockAfterConvenienceKeyLossDoesNotSilentlyRecreateKey() {
        Fixture().use { f ->
            f.enroll()
            val ciphertext = f.manager.encryptData("synthetic-preserved-vault-secret")
            val prefs = SecurePreferencesStore.open(f.context, SecurePreferencesStore.MONICA).preferences
            val beforeWrapper = prefs.getString("mdk_keystore_blob", null)
            assertNotNull(beforeWrapper)
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry("monica_data_key_v2_compat")
            SecurityManager.clearRuntimeUnlockCache()
            val restored = f.reopened()
            assertTrue(restored.unlockVaultWithPassword(f.password))
            assertFalse("Password unlock is not consent to replace a lost Keystore key", ks.containsAlias("monica_data_key_v2_compat"))
            assertEquals(beforeWrapper, prefs.getString("mdk_keystore_blob", null))
            assertEquals("synthetic-preserved-vault-secret", restored.decryptData(ciphertext))
        }
    }


    @Test fun confirmedRecoveryProviderTimeoutAndCancellationNeverPublishLateOrQueueRetries() = runBlocking {
        for (cancel in listOf(false, true)) Fixture().use { f ->
            f.enroll(); f.damageOriginal()
            val before = f.raw.all.toMap()
            val envelope = f.envelope.readBytes()
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val blocking = object : ContextWrapper(f.context) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val prefs = f.context.getSharedPreferences(name, mode)
                    if (!name.startsWith("monica_recovered_")) return prefs
                    return object : SharedPreferences by prefs {
                        override fun getAll(): MutableMap<String, *> {
                            if (reads.incrementAndGet() == 1) {
                                entered.complete(Unit)
                                check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                            }
                            return prefs.all
                        }
                    }
                }
            }
            val outcome = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val job = launch(kotlinx.coroutines.Dispatchers.Main) {
                outcome.complete(ConfirmedStorageRecovery.recover(blocking, f.password, timeoutMillis = 3_000))
            }
            try {
                kotlinx.coroutines.withTimeout(10_000) { entered.await() }
                if (cancel) { job.cancel(); job.join() }
                else assertFalse(kotlinx.coroutines.withTimeout(4_000) { outcome.await() })
                assertTrue(ConfirmedStorageRecovery.inProgress)
                repeat(12) { assertFalse(ConfirmedStorageRecovery.recover(f.context, f.password, timeoutMillis = 100)) }
                assertEquals(1, reads.get())
                assertEquals(before, f.raw.all)
                assertArrayEquals(envelope, f.envelope.readBytes())
                // activeStore shares the recovery lock, held by the provider worker.
                // Inspect the selector bytes externally instead of taking that lock.
                assertFalse(File(f.root, "files/local-vault-recovery/active-store.json").exists())
            } finally {
                release.countDown()
                job.cancel(); job.join()
                kotlinx.coroutines.withTimeout(5_000) { while (ConfirmedStorageRecovery.inProgress) kotlinx.coroutines.delay(10) }
            }
            assertNull(f.recovery.activeStore())
            assertEquals(before, f.raw.all)
            assertArrayEquals(envelope, f.envelope.readBytes())
        }
    }

    @Test fun confirmedRecoveryWithLostOrReplacedRealStorageKeyPreservesDataAndPreviousStore() = runBlocking {
        for (replace in listOf(false, true)) Fixture().use { f ->
            f.enroll()
            val passwordCipher = f.manager.encryptData("synthetic-real-key-loss-password")
            val otpCipher = f.manager.encryptData("JBSWY3DPEHPK3PXP")
            val attachmentKey = ByteArray(32) { (it + 3).toByte() }
            val wrapped = takagi.ru.monica.attachments.storage.AttachmentKeyVault(f.manager).wrap(attachmentKey)
            f.manager.putProtectedString("passkey_private_key_v1_confirmed", "synthetic-passkey-private-material")
            f.damageOriginal()
            assertTrue(ConfirmedStorageRecovery.recover(f.context, f.password))
            val first = f.recovery.activeStore()!!
            val manager = f.reopened()
            assertTrue(manager.unlockVaultWithPassword(f.password))
            val previous = f.context.getSharedPreferences(first.first, Context.MODE_PRIVATE).all.toMap()
            val beforeEnvelope = f.envelope.readBytes()
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (replace) {
                val generator = javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore")
                generator.init(android.security.keystore.KeyGenParameterSpec.Builder(first.second,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
                generator.generateKey()
            } else ks.deleteEntry(first.second)
            SecurityManager.clearRuntimeUnlockCache()
            val blocked = SecureStorageStartup.prepare(f.context) as SecureStartupResult.Blocked
            assertTrue(blocked.failure.reason in setOf("MISSING_MASTER_KEY", "AUTHENTICATION_FAILED", "UNREADABLE_SECURE_STORAGE"))
            assertArrayEquals(beforeEnvelope, f.envelope.readBytes())
            assertTrue(ConfirmedStorageRecovery.recover(f.context, f.password))
            val second = f.recovery.activeStore()!!
            assertNotEquals(first, second)
            assertEquals(previous, f.context.getSharedPreferences(first.first, Context.MODE_PRIVATE).all)
            assertArrayEquals(beforeEnvelope, f.envelope.readBytes())
            if (!replace) assertFalse(ks.containsAlias(first.second))
            val restored = f.reopened()
            assertTrue(restored.unlockVaultWithPassword(f.password))
            assertEquals("synthetic-real-key-loss-password", restored.decryptData(passwordCipher))
            assertEquals("JBSWY3DPEHPK3PXP", restored.decryptData(otpCipher))
            assertArrayEquals(attachmentKey, takagi.ru.monica.attachments.storage.AttachmentKeyVault(restored).unwrap(wrapped))
            assertEquals("synthetic-passkey-private-material", restored.getProtectedString("passkey_private_key_v1_confirmed"))
            assertFalse(restored.isBiometricEnabled())
        }
    }

    @Test fun deniedPublicationPreservesEnvelopeAndSelectorAfterStaging() {
        Fixture().use { f ->
            f.enroll(); f.damageOriginal()
            val original = f.raw.all.toMap()
            val envelope = f.envelope.readBytes()
            assertFalse(f.recovery.recover(f.password) { throw kotlinx.coroutines.CancellationException("Synthetic withdrawn confirmation") })
            assertEquals(original, f.raw.all)
            assertArrayEquals(envelope, f.envelope.readBytes())
            assertNull(f.recovery.activeStore())
        }
    }


    @Test fun missingEnvelopeCannotCreateReplacementStoreOrKeys() {
        Fixture().use { f ->
            f.enroll(); f.damageOriginal()
            assertTrue(f.envelope.delete())
            val original = f.raw.all.toMap()
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val aliases = java.util.Collections.list(ks.aliases()).sorted()
            assertFalse(f.recovery.available())
            assertFalse(f.recovery.recover(f.password))
            assertEquals(original, f.raw.all)
            assertEquals(aliases, java.util.Collections.list(ks.aliases()).sorted())
            assertNull(f.recovery.activeStore())
        }
    }

}
