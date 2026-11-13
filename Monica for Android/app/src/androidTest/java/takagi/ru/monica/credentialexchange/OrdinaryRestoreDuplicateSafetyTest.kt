package takagi.ru.monica.credentialexchange

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.backup.AttachmentBackupCodec
import takagi.ru.monica.attachments.backup.PortableAttachmentBackup
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.data.ItemCounts
import takagi.ru.monica.data.RestoreReport
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.transfer.TransferPhase
import takagi.ru.monica.transfer.TransferProgressReporter
import takagi.ru.monica.util.DataExportImportManager.ExportItem
import takagi.ru.monica.utils.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Actual ordinary restore writes; expected preservation is independent of duplicate policy helpers. */
@RunWith(AndroidJUnit4::class)
class OrdinaryRestoreDuplicateSafetyTest {
    private suspend fun fixture(action: suspend (TransferFixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready)
        val f = TransferFixture()
        try { action(f) } finally {
            // TransferFixture's display feed excludes archived rows; remove our own extras too.
            rows(f).forEach { row ->
                AttachmentContainer.facade(f.context).list(AttachmentOwner.secureItem(row.id))
                    .forEach { AttachmentContainer.facade(f.context).forgetLocalAttachment(it.id) }
                f.secureItems.deleteItemById(row.id)
            }
            f.close()
        }
    }

    private fun card(f: TransferFixture, id: Long = 8101) = ExportItem(id, "BANK_CARD", f.prefix,
        JSONObject().put("cardNumber", "4111111111111111").put("cardholderName", "Synthetic Holder")
            .put("expiryMonth", "01").put("expiryYear", "2030").put("cvv", "111").toString(),
        "original notes", false, "", 1000, 2000)

    private fun document(f: TransferFixture) = card(f).copy(itemType = "DOCUMENT", itemData =
        JSONObject().put("documentType", "PASSPORT").put("documentNumber", "SYNTHETIC-DOCUMENT")
            .put("fullName", "Synthetic Holder").put("expiryDate", "2030-01-01").toString())

    private fun note(f: TransferFixture, text: String = "original body") = card(f).copy(itemType = "NOTE",
        itemData = JSONObject().put("content", text).toString())

    private suspend fun rows(f: TransferFixture): List<SecureItem> =
        (f.db.secureItemDao().getAllItemsIncludingArchived().first() + f.db.secureItemDao().getDeletedItemsSync())
            .distinctBy { it.id }.filter { it.title.startsWith(f.prefix) }

    private suspend fun restore(f: TransferFixture, items: List<ExportItem>, passwords: List<PasswordEntry> = emptyList(),
        plan: PortableAttachmentBackup.RestorePlan = PortableAttachmentBackup.RestorePlan(),
        progress: TransferProgressReporter = TransferProgressReporter.None,
        legacyAttachments: List<AttachmentBackupCodec.Entry> = emptyList()): RestoreApplyStats {
        val counts = ItemCounts(passwords = passwords.size)
        return BackupRestoreApplier.applyRestoreResult(f.context,
            RestoreResult(BackupContent(passwords, items, portableAttachments = plan, attachments = legacyAttachments),
                RestoreReport(true, counts, counts, emptyList(), emptyList())),
            f.passwords, f.secureItems, localOnlyDedup = true, logTag = "OrdinaryRestoreSafety", progress = progress)
    }

    private fun payload(f: TransferFixture, item: ExportItem, bytes: ByteArray): PortableAttachmentBackup.RestorePlan {
        val name = "attachments_portable/${UUID.randomUUID()}.bin"
        val file = File(f.root, "${UUID.randomUUID()}.bin").apply { writeBytes(bytes) }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val entry = PortableAttachmentBackup.Entry(parentSecureItemId = item.id, fileName = "synthetic.bin",
            mimeType = "application/octet-stream", sizeBytes = bytes.size.toLong(), sha256Hex = sha,
            payloadPath = name, createdAt = 1000, updatedAt = 2000)
        return PortableAttachmentBackup.RestorePlan(listOf(entry), mapOf(name to file))
    }

