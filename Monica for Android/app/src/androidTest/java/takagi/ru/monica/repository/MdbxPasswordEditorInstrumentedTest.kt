package takagi.ru.monica.repository

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.autofill_ng.MdbxAutofillFixture
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SessionManager
import uniffi.mdbx_ffi.*
import java.util.UUID

class MdbxPasswordEditorInstrumentedTest : MdbxAutofillFixture() {
    private var lease: AutoCloseable? = null
    private fun editor(): MdbxPasswordEditor {
        SessionManager.markUnlocked(); Mdbx2NativeReadSessions.updateForeground(true)
        return MdbxPasswordEditor(context, database, security).also { lease = it.begin() }
    }
    @After fun closeEditor() { lease?.close(); Mdbx2NativeReadSessions.updateForeground(false); SessionManager.markLocked() }
    private suspend fun mirror(f: Fixture): PasswordEntry {
        val row = PasswordEntry(title = "Stale mirror", website = "", username = "stale", password = security.encryptData("stale"),
            mdbxDatabaseId = f.databaseId, replicaGroupId = f.objectId)
        return row.copy(id = database.passwordEntryDao().insertPasswordEntry(row))
    }
    private fun <T> native(f: Fixture, block: (MdbxVault) -> T): T {
        val v = openVault(f.file.path, vaultPassword, "editor-independent-reader")
        return try { block(v) } finally { v.close() }
    }

    @Test fun regularEditorReadsOriginalAndSavesOneNativeCommitWithOpaqueFields() = runBlocking {
        val payload = """{"username":"original","password_plain":"old","future":{"big":900719925474099312345,"a":[null,false]},"custom_fields":[{"title":"same","value":"one","is_protected":true,"uuid":"first"},{"title":"same","value":"two","uuid":"second"}]}"""
        val f = fixture(payload = payload)
        val row = mirror(f)
        val editor = editor()
        val read = editor.load(row)
        assertEquals("original", read.username)
        assertEquals("old", security.decryptData(read.password))
        val fields = editor.fields(row.id)!!.map(CustomFieldDraft::fromCustomField)
        val before = native(f) { it.listCommitHistory(100u, null).items.size }
        editor.save(f.databaseId, listOf(read.copy(password = "new", notes = "edited") to listOf(fields[1], fields[0].copy(value = "changed"))))
        native(f) { vault ->
            val saved = readMdbxNativeObject(vault, f.objectId)
            val json = Json.parseToJsonElement(saved.payload).jsonObject
            assertEquals("new", json.getValue("password_plain").jsonPrimitive.content)
            assertEquals(Json.parseToJsonElement(payload).jsonObject["future"], json["future"])
            assertEquals("second", json.getValue("custom_fields").jsonArray[0].jsonObject.getValue("uuid").jsonPrimitive.content)
            assertEquals(before + 1, vault.listCommitHistory(100u, null).items.size)
            assertEquals(f.objectId, saved.summary.id)
            assertEquals(f.folderId, saved.summary.collectionId)
        }
        assertEquals("new", security.decryptData(database.passwordEntryDao().getPasswordEntryById(row.id)!!.password))
        lease!!.close()
        assertEquals("new", security.decryptData(editor().load(row).password))
    }

    @Test fun externalCommitRejectsStaleFormAndLeavesRoomAndNativeUntouched() = runBlocking {
        val f = fixture(); val row = mirror(f); val editor = editor(); val read = editor.load(row)
        replace(f, payload = LOGIN.replace("native-secret", "external-new"))
        val before = native(f) { it.listCommitHistory(100u, null).items.size }
        assertTrue(runCatching { editor.save(f.databaseId, listOf(read.copy(password = "stale-overwrite") to emptyList())) }.isFailure)
        native(f) { v ->
            assertEquals(before, v.listCommitHistory(100u, null).items.size)
            assertTrue(readMdbxNativeObject(v, f.objectId).payload.contains("external-new"))
        }
        assertEquals("native-secret", security.decryptData(database.passwordEntryDao().getPasswordEntryById(row.id)!!.password))
    }

    @Test fun newCredentialRowsAreCreatedTogetherWithoutRoomAssignedNativeIds() = runBlocking {
        val f = fixture(); val editor = editor()
        val group = UUID.randomUUID().toString()
        val draft = PasswordEntry(title = "New group", website = "", username = "first", password = "one",
            mdbxDatabaseId = f.databaseId, mdbxFolderId = f.folderId, passwordGroupId = group)
        val before = native(f) { it.listCommitHistory(100u, null).items.size }
        val saved = editor.save(f.databaseId, listOf(draft to emptyList(), draft.copy(username = "second", password = "two") to emptyList()))
        assertEquals(2, saved.size); assertNotEquals(saved[0].replicaGroupId, saved[1].replicaGroupId)
        native(f) { v ->
            assertEquals(before + 1, v.listCommitHistory(100u, null).items.size)
            saved.forEach { assertNotNull(v.getObjectSummary(it.replicaGroupId!!)) }
        }
    }

