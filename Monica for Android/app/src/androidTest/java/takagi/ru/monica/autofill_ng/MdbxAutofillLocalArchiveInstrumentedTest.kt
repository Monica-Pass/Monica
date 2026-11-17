package takagi.ru.monica.autofill_ng

import java.util.Date
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.domain.provider.MdbxPasswordProvider
import takagi.ru.monica.domain.provider.PasswordCommandStateFactory
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.repository.mdbx2PhysicalEntryId

/** Uses the Android writer and archive state factory; this is not a UI gesture test. */
class MdbxAutofillLocalArchiveInstrumentedTest : MdbxAutofillFixture() {
    @Test fun actualAndroidWriterArchiveSuppressesOnlyPhysicallyProvenLogicalEntry() = runBlocking {
        val vault = fixture()
        val native = Mdbx2Repository(context, database.localMdbxDatabaseDao(), security,
            database.passwordEntryDao(), database.secureItemDao(), database.customFieldDao())
        val passwords = PasswordRepository(database.passwordEntryDao(), mdbxRepository = native)
        val id = passwords.insertPasswordEntry(PasswordEntry(title = "Synthetic archived login",
            username = "archive-user", website = "https://archive.test", password = security.encryptData("archive-secret"),
            mdbxDatabaseId = vault.databaseId))
        val stored = checkNotNull(database.passwordEntryDao().getPasswordEntryById(id))
        val logical = checkNotNull(stored.replicaGroupId)
        val vaultId = native.withVaultForSync(vault.databaseId) { _, handle -> handle.info().vaultId }
        val objectId = mdbx2PhysicalEntryId(vaultId, logical)
        val initial = native.readAutofillSnapshot(vault.databaseId) {}.credentials.single { it.objectId == objectId }
        assertEquals(logical, initial.logicalEntryId)
        assertEquals("archive-secret", initial.entry.password)
        val oldSource = source()
        val before = oldSource.load(AutofillScope.Mdbx(vault.databaseId)).single { it.replicaGroupId == objectId }
        val beforeSelection = checkNotNull(oldSource.selection(before))
        assertEquals(before, oldSource.getSavedRoomEntry(id))
        assertTrue(AutofillPasswordSource.isMdbx(before))

        // The same factory and repository call used by PasswordViewModel.archiveSingleEntry.
        val provider = MdbxPasswordProvider(security::decryptData, security::encryptData)
        val archived = PasswordCommandStateFactory().createArchivedEntry(stored, Date(), provider.commandPolicy(stored))
        passwords.updatePasswordEntry(archived)
        assertTrue(database.passwordEntryDao().getPasswordEntryById(id)!!.isArchived)
        val raw = native.nativeObject(vault.databaseId, objectId)
        assertFalse("Current Android writer stores archive only in the local row", JSONObject(raw.payload).has("is_archived"))
        assertEquals("login", raw.summary.type)
        assertNotNull("Native object still exists; suppression must be local metadata only",
            native.readAutofillSnapshot(vault.databaseId) {}.credentials.singleOrNull { it.objectId == objectId })

        // An unrelated native object can carry the same room_id/logical text from another client.
        val decoy = native.saveNativeObject(vault.databaseId, null, "Synthetic independent native object", "login",
            """{"kind":"password","monica_entry_id":"$logical","room_id":$id,"username":"MDK|literal-independent","password_plain":"decoy-secret"}""")
        val unproven = native.readAutofillSnapshot(vault.databaseId) {}.credentials.single { it.objectId == decoy }
        assertNull(unproven.logicalEntryId)
        assertEquals("MDK|literal-independent", unproven.entry.username)
        val archivedSource = source()
        val rows = archivedSource.load(AutofillScope.Mdbx(vault.databaseId))
        assertFalse("A locally archived project must not reappear as a fillable native row", rows.any { it.replicaGroupId == objectId })
        assertTrue("room_id collision must not suppress an unrelated native object", rows.any { it.replicaGroupId == decoy })
        assertNull(oldSource.resolve(before))
        assertNull(archivedSource.getSavedRoomEntry(id))

        // External metadata removal cannot change the physical identity of an archived object.
        val withoutIdentity = JSONObject(raw.payload).apply { remove("monica_entry_id"); remove("room_id") }.toString()
        assertEquals(objectId, native.saveNativeObject(vault.databaseId, raw, raw.summary.title,
            "login", withoutIdentity))
        val stripped = native.readAutofillSnapshot(vault.databaseId) {}.credentials.single { it.objectId == objectId }
        assertNull(stripped.logicalEntryId)
        assertEquals(vaultId, stripped.nativeVaultId)
        val strippedSource = source()
        val strippedRows = strippedSource.load(AutofillScope.Mdbx(vault.databaseId))
        assertFalse("Deleting payload identity must not undo local archive", strippedRows.any { it.replicaGroupId == objectId })
        val independent = strippedRows.single { it.replicaGroupId == decoy }
        assertEquals(independent, strippedSource.resolve(independent))
        assertNull(strippedSource.getSavedRoomEntry(id))
        // Supply the current native revision deliberately: rejection must be archive denial,
        // not merely the old capability's stale-head check. This is a synthetic internal capability.
        assertNull(source().getSelected(beforeSelection.copy(revision = stripped.revision)))

        val unarchived = PasswordCommandStateFactory().createUnarchivedEntry(archived, Date(), provider.commandPolicy(archived))
        passwords.updatePasswordEntry(unarchived)
        val restoredSource = source()
        val restored = restoredSource.load(AutofillScope.Mdbx(vault.databaseId)).single { it.replicaGroupId == objectId }
        assertEquals("archive-secret", restored.password)
        assertEquals(restored, restoredSource.getSavedRoomEntry(id))
    }

