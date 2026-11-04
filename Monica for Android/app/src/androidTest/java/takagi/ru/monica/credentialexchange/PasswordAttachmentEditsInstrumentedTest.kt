package takagi.ru.monica.credentialexchange

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.crypto.BitwardenAttachmentCrypto
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.ui.PasswordAttachmentEdits
import takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.BitwardenSyncService
import takagi.ru.monica.bitwarden.service.UploadResult
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SessionManager

/** Synthetic server and files only. Queue recreation is not a claim of process-kill UI testing. */
@RunWith(AndroidJUnit4::class)
class PasswordAttachmentEditsInstrumentedTest {
    private val bytes = ByteArray(4097) { (it * 17).toByte() }

    private suspend fun TransferFixture.parent(vaultId: Long): Long = db.passwordEntryDao().insertPasswordEntry(
        PasswordEntry(title = prefix, username = "synthetic", website = "https://example.invalid",
            password = security.encryptData("synthetic password"), bitwardenVaultId = vaultId, bitwardenLocalModified = true))

    private fun TransferFixture.file(name: String = "same-name.bin") = File(root, name).also { it.writeBytes(bytes) }
    private fun TransferFixture.pending(vaultId: Long) = context.getSharedPreferences("credential_import_attachments_v1", 0)
        .getStringSet("vault:$vaultId", emptySet()).orEmpty()

    private suspend fun TransferFixture.syncParents(vaultId: Long) {
        val vault = requireNotNull(db.bitwardenVaultDao().getVaultById(vaultId))
        val result = BitwardenSyncService(context).uploadLocalEntries(vault, accessToken, vaultKey) as UploadResult.Success
        assertEquals("Parent creation must succeed with an authenticated profile and encryptedFor", 0, result.failed)
    }

    private suspend fun TransferFixture.syncAttachments(vaultId: Long) {
        val vault = requireNotNull(db.bitwardenVaultDao().getVaultById(vaultId))
        BitwardenSyncService(context).uploadModifiedEntries(vault, accessToken, vaultKey)
    }

    @Test fun missingUserIdReadsAuthenticatedProfileBeforeCreatingParent() = runBlocking {
        val fixture = TransferFixture()
        try { with(fixture) {
            val vaultId = bitwarden().databaseId
            val bound = requireNotNull(db.bitwardenVaultDao().getVaultById(vaultId))
            val expectedId = requireNotNull(bound.userId)
            db.bitwardenVaultDao().update(bound.copy(userId = null))
            val parentId = parent(vaultId)
            syncParents(vaultId)
            assertTrue("Missing userId must use the authenticated profile endpoint", remote.profileReads.get() > 0)
            assertEquals(expectedId, remote.created.single().getString("encryptedFor"))
            assertEquals(remote.created.single().getString("id"), db.passwordEntryDao().getPasswordEntryById(parentId)!!.bitwardenCipherId)
        } } finally { fixture.close() }
    }