    @Test fun lockBackgroundAndDisposedScreenCannotSaveLateDraft() = runBlocking {
        val f = fixture(); val row = mirror(f); var editor = editor(); var read = editor.load(row)
        SessionManager.markLocked()
        assertTrue(runCatching { editor.save(f.databaseId, listOf(read.copy(password = "late") to emptyList())) }.isFailure)
        lease!!.close(); editor = editor(); read = editor.load(row)
        Mdbx2NativeReadSessions.updateForeground(false); Mdbx2NativeReadSessions.updateForeground(true)
        assertTrue(runCatching { editor.save(f.databaseId, listOf(read.copy(password = "late") to emptyList())) }.isFailure)
        lease!!.close(); editor = editor(); read = editor.load(row); lease!!.close()
        assertTrue(runCatching { editor.save(f.databaseId, listOf(read.copy(password = "late") to emptyList())) }.isFailure)
        assertTrue(native(f) { readMdbxNativeObject(it, f.objectId).payload.contains("native-secret") })
    }

    @Test fun noOpSaveCreatesNoCommitAndUnknownTypeIsRejected() = runBlocking {
        val f = fixture(); val row = mirror(f); val editor = editor(); val read = editor.load(row)
        val before = native(f) { it.listCommitHistory(100u, null).items.size }
        editor.save(f.databaseId, listOf(read.copy(password = security.decryptData(read.password), authenticatorKey = "") to emptyList()))
        assertEquals(before, native(f) { it.listCommitHistory(100u, null).items.size })
        val unknown = fixture(type = "identity", payload = """{"secret":"retained"}""")
        assertTrue(runCatching { editor.load(mirror(unknown)) }.isFailure)
        assertEquals("identity", native(unknown) { it.getObjectSummary(unknown.objectId)!!.objectTypeId })
    }

    @Test fun editRetainsAttachmentsAndSupportsDeltaSyncRevertAndSnapshotRestore() = runBlocking {
        val f = fixture(); val attachment = UUID.randomUUID().toString()
        val bytes = "synthetic retained attachment".toByteArray()
        val device = MdbxDeviceContext(MdbxDeviceAssurance.STANDARD, true, true, true)
        native(f) { v -> v.executeCompositeWriteOperation(UUID.randomUUID().toString(), "fixture-attachment",
            listOf(MdbxWriteCommand.UpdateEntry(f.objectId, f.folderId, "login", "Native source record", LOGIN)),
            listOf(MdbxAttachmentBatchCommand.Create(attachment, f.folderId, f.objectId, "fixture.txt", "text/plain", bytes))) }
        val clone = java.io.File(context.cacheDir, "editor-sync-${UUID.randomUUID()}.mdbx")
        val delta = java.io.File(context.cacheDir, "editor-sync-${UUID.randomUUID()}.mdbxsync")
        try {
            val snapshot = native(f) { it.createManualSnapshot("before editor", device) }
            val checkpoint = native(f) { it.createIncrementalSyncBootstrap(clone.path).checkpoint }
            val editor = editor(); val read = editor.load(mirror(f))
            editor.save(f.databaseId, listOf(read.copy(password = "after-edit") to emptyList()))
            val committed = native(f) { v ->
                assertArrayEquals(bytes, v.readAttachmentContent(attachment, 1024uL))
                val history = v.listCommitHistory(100u, null).items.first { it.operationKind == "native-password-editor" }
                assertEquals(1, history.changes.size)
                assertEquals(f.objectId, history.changes.single().objectId)
                val segment = v.exportIncrementalSyncSegment(delta.path, checkpoint, null, 100u)
                assertTrue(segment.isLast); assertTrue(segment.commitCount > 0u)
                history.commitId
            }
            val other = openVault(clone.path, vaultPassword, "editor-sync-peer")
            try {
                other.applyIncrementalSyncSegment(delta.path, checkpoint, null)
                assertTrue(readMdbxNativeObject(other, f.objectId).payload.contains("after-edit"))
                assertArrayEquals(bytes, other.readAttachmentContent(attachment, 1024uL))
            } finally { other.close() }
            native(f) { v ->
                v.revertCommit(committed, UUID.randomUUID().toString(), device)
                assertTrue(readMdbxNativeObject(v, f.objectId).payload.contains("native-secret"))
                assertArrayEquals(bytes, v.readAttachmentContent(attachment, 1024uL))
                v.restoreSnapshot(snapshot.snapshotId, device)
                assertTrue(readMdbxNativeObject(v, f.objectId).payload.contains("native-secret"))
            }
            assertTrue(runCatching { editor.save(f.databaseId, listOf(read.copy(password = "late") to emptyList())) }.isFailure)
        } finally {
            Mdbx2NativeReadSessions.clear()
            listOf(clone, java.io.File(clone.path + "-wal"), java.io.File(clone.path + "-shm"), delta).forEach { it.delete() }
        }
    }

