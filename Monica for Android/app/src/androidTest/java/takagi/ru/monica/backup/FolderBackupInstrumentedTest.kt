package takagi.ru.monica.backup

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.edit
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.WebDavHelper
import java.io.File

class FolderBackupInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val authority = instrumentation.context.packageName + ".folder-backups"
    private val tree = DocumentsContract.buildTreeDocumentUri(authority, "folder332")
    private val destination get() = SafBackupDestination(context, tree)
    private val password = "Synthetic folder archive 332!"
    private var entryId = 0L
    private lateinit var security: SecurityManager
    private lateinit var db: PasswordDatabase

    private fun fixture(fault: String = "", reset: Boolean = false, grant: Boolean = false) {
        context.contentResolver.call(Uri.parse("content://$authority"), "fixture", null, Bundle().apply {
            putString("fault", fault); putBoolean("reset", reset)
            if (grant) putString("target", context.packageName)
        })
        if (grant) context.contentResolver.takePersistableUriPermission(tree, SafBackupDestination.GRANTS)
    }

    @Before fun setup() = runBlocking {
        check(context.packageName.contains("folderbackup")) { "Isolated task package required" }
        withContext(Dispatchers.IO) {
            security = SecurityManager(context)
            if (!security.isMasterPasswordSet()) assertTrue(security.setMasterPassword("Synthetic local master 332!"))
            assertTrue(security.unlockVaultWithPassword("Synthetic local master 332!"))
            SessionManager.markUnlocked()
            FolderBackupRunner.foreground = true
            fixture(reset = true, grant = true)
            security.removeProtectedString("folder_backup_v1")
            context.getSharedPreferences("folder_backup_schedule", 0).edit().clear().commit()
            FolderBackupStore(context).configure(tree.toString(), "Synthetic backup folder", password, false, true, 2)
            FolderBackupScheduler.configure(context, FolderBackupStore(context).config())
            db = PasswordDatabase.getDatabase(context)
            entryId = db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Folder fixture",
                username = "synthetic", password = security.encryptData("Synthetic record secret"),
                website = "https://example.invalid"))
        }
    }

    @After fun cleanup(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            if (entryId != 0L) db.passwordEntryDao().getPasswordEntryById(entryId)?.let { db.passwordEntryDao().deletePasswordEntry(it) }
            fixture(reset = true)
            security.removeProtectedString("folder_backup_v1")
            context.getSharedPreferences("folder_backup_schedule", 0).edit().clear().commit()
            runCatching { context.contentResolver.releasePersistableUriPermission(tree, SafBackupDestination.GRANTS) }
        }
    }

    @Test fun realEncryptedArchiveRoundTripsWithoutWebDavConfiguration() = runBlocking {
        FolderBackupRunner.run(context, manual = true)
        val record = FolderBackupStore(context).records().single()
        assertTrue(record.complete && record.permanent)
        assertTrue(record.name.endsWith(".enc.zip"))
        val file = File.createTempFile("folder-restore-", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(Uri.parse(record.uri))!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            val helper = WebDavHelper(context)
            assertTrue(helper.restoreFromBackupFile(file, "wrong", restoreMonicaConfig = false, importDataOnly = true).isFailure)
            val restored = helper.restoreFromBackupFile(file, password, restoreMonicaConfig = false, importDataOnly = true).getOrThrow()
            assertEquals("Synthetic record secret", restored.content.passwords.single { it.title == "Folder fixture" }.password)
            assertEquals("Synthetic record secret", security.decryptData(db.passwordEntryDao().getPasswordEntryById(entryId)!!.password))
        } finally { file.delete() }
    }

    @Test fun writeFailurePreservesExistingBackupAndRecordsIncompleteAttempt() = runBlocking {
        FolderBackupRunner.run(context, manual = true)
        val old = FolderBackupStore(context).records().single()
        fixture("write-denied")
        assertTrue(runCatching { FolderBackupRunner.run(context, manual = true) }.isFailure)
        fixture()
        destination.verify(old) {}
        val records = FolderBackupStore(context).records()
        assertEquals(1, records.count { it.complete })
        assertEquals(1, records.count { !it.complete })
    }

    @Test fun corruptReadbackIsNotPublishedAndOldBackupSurvives() = runBlocking {
        FolderBackupRunner.run(context, manual = true)
        val old = FolderBackupStore(context).records().single()
        fixture("read-corrupt")
        assertTrue(runCatching { FolderBackupRunner.run(context, manual = true) }.isFailure)
        fixture()
        destination.verify(old) {}
        assertEquals(1, FolderBackupStore(context).records().count { it.complete })
    }

    @Test fun nullAndLoadingDirectoryListsNeverBecomeEmptySuccess() = runBlocking {
        FolderBackupRunner.run(context, manual = true)
        val before = FolderBackupStore(context).records()
        for (fault in listOf("null-list", "partial-list")) {
            fixture(fault)
            assertTrue(runCatching { FolderBackupRunner.run(context, manual = true) }.isFailure)
            assertEquals(before, FolderBackupStore(context).records())
        }
        fixture()
        destination.verify(before.single()) {}
    }

    @Test fun revokedFolderGrantDoesNotChangeLedgerOrExistingFiles() = runBlocking {
        FolderBackupRunner.run(context, manual = true)
        val before = FolderBackupStore(context).records()
        context.contentResolver.releasePersistableUriPermission(tree, SafBackupDestination.GRANTS)
        assertTrue(runCatching { FolderBackupRunner.run(context, manual = true) }.exceptionOrNull() is SecurityException)
        assertEquals(before, FolderBackupStore(context).records())
        fixture(grant = true)
        destination.verify(before.single()) {}
    }

    @Test fun lockedAndBackgroundRequestsNeverCreateDocuments() = runBlocking {
        SessionManager.markLocked()
        assertTrue(runCatching { FolderBackupRunner.run(context, true) }.exceptionOrNull() is FolderBackupDeferred)
        assertTrue(destination.list().isEmpty())
        assertTrue(security.unlockVaultWithPassword("Synthetic local master 332!"))
        SessionManager.markUnlocked()
        FolderBackupRunner.foreground = false
        assertTrue(runCatching { FolderBackupRunner.run(context, true) }.exceptionOrNull() is FolderBackupDeferred)
        FolderBackupRunner.foreground = true
        assertTrue(destination.list().isEmpty())
    }

    @Test fun unreadableRecordPreventsArchivePublication() = runBlocking {
        val row = db.passwordEntryDao().getPasswordEntryById(entryId)!!
        db.passwordEntryDao().updatePasswordEntry(row.copy(password = "MDK|invalid-ciphertext"))
        try {
            assertTrue(runCatching { FolderBackupRunner.run(context, true) }.isFailure)
            assertTrue(destination.list().isEmpty())
            assertTrue(FolderBackupStore(context).records().isEmpty())
        } finally { db.passwordEntryDao().updatePasswordEntry(row) }
    }

    @Test fun cancellationDuringWriteDoesNotDeletePreviousDocument() = runBlocking {
        FolderBackupRunner.run(context, true)
        val old = FolderBackupStore(context).records().single()
        val file = File.createTempFile("folder-cancel-", ".zip", context.cacheDir).apply { writeBytes(ByteArray(300_000)) }
        try {
            val (uri, _) = destination.create(false, destination.list().map { it.uri }.toSet())
            var checks = 0
            assertTrue(runCatching { destination.write(uri, file) {
                if (++checks > 2) throw CancellationException("Synthetic cancellation")
            } }.exceptionOrNull() is CancellationException)
            destination.verify(old) {}
            assertEquals(listOf(old), FolderBackupStore(context).records())
        } finally { file.delete() }
    }


    @Test fun providerCleanupFailurePreservesBackupsThenRetriesOnlyOwnedOldGenerations() = runBlocking {
        FolderBackupRunner.run(context, true)
        val store = FolderBackupStore(context)
        val permanent = store.records().single()
        val file = File.createTempFile("folder-retention-", ".zip", context.cacheDir)
        try {
            context.contentResolver.openInputStream(Uri.parse(permanent.uri))!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            val old = (1..4).map { index ->
                val (uri, name) = destination.create(false, destination.list().map { it.uri }.toSet())
                destination.write(uri, file) {}
                permanent.copy(uri = uri.toString(), name = name, permanent = false,
                    createdAt = System.currentTimeMillis() - FolderBackupPolicy.CLOUD_GRACE_MS - index * 60_000L)
                    .also { store.saveRecord(it) }
            }
            val (foreign, _) = destination.create(false, destination.list().map { it.uri }.toSet())
            destination.write(foreign, file) {} // Not in this installation's ledger.
            fixture("delete-denied")
            FolderBackupRunner.run(context, true)
            assertEquals(takagi.ru.monica.R.string.folder_backup_cleanup_deferred, FolderBackupRunner.status.value)
            old.forEach { destination.verify(it) {} }
            fixture()
            FolderBackupRunner.run(context, true)
            val documents = destination.list().map { it.uri }.toSet()
            assertTrue(permanent.uri in documents && foreign.toString() in documents)
            assertTrue(old.take(2).all { it.uri in documents })
            assertTrue(old.drop(2).none { it.uri in documents })
            assertEquals(2, store.records().count { !it.permanent })
        } finally { file.delete() }
    }

    @Test fun workManagerDefersWhileLockedThenCreatesVerifiedAutomaticArchive(): Unit = runBlocking {
        val store = FolderBackupStore(context)
        store.configure(tree.toString(), "Automatic fixture", password, true, true, 2)
        FolderBackupScheduler.currentRevision(context)
        val prefs = context.getSharedPreferences("folder_backup_schedule", 0)
        check(prefs.edit().putBoolean("automatic", true).putBoolean("changes", true)
            .putLong("changed", 0).putLong("last", 0).putLong("completed", 0).commit())
        val manager = androidx.work.WorkManager.getInstance(context)
        suspend fun runWorker() {
            val request = androidx.work.OneTimeWorkRequestBuilder<FolderBackupWorker>().build()
            manager.enqueue(request)
            val finished = withTimeout(45_000L) { manager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true } }
            assertEquals(androidx.work.WorkInfo.State.SUCCEEDED, finished?.state)
        }
        SessionManager.markLocked()
        runWorker()
        assertTrue(store.records().isEmpty())
        assertEquals(0, prefs.getLong("last", 0))
        assertTrue(security.unlockVaultWithPassword("Synthetic local master 332!"))
        SessionManager.markUnlocked()
        FolderBackupRunner.foreground = true
        runWorker()
        val record = store.records().single()
        assertTrue(record.complete && !record.permanent)
        destination.verify(record) {}
        assertNull(FolderBackupScheduler.dueDelay(context))
    }

    @Test fun changesDisabledWaitsForPeriodicBackupEvenWithNewRevision() {
        val prefs = context.getSharedPreferences("folder_backup_schedule", 0)
        prefs.edit().putBoolean("automatic", true).putBoolean("changes", false)
            .putLong("revision", 9).putLong("completed", 8).putLong("last", System.currentTimeMillis()).commit()
        assertNull(FolderBackupScheduler.dueDelay(context))
        prefs.edit().putLong("last", System.currentTimeMillis() - FolderBackupPolicy.PERIOD_MS - 1).commit()
        assertNotNull(FolderBackupScheduler.dueDelay(context))
    }

    @Test fun unreadableGeneratorHistoryBlocksCompleteBackupInsteadOfExportingEmptyHistory(): Unit = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val historyStore = Class.forName("takagi.ru.monica.data.PasswordHistoryManagerKt")
            .getDeclaredMethod("getPasswordHistoryDataStore", android.content.Context::class.java)
            .apply { isAccessible = true }.invoke(null, context) as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
        val key = androidx.datastore.preferences.core.stringPreferencesKey("password_generation_history")
        val original = historyStore.data.first()[key]
        try {
            historyStore.edit { it[key] = "MDK|invalid-history" }
            assertTrue(runCatching { FolderBackupRunner.run(context, true) }.exceptionOrNull() is FolderBackupIncomplete)
            assertTrue(destination.list().isEmpty())
            assertTrue(FolderBackupStore(context).records().isEmpty())
        } finally {
            historyStore.edit {
                if (original == null) it.remove(key) else it[key] = original
            }
        }
    }

    @Test fun completionDoesNotLoseMutationThatArrivedDuringBackup() {
        FolderBackupScheduler.currentRevision(context) // Drain the setup insert before setting exact counter fixtures.
        val prefs = context.getSharedPreferences("folder_backup_schedule", 0)
        prefs.edit().putBoolean("automatic", true).putLong("revision", 4).putLong("completed", 2).commit()
        FolderBackupScheduler.completed(context, 3)
        assertTrue("Completion must preserve every later invalidation", FolderBackupScheduler.revision(context) >= 4)
        assertNotNull(FolderBackupScheduler.dueDelay(context))
        FolderBackupScheduler.completed(context, FolderBackupScheduler.currentRevision(context))
        assertNull(FolderBackupScheduler.dueDelay(context))
    }
}