    @Test fun savedOfflineParentAndQueueRecreationUploadOnlyExplicitSameNameAttachment() = runBlocking {
        val f = TransferFixture()
        try { with(f) {
            val vaultId = bitwarden().databaseId
            BitwardenVaultPremiumStore.setPremium(context, vaultId, true)
            val id = parent(vaultId)
            val owner = AttachmentOwner.password(id)
            val facade = AttachmentContainer.facade(context)
            val uri = Uri.fromFile(file())
            val preexisting = facade.addAttachment(AttachmentFacade.UploadRequest(owner, AttachmentSource.LOCAL, uri, true))
            PasswordAttachmentEdits(context).persist(id, uri, true, false)
            val queued = facade.list(owner).single { it.id != preexisting.id }
            assertEquals(setOf(queued.id.toString()), pending(vaultId))
            assertNull(db.passwordEntryDao().getPasswordEntryById(id)!!.bitwardenCipherId)
            assertEquals(AttachmentSource.LOCAL, queued.sourceEnum)
            assertArrayEquals(bytes, facade.readAttachmentBytes(queued.id, 8192))
            // A new service/queue reads the on-disk intent after parent creation.
            syncParents(vaultId)
            syncAttachments(vaultId)
            val uploaded = facade.getById(queued.id)!!
            assertEquals(AttachmentSource.BITWARDEN, uploaded.sourceEnum)
            assertEquals(AttachmentSource.LOCAL, facade.getById(preexisting.id)!!.sourceEnum)
            assertTrue(pending(vaultId).isEmpty())
            assertEquals(1, remote.attachmentCreates.size)
            val metadata = remote.attachmentCreates.single()
            val parent = remote.created.single { it.getString("id") == metadata.getString("cipherId") }
            val itemKeyBytes = parent.optString("key").takeIf { it.isNotBlank() && it != "null" }?.let { BitwardenCrypto.decrypt(it, vaultKey) }
            val key = itemKeyBytes?.let { BitwardenCrypto.SymmetricCryptoKey(it.copyOfRange(0, 32), it.copyOfRange(32, 64)) } ?: vaultKey
            val attachmentKeyBytes = BitwardenCrypto.decrypt(metadata.getString("key"), key)
            val attachmentKey = BitwardenCrypto.SymmetricCryptoKey(attachmentKeyBytes.copyOfRange(0, 32), attachmentKeyBytes.copyOfRange(32, 64))
            try {
                val remoteFile = File(root, "remote.enc").also { it.writeBytes(remote.attachmentUploads.getValue(uploaded.bitwardenAttachmentId!!)) }
                BitwardenAttachmentCrypto.openDecrypted(remoteFile, attachmentKey).use { assertArrayEquals(bytes, it.readBytes()) }
            } finally { attachmentKey.clear(); attachmentKeyBytes.fill(0); if (itemKeyBytes != null) { key.clear(); itemKeyBytes.fill(0) } }
            syncAttachments(vaultId)
            assertEquals(1, remote.attachmentCreates.size)
        } } finally { f.close() }
    }

    @Test fun failedUploadKeepsBytesAndPersistentIntentUntilRetry() = runBlocking {
        val f = TransferFixture()
        try { with(f) {
            val vaultId = bitwarden().databaseId
            BitwardenVaultPremiumStore.setPremium(context, vaultId, true)
            val id = parent(vaultId)
            PasswordAttachmentEdits(context).persist(id, Uri.fromFile(file()), true, false)
            syncParents(vaultId)
            remote.rejectAttachments = true
            syncAttachments(vaultId)
            val facade = AttachmentContainer.facade(context)
            val attachment = facade.list(AttachmentOwner.password(id)).single()
            assertEquals(AttachmentSource.LOCAL, attachment.sourceEnum)
            assertEquals(setOf(attachment.id.toString()), pending(vaultId))
            assertArrayEquals(bytes, facade.readAttachmentBytes(attachment.id, 8192))
            remote.rejectAttachments = false
            syncAttachments(vaultId)
            assertEquals(AttachmentSource.BITWARDEN, facade.getById(attachment.id)!!.sourceEnum)
            assertTrue(pending(vaultId).isEmpty())
        } } finally { f.close() }
    }

    @Test fun nonPremiumAndLockedVaultCannotStageFiles() = runBlocking {
        val f = TransferFixture()
        try { with(f) {
            val vaultId = bitwarden().databaseId
            val id = parent(vaultId)
            val edits = PasswordAttachmentEdits(context)
            val uri = Uri.fromFile(file())
            assertTrue(runCatching { edits.persist(id, uri, true, false) }.exceptionOrNull() is AttachmentError.PremiumRequired)
            BitwardenVaultPremiumStore.setPremium(context, vaultId, true)
            BitwardenRepository.getInstance(context).lock(vaultId)
            assertTrue(runCatching { edits.persist(id, uri, true, false) }.exceptionOrNull() is AttachmentError.BitwardenLocked)
            assertTrue(AttachmentContainer.facade(context).list(AttachmentOwner.password(id)).isEmpty())
            assertTrue(pending(vaultId).isEmpty())
        } } finally { f.close() }
    }

