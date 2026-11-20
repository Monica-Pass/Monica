package takagi.ru.monica.credentialexchange

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.ProjectCredentialGroup
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.*
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.*
import java.io.IOException

/** Real Room writes with synthetic data; faults do not replace the save implementation. */
class LocalCredentialCreateAtomicityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var model: PasswordViewModel
    private lateinit var security: SecurityManager
    private var fieldWrites = 0
    private var failFieldWrite = -1
    private var failAfterFields = false
    private var inserts = 0
    private var lockAfterInsert = false
    private var cancelAfterInsert = false
    private var readDuringInsert: (suspend () -> Unit)? = null
    private val inserted = CompletableDeferred<Unit>()
    private val common = PasswordEntry(title = "Synthetic local project", username = "same-user", password = "", website = "https://example.invalid")
    private val fields = listOf(CustomFieldDraft(title = "Synthetic field", value = "kept", isProtected = true))
    private val targets = listOf(StorageTarget.MonicaLocal(null))
    private var previouslyUnlocked = false

    @Before fun setup() = runBlocking {
        assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready)
        security = SecurityManager(context)
        previouslyUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val passwords = db.passwordEntryDao()
        val passwordDao = object : PasswordEntryDao by passwords {
            override suspend fun insertPasswordEntry(entry: PasswordEntry): Long {
                val id = passwords.insertPasswordEntry(entry)
                inserts++
                inserted.complete(Unit)
                readDuringInsert?.invoke()
                if (lockAfterInsert) SessionManager.markLocked()
                if (cancelAfterInsert) currentCoroutineContext().cancel()
                return id
            }
        }
        val actual = db.customFieldDao()
        val fieldDao = object : CustomFieldDao by actual {
            override suspend fun replaceFieldsForEntry(entryId: Long, newFields: List<CustomField>) {
                fieldWrites++
                if (fieldWrites == failFieldWrite && !failAfterFields) throw IOException("Synthetic field failure")
                actual.replaceFieldsForEntry(entryId, newFields)
                if (fieldWrites == failFieldWrite && failAfterFields) throw IOException("Synthetic failure after field write")
            }
        }
        model = PasswordViewModel(PasswordRepository(passwordDao, categoryDao = db.categoryDao(), passwordHistoryDao = db.passwordHistoryDao()), security,
            customFieldRepository = CustomFieldRepository(fieldDao), strings = AppLocaleStringResolver(context))
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        db.close()
        if (previouslyUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
    }

    private suspend fun rows() = db.passwordEntryDao().getAllPasswordEntriesSync()
    private suspend fun save(passwords: List<String> = listOf("same-secret", "same-secret"), ids: List<Long> = emptyList(),
        groups: List<ProjectCredentialGroup.Group>? = null, destinations: List<StorageTarget> = targets): Long? {
        val done = CompletableDeferred<Long?>()
        model.savePasswordsAcrossTargets(ids, common, passwords, destinations, fields, projectCredentials = groups,
            onComplete = { done.complete(it) })
        return withTimeout(30_000) { done.await() }
    }
    private suspend fun batch(): List<SavedPasswordCredential> {
        val done = CompletableDeferred<List<SavedPasswordCredential>>()
        model.saveCredentialsAcrossTargets(common,
            listOf(PasswordCredentialDraft("same-user", "same-secret"), PasswordCredentialDraft("same-user", "same-secret")),
            targets, fields, onComplete = { done.complete(it) })
        return withTimeout(30_000) { done.await() }
    }
    private fun groups() = List(2) { ProjectCredentialGroup.Group(username = "same-user",
        passwords = listOf(ProjectCredentialGroup.Password(value = "same-secret"))) }

    @Test fun realEditorSaveKeepsProjectIdentityAndPasswordHistoryWithoutTimeline() = runBlocking {
        val oldSecrets = listOf("old-one", "old-two", "old-three")
        assertNotNull(save(passwords = oldSecrets))
        val original = rows().sortedBy { it.id }
        val identities = original.map { it.id to it.passwordProjectKey() }
        val newSecrets = listOf("new-one", "new-two", "new-three")
        assertNotNull(save(passwords = newSecrets, ids = original.map { it.id }))
        assertEquals(newSecrets, rows().sortedBy { it.id }.map { security.decryptData(it.password) })
        assertEquals(identities, rows().sortedBy { it.id }.map { it.id to it.passwordProjectKey() })
        original.forEachIndexed { index, row ->
            assertTrue(db.passwordHistoryDao().getHistoryByEntryIdSync(row.id).any { security.decryptData(it.password) == oldSecrets[index] })
        }
        assertEquals(0, db.operationLogDao().getLogCount())
    }

    @Test fun failedGroupedFieldsLeaveNoRowsAndRetryCreatesOneProject() = runBlocking {
        checkGroupedFailure(afterFields = false)
    }
    @Test fun failureAfterGroupedFieldsWriteRollsBackRowsAndFields() = runBlocking {
        checkGroupedFailure(afterFields = true)
    }
    private suspend fun checkGroupedFailure(afterFields: Boolean) {
        failFieldWrite = 1; failAfterFields = afterFields
        assertNull(save())
        assertEquals(2, inserts)
        assertTrue("A failed save must not leave password rows", rows().isEmpty())
        assertTrue(db.customFieldDao().getAllFieldsSync().isEmpty())
        failFieldWrite = -1
        assertNotNull(save())
        assertEquals(2, rows().size)
        assertEquals(1, rows().map { it.passwordProjectKey() }.distinct().size)
        assertTrue(rows().all { security.decryptData(it.password) == "same-secret" })
    }
    @Test fun failedFirstBatchFieldsLeaveNoRowsAndRetryCreatesOnlyRequestedProjects() = runBlocking {
        checkBatchFailure(1)
    }
    @Test fun failedSecondBatchFieldsRollBackEarlierCredentialAndRetryCreatesOnlyRequestedProjects() = runBlocking {
        checkBatchFailure(2)
    }
    private suspend fun checkBatchFailure(index: Int) {
        failFieldWrite = index
        assertTrue(batch().isEmpty())
        assertEquals(index, inserts)
        assertTrue("The entire local batch must roll back", rows().isEmpty())
        assertTrue(db.customFieldDao().getAllFieldsSync().isEmpty())
        failFieldWrite = -1
        assertEquals(2, batch().size)
        assertEquals(2, rows().size)
        assertEquals(2, rows().map { it.passwordProjectKey() }.distinct().size)
    }
    @Test fun identicalBatchInputsRemainTwoIndependentProjects() = runBlocking {
        assertEquals(2, batch().size)
        assertEquals(2, rows().map { it.passwordProjectKey() }.distinct().size)
    }
    @Test fun failedSecondLocalFolderRollsBackEarlierDestinationAndRetryCreatesOnlyTwoCopies() = runBlocking {
        val categories = listOf("Synthetic A", "Synthetic B").map { db.categoryDao().insert(Category(name = it)) }
        val destinations = categories.map { StorageTarget.MonicaLocal(it) }
        failFieldWrite = 2
        assertNull(save(destinations = destinations))
        assertEquals(4, inserts)
        assertTrue(rows().isEmpty())
        assertTrue(db.customFieldDao().getAllFieldsSync().isEmpty())
        failFieldWrite = -1
        assertNotNull(save(destinations = destinations))
        assertEquals(4, rows().size)
        assertEquals(2, rows().map { it.passwordProjectKey() }.distinct().size)
        assertTrue(categories.all { category -> rows().count { it.categoryId == category } == 2 })
        val created = rows().associate { it.id to it.passwordProjectKey() }
        // Start on the second folder, as when reopening that copy from its own list.
        val reopenedIds = rows().filter { it.categoryId == categories.last() }.map { it.id }
        assertNotNull(save(passwords = listOf("edited-one", "edited-two"), ids = reopenedIds,
            destinations = destinations))
        assertEquals(created, rows().associate { it.id to it.passwordProjectKey() })
        for (category in categories) {
            assertEquals(listOf("edited-one", "edited-two"), rows().filter { it.categoryId == category }
                .sortedBy { it.id }.map { security.decryptData(it.password) })
        }
    }
    @Test fun identicalProjectCredentialsKeepOneProjectAndOriginalIdsAcrossEdit() = runBlocking {
        assertNotNull(save(groups = groups()))
        val original = rows()
        val loaded = ProjectCredentialGroup.restoreForEditing(original.map { it.copy(password = security.decryptData(it.password)) },
            original.associate { it.id to db.customFieldDao().getFieldsByEntryIdSync(it.id).map(CustomFieldDraft::fromCustomField) })
        assertEquals(2, loaded.size)
        assertNotNull(save(ids = original.map { it.id }, groups = loaded))
        assertEquals(original.map { it.id }.toSet(), rows().map { it.id }.toSet())
        assertEquals(1, rows().map { it.passwordProjectKey() }.distinct().size)
    }
    @Test fun projectCredentialFieldFailureStillRollsBackAllCreatedRows() = runBlocking {
        failFieldWrite = 2
        assertNull(save(groups = groups()))
        assertTrue(rows().isEmpty())
        failFieldWrite = -1
        assertNotNull(save(groups = groups()))
        assertEquals(1, rows().map { it.passwordProjectKey() }.distinct().size)
    }
    @Test fun failedLocalEditPreservesOriginalCiphertextIdsAndUnknownFields() = runBlocking {
        assertNotNull(save())
        val original = rows()
        val originalFields = db.customFieldDao().getAllFieldsSync()
        failFieldWrite = fieldWrites + 1; failAfterFields = true
        assertNull(save(passwords = listOf("changed-secret", "changed-secret"), ids = original.map { it.id }))
        assertEquals(original.sortedBy { it.id }, rows().sortedBy { it.id })
        assertEquals(originalFields, db.customFieldDao().getAllFieldsSync())
    }
    @Test fun lockingAfterActualInsertRollsBackLocalSave() = runBlocking {
        lockAfterInsert = true
        assertNull(save(passwords = listOf("same-secret")))
        assertEquals(1, inserts)
        assertTrue(rows().isEmpty())
        assertTrue(db.customFieldDao().getAllFieldsSync().isEmpty())
    }
    @Test fun cancellationAfterActualInsertRollsBackWithoutSuccessCallback() = runBlocking {
        cancelAfterInsert = true
        val callback = CompletableDeferred<Long?>()
        val scopeJob = requireNotNull(model.viewModelScope.coroutineContext[Job])
        val previousJobs = scopeJob.children.toSet()
        model.savePasswordsAcrossTargets(emptyList(), common, listOf("same-secret"), targets, fields,
            onComplete = { callback.complete(it) })
        val saveJobs = scopeJob.children.filterNot { it in previousJobs }.toList()
        withTimeout(30_000) { inserted.await() }
        withTimeout(30_000) { saveJobs.joinAll() }
        // An independent Room read waits for the cancelled write transaction to finish.
        assertTrue(withTimeout(30_000) { rows() }.isEmpty())
        assertTrue(db.customFieldDao().getAllFieldsSync().isEmpty())
        assertFalse(callback.isCompleted)
    }
    @Test fun localSaveQueuedBeforeLockCannotInsertAfterLock() = runBlocking {
        checkQueuedLock(independent = false)
    }
    @Test fun localBatchQueuedBeforeLockCannotInsertAfterLock() = runBlocking {
        checkQueuedLock(independent = true)
    }
    private suspend fun checkQueuedLock(independent: Boolean) = coroutineScope {
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rejected = CompletableDeferred<Boolean>()
        // Hold Main only until the save is queued and the lock generation changes.
        val blocker = launch(Dispatchers.Main) { ready.complete(Unit); runBlocking { release.await() } }
        try {
            ready.await()
            if (independent) {
                model.saveCredentialsAcrossTargets(common, listOf(PasswordCredentialDraft("same-user", "same-secret")),
                    targets, fields, onComplete = { rejected.complete(it.isEmpty()) })
            } else {
                model.savePasswordsAcrossTargets(emptyList(), common, listOf("same-secret"), targets, fields,
                    onComplete = { rejected.complete(it == null) })
            }
            SessionManager.markLocked()
            release.complete(Unit)
            assertTrue(withTimeout(30_000) { rejected.await() })
            assertEquals(0, inserts)
            assertTrue(rows().isEmpty())
            assertTrue(db.customFieldDao().getAllFieldsSync().isEmpty())
        } finally { release.complete(Unit); blocker.cancelAndJoin() }
    }
    @Test fun readersSeeOnlyCompleteGroupedPasswordsAndFields() = runBlocking {
        checkReaderVisibility(batch = false)
    }
    @Test fun readersSeeOnlyCompleteLocalBatchAndFields() = runBlocking {
        checkReaderVisibility(batch = true)
    }
    private suspend fun checkReaderVisibility(batch: Boolean) {
        val reader = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val observed = CompletableDeferred<Pair<List<PasswordEntry>, List<CustomField>>>()
        val started = CompletableDeferred<Unit>()
        readDuringInsert = {
            readDuringInsert = null
            reader.launch {
                started.complete(Unit)
                val entries = rows()
                observed.complete(entries to db.customFieldDao().getAllFieldsSync())
            }
            started.await()
            withTimeoutOrNull(300) { observed.await() }
            Unit
        }
        try {
            if (batch) assertEquals(2, batch().size) else assertNotNull(save())
            val (entries, savedFields) = withTimeout(10_000) { observed.await() }
            assertEquals("Readers must not see a partial local save", 2, entries.size)
            assertEquals(if (batch) 2 else 1, savedFields.size)
            assertTrue(savedFields.all { it.value == "kept" && it.isProtected })
        } finally { reader.cancel() }
    }
}