    private suspend fun assertAttachment(f: TransferFixture, ownerId: Long, bytes: ByteArray) {
        val attachment = f.db.attachmentDao().getAllBySecureItem(ownerId).single()
        val cek = AttachmentKeyVault(f.security).unwrap(requireNotNull(attachment.wrappedCek))
        try {
            AttachmentStorage(f.context).openDecryptedStream(requireNotNull(attachment.localPath), cek).use {
                assertArrayEquals(bytes, it.readBytes())
            }
        } finally { cek.fill(0) }
    }

    @Test fun sameCardNumberWithDifferentCvvExpiryOrHolderPreservesEveryPayload() = runBlocking { fixture { f ->
        val original = card(f)
        assertEquals(1, restore(f, listOf(original)).secureItemImported)
        val before = rows(f).single()
        for ((key, value) in listOf("cvv" to "222", "expiryYear" to "2034", "cardholderName" to "Other Holder")) {
            val incoming = original.copy(itemData = JSONObject(original.itemData).put(key, value).toString())
            val result = restore(f, listOf(incoming))
            assertEquals("Changed $key must be retained", 1, result.secureItemImported)
            assertEquals(0, result.secureItemSkipped)
            assertTrue(rows(f).any { f.security.decryptDataIfMonicaCiphertext(it.itemData) == incoming.itemData })
            assertEquals(before, f.secureItems.getItemById(before.id))
        }
        assertEquals(4, rows(f).size)
    } }

    @Test fun sameCardNumberWithDifferentTitleOrNotesDoesNotReplaceOriginalMetadata() = runBlocking { fixture { f ->
        val original = card(f)
        restore(f, listOf(original))
        val before = rows(f).single()
        for (incoming in listOf(original.copy(title = f.prefix + "-other"), original.copy(notes = "different notes"))) {
            assertEquals(1, restore(f, listOf(incoming)).secureItemImported)
            assertTrue(rows(f).any { it.title == incoming.title && it.notes == incoming.notes })
        }
        assertEquals(before, f.secureItems.getItemById(before.id))
    } }

    @Test fun sameDocumentNumberWithDifferentExpiryOrNamePreservesBothVersions() = runBlocking { fixture { f ->
        val original = document(f)
        restore(f, listOf(original))
        val before = rows(f).single()
        for ((key, value) in listOf("expiryDate" to "2035-03-02", "fullName" to "Changed Holder")) {
            val incoming = original.copy(itemData = JSONObject(original.itemData).put(key, value).toString())
            assertEquals(1, restore(f, listOf(incoming)).secureItemImported)
            assertTrue(rows(f).any { f.security.decryptDataIfMonicaCiphertext(it.itemData) == incoming.itemData })
        }
        assertEquals(before, f.secureItems.getItemById(before.id))
    } }

    @Test fun sameNoteTitleWithDifferentBodyOrProtectedFieldPreservesBoth() = runBlocking { fixture { f ->
        val original = note(f).let { base -> base.copy(itemData = JSONObject(base.itemData)
            .put("customFields", JSONArray().put(JSONObject().put("label", "Synthetic secret")
                .put("value", "synthetic").put("type", "TEXT"))).toString()) }
        restore(f, listOf(original))
        val before = rows(f).single()
        val fields = JSONArray().put(JSONObject().put("label", "Synthetic secret").put("value", "synthetic")
            .put("type", "HIDDEN"))
        for (incoming in listOf(note(f, "different body"), original.copy(itemData =
            JSONObject(original.itemData).put("customFields", fields).toString()))) {
            assertEquals(1, restore(f, listOf(incoming)).secureItemImported)
            assertTrue(rows(f).any { it.itemData == incoming.itemData })
        }
        assertEquals(before, f.secureItems.getItemById(before.id))
    } }

    @Test fun completeScalarMetadataDifferencesNeverCollapseIntoAnExistingCard() = runBlocking { fixture { f ->
        val original = card(f)
        restore(f, listOf(original))
        val before = rows(f).single()
        val variants = listOf(original.copy(isFavorite = true), original.copy(sortOrder = 12),
            original.copy(createdAt = 900), original.copy(updatedAt = 3000),
            original.copy(isArchived = true, archivedAt = 3000),
            original.copy(isDeleted = true, deletedAt = 3000), original.copy(categoryId = 900000),
            original.copy(imagePaths = "[\"unresolved-image\"]"))
        for (incoming in variants) assertEquals("Distinct metadata must remain a separate row", 1,
            restore(f, listOf(incoming)).secureItemImported)
        assertEquals(variants.size + 1, rows(f).size)
        assertEquals(before, f.secureItems.getItemById(before.id))
    } }

