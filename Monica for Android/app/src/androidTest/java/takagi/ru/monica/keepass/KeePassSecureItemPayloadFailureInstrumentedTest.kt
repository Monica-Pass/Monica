package takagi.ru.monica.keepass

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.models.EntryValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.credentialexchange.TransferFixture
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.utils.KeePassKdbxService

/** Real service/file regression; the fixture creates and removes only its own synthetic vault. */
@RunWith(AndroidJUnit4::class)
class KeePassSecureItemPayloadFailureInstrumentedTest {
    @Test
    fun unreadableSecurePayloadRejectsBatchCreateAndUpdateWithoutChangingKdbxBytes() = runBlocking {
        val user = Process.myUid() / 100_000
        check(user > 0 && InstrumentationRegistry.getArguments().getString("keepassRefactorUser") == user.toString()) {
            "Requires an explicitly owned secondary user via keepassRefactorUser"
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val startup = withContext(Dispatchers.Default) {
            SecureStorageStartup.prepareForEntry(context, timeoutMillis = 15_000L)
        }
        assertTrue("Storage must be ready before constructing the fixture", startup is SecureStartupResult.Ready)
        val fixture = TransferFixture()
        try {
            with(fixture) {
                val target = keepass()
                val service = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
                val card = BankCardData(
                    cardNumber = "4111111111111111", cardholderName = "Synthetic Holder",
                    expiryMonth = "09", expiryYear = "2031", cvv = "123", pin = "4321"
                )
                val original = SecureItem(
                    id = 1001L, itemType = ItemType.BANK_CARD, title = "$prefix-original",
                    itemData = security.encryptData(CardWalletDataCodec.encodeBankCardData(card)),
                    keepassDatabaseId = target.databaseId
                )
                assertEquals(1, service.addOrUpdateSecureItems(
                    target.databaseId, listOf(original), forceSyncWrite = true
                ).getOrThrow())
                val nativeBefore = keepassEntries(target.databaseId).single()
                val originalUpdate = original.copy(keepassEntryUuid = nativeBefore.uuid.toString())
                val file = keepassFile(target.databaseId)
                val bytesBefore = file.readBytes()
                val fieldsBefore = nativeBefore.fields.map { (key, value) ->
                    Triple(key, value.content, value is EntryValue.Encrypted)
                }
                assertEquals(card.cardNumber, nativeBefore.fields.getValue("Card Number").content)
                assertTrue(nativeBefore.fields.getValue("Card Number") is EntryValue.Encrypted)
                assertTrue(nativeBefore.fields.getValue("Card CVV") is EntryValue.Encrypted)

                // A recognized envelope containing only one byte cannot contain an IV and GCM tag.
                val unreadable = "C2|AA=="
                assertTrue(security.looksLikeMonicaCiphertext(unreadable))
                val newItem = original.copy(id = 2002L, title = "$prefix-new")
                val scenarios = listOf(
                    "create" to listOf(
                        originalUpdate.copy(title = "$prefix-must-not-update"),
                        newItem.copy(itemData = unreadable)
                    ),
                    "update" to listOf(
                        newItem,
                        originalUpdate.copy(title = "$prefix-must-not-replace", itemData = unreadable)
                    )
                )
                for ((operation, batch) in scenarios) {
                    val result = service.addOrUpdateSecureItems(target.databaseId, batch, forceSyncWrite = true)
                    assertTrue("Unreadable payload must reject $operation", result.isFailure)
                    val failure = requireNotNull(result.exceptionOrNull())
                    assertTrue("Failure must originate from secure payload portability: $failure",
                        generateSequence(failure) { it.cause }.any {
                            it.message?.contains("Cannot write encrypted secure item data to KeePass") == true
                        })
                    assertArrayEquals("Failed $operation must not rewrite the encrypted file", bytesBefore, file.readBytes())
                    KeePassKdbxService.invalidateProcessCache(target.databaseId)
                    val reopened = service.readSecureItems(target.databaseId).getOrThrow().single().item
                    assertEquals(original.title, reopened.title)
                    assertEquals(card, CardWalletDataCodec.parseBankCardData(reopened.itemData))
                    val nativeAfter = keepassEntries(target.databaseId).single()
                    assertEquals(nativeBefore.uuid, nativeAfter.uuid)
                    assertEquals(nativeBefore.history.size, nativeAfter.history.size)
                    assertEquals(fieldsBefore, nativeAfter.fields.map { (key, value) ->
                        Triple(key, value.content, value is EntryValue.Encrypted)
                    })
                    assertArrayEquals("Cold reading after $operation must also preserve the file", bytesBefore, file.readBytes())
                }

                // Failure must not leave a poisoned cached plan or prevent the next authorized write.
                service.addOrUpdateSecureItems(target.databaseId,
                    listOf(originalUpdate.copy(title = "$prefix-retry")), forceSyncWrite = true).getOrThrow()
                KeePassKdbxService.invalidateProcessCache(target.databaseId)
                val retried = service.readSecureItems(target.databaseId).getOrThrow().single().item
                assertEquals("$prefix-retry", retried.title)
                assertEquals(card, CardWalletDataCodec.parseBankCardData(retried.itemData))
                assertEquals(nativeBefore.uuid, keepassEntries(target.databaseId).single().uuid)
            }
        } finally {
            fixture.close()
        }
    }
}