    @Test fun committedCreateReadbackFailureRetriesWithoutDuplicateAndFinalizesSyncStatus() = runBlocking {
        val f = fixture()
        SessionManager.markUnlocked(); Mdbx2NativeReadSessions.updateForeground(true)
        var failReadback = true
        val editor = MdbxPasswordEditor(context, database, security) { vault, id ->
            if (failReadback) { failReadback = false; throw java.io.IOException("synthetic readback failure") }
            readMdbxNativeObject(vault, id)
        }
        lease = editor.begin()
        database.localMdbxDatabaseDao().updateSyncStatus(f.databaseId, MdbxSyncStatus.IN_SYNC.name, null)
        val draft = PasswordEntry(title = "Retry creation", username = "synthetic", password = "retry-secret", website = "",
            mdbxDatabaseId = f.databaseId, mdbxFolderId = f.folderId)
        val before = native(f) { it.listCommitHistory(100u, null).items.size }
        assertTrue(runCatching { editor.save(f.databaseId, listOf(draft to emptyList())) }.isFailure)
        assertEquals(0, database.passwordEntryDao().getByMdbxDatabaseIdSync(f.databaseId).size)
        assertEquals(MdbxSyncStatus.LOCAL_ONLY.name, database.localMdbxDatabaseDao().getDatabaseById(f.databaseId)!!.lastSyncStatus)
        val saved = editor.save(f.databaseId, listOf(draft to emptyList())).single()
        assertEquals("retry-secret", security.decryptData(saved.password))
        assertEquals(before + 1, native(f) { it.listCommitHistory(100u, null).items.size })
        assertEquals(2, native(f) { readMdbxNativeBrowser(it).objects.size })
    }

    @Test fun revocationDuringCommittedReadbackDoesNotRetainOrPublishLatePayload() = runBlocking {
        val f = fixture()
        SessionManager.markUnlocked(); Mdbx2NativeReadSessions.updateForeground(true)
        val editor = MdbxPasswordEditor(context, database, security) { vault, id ->
            val detail = readMdbxNativeObject(vault, id)
            Mdbx2NativeReadSessions.updateForeground(false)
            detail
        }
        lease = editor.begin()
        val before = native(f) { it.listCommitHistory(100u, null).items.size }
        val draft = PasswordEntry(title = "Revoked readback", username = "synthetic", password = "late-secret",
            website = "", mdbxDatabaseId = f.databaseId, mdbxFolderId = f.folderId)
        assertTrue(runCatching { editor.save(f.databaseId, listOf(draft to emptyList())) }.isFailure)
        assertFalse(editor.isOpen)
        assertTrue(editor.entries().isEmpty())
        assertTrue(database.passwordEntryDao().getByMdbxDatabaseIdSync(f.databaseId).isEmpty())
        // The commit is durable, but revocation must also release the private readback cache.
        for (name in listOf("pending", "committed")) {
            val field = MdbxPasswordEditor::class.java.getDeclaredField(name).apply { isAccessible = true }
            assertNull("Late payload retained in $name", field.get(editor))
        }
        native(f) { v ->
            assertEquals(before + 1, v.listCommitHistory(100u, null).items.size)
            assertEquals(2, readMdbxNativeBrowser(v).objects.size)
        }
        assertEquals(MdbxSyncStatus.LOCAL_ONLY.name, database.localMdbxDatabaseDao().getDatabaseById(f.databaseId)!!.lastSyncStatus)
    }

    @Test fun groupedSaveUsesOneCommitAndNativeTombstoneForRemovedCredential() = runBlocking {
        val group = UUID.randomUUID().toString()
        val f = fixture(payload = """{"password_plain":"one","password_group_id":"$group"}""")
        val second = UUID.randomUUID().toString()
        native(f) { it.executeWriteOperation(UUID.randomUUID().toString(), "fixture-sibling", listOf(
            MdbxWriteCommand.CreateEntry(second, f.folderId, "login", "Second", """{"password_plain":"two","password_group_id":"$group"}"""))) }
        val editor = editor(); val first = editor.load(mirror(f))
        val sibling = editor.entries().single { it.id != first.id }
        val previous = native(f) { it.listCommitHistory(100u, null).items.first().commitId }
        editor.save(f.databaseId, listOf(first.copy(password = "changed") to emptyList()), listOf(sibling.id))
        native(f) { v ->
            assertTrue(v.getObjectSummary(second)!!.deleted)
            val commit = v.listCommitHistory(100u, null).items.first()
            assertEquals(2, commit.changes.size)
            assertTrue(commit.parentIds.contains(previous))
            assertEquals("native-password-editor", commit.operationKind)
        }
        assertTrue(database.passwordEntryDao().getPasswordEntryById(sibling.id)!!.isDeleted)
        assertEquals(first.replicaGroupId, database.passwordEntryDao().getPasswordEntryById(first.id)!!.replicaGroupId)
    }
}
