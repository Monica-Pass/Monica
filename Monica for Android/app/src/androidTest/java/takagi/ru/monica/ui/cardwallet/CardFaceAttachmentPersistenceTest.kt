package takagi.ru.monica.ui.cardwallet

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.CardFaceAttachmentManager
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxStorageLocation
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.CardFaceAttachment
import takagi.ru.monica.data.model.CardFaceConfig
import takagi.ru.monica.data.model.BillingAddressData
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.repository.MdbxRepositoryFactory
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.viewmodel.BillingAddressViewModel

@RunWith(AndroidJUnit4::class)
class CardFaceAttachmentPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository by lazy { SecureItemRepository(PasswordDatabase.getDatabase(context).secureItemDao()) }
    private val facade by lazy { AttachmentContainer.facade(context) }
    private val manager by lazy { CardFaceAttachmentManager(repository, facade, null) }

    @Test
    fun allWalletTypesKeepIndependentEncryptedImagesWhenCopiedReplacedAndRemoved() = runBlocking {
        for (type in listOf(ItemType.BANK_CARD, ItemType.DOCUMENT, ItemType.BILLING_ADDRESS)) {
            val sourceId = insert(type)
            val copyId = insert(type)
            val firstBytes = jpeg(Color.BLUE)
            val replacementBytes = jpeg(Color.GREEN)
            try {
                val first = CardFaceConfig(CardFaceAttachment.newFileName())
                manager.update(sourceId, first, firstBytes, null).getOrThrow()
                val original = facade.list(AttachmentOwner.secureItem(sourceId)).single()
                assertEquals(AttachmentSource.LOCAL, original.sourceEnum)
                assertNotNull(original.wrappedCek)
                assertNotNull(original.localPath)
                assertArrayEquals(firstBytes, facade.readAttachmentBytes(original.id, 100_000))

                manager.update(copyId, first, null, null, sourceItemId = sourceId).getOrThrow()
                val copied = facade.list(AttachmentOwner.secureItem(copyId)).single()
                assertNotEquals(original.id, copied.id)
                assertNotEquals(original.localPath, copied.localPath)
                assertArrayEquals(firstBytes, facade.readAttachmentBytes(copied.id, 100_000))

                val replacement = CardFaceConfig(CardFaceAttachment.newFileName(), showBrandIcon = false)
                manager.update(copyId, replacement, replacementBytes, first.imageAttachmentName).getOrThrow()
                val replaced = facade.list(AttachmentOwner.secureItem(copyId)).single()
                assertEquals(replacement.imageAttachmentName, replaced.fileName)
                assertArrayEquals(replacementBytes, facade.readAttachmentBytes(replaced.id, 100_000))

                manager.update(copyId, null, null, replacement.imageAttachmentName).getOrThrow()
                assertTrue(facade.list(AttachmentOwner.secureItem(copyId)).isEmpty())
                assertArrayEquals(firstBytes, facade.readAttachmentBytes(original.id, 100_000))
            } finally {
                firstBytes.fill(0)
                replacementBytes.fill(0)
                cleanup(sourceId)
                cleanup(copyId)
            }
        }
    }

    @Test
    fun transferringCachedRemoteImageDoesNotDeleteTheNewBackendImage() = runBlocking {
        val id = insert(ItemType.BANK_CARD)
        val bytes = jpeg(Color.RED)
        try {
            val face = CardFaceConfig(CardFaceAttachment.newFileName())
            manager.update(id, face, bytes, null).getOrThrow()
            val old = facade.list(AttachmentOwner.secureItem(id)).single()
            // This is the cached source record left while an owner moves out of KeePass.
            AttachmentContainer.repository(context).update(
                old.copy(source = AttachmentSource.KEEPASS.name, keepassBinaryRef = "previous-database-binary")
            )
            manager.update(id, face, bytes, face.imageAttachmentName, ownerBackendChanged = true).getOrThrow()
            val transferred = facade.list(AttachmentOwner.secureItem(id)).single()
            assertEquals(AttachmentSource.LOCAL, transferred.sourceEnum)
            assertNotEquals(old.id, transferred.id)
            assertArrayEquals(bytes, facade.readAttachmentBytes(transferred.id, 100_000))
            AttachmentContainer.repository(context).update(
                transferred.copy(source = AttachmentSource.KEEPASS.name, keepassBinaryRef = "previous-database-binary")
            )
            manager.update(id, null, null, face.imageAttachmentName, ownerBackendChanged = true).getOrThrow()
            assertTrue(facade.list(AttachmentOwner.secureItem(id)).isEmpty())
        } finally {
            bytes.fill(0)
            cleanup(id)
        }
    }

    @Test
    fun billingAddressMoveIntoMdbxStoresTheImageAndMoveBackPreservesIt() = runBlocking {
        val room = PasswordDatabase.getDatabase(context)
        val security = SecurityManager(context)
        val databaseDao = room.localMdbxDatabaseDao()
        val vaultPassword = "card-face-${UUID.randomUUID()}"
        val vaultFile = Mdbx2Repository(context, databaseDao, security)
            .createInitializedVaultFile(MdbxTigaMode.SKY, vaultPassword)
        val mdbx = MdbxRepositoryFactory.create(context, room, security)
        val items = SecureItemRepository(room.secureItemDao(), mdbx)
        val viewModel = BillingAddressViewModel(items, security, context)
        val bytes = jpeg(Color.CYAN)
        var databaseId = 0L
        var itemId = 0L
        try {
            databaseId = databaseDao.insertDatabase(
                LocalMdbxDatabase(
                    name = "Card face storage test",
                    filePath = vaultFile.absolutePath,
                    storageLocation = MdbxStorageLocation.INTERNAL.name,
                    sourceType = MdbxSourceType.LOCAL_INTERNAL.name,
                    engineType = MdbxEngineType.RUST_MDBX2.name,
                    tigaMode = MdbxTigaMode.SKY.name,
                    encryptedPassword = security.encryptData(vaultPassword),
                    unlockMethod = MdbxUnlockMethod.MASTER_PASSWORD.storedValue,
                    kdfProfile = "argon2id-mdbx2",
                    workingCopyPath = vaultFile.absolutePath,
                    cacheCopyPath = vaultFile.absolutePath,
                    isOfflineAvailable = true
                )
            )
            val face = CardFaceConfig(CardFaceAttachment.newFileName())
            itemId = viewModel.addAddress(
                title = "Test address",
                addressData = BillingAddressData(fullName = "Test", streetAddress = "123 Test Road", cardFace = face),
                cardFaceImageBytes = bytes
            ).await()
            assertTrue(viewModel.moveAddressToStorage(itemId, null, databaseId))
            val reopened = MdbxRepositoryFactory.create(context, room, security)
            val stored = reopened.readStoredAttachments(databaseId).single()
            assertEquals(face.imageAttachmentName, stored.fileName)
            assertEquals(bytes.size.toLong(), stored.originalSize)
            assertTrue(stored.blob.isNotEmpty())
            assertTrue(!stored.wrappedCek.isNullOrBlank())
            assertTrue(viewModel.moveAddressToStorage(itemId, null))
            val local = facade.list(AttachmentOwner.secureItem(itemId)).single()
            assertArrayEquals(bytes, facade.readAttachmentBytes(local.id, 100_000))
        } finally {
            viewModel.viewModelScope.cancel()
            bytes.fill(0)
            if (itemId > 0L) {
                items.getItemById(itemId)?.let { mdbx.deleteSecureItem(it) }
                cleanup(itemId)
            }
            if (databaseId > 0L) databaseDao.deleteDatabaseById(databaseId)
            vaultFile.delete()
        }
    }

    private suspend fun insert(type: ItemType): Long = repository.insertItem(
        SecureItem(itemType = type, title = "card-face-test-${UUID.randomUUID()}", itemData = "{}")
    )

    private suspend fun cleanup(id: Long) {
        facade.list(AttachmentOwner.secureItem(id)).forEach { facade.forgetLocalAttachment(it.id) }
        repository.deleteItemById(id)
    }

    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
