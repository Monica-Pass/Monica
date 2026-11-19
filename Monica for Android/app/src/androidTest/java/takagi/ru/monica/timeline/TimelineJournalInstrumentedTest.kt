package takagi.ru.monica.timeline

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.data.*
import takagi.ru.monica.security.*
import java.util.Date

class TimelineJournalInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var security: SecurityManager
    private var unlocked = false
    @Before fun setup() = runBlocking {
        assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready)
        security = SecurityManager(context)
        unlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        db.timelineJournal.configure(context)
    }
    @After fun cleanup() { db.close(); if (unlocked) SessionManager.markUnlocked() else SessionManager.markLocked() }
    private suspend fun insert(title: String = "Synthetic project"): Long = db.passwordEntryDao().insertPasswordEntry(
        PasswordEntry(title = title, username = "synthetic-account", password = security.encryptData("old-secret"), website = "https://example.invalid"))
    private suspend fun events() = db.timelineDao().page("ALL", "", 0, Long.MAX_VALUE, Long.MAX_VALUE, "~", System.currentTimeMillis(), 1000)
    private suspend fun edit(id: Long, value: String = "new-secret"): TimelineRecord {
        val oldIds = events().map { it.id }.toSet()
        val row = requireNotNull(db.passwordEntryDao().getPasswordEntryById(id))
        db.passwordEntryDao().updatePasswordEntry(row.copy(password = security.encryptData(value), updatedAt = Date()))
        return events().first { it.action == "UPDATE" && it.id !in oldIds }
    }
    @Test fun threeAccountsInOneSaveCreateOneEncryptedEvent() = runBlocking {
        db.passwordEntryDao().commitLocalPasswordSave { repeat(3) { insert() } }
        val events = events()
        assertEquals(1, events.size)
        assertEquals(3, db.timelineJournal.read(events.single()).changes.size)
        assertFalse(events.single().sealedDetails!!.contains("synthetic-account"))
        assertEquals(3, db.timelineDao().targets(events.single().id).size)
    }
    @Test fun editingThreeAccountsRestoresTheirOwnPasswordsInOneTransaction() = runBlocking {
        val ids = List(3) { insert() }
        db.passwordEntryDao().commitLocalPasswordSave { ids.forEachIndexed { i, id ->
            val row = db.passwordEntryDao().getPasswordEntryById(id)!!
            db.passwordEntryDao().update(row.copy(password = security.encryptData("new-$i")))
        } }
        val event = events().first { it.action == "UPDATE" }
        assertEquals(3, db.timelineJournal.read(event).changes.size)
        val plan = db.timelineJournal.preview(event.id)
        db.timelineJournal.confirm(plan)
        ids.forEach { assertEquals("old-secret", security.decryptData(db.passwordEntryDao().getPasswordEntryById(it)!!.password)) }
    }
    @Test fun recoveryIsLinkedIdempotentAndKeepsTheOverwrittenVersion() = runBlocking {
        val id = insert(); val event = edit(id)
        val plan = db.timelineJournal.preview(event.id)
        val restored = db.timelineJournal.confirm(plan)
        assertEquals(restored, db.timelineJournal.confirm(plan))
        val recovery = db.timelineDao().get(restored)!!
        assertEquals(event.id, recovery.parentId)
        assertEquals(1, events().count { it.parentId == event.id })
        assertEquals("new-secret", security.decryptData(db.timelineJournal.read(recovery).changes.single().before!!.row.text("password")))
        val secondPreview = db.timelineJournal.preview(recovery.id)
        db.timelineJournal.confirm(secondPreview)
        assertEquals("new-secret", security.decryptData(db.passwordEntryDao().getPasswordEntryById(id)!!.password))
    }
    @Test fun subsequentEditBeforeConfirmationIsNeverOverwritten() = runBlocking {
        val id = insert(); val event = edit(id); val plan = db.timelineJournal.preview(event.id)
        edit(id, "newer-secret")
        assertTrue(runCatching { db.timelineJournal.confirm(plan) }.exceptionOrNull()?.message?.contains("CONFLICT") == true)
        assertEquals("newer-secret", security.decryptData(db.passwordEntryDao().getPasswordEntryById(id)!!.password))
    }
    @Test fun lockAfterPreviewRejectsWrite() = runBlocking {
        val id = insert(); val plan = db.timelineJournal.preview(edit(id).id)
        SessionManager.markLocked()
        assertTrue(runCatching { db.timelineJournal.confirm(plan) }.isFailure)
        assertFalse(events().any { it.action == "RESTORE_FIELDS" })
    }
    @Test fun lockDuringBusinessTransactionRollsBackBusinessAndEvent() = runBlocking {
        val result = runCatching { db.passwordEntryDao().commitLocalPasswordSave { insert(); SessionManager.markLocked() } }
        assertTrue(result.isFailure)
        assertTrue(db.passwordEntryDao().getAllPasswordEntriesSync().isEmpty())
        assertTrue(events().isEmpty())
    }
    @Test fun failedAndCancelledOperationsLeaveNoFalseSuccess() = runBlocking {
        assertTrue(runCatching { db.passwordEntryDao().commitLocalPasswordSave { insert(); error("synthetic failure") } }.isFailure)
        assertTrue(events().isEmpty())
        val job = launch { db.passwordEntryDao().commitLocalPasswordSave { insert(); throw CancellationException("synthetic cancel") } }
        job.join()
        assertTrue(events().isEmpty())
        assertTrue(db.passwordEntryDao().getAllPasswordEntriesSync().isEmpty())
    }
    @Test fun customFieldsKeepDuplicateTitlesIdsAndProtection() = runBlocking {
        val id = insert()
        val one = db.customFieldDao().insert(CustomField(entryId = id, title = "Duplicated", value = "one", isProtected = true, sortOrder = 1))
        val two = db.customFieldDao().insert(CustomField(entryId = id, title = "Duplicated", value = "two", isProtected = false, sortOrder = 2))
        val before = db.customFieldDao().getFieldsByEntryIdSync(id)
        db.passwordEntryDao().commitLocalPasswordSave {
            db.customFieldDao().update(before.first().copy(value = "changed"))
            val row = db.passwordEntryDao().getPasswordEntryById(id)!!
            db.passwordEntryDao().update(row.copy(password = security.encryptData("new-secret")))
        }
        val event = events().first { it.action == "UPDATE" }
        db.timelineJournal.confirm(db.timelineJournal.preview(event.id))
        assertEquals(before, db.customFieldDao().getFieldsByEntryIdSync(id))
        assertEquals(listOf(one, two), db.customFieldDao().getFieldsByEntryIdSync(id).map { it.id })
    }
    @Test fun reencryptedUnchangedValuesAreNotEditsAndTheirRawBytesStillGuardRecovery() = runBlocking {
        val id = insert()
        val original = db.passwordEntryDao().getPasswordEntryById(id)!!
        val beforeCount = events().size
        val reencrypted = security.encryptData("old-secret")
        assertNotEquals(original.password, reencrypted)
        db.passwordEntryDao().update(original.copy(password = reencrypted))
        assertEquals("Re-encryption alone is not a user edit", beforeCount, events().size)
        val afterNoop = db.passwordEntryDao().getPasswordEntryById(id)!!
        val cipher = security.encryptData("old-secret")
        db.passwordEntryDao().update(afterNoop.copy(username = "changed-user", password = cipher))
        val event = events().first { it.action == "UPDATE" }
        val details = db.timelineJournal.read(event)
        assertEquals(setOf("username"), TimelineRecoveryPolicy.columns(details.changes.single()))
        val plan = db.timelineJournal.preview(event.id)
        db.timelineJournal.confirm(plan)
        val restored = db.passwordEntryDao().getPasswordEntryById(id)!!
        assertEquals(original.username, restored.username)
        assertEquals("Keep the current ciphertext for an unchanged password", cipher, restored.password)
        assertEquals("old-secret", security.decryptData(restored.password))
        val edit = edit(id)
        val stale = db.timelineJournal.preview(edit.id)
        val current = db.passwordEntryDao().getPasswordEntryById(id)!!
        db.passwordEntryDao().update(current.copy(password = security.encryptData("new-secret")))
        assertTrue(runCatching { db.timelineJournal.confirm(stale) }.isFailure)
    }

    @Test fun reencryptedCustomValueIsPreservedWhenRestoringAnotherField() = runBlocking {
        val id = insert()
        val fieldId = db.customFieldDao().insert(CustomField(entryId = id, title = "Private", value = security.encryptData("same-field"), isProtected = true, sortOrder = 0))
        val originalField = db.customFieldDao().getFieldsByEntryIdSync(id).single()
        val cipher = security.encryptData("same-field")
        db.passwordEntryDao().commitLocalPasswordSave {
            db.customFieldDao().update(originalField.copy(value = cipher))
            val row = db.passwordEntryDao().getPasswordEntryById(id)!!
            db.passwordEntryDao().update(row.copy(username = "changed"))
        }
        val event = events().first { it.action == "UPDATE" && it.recoveryCandidate }
        val details = db.timelineJournal.read(event)
        assertEquals(setOf("username"), TimelineRecoveryPolicy.columns(details.changes.single()))
        db.timelineJournal.confirm(db.timelineJournal.preview(event.id))
        val restored = db.customFieldDao().getFieldsByEntryIdSync(id).single()
        assertEquals(fieldId, restored.id)
        assertEquals(cipher, restored.value)
    }

    @Test fun unknownFieldsAndBindingsDisableRecoveryWithoutDataLoss() = runBlocking {
        val id = insert()
        db.customFieldDao().insert(CustomField(entryId = id, title = "monica.unknown.binding", value = "reference"))
        val original = db.customFieldDao().getFieldsByEntryIdSync(id).single()
        db.customFieldDao().update(original.copy(value = "changed reference"))
        assertFalse(events().first().recoveryCandidate)
        val row = db.passwordEntryDao().getPasswordEntryById(id)!!
        db.passwordEntryDao().update(row.copy(passkeyBindings = "synthetic-binding"))
        assertFalse(events().first().recoveryCandidate)
        assertEquals("changed reference", db.customFieldDao().getFieldsByEntryIdSync(id).single().value)
    }
    @Test fun permanentDeletionPurgesAllRelatedMaterialButKeepsFacts() = runBlocking {
        val id = insert(); edit(id)
        db.passwordEntryDao().softDelete(id)
        assertTrue(events().any { it.action == "TRASH" })
        val reader = TimelineReader(db)
        assertTrue(reader.page(TimelineReader.Filter(tab = "RECOVERABLE"), TimelineReader.Cursor()).entries.any { it.record.action == "TRASH" })
        db.passwordEntryDao().deletePasswordEntryById(id)
        assertTrue(events().all { it.sealedDetails == null && !it.recoveryCandidate })
        assertTrue(events().any { it.action == "PERMANENT_DELETE" })
        assertTrue(reader.page(TimelineReader.Filter(tab = "RECOVERABLE"), TimelineReader.Cursor()).entries.isEmpty())
    }
    @Test fun expiredMaterialIsRejectedBeforeCleanup() = runBlocking {
        val id = insert(); val event = edit(id)
        val expired = event.copy(id = "expired", expiresAt = 1)
        db.timelineDao().insert(expired)
        assertTrue(runCatching { db.timelineJournal.preview(expired.id) }.exceptionOrNull()?.message?.contains("EXPIRED") == true)
        assertNotNull(db.timelineDao().get(expired.id)!!.sealedDetails)
    }
    @Test fun moreThanOneHundredSyncChecksCannotHideAnEdit() = runBlocking {
        val id = insert(); val edit = edit(id)
        repeat(130) { db.timelineJournal.observe("BITWARDEN_SYNC", it.toLong(), "Synthetic vault", "SYNC", emptyList(), System.currentTimeMillis(), "bitwarden:1", true) }
        val reader = TimelineReader(db)
        val page = reader.page(TimelineReader.Filter(), TimelineReader.Cursor())
        assertTrue(page.entries.any { it.record.id == edit.id })
        assertTrue(page.entries.none { it.record.action == "SYNC" })
        val sync = reader.page(TimelineReader.Filter(tab = "SYNC"), TimelineReader.Cursor())
        assertEquals(64, sync.entries.size)
        assertEquals(1, TimelineReader.collapse(sync.entries).size)
    }
    @Test fun searchDoesNotMatchPasswordsOrNoteBodies() = runBlocking {
        val id = insert("Findable title"); edit(id, "unsearchable-secret")
        val reader = TimelineReader(db)
        assertTrue(reader.page(TimelineReader.Filter(query = "Findable"), TimelineReader.Cursor()).entries.isNotEmpty())
        assertTrue(reader.page(TimelineReader.Filter(query = "unsearchable-secret"), TimelineReader.Cursor()).entries.isEmpty())
    }
    @Test fun stableCursorDoesNotSkipEqualTimestampsOrCrossVaults() = runBlocking {
        repeat(80) { index -> db.timelineDao().insert(TimelineRecord("id%03d".format(index), 10, "UPDATE", "TEST", if (index % 2 == 0) "bitwarden:1" else "bitwarden:2", null, Long.MAX_VALUE, false)) }
        val reader = TimelineReader(db)
        val first = reader.page(TimelineReader.Filter(), TimelineReader.Cursor())
        val second = reader.page(TimelineReader.Filter(), first.cursor)
        assertEquals(80, (first.entries + second.entries).map { it.key }.distinct().size)
        assertEquals(40, reader.page(TimelineReader.Filter(scope = "bitwarden:1"), TimelineReader.Cursor()).entries.size)
    }

    @Test fun noteContentRoundTripsWithoutReplacingUnknownOrImageReferences() = runBlocking {
        val before = "{\"content\":\"old body\",\"tags\":[],\"isMarkdown\":false,\"customFields\":[]}"
        val after = before.replace("old body", "new body")
        val row = SecureItem(itemType = ItemType.NOTE, title = "Synthetic note", itemData = before, notes = "old body")
        val id = db.secureItemDao().insertItem(row)
        db.secureItemDao().updateItem(row.copy(id = id, itemData = after, notes = "new body"))
        val event = events().first { it.action == "UPDATE" }
        assertTrue(event.recoveryCandidate)
        db.timelineJournal.confirm(db.timelineJournal.preview(event.id))
        assertEquals(before, db.secureItemDao().getItemById(id)!!.itemData)
        val current = db.secureItemDao().getItemById(id)!!
        db.secureItemDao().updateItem(current.copy(itemData = "{\"unknownRef\":\"reference\",\"content\":\"new body\"}"))
        val recent = events().filter { it.action == "UPDATE" }.first { db.timelineJournal.read(it).changes.single().after?.row?.text("itemData")?.contains("unknownRef") == true }
        assertFalse(recent.recoveryCandidate)
    }

    @Test fun attachmentsAndPasswordHistoryRemainByteForByteAndChangedAttachmentsBlockRecovery() = runBlocking {
        val id = insert()
        val attachment = takagi.ru.monica.attachments.model.Attachment(parentPasswordId = id, source = "LOCAL",
            fileName = "Synthetic attachment", mimeType = "application/octet-stream", sizeBytes = 3,
            localPath = "synthetic-unopened-path", wrappedCek = "synthetic-wrapped-key", downloadState = "DOWNLOADED",
            createdAt = 1, updatedAt = 1)
        val attachmentId = db.attachmentDao().insert(attachment)
        db.passwordHistoryDao().insert(PasswordHistoryEntry(entryId = id, password = security.encryptData("historical-secret")))
        val beforeAttachments = db.timelineJournal.snapshot("PASSWORD", id)!!.attachments
        val beforeHistory = db.passwordHistoryDao().getHistoryByEntryIdSync(id)
        val event = edit(id)
        val plan = db.timelineJournal.preview(event.id)
        db.timelineJournal.confirm(plan)
        assertEquals(beforeAttachments, db.timelineJournal.snapshot("PASSWORD", id)!!.attachments)
        assertEquals(beforeHistory, db.passwordHistoryDao().getHistoryByEntryIdSync(id))
        val second = edit(id, "another-secret")
        val stale = db.timelineJournal.preview(second.id)
        db.openHelper.writableDatabase.execSQL("UPDATE attachments SET updated_at = 2 WHERE id = ?", arrayOf<Any?>(attachmentId))
        assertTrue(runCatching { db.timelineJournal.confirm(stale) }.exceptionOrNull()?.message?.contains("CONFLICT") == true)
    }

    @Test fun nativeNoticesKeepCountersButNeverCopyProviderSecrets() = runBlocking {
        db.timelineJournal.observe("BITWARDEN_SYNC", 99, "Synthetic private vault", "SYNC",
            listOf(TimelineFact("sync.uploaded", "", "2"), TimelineFact("password", "secret-before", "secret-after")),
            System.currentTimeMillis(), "bitwarden:2", false)
        val event = events().single()
        assertFalse(event.recoveryCandidate)
        val details = db.timelineJournal.read(event)
        assertEquals("", details.title)
        assertEquals("2", details.facts.first().after)
        assertEquals("<redacted>", details.facts.last().after)
        assertFalse(details.toString().contains("secret-after"))
    }

    @Test fun lateNoticeCannotPersistContentAcrossLockAndUnlock() = runBlocking {
        val generation = SessionManager.lockGeneration.value
        SessionManager.markLocked()
        SessionManager.markUnlocked()
        db.timelineJournal.observe("PASSKEY", 99, "old private title", "UPDATE",
            listOf(TimelineFact("unknown", "private-before", "private-after")), System.currentTimeMillis(),
            observedGeneration = generation)
        assertNull(events().single().sealedDetails)
        assertFalse(events().single().recoveryCandidate)
    }

    @Test fun missingProviderProjectionDoesNotMakeItsNoticeLocal() = runBlocking {
        db.timelineJournal.observe("PASSWORD", 987, "private former provider title", "DELETE",
            listOf(TimelineFact("password", "old secret", "new secret")), System.currentTimeMillis())
        val event = events().single()
        val details = db.timelineJournal.read(event)
        assertEquals("unknown", event.scopeKey)
        assertEquals("", details.title)
        assertEquals("<redacted>", details.facts.single().after)
        assertEquals("", details.facts.single().before)
    }
}
