package takagi.ru.monica.autofill_ng

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import java.util.Date

/** Tests scope selection before the decrypt adapter; real encryption is covered by WalletAutofillRepositoryTest. */
class WalletAutofillLocalScopeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun owner(title: String) = PasswordEntry(title = title, website = "", username = "", password = "")
    private fun card() = SecureItem(itemType = ItemType.BANK_CARD, title = "Synthetic card",
        itemData = Json.encodeToString(BankCardData("4111111111111111", "Synthetic Person", "08", "2030", "123")))

    // Virtual display items receive fresh timestamps on each load; compare all stable fields.
    private fun stable(items: List<SecureItem>) = items.map {
        it.copy(createdAt = Date(0), updatedAt = Date(0))
    }

    private suspend fun addCard(db: PasswordDatabase, row: PasswordEntry, marker: String,
        payloads: MutableMap<String, String>): Long {
        val id = db.passwordEntryDao().insertPasswordEntry(row)
        payloads[marker] = EmbeddedWalletContent.create(card(), marker).encode()
        return db.customFieldDao().insert(CustomField(entryId = id,
            title = EmbeddedWalletContent.fieldName(EmbeddedWalletContent.Kind.BANK_CARD),
            value = marker, isProtected = true))
    }

    @Test fun localEmbeddedFiltersBeforeDecryptAndDefaultIncludesOtherSources() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val payloads = linkedMapOf<String, String>()
            val mdbx = db.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(
                name = "Synthetic scope MDBX", filePath = "unused-scope-fixture", engineType = MdbxEngineType.RUST_MDBX2.name))
            val localField = addCard(db, owner("Local"), "local-envelope", payloads)
            addCard(db, owner("KeePass").copy(keepassDatabaseId = 8, keepassGroupPath = "Work"), "keepass-envelope", payloads)
            addCard(db, owner("Bitwarden").copy(bitwardenVaultId = 9, bitwardenFolderId = "folder"), "bitwarden-envelope", payloads)
            addCard(db, owner("MDBX").copy(mdbxDatabaseId = mdbx), "mdbx-envelope", payloads)
            addCard(db, owner("Conflict").copy(keepassDatabaseId = 8, bitwardenVaultId = 9), "conflict-envelope", payloads)
            val before = db.customFieldDao().getAllFieldsSync()
            val calls = mutableListOf<String>()
            val repo = WalletAutofillRepository(db) { value -> calls += value; payloads.getValue(value) }

            val local = repo.loadEmbeddedItems(localOnly = true)
            assertEquals(listOf(-localField), local.map { it.id })
            assertEquals(listOf("local-envelope"), calls)
            assertTrue(local.all { it.isLocalOnlyItem() })
            calls.clear()
            val all = repo.loadEmbeddedItems()
            assertEquals(4, all.size)
            assertEquals(setOf("local-envelope", "keepass-envelope", "bitwarden-envelope", "mdbx-envelope"), calls.toSet())
            assertEquals(4, calls.size)
            assertEquals(stable(local), stable(all.filter { it.isLocalOnlyItem() }))

            val formerExternal = all.single { it.keepassDatabaseId == 8L }
            calls.clear()
            repo.loadEmbeddedItems(localOnly = true)
            assertNull(repo.resolveCurrent(formerExternal))
            assertEquals(listOf("local-envelope"), calls)
            assertEquals(before, db.customFieldDao().getAllFieldsSync())
            assertTrue(db.secureItemDao().getActiveItemsByTypeSync(ItemType.BANK_CARD).isEmpty())
        } finally { db.close() }
    }

    @Test fun emptyBitwardenBindingKeepsOriginalLocalOwnershipAndDualOwnersStayExcluded() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val payloads = linkedMapOf<String, String>()
            val legacy = addCard(db, owner("Legacy local").copy(bitwardenVaultId = 19), "legacy-envelope", payloads)
            addCard(db, owner("Dual empty owners").copy(keepassDatabaseId = 8, bitwardenVaultId = 19), "dual-envelope", payloads)
            addCard(db, owner("Real folder").copy(bitwardenVaultId = 19, bitwardenFolderId = "folder"), "folder-envelope", payloads)
            val calls = mutableListOf<String>()
            val repo = WalletAutofillRepository(db) { value -> calls += value; payloads.getValue(value) }
            val local = repo.loadEmbeddedItems(localOnly = true)
            assertEquals(listOf(-legacy), local.map { it.id })
            assertEquals(listOf("legacy-envelope"), calls)
            assertTrue(local.single().isLocalOnlyItem())
            calls.clear()
            val all = repo.loadEmbeddedItems()
            assertEquals(2, all.size)
            assertEquals(stable(local), stable(all.filter { it.isLocalOnlyItem() }))
            assertFalse(calls.contains("dual-envelope"))
        } finally { db.close() }
    }

    @Test fun localLegacyWalletSkipsExternalOwnersBeforeFieldDecrypt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            db.passwordEntryDao().insertPasswordEntry(owner("Legacy local").copy(
                creditCardNumber = "local-card", creditCardExpiry = "08/2030", addressLine = "local-address"))
            db.passwordEntryDao().insertPasswordEntry(owner("Legacy KeePass").copy(keepassDatabaseId = 8,
                creditCardNumber = "external-card", creditCardExpiry = "08/2030", addressLine = "external-address"))
            val payloads = mapOf("local-card" to "4111111111111111", "local-address" to "Synthetic local street",
                "external-card" to "4242424242424242", "external-address" to "Synthetic external street")
            val calls = mutableListOf<String>()
            val repo = WalletAutofillRepository(db) { value ->
                if (value.isEmpty()) "" else { calls += value; payloads.getValue(value) }
            }
            val local = repo.loadEmbeddedItems(localOnly = true)
            assertEquals(2, local.size)
            assertEquals(setOf("local-card", "local-address"), calls.toSet())
            assertEquals(2, calls.size)
            assertEquals("4111111111111111", parseBankCardCandidate(local.single { it.itemType == ItemType.BANK_CARD })!!.second.cardNumber)
            assertEquals("Synthetic local street", parseBillingAddressCandidate(local.single { it.itemType == ItemType.BILLING_ADDRESS })!!.second.streetAddress)
            calls.clear()
            val all = repo.loadEmbeddedItems()
            assertEquals(4, all.size)
            assertEquals(payloads.keys, calls.toSet())
            assertEquals(stable(local), stable(all.filter { it.isLocalOnlyItem() }))
        } finally { db.close() }
    }

    @Test fun unreadableLocalSnapshotStillSuppressesLegacyWithoutWriting() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val id = db.passwordEntryDao().insertPasswordEntry(owner("Local").copy(creditCardNumber = "must-not-read-legacy"))
            db.customFieldDao().insert(CustomField(entryId = id,
                title = EmbeddedWalletContent.fieldName(EmbeddedWalletContent.Kind.BANK_CARD), value = "broken-envelope"))
            val before = db.customFieldDao().getAllFieldsSync()
            val calls = mutableListOf<String>()
            val repo = WalletAutofillRepository(db) { value -> calls += value; error("Synthetic unreadable payload") }
            assertTrue(repo.loadEmbeddedItems(localOnly = true).isEmpty())
            assertEquals(listOf("broken-envelope"), calls)
            assertEquals(before, db.customFieldDao().getAllFieldsSync())
            assertEquals("must-not-read-legacy", db.passwordEntryDao().getPasswordEntryById(id)!!.creditCardNumber)
        } finally { db.close() }
    }
}
