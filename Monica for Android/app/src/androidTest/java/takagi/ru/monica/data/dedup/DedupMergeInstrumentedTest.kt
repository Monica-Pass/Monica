package takagi.ru.monica.data.dedup

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.SecureCustomField
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.AppLocaleStringResolver

/** Core planner/writer tests use in-memory Room with unsynced synthetic drafts.
 * Native KeePass/BW source compatibility is tested separately with authoritative snapshots. */
@RunWith(AndroidJUnit4::class)
class DedupMergeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var service: DedupMergeService
    private lateinit var security: SecurityManager
    private val sources = setOf("bitwarden:1", "bitwarden:2")
    private var wasUnlocked = false

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        wasUnlocked = takagi.ru.monica.security.SessionManager.isUnlocked.value
        takagi.ru.monica.security.SessionManager.markUnlocked()
        db.bitwardenVaultDao().insert(BitwardenVault(id = 2, email = "source-b@example.invalid", accountKey = "synthetic-dedup-b"))
        db.bitwardenVaultDao().insert(BitwardenVault(id = 1, email = "source@example.invalid", accountKey = "synthetic-dedup-a"))
        service = DedupMergeService(
            PasswordRepository(db.passwordEntryDao()), SecureItemRepository(db.secureItemDao()),
            PasskeyRepository(db.passkeyDao()), CustomFieldRepository(db.customFieldDao()),
            db.localKeePassDatabaseDao(), db.localMdbxDatabaseDao(), db.bitwardenVaultDao(),
            security, AppLocaleStringResolver(context), db
        )
    }

    @After fun tearDown() { db.close(); if (!wasUnlocked) takagi.ru.monica.security.SessionManager.markLocked() }

    @Test fun duplicatePasswordsWriteOneCopyAndRepeatedMergeWritesNothing() = runBlocking {
        val a = password(1).copy(password = security.encryptData("same password"))
        val b = password(2).copy(password = security.encryptData("same password"))
        add(a, b)
        val before = db.passwordEntryDao().getActiveEntries().first()
        val plan = service.buildPlan(sources, DedupMergeTarget.MonicaLocal)
        assertEquals(1, plan.uniquePasswords)
        assertEquals(1, plan.duplicateGroups)
        assertEquals(0, plan.passwordConflictGroups)
        val result = service.executePlan(plan)
        assertEquals(result.failures.toString(), 1, result.insertedPasswords)
        val after = db.passwordEntryDao().getActiveEntries().first()
        assertEquals(before, after.filterNot { it.isLocalOnlyEntry() })
        assertEquals("same password", security.decryptData(after.single { it.isLocalOnlyEntry() }.password))
        assertEquals(0, service.executePlan(service.buildPlan(sources, DedupMergeTarget.MonicaLocal)).insertedItems)
        assertEquals(3, db.passwordEntryDao().getActiveEntries().first().size)
    }

    @Test fun targetWithDifferentPasswordKeepsTheIncomingVariant() = runBlocking {
        add(password(1), password(2), password(3).copy(bitwardenVaultId = null, bitwardenLocalModified = false, password = "older password"))
        val plan = service.buildPlan(sources, DedupMergeTarget.MonicaLocal)
        assertEquals("A matching account is not proof that the target has this password", 1, plan.writablePasswords)
        service.executePlan(plan)
        assertEquals(setOf("password", "older password"), db.passwordEntryDao().getActiveEntries().first()
            .filter { it.isLocalOnlyEntry() }.map { it.password }.toSet())
    }

    @Test fun mostCompletePrefersContentOverFavoriteStatus() = runBlocking {
        add(password(1).copy(isFavorite = true, password = "sparse password"),
            password(2).copy(password = "complete password", notes = "Details", email = "a@example.invalid", phone = "123"))
        val resolved = service.buildPlan(sources, DedupMergeTarget.MonicaLocal).previewPasswords
        assertEquals(setOf("sparse password", "complete password"), resolved.map { it.entry.password }.toSet())
        assertTrue(resolved.single { it.entry.password == "sparse password" }.entry.isFavorite)
        assertFalse(resolved.single { it.entry.password == "complete password" }.entry.isFavorite)
    }

    @Test fun newestPolicyPreservesBothPasswordsAndTheirOwnNotes() = runBlocking {
        add(password(1).copy(password = "old", notes = "Keep these notes", updatedAt = Date(1)),
            password(2).copy(password = "new", updatedAt = Date(2)))
        val resolved = service.buildPlan(sources, DedupMergeTarget.MonicaLocal, DedupConflictPolicy.NEWEST).previewPasswords
        assertEquals(setOf("old", "new"), resolved.map { it.entry.password }.toSet())
        assertEquals("Keep these notes", resolved.single { it.entry.password == "old" }.entry.notes)
        assertEquals("", resolved.single { it.entry.password == "new" }.entry.notes)
    }

    @Test fun passwordWhitespaceIsARealConflict() = runBlocking {
        add(password(1).copy(password = " secret "), password(2).copy(password = "secret"))
        assertEquals(2, service.buildPlan(sources, DedupMergeTarget.MonicaLocal).passwordConflictGroups)
    }

    @Test fun caseSensitiveUrlPathsRemainSeparate() = runBlocking {
        add(password(1).copy(website = "https://example.invalid/Admin"),
            password(2).copy(website = "https://example.invalid/admin"))
        assertEquals(2, service.buildPlan(sources, DedupMergeTarget.MonicaLocal).uniquePasswords)
    }

    @Test fun caseSensitiveNonEmailUsernamesRemainSeparate() = runBlocking {
        add(password(1).copy(username = "Operator"), password(2).copy(username = "operator"))
        assertEquals(2, service.buildPlan(sources, DedupMergeTarget.MonicaLocal).uniquePasswords)
    }

    @Test fun authenticatorParametersMustMatchBeforeMerging() = runBlocking {
        val base = TotpData("JBSWY3DPEHPK3PXP", issuer = "Example", accountName = "user")
        val variants = listOf(base, base.copy(algorithm = "SHA256"), base.copy(period = 60),
            base.copy(digits = 8), base.copy(otpType = OtpType.HOTP), base.copy(otpType = OtpType.STEAM, digits = 5))
        variants.forEachIndexed { index, data ->
            db.secureItemDao().insertItem(SecureItem(itemType = ItemType.TOTP, title = "Example",
                itemData = Json.encodeToString(data), bitwardenVaultId = if (index % 2 == 0) 1 else 2, bitwardenLocalModified = true))
        }
        val plan = service.buildPlan(sources, DedupMergeTarget.MonicaLocal)
        assertEquals(variants.size, plan.uniqueSecureItems)
    }

    @Test fun identicalAuthenticatorsReallyMerge() = runBlocking {
        val data = Json.encodeToString(TotpData("JBSWY3DPEHPK3PXP", issuer = "Example", accountName = "user"))
        db.secureItemDao().insertItem(SecureItem(itemType = ItemType.TOTP, title = "Example", itemData = data, bitwardenVaultId = 1, bitwardenLocalModified = true))
        db.secureItemDao().insertItem(SecureItem(itemType = ItemType.TOTP, title = "Example", itemData = data, bitwardenVaultId = 2, bitwardenLocalModified = true))
        val plan = service.buildPlan(sources, DedupMergeTarget.MonicaLocal)
        assertEquals(1, plan.uniqueSecureItems)
        val result = service.executePlan(plan)
        assertEquals(result.failures.toString(), 1, result.insertedSecureItems)
        assertEquals(0, service.executePlan(service.buildPlan(sources, DedupMergeTarget.MonicaLocal)).insertedSecureItems)
    }

    @Test fun deletedEntriesNeverEnterTheMerge() = runBlocking {
        add(password(1), password(2).copy(isDeleted = true))
        assertEquals(1, service.buildPlan(sources, DedupMergeTarget.MonicaLocal).totalSourcePasswords)
    }

    @Test fun singleSourceCanBeConsolidatedAndTargetIsRecheckedBeforeWriting() = runBlocking {
        add(password(1), password(3))
        val plan = service.buildPlan(setOf("bitwarden:1"), DedupMergeTarget.MonicaLocal)
        assertEquals(2, plan.totalSourcePasswords)
        assertEquals(1, plan.writableItems)
        // Another operation adds the same content after the preview was shown.
        add(password(5).copy(bitwardenVaultId = null, bitwardenLocalModified = false))
        assertTrue(runCatching { service.executePlan(plan) }.isFailure)
        assertEquals(3, db.passwordEntryDao().getActiveEntries().first().size)
    }

    @Test fun customFieldsCompareDecryptedValuesAndRetainDifferentAnswers() = runBlocking {
        add(password(1), password(2))
        db.customFieldDao().insertAll(listOf(
            CustomField(entryId = 1, title = "Recovery", value = security.encryptData("answer A"), isProtected = true),
            CustomField(entryId = 2, title = "Recovery", value = security.encryptData("answer A"), isProtected = true),
            CustomField(entryId = 2, title = "Recovery", value = security.encryptData("answer B"), isProtected = true)
        ))
        val plan = service.buildPlan(sources, DedupMergeTarget.MonicaLocal)
        assertEquals(2, plan.passwordConflictGroups)
        assertEquals(listOf(1, 2), plan.previewPasswords.map { it.customFields.size }.sorted())
        assertEquals(2, service.executePlan(plan).insertedPasswords)
        val targets = db.passwordEntryDao().getActiveEntries().first().filter { it.isLocalOnlyEntry() }
        assertEquals(listOf(1, 2), targets.map { db.customFieldDao().getFieldsByEntryIdSync(it.id).size }.sorted())
        assertEquals(setOf("answer A", "answer B"), targets.flatMap { db.customFieldDao().getFieldsByEntryIdSync(it.id) }
            .map { security.decryptData(it.value) }.toSet())
        assertEquals(0, service.executePlan(service.buildPlan(sources, DedupMergeTarget.MonicaLocal)).insertedItems)
    }

    @Test fun targetCardWithDifferentCustomFieldsIsNotSkipped() = runBlocking {
        val base = BankCardData("4111111111111111", "Example", "12", "2030")
        db.secureItemDao().insertItem(SecureItem(itemType = ItemType.BANK_CARD, title = "Card",
            itemData = Json.encodeToString(base.copy(customFields = listOf(SecureCustomField("Extra", "Keep me")))),
            bitwardenVaultId = 1, bitwardenLocalModified = true))
        db.secureItemDao().insertItem(SecureItem(itemType = ItemType.BANK_CARD, title = "Card",
            itemData = Json.encodeToString(base)))
        val plan = service.buildPlan(setOf("bitwarden:1"), DedupMergeTarget.MonicaLocal)
        assertEquals(1, plan.writableSecureItems)
        assertTrue(plan.previewSecureItems.single().targetHasDifferentContent)
    }

    @Test fun mostCompleteCardUsesPayloadFieldsInsteadOfOnlyTimestamps() = runBlocking {
        val minimal = BankCardData("4111111111111111", "Example", "12", "2030")
        val complete = minimal.copy(cvv = security.encryptData("123"), bankName = "Example Bank",
            customFields = listOf(SecureCustomField("Recovery", "Keep this value")))
        db.secureItemDao().insertItem(SecureItem(itemType = ItemType.BANK_CARD, title = "Card",
            itemData = Json.encodeToString(complete), bitwardenVaultId = 1, bitwardenLocalModified = true, updatedAt = Date(1_000)))
        db.secureItemDao().insertItem(SecureItem(itemType = ItemType.BANK_CARD, title = "Card",
            itemData = Json.encodeToString(minimal), bitwardenVaultId = 1, bitwardenLocalModified = true, updatedAt = Date(2_000)))
        val plan = service.buildPlan(setOf("bitwarden:1"), DedupMergeTarget.MonicaLocal, DedupConflictPolicy.MOST_COMPLETE)
        assertEquals(setOf(Json.encodeToString(complete), Json.encodeToString(minimal)), plan.previewSecureItems.map { it.item.itemData }.toSet())
        val newest = service.buildPlan(setOf("bitwarden:1"), DedupMergeTarget.MonicaLocal, DedupConflictPolicy.NEWEST)
        assertEquals(plan.previewSecureItems.map { it.item.itemData }.toSet(), newest.previewSecureItems.map { it.item.itemData }.toSet())
    }

    @Test fun completeCardComparisonHandlesEncryptionKeyOrderAndUnknownFields() = runBlocking {
        fun card(cipher: String, extra: String) = org.json.JSONObject()
            .put("cardNumber", cipher).put("cardholderName", "Example")
            .put("expiryMonth", "12").put("expiryYear", "2030").put("bankName", extra).toString()
        val source = SecureItem(itemType = ItemType.BANK_CARD, title = "Card",
            itemData = card(security.encryptData("4111111111111111"), "preserve"), bitwardenVaultId = 1, bitwardenLocalModified = true)
        val sourceId = db.secureItemDao().insertItem(source)
        val targetData = org.json.JSONObject(card(security.encryptData("4111111111111111"), "preserve"))
        val reversed = org.json.JSONObject()
        targetData.keys().asSequence().toList().reversed().forEach { reversed.put(it, targetData.get(it)) }
        val id = db.secureItemDao().insertItem(source.copy(bitwardenVaultId = null, bitwardenLocalModified = false, itemData = reversed.toString()))
        assertEquals(0, service.buildPlan(setOf("bitwarden:1"), DedupMergeTarget.MonicaLocal).writableItems)
        db.secureItemDao().updateItem(source.copy(id = id, bitwardenVaultId = null, bitwardenLocalModified = false,
            itemData = card(security.encryptData("4111111111111111"), "different")))
        assertEquals(1, service.buildPlan(setOf("bitwarden:1"), DedupMergeTarget.MonicaLocal).writableItems)
        val unknown = source.copy(id = sourceId,
            itemData = org.json.JSONObject(source.itemData).put("futureField", "preserve").toString())
        db.secureItemDao().updateItem(unknown)
        val blocked = service.buildPlan(setOf("bitwarden:1"), DedupMergeTarget.MonicaLocal)
        assertEquals(0, blocked.writableItems)
        assertEquals(1, blocked.unsupportedItems.size)
        assertEquals(0, service.executePlan(blocked).insertedItems)
        assertEquals(unknown, db.secureItemDao().getItemById(sourceId))
    }

    @Test fun allWhitespacePasswordsAreNotTreatedAsEmpty() = runBlocking {
        add(password(1).copy(password = "   "), password(3).copy(bitwardenVaultId = null, bitwardenLocalModified = false, password = ""))
        val plan = service.buildPlan(setOf("bitwarden:1"), DedupMergeTarget.MonicaLocal)
        assertEquals(1, plan.writablePasswords)
        assertEquals("   ", plan.previewPasswords.single().entry.password)
        assertEquals(1, service.executePlan(plan).insertedPasswords)
    }

    @Test fun passkeysAreCountedButNeverCopiedOrChanged() = runBlocking {
        add(password(1))
        val key = PasskeyEntry(credentialId = "synthetic-credential", rpId = "example.invalid", rpName = "Example",
            userId = "user", userName = "user", userDisplayName = "User", publicKey = "synthetic-public-key",
            privateKeyAlias = "synthetic-not-a-real-key", bitwardenVaultId = 1,
            bitwardenCipherId = "synthetic-bitwarden-passkey", syncStatus = "SYNCED", signCount = 7)
        assertTrue(key.resolveOwnership() is PasskeyOwnership.Bitwarden)
        db.passkeyDao().insert(key)
        val before = db.passkeyDao().getAllPasskeysSync()
        val plan = service.buildPlan(setOf("bitwarden:1"), DedupMergeTarget.MonicaLocal)
        assertEquals(1, plan.totalSourcePasskeys)
        assertEquals(1, plan.unsupportedSourcePasskeys)
        val result = service.executePlan(plan)
        assertEquals(1, result.skippedUnsupportedPasskeys)
        assertEquals(before, db.passkeyDao().getAllPasskeysSync())
    }

    @Test fun thousandsOfEntriesAndCustomFieldsProduceTheExpectedCount() = runBlocking {
        val unique = 1500
        db.withTransaction {
            (1..unique).forEach { index ->
                val firstId = index * 2L - 1
                val secondId = index * 2L
                val entry = password(firstId).copy(title = "Entry $index", username = "user-$index")
                add(entry, password(secondId).copy(title = entry.title, username = entry.username))
                db.customFieldDao().insertAll(listOf(firstId, secondId).map { id ->
                    CustomField(entryId = id, title = "Extra", value = "value-$index")
                })
            }
        }
        val started = SystemClock.elapsedRealtime()
        val plan = service.buildPlan(sources, DedupMergeTarget.MonicaLocal)
        val scanned = SystemClock.elapsedRealtime()
        assertEquals(3000, plan.totalSourcePasswords)
        assertEquals(unique, plan.writableItems)
        assertEquals(unique, plan.consolidatedCopies)
        val result = service.executePlan(plan)
        assertEquals(result.failures.toString(), unique, result.insertedPasswords)
        assertEquals(4500, db.passwordEntryDao().getActiveEntries().first().size)
        assertEquals(4500, db.customFieldDao().getAllFieldsSync().size)
        assertEquals(0, service.executePlan(service.buildPlan(sources, DedupMergeTarget.MonicaLocal)).insertedItems)
        Log.i("DedupValidation", "3000 source rows -> 1500 inserted; 3000 custom fields -> 1500 copied; " +
            "scan=${scanned - started}ms, total=${SystemClock.elapsedRealtime() - started}ms")
        Unit
    }

    private suspend fun add(vararg entries: PasswordEntry) {
        entries.forEach { db.passwordEntryDao().insertPasswordEntry(it) }
    }

    private fun password(id: Long) = PasswordEntry(id = id, title = "Example", website = "https://example.invalid",
        username = "user@example.invalid", password = "password", createdAt = Date(1), updatedAt = Date(1),
        bitwardenVaultId = if (id % 2L == 1L) 1 else 2, bitwardenLocalModified = true)
}
