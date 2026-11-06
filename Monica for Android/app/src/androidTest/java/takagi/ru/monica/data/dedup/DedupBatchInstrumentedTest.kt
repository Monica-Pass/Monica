package takagi.ru.monica.data.dedup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasskeyRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.utils.AppLocaleStringResolver

/** Separate Room database; failures and cancellation must not leave partial copies or fields. */
@RunWith(AndroidJUnit4::class)
class DedupBatchInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var writer: RepositoryDedupMergeWriter

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        writer = RepositoryDedupMergeWriter(PasswordRepository(db.passwordEntryDao()),
            SecureItemRepository(db.secureItemDao()), CustomFieldRepository(db.customFieldDao()),
            PasskeyRepository(db.passkeyDao()), db, DedupAttachmentSupport(context, db))
    }

    @After fun tearDown() { db.close() }

    @Test fun fieldFailureRollsBackBatchThenRetriesWithoutDuplicateCopies() = runBlocking {
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_test_field BEFORE INSERT ON custom_fields
            WHEN NEW.value = 'value-48'
            BEGIN SELECT RAISE(ABORT, 'synthetic field failure'); END
        """.trimIndent())
        val progress = mutableListOf<DedupMergeExecutionProgress>()
        val result = execute(List(205, ::password)) {
            assertFalse("Progress must follow the transaction commit", db.inTransaction())
            progress += it
        }
        assertEquals(204, result.insertedPasswords)
        assertEquals(1, result.failedItems)
        assertEquals("Synthetic 48", result.failures.single().label)
        assertEquals(AppLocaleStringResolver(context).get(takagi.ru.monica.R.string.dedup_merge_changed_review),
            result.failures.single().reason)
        assertFalse(result.failures.single().reason.contains("synthetic field failure"))
        val entries = db.passwordEntryDao().getActiveEntries().first()
        assertEquals(204, entries.size)
        assertEquals(204, entries.map { it.title }.distinct().size)
        val fields = CustomFieldRepository(db.customFieldDao()).getFieldsByEntryIds(entries.map { it.id })
        for (entry in entries) {
            val field = fields.getValue(entry.id).single()
            assertEquals("value-${entry.username}", field.value)
            assertTrue(field.isProtected)
        }
        assertEquals(205, progress.last().completedItems)
        assertTrue(progress.zipWithNext().all { (a, b) -> a.completedItems < b.completedItems })
    }

    @Test fun cancellationRollsBackCurrentBatchAndItsFieldsButPreservesEarlierCommit() = runBlocking {
        val reachedSecondBatch = CompletableDeferred<Unit>()
        val cancelling = object : DedupMergeWriter by writer {
            override suspend fun writePassword(resolved: DedupResolvedPassword) {
                writer.writePassword(resolved)
                if (resolved.entry.username == "105") {
                    reachedSecondBatch.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        val progress = mutableListOf<DedupMergeExecutionProgress>()
        val job = launch {
            execute(List(205, ::password), cancelling, onProgress = progress::add)
        }
        try { withTimeout(10_000) { reachedSecondBatch.await() } } finally { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        val entries = db.passwordEntryDao().getActiveEntries().first()
        assertEquals(100, entries.size)
        assertTrue(entries.all { it.username.toInt() < 100 })
        assertEquals(100, CustomFieldRepository(db.customFieldDao())
            .getFieldsByEntryIds(entries.map { it.id }).values.sumOf { it.size })
        assertEquals(listOf(100), progress.map { it.completedItems })
    }

    @Test fun secureItemFailureKeepsOtherItemsAndDoesNotReportRolledBackProgress() = runBlocking {
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_test_note BEFORE INSERT ON secure_items
            WHEN NEW.title = 'Synthetic 48'
            BEGIN SELECT RAISE(ABORT, 'synthetic note failure'); END
        """.trimIndent())
        val notes = List(205) { index -> DedupResolvedSecureItem(
            mergeKey = "$index", item = SecureItem(itemType = ItemType.NOTE,
                title = "Synthetic $index", itemData = "{}"), sourceItemIds = listOf(index.toLong() + 1),
            sourceLabels = listOf("Synthetic source"), conflictFields = emptySet()) }
        val progress = mutableListOf<DedupMergeExecutionProgress>()
        val result = DedupMergeExecutor(writer, AppLocaleStringResolver(context)).execute(
            emptyList(), notes, 0, 0, 0, "Local", onProgress = {
                assertFalse(db.inTransaction())
                progress += it
            })
        assertEquals(204, result.insertedSecureItems)
        assertEquals(1, result.failedItems)
        val stored = db.secureItemDao().getAllItems().first()
        assertEquals(204, stored.size)
        assertEquals(204, stored.map { it.title }.distinct().size)
        assertEquals(205, progress.last().completedItems)
    }

    private suspend fun execute(
        entries: List<DedupResolvedPassword>, selectedWriter: DedupMergeWriter = writer,
        onProgress: (DedupMergeExecutionProgress) -> Unit = {}
    ) = DedupMergeExecutor(selectedWriter, AppLocaleStringResolver(context)).execute(
        entries, emptyList(), 0, 0, 0, "Local", onProgress = onProgress)

    private fun password(index: Int) = DedupResolvedPassword(
        mergeKey = "$index", entry = PasswordEntry(title = "Synthetic $index",
            website = "https://fixture-$index.invalid", username = "$index", password = "synthetic"),
        customFields = listOf(CustomField(entryId = 0, title = "Recovery", value = "value-$index", isProtected = true)),
        sourceEntryIds = listOf(index.toLong() + 1), sourceLabels = listOf("Synthetic source"), conflictFields = emptySet())
}