    @Test fun deletedAndReboundParentsNeverUploadOldIntents() = runBlocking {
        val f = TransferFixture()
        try { with(f) {
            val first = bitwarden().databaseId
            val second = bitwarden().databaseId
            BitwardenVaultPremiumStore.setPremium(context, first, true)
            val moved = parent(first)
            val deleted = parent(first)
            val edits = PasswordAttachmentEdits(context)
            edits.persist(moved, Uri.fromFile(file()), true, false)
            edits.persist(deleted, Uri.fromFile(file("deleted.bin")), true, false)
            val dao = db.passwordEntryDao()
            dao.updatePasswordEntry(dao.getPasswordEntryById(moved)!!.copy(bitwardenVaultId = second))
            dao.updatePasswordEntry(dao.getPasswordEntryById(deleted)!!.copy(isDeleted = true))
            syncAttachments(first)
            assertTrue(remote.attachmentCreates.isEmpty())
            assertTrue(pending(first).isEmpty())
            assertTrue(pending(second).isEmpty())
            for (id in listOf(moved, deleted)) {
                val attachment = AttachmentContainer.facade(context).list(AttachmentOwner.password(id)).single()
                assertEquals(AttachmentSource.LOCAL, attachment.sourceEnum)
                assertArrayEquals(bytes, AttachmentContainer.facade(context).readAttachmentBytes(attachment.id, 8192))
            }
        } } finally { f.close() }
    }

    @Test fun lockDuringUploadPreventsPublicationAndRetainsLocalSource() = runBlocking {
        val f = TransferFixture()
        try { with(f) {
            val vaultId = bitwarden().databaseId
            BitwardenVaultPremiumStore.setPremium(context, vaultId, true)
            val id = parent(vaultId)
            PasswordAttachmentEdits(context).persist(id, Uri.fromFile(file()), true, false)
            syncParents(vaultId)
            val deleted = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "DELETE" && request.path.orEmpty().contains("/attachment/")) {
                        deleted += request.path!!
                        return MockResponse().setResponseCode(200)
                    }
                    return remote.dispatch(request).also {
                        if (request.method == "PUT" && request.path.orEmpty().startsWith("/blobs/")) SessionManager.markLocked()
                    }
                }
            }
            syncAttachments(vaultId)
            val facade = AttachmentContainer.facade(context)
            val attachment = facade.list(AttachmentOwner.password(id)).single()
            assertEquals(AttachmentSource.LOCAL, attachment.sourceEnum)
            assertEquals(setOf(attachment.id.toString()), pending(vaultId))
            assertEquals(1, deleted.size)
            SessionManager.markUnlocked()
            assertArrayEquals(bytes, facade.readAttachmentBytes(attachment.id, 8192))
        } } finally { f.close() }
    }

    @Test fun removingQueuedFilePreventsLaterUpload() = runBlocking {
        val f = TransferFixture()
        try { with(f) {
            val vaultId = bitwarden().databaseId
            BitwardenVaultPremiumStore.setPremium(context, vaultId, true)
            val id = parent(vaultId)
            val edits = PasswordAttachmentEdits(context)
            edits.persist(id, Uri.fromFile(file()), true, false)
            val facade = AttachmentContainer.facade(context)
            edits.remove(id, facade.list(AttachmentOwner.password(id)).single().id)
            syncParents(vaultId)
            syncAttachments(vaultId)
            assertTrue(facade.list(AttachmentOwner.password(id)).isEmpty())
            assertTrue(remote.attachmentCreates.isEmpty())
            assertTrue(pending(vaultId).isEmpty())
        } } finally { f.close() }
    }
}
