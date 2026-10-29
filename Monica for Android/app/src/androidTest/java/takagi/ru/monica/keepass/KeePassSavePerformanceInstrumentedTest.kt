package takagi.ru.monica.keepass

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.Meta
import java.security.MessageDigest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.rustcore.RustBitwardenKdfCore
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.KeePassKdbxService

/** Timings are observations, not device-dependent pass/fail thresholds. */
@RunWith(AndroidJUnit4::class)
class KeePassSavePerformanceInstrumentedTest {
    @Test
    fun groupedPasswordEditMeasuresCommitsAndPreservesOtherEntries() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, uri ->
            val snapshot = service.loadWorkspace(id).getOrThrow()
            assertTrue(model.applyKeePassWorkspaceSnapshot(id, snapshot, true, false))
            val originals = room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id)
                .filter { it.username in setOf("user-0", "user-1", "user-2") }.sortedBy { it.username }
            assertEquals(3, originals.size)
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            fun commits(): Int = resolver.query(uri, arrayOf("commits"), null, null, null)!!.use {
                it.moveToFirst()
                it.getInt(0)
            }
            val before = commits()
            val completed = CompletableDeferred<Long?>()
            val start = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) {
                model.saveGroupedPasswords(
                    originals.map { it.id }, originals.first().copy(notes = "Grouped edit probe"),
                    listOf("secret-0", "secret-1", "secret-2"),
                    onComplete = { completed.complete(it) },
                )
            }
            assertNotNull(withTimeout(30_000) { completed.await() })
            record("three_password_group_save", SystemClock.elapsedRealtime() - start)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("stream", "KDBX_SAVE_PERF stage=three_password_group_save commits=${commits() - before}\n")
            })
            KeePassKdbxService.invalidateProcessCache(id)
            val reopened = service.readPasswordEntries(id).getOrThrow()
            assertEquals(300, reopened.size)
            originals.forEach { original ->
                val saved = reopened.single { it.entryUuid == original.keepassEntryUuid }
                assertEquals("Grouped edit probe", saved.notes)
                assertEquals("secret-${original.username.removePrefix("user-")}", saved.password)
            }
            snapshot.passwords.filter { source -> originals.none { it.keepassEntryUuid == source.entryUuid } }
                .forEach { source ->
                    val saved = reopened.single { it.entryUuid == source.entryUuid }
                    assertEquals(source.username, saved.username)
                    assertEquals(source.password, saved.password)
                    assertEquals(source.notes, saved.notes)
                }
        }
    }

    @Test
    fun passwordListEditCommitsKdbxBeforePublishingAndMeasuresEachBoundary() = runBlocking {
        // Reuse the isolated 300-entry external-document fixture, with Argon2d,
        // 32 MiB, eight iterations and two lanes. No personal database is touched.
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, _ ->
            val coldStart = SystemClock.elapsedRealtime()
            val snapshot = service.loadWorkspace(id).getOrThrow()
            record("cold_workspace", SystemClock.elapsedRealtime() - coldStart)
            assertEquals(300, snapshot.passwords.size)

            val warmStart = SystemClock.elapsedRealtime()
            repeat(3) { assertEquals(300, service.loadWorkspace(id).getOrThrow().passwords.size) }
            record("warm_workspace_average", (SystemClock.elapsedRealtime() - warmStart) / 3)

            val projectStart = SystemClock.elapsedRealtime()
            assertTrue(model.applyKeePassWorkspaceSnapshot(id, snapshot, true, false))
            record("initial_room_projection", SystemClock.elapsedRealtime() - projectStart)
            val unchangedStart = SystemClock.elapsedRealtime()
            assertTrue(model.applyKeePassWorkspaceSnapshot(id, snapshot, true, false))
            record("unchanged_room_projection", SystemClock.elapsedRealtime() - unchangedStart)

            val original = room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id)
                .single { it.username == "user-0" }
            val editStart = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) {
                model.updatePasswordEntry(original.copy(password = "secret-0", notes = "List edit probe"))
            }
            withTimeout(30_000) {
                room.passwordEntryDao().getPasswordEntriesByKeePassDatabase(id)
                    .first { entries -> entries.any { it.id == original.id && it.notes == "List edit probe" } }
            }
            record("list_edit_until_published", SystemClock.elapsedRealtime() - editStart)
            val edited = service.readPasswordEntries(id).getOrThrow()
                .single { it.entryUuid == original.keepassEntryUuid }
            assertEquals("List edit probe", edited.notes)
            assertEquals("secret-0", edited.password)

            val savedRow = room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id)
                .single { it.id == original.id }
            val nativeStart = SystemClock.elapsedRealtime()
            service.updatePasswordEntry(id, savedRow.copy(notes = "Direct KDBX probe"), { "secret-0" })
                .getOrThrow()
            record("direct_kdbx_save", SystemClock.elapsedRealtime() - nativeStart)

            KeePassKdbxService.invalidateProcessCache(id)
            val reopenStart = SystemClock.elapsedRealtime()
            val reopened = service.readPasswordEntries(id).getOrThrow()
            record("cold_reopen_after_save", SystemClock.elapsedRealtime() - reopenStart)
            assertEquals(300, reopened.size)
            assertEquals("Direct KDBX probe", reopened.single { it.entryUuid == original.keepassEntryUuid }.notes)
            reopened.forEach { entry -> assertEquals("secret-${entry.username.removePrefix("user-")}", entry.password) }
            // Actual native edit changed notes but not the password. Publishing that
            // revision must retain the current ciphertext rather than encrypting it again.
            val afterNativeEdit = service.loadWorkspace(id).getOrThrow()
            assertTrue(model.applyKeePassWorkspaceSnapshot(id, afterNativeEdit, true, false))
            val projected = room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id)
                .single { it.id == original.id }
            assertEquals("Direct KDBX probe", projected.notes)
            assertEquals("Unchanged password ciphertext should survive a metadata-only projection", savedRow.password, projected.password)
        }
    }

    @Test
    fun projectionStillUpgradesCompatCiphertextAfterMasterPasswordEnrollment() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "kdbx-projection-security-${UUID.randomUUID()}-"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val names = mutableSetOf<String>()
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
                dataDir = root.absolutePath
            }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                names.add(name)
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        SecurityManager.clearRuntimeUnlockCache()
        try {
            val security = SecurityManager(isolated)
            KeePassDeleteInvestigationTest().scenario(securityOverride = security) { model, service, id, room, _ ->
                val snapshot = service.loadWorkspace(id).getOrThrow()
                assertTrue(model.applyKeePassWorkspaceSnapshot(id, snapshot, true, false))
                val existing = room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id)
                    .single { it.username == "user-0" }
                assertTrue(existing.password.startsWith("C2|"))
                assertTrue(security.setMasterPassword("Synthetic projection master password"))
                service.updatePasswordEntry(id, existing.copy(notes = "Migrate protected projection"), { "secret-0" })
                    .getOrThrow()
                assertTrue(model.applyKeePassWorkspaceSnapshot(id, service.loadWorkspace(id).getOrThrow(), true, false))
                val migrated = room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id)
                    .single { it.id == existing.id }
                assertEquals("Migrate protected projection", migrated.notes)
                assertTrue("Do not reuse device-only ciphertext after enabling password recovery", migrated.password.startsWith("MDK|"))
                assertEquals("secret-0", security.decryptData(migrated.password))
            }
        } finally {
            SecurityManager.clearRuntimeUnlockCache()
            names.forEach { base.deleteSharedPreferences(prefix + it) }
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile && root.name == prefix)
            root.deleteRecursively()
        }
    }

    @Test
    fun kotpassAndExistingNativeArgon2idProduceIdenticalKeysAtTheSameCost() {
        val credentials = Credentials.from(EncryptedValue.fromString("Synthetic KDBX KDF probe"))
        val created = KeePassDatabase.Ver4x.create("Probe", Meta(), credentials)
        val salt = ByteArray(32) { (it + 1).toByte() }
        val parameters = KdfParameters.Argon2(
            variant = KdfParameters.Argon2.Variant.Argon2id,
            salt = okio.ByteString.of(*salt),
            parallelism = 2U, memory = 32UL * 1024UL * 1024UL,
            iterations = 8U, version = 0x13U, secretKey = null, associatedData = null,
        )
        val header = created.header.copy(kdfParameters = parameters)
        // Kotpass 0.10.0 exposes no KDF-provider hook. Reflection is test-only:
        // measure its actual transform rather than a separate approximate algorithm.
        val transformClass = Class.forName("app.keemobile.kotpass.cryptography.KeyTransform")
        val transform = transformClass.getMethod("transformedKey", DatabaseHeader::class.java, Credentials::class.java)
        val instance = transformClass.getField("INSTANCE").get(null)
        val passphrase = requireNotNull(credentials.passphrase).getBinary()
        val composite = MessageDigest.getInstance("SHA-256").digest(passphrase)
        try {
            repeat(3) { iteration ->
                val kotlinStart = SystemClock.elapsedRealtime()
                val expected = transform.invoke(instance, header, credentials) as ByteArray
                record("kotpass_argon2id_$iteration", SystemClock.elapsedRealtime() - kotlinStart)
                try {
                    val nativeStart = SystemClock.elapsedRealtime()
                    val actual = RustBitwardenKdfCore.deriveArgon2id(composite, salt, 8, 32 * 1024, 2)
                    record("native_argon2id_$iteration", SystemClock.elapsedRealtime() - nativeStart)
                    assertNotNull("The x86_64 validation build includes the native KDF", actual)
                    try { assertArrayEquals(expected, actual) } finally { actual?.fill(0) }
                } finally { expected.fill(0) }
            }
        } finally {
            passphrase.fill(0)
            composite.fill(0)
            salt.fill(0)
        }
    }

    private fun record(stage: String, milliseconds: Long) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "KDBX_SAVE_PERF stage=$stage ms=$milliseconds\n")
        })
    }
}