    @Test fun identicalStandaloneCardsDocumentsAndNotesCanBeRetriedWithoutNewRows() = runBlocking { fixture { f ->
        for ((index, base) in listOf(card(f), document(f), note(f)).withIndex()) {
            val item = base.copy(id = 8200L + index, title = f.prefix + "-$index")
            assertEquals(1, restore(f, listOf(item)).secureItemImported)
            val before = rows(f).single { it.title == item.title }
            val retried = restore(f, listOf(item))
            assertEquals(0, retried.secureItemImported)
            assertEquals(1, retried.secureItemSkipped)
            assertEquals(before, f.secureItems.getItemById(before.id))
        }
        assertEquals(3, rows(f).size)
    } }

    @Test fun unknownPayloadMembersAreRetainedWithoutClaimingSafeProjectEquality() = runBlocking { fixture { f ->
        val original = card(f)
        restore(f, listOf(original))
        val extended = original.copy(itemData = JSONObject(original.itemData)
            .put("futureRelation", JSONObject().put("targetId", 89123).put("protected", true)).toString())
        repeat(2) { assertEquals(1, restore(f, listOf(extended)).secureItemImported) }
        assertEquals(2, rows(f).count { f.security.decryptDataIfMonicaCiphertext(it.itemData) == extended.itemData })
        assertEquals(3, rows(f).size)
    } }

    @Test fun incomingAttachmentGetsIndependentOwnerAndRepeatedRestoreKeepsVerifiedBytes() = runBlocking { fixture { f ->
        val item = note(f)
        restore(f, listOf(item))
        val original = rows(f).single()
        val bytes = "Synthetic attachment bytes\u0000\u00ff".toByteArray()
        repeat(2) {
            assertEquals(1, restore(f, listOf(item), plan = payload(f, item, bytes)).secureItemImported)
        }
        val copies = rows(f).filter { it.id != original.id }
        assertEquals(2, copies.size)
        copies.forEach { assertAttachment(f, it.id, bytes) }
        assertTrue(f.db.attachmentDao().getAllBySecureItem(original.id).isEmpty())
        assertEquals(original, f.secureItems.getItemById(original.id))
    } }

    @Test fun anExistingAttachmentPreventsCollapsingAnIncomingStandaloneItem() = runBlocking { fixture { f ->
        val item = card(f)
        val bytes = "existing attachment".toByteArray()
        assertEquals(1, restore(f, listOf(item), plan = payload(f, item, bytes)).secureItemImported)
        val before = rows(f).single()
        assertEquals(1, restore(f, listOf(item)).secureItemImported)
        assertAttachment(f, before.id, bytes)
        assertEquals(before, f.secureItems.getItemById(before.id))
        assertTrue(f.db.attachmentDao().getAllBySecureItem(rows(f).single { it.id != before.id }.id).isEmpty())
    } }

    @Test fun boundNoteCreatesIndependentPasswordAndRemapsOnlyTheNewRelationship() = runBlocking { fixture { f ->
        val originalNote = note(f, "existing body")
        restore(f, listOf(originalNote))
        val oldNote = rows(f).single()
        val oldPasswordId = f.passwords.insertPasswordEntry(PasswordEntry(title = f.prefix + "-login", website = "",
            username = "synthetic", password = f.security.encryptDataLegacyCompat("synthetic password"), boundNoteId = oldNote.id))
        val oldPassword = requireNotNull(f.passwords.getPasswordEntryById(oldPasswordId))
        val incoming = note(f, "restored body")
        val password = oldPassword.copy(id = 8901, password = "synthetic password", boundNoteId = incoming.id)
        val bytes = "bound note attachment".toByteArray()
        val result = restore(f, listOf(incoming), listOf(password), payload(f, incoming, bytes))
        assertEquals(1, result.passwordImported)
        assertEquals(1, result.secureItemImported)
        val newPassword = f.db.passwordEntryDao().getAllPasswordEntriesSync()
            .single { it.title == password.title && it.id != oldPassword.id }
        val newNote = rows(f).single { it.itemData == incoming.itemData }
        assertEquals(newNote.id, newPassword.boundNoteId)
        assertAttachment(f, newNote.id, bytes)
        assertEquals(oldPassword, f.passwords.getPasswordEntryById(oldPassword.id))
        assertEquals(oldNote, f.secureItems.getItemById(oldNote.id))
    } }