    @Test fun archivedWalletWithRemovedPayloadIdentityRejectsFreshLoadAndCurrentSelection() = runBlocking {
        val vault = fixture()
        val native = Mdbx2Repository(context, database.localMdbxDatabaseDao(), security,
            database.passwordEntryDao(), database.secureItemDao(), database.customFieldDao())
        val wallets = SecureItemRepository(database.secureItemDao(), mdbxRepository = native)
        val card = """{"cardNumber":"4111111111111111","cardholderName":"MDK|literal-owner","expiryMonth":"12","expiryYear":"2030"}"""
        val id = wallets.insertItem(SecureItem(title = "Synthetic archived wallet", itemType = ItemType.BANK_CARD,
            itemData = security.encryptData(card), mdbxDatabaseId = vault.databaseId))
        val stored = checkNotNull(database.secureItemDao().getItemById(id))
        val logical = checkNotNull(stored.replicaGroupId)
        val vaultId = native.withVaultForSync(vault.databaseId) { _, handle -> handle.info().vaultId }
        val objectId = mdbx2PhysicalEntryId(vaultId, logical)
        val raw = native.nativeObject(vault.databaseId, objectId)
        val withoutIdentity = JSONObject(raw.payload).apply { remove("monica_entry_id"); remove("room_id") }.toString()
        assertEquals(objectId, native.saveNativeObject(vault.databaseId, raw, raw.summary.title,
            "card", withoutIdentity))
        val unproven = native.readAutofillSnapshot(vault.databaseId) {}.walletItems.single { it.objectId == objectId }
        assertNull(unproven.logicalEntryId)
        assertEquals(vaultId, unproven.nativeVaultId)
        val selectedSource = source()
        selectedSource.load(AutofillScope.Mdbx(vault.databaseId))
        val selected = selectedSource.nativeWalletItems.single { it.replicaGroupId == objectId }
        assertEquals(selected, selectedSource.resolveCurrentWallet(selected))

        // Directly persist the local archive state so the native revision is unchanged. This
        // isolates action-time denial from the separate stale-revision defense (no UI gesture).
        database.secureItemDao().updateItem(stored.copy(isArchived = true, archivedAt = Date()))
        assertEquals(unproven.revision, native.readAutofillSnapshot(vault.databaseId) {}.walletItems
            .single { it.objectId == objectId }.revision)
        assertNull(selectedSource.resolveCurrentWallet(selected))
        val freshSource = source()
        freshSource.load(AutofillScope.Mdbx(vault.databaseId))
        assertFalse(freshSource.nativeWalletItems.any { it.replicaGroupId == objectId })

        val decoyPayload = JSONObject(withoutIdentity).put("monica_entry_id", logical).put("room_id", id).toString()
        val decoy = native.saveNativeObject(vault.databaseId, null, "Synthetic independent wallet", "card", decoyPayload)
        val decoySource = source()
        decoySource.load(AutofillScope.Mdbx(vault.databaseId))
        val independent = decoySource.nativeWalletItems.single { it.replicaGroupId == decoy }
        assertEquals(independent, decoySource.resolveCurrentWallet(independent))
        assertEquals(card, independent.itemData)

        database.secureItemDao().updateItem(stored)
        val restoredSource = source()
        restoredSource.load(AutofillScope.Mdbx(vault.databaseId))
        val restored = restoredSource.nativeWalletItems.single { it.replicaGroupId == objectId }
        assertEquals(restored, restoredSource.resolveCurrentWallet(restored))
    }
}