    @Test fun missingWrongTypeOrAmbiguousSourceNoteNeverBindsAnExistingDestinationId() = runBlocking { fixture { f ->
        restore(f, listOf(note(f, "unrelated existing note")))
        val old = rows(f).single()
        val password = PasswordEntry(id = 8911, title = f.prefix + "-missing", website = "", username = "",
            password = "synthetic password", boundNoteId = old.id)
        val invalidSources = listOf(emptyList(), listOf(card(f).copy(id = old.id)),
            listOf(note(f).copy(id = old.id), note(f, "ambiguous").copy(id = old.id)))
        for (items in invalidSources) {
            val result = restore(f, items, listOf(password))
            assertEquals(1, result.passwordFailed)
            assertEquals(0, result.passwordImported)
            assertTrue(f.db.passwordEntryDao().getAllPasswordEntriesSync().none { it.title == password.title })
            assertEquals(old, f.secureItems.getItemById(old.id))
        }
    } }

    @Test fun cancellationBeforeNoteImportNeverPersistsTheSourceNoteIdOnNewPassword() = runBlocking { fixture { f ->
        restore(f, listOf(note(f, "unrelated existing note")))
        val old = rows(f).single()
        val incoming = note(f, "restored note").copy(id = old.id)
        val password = PasswordEntry(id = 8912, title = f.prefix + "-cancel", website = "", username = "",
            password = "synthetic password", boundNoteId = incoming.id)
        val progress = TransferProgressReporter { value ->
            if (value.phase == TransferPhase.PREPARING && value.completed == 1L)
                throw kotlinx.coroutines.CancellationException("Synthetic cancellation before linked note")
        }
        assertTrue(runCatching { restore(f, listOf(incoming), listOf(password), progress = progress) }.exceptionOrNull()
            is kotlinx.coroutines.CancellationException)
        val saved = f.db.passwordEntryDao().getAllPasswordEntriesSync().single { it.title == password.title }
        assertNull(saved.boundNoteId)
        assertEquals(old, f.secureItems.getItemById(old.id))
    } }

    @Test fun failedNoteInsertReportsIncompletePasswordAndNeverLeavesAnUnrelatedBinding() = runBlocking { fixture { f ->
        restore(f, listOf(note(f, "unrelated existing note")))
        val old = rows(f).single()
        val incoming = note(f, "restored note").copy(id = old.id, title = f.prefix + "-reject")
        val password = PasswordEntry(id = 8913, title = f.prefix + "-failed", website = "", username = "",
            password = "synthetic password", boundNoteId = incoming.id)
        val trigger = "ordinary_restore_" + UUID.randomUUID().toString().replace("-", "")
        val sql = f.db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER $trigger BEFORE INSERT ON secure_items WHEN NEW.title = '${incoming.title}' BEGIN SELECT RAISE(FAIL, 'synthetic note write rejection'); END")
        try {
            val result = restore(f, listOf(incoming), listOf(password))
            assertEquals(1, result.secureItemFailed)
            assertEquals(1, result.passwordFailed)
            assertEquals(0, result.passwordImported)
            val saved = f.db.passwordEntryDao().getAllPasswordEntriesSync().single { it.title == password.title }
            assertNull(saved.boundNoteId)
            assertEquals(old, f.secureItems.getItemById(old.id))
        } finally { sql.execSQL("DROP TRIGGER $trigger") }
    } }

    @Test fun portableAttachmentWithAmbiguousOrMissingSourceOwnerRejectsArchiveBeforeAnyWrite() = runBlocking { fixture { f ->
        restore(f, listOf(note(f, "unrelated original")))
        val original = rows(f).single()
        val first = note(f, "first incoming").copy(id = original.id, title = f.prefix + "-first")
        val second = first.copy(title = f.prefix + "-second", itemData = note(f, "second incoming").itemData)
        val password = PasswordEntry(id = 9901, title = f.prefix + "-must-not-write", website = "", username = "",
            password = "synthetic password")
        for (items in listOf(listOf(first, second), emptyList())) {
            val result = runCatching { restore(f, items, listOf(password), payload(f, first, "ambiguous bytes".toByteArray())) }
            assertTrue("Malformed owner must reject the whole archive", result.exceptionOrNull() is IllegalArgumentException)
            assertEquals(listOf(original), rows(f))
            assertTrue(f.db.passwordEntryDao().getAllPasswordEntriesSync().none { it.title == password.title })
            assertTrue(f.db.attachmentDao().getAllBySecureItem(original.id).isEmpty())
        }
    } }

    @Test fun legacyAttachmentWithAmbiguousOrMissingSourceOwnerRejectsArchiveAndPreservesOriginalBlob() = runBlocking { fixture { f ->
        val source = note(f, "original with encrypted attachment")
        val bytes = "existing encrypted attachment".toByteArray()
        restore(f, listOf(source), plan = payload(f, source, bytes))
        val original = rows(f).single()
        val attachment = f.db.attachmentDao().getAllBySecureItem(original.id).single()
        val legacy = AttachmentBackupCodec.Entry(parentSecureItemId = original.id,
            fileName = attachment.fileName, mimeType = attachment.mimeType, sizeBytes = attachment.sizeBytes,
            sha256Hex = attachment.sha256Hex, wrappedCek = requireNotNull(attachment.wrappedCek),
            localPath = requireNotNull(attachment.localPath), createdAt = attachment.createdAt, updatedAt = attachment.updatedAt)
        val first = note(f, "first incoming").copy(id = original.id, title = f.prefix + "-first")
        val second = first.copy(title = f.prefix + "-second", itemData = note(f, "second incoming").itemData)
        val password = PasswordEntry(id = 9902, title = f.prefix + "-must-not-write", website = "", username = "",
            password = "synthetic password")
        for (items in listOf(listOf(first, second), emptyList())) {
            val result = runCatching { restore(f, items, listOf(password), legacyAttachments = listOf(legacy)) }
            assertTrue("Malformed owner must reject the whole archive", result.exceptionOrNull() is IllegalArgumentException)
            assertEquals(listOf(original), rows(f))
            assertTrue(f.db.passwordEntryDao().getAllPasswordEntriesSync().none { it.title == password.title })
            assertEquals(listOf(attachment), f.db.attachmentDao().getAllBySecureItem(original.id))
            assertAttachment(f, original.id, bytes)
        }
    } }

    private suspend fun lockBeforeNoteCannotPublishBinding(f: TransferFixture, unlockAgain: Boolean) {
        restore(f, listOf(note(f, "unrelated existing note")))
        val original = rows(f).single()
        val incoming = note(f, "restored note").copy(id = original.id)
        val password = PasswordEntry(id = 9903, title = f.prefix + "-lock", website = "", username = "",
            password = "synthetic password", boundNoteId = incoming.id)
        var callbackRan = false
        val progress = TransferProgressReporter { value ->
            if (value.phase == TransferPhase.PREPARING && value.completed == 1L) {
                callbackRan = true
                SessionManager.markLocked()
                if (unlockAgain) SessionManager.markUnlocked()
            }
        }
        try {
            val result = runCatching { restore(f, listOf(incoming), listOf(password), progress = progress) }
            assertTrue(callbackRan)
            assertTrue("Lock generation must invalidate late note publication", result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            val saved = f.db.passwordEntryDao().getAllPasswordEntriesSync().single { it.title == password.title }
            assertNull(saved.boundNoteId)
            assertEquals(original, f.secureItems.getItemById(original.id))
        } finally {
            // The fixture uses explicit synthetic session authorization, as in its own setup.
            SessionManager.markUnlocked()
        }
    }

    @Test fun lockBeforeNoteImportRevokesLatePasswordBinding() = runBlocking { fixture { f ->
        lockBeforeNoteCannotPublishBinding(f, unlockAgain = false)
    } }

    @Test fun lockThenUnlockBeforeNoteImportDoesNotReviveOldBindingAuthorization() = runBlocking { fixture { f ->
        lockBeforeNoteCannotPublishBinding(f, unlockAgain = true)
    } }
}
