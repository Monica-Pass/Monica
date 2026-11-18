package takagi.ru.monica.ui

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.api.CipherApiResponse
import takagi.ru.monica.bitwarden.api.CipherLoginApiData
import takagi.ru.monica.bitwarden.mapper.TotpMapper
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.TotpCategoryFilter
import takagi.ru.monica.viewmodel.TotpViewModel

/** Synthetic Room inputs exercise the production merge and source filters, without network writes. */
class BitwardenTotpSourceTest {
    @Test fun rootPasswordAuthenticatorIsBitwardenRatherThanLocal() = withModel { db, model ->
        val source = password("Root remote", cipher = "root-cipher")
        val id = db.passwordEntryDao().insertPasswordEntry(source)
        val shown = awaitAll(model, setOf(-id)).single()
        assertEquals(source.copy(id = id).resolveOwnership(),
            shown.resolveOwnership().let { owner ->
                (owner as? SecureItemOwnership.Bitwarden)?.let {
                    PasswordOwnership.Bitwarden(it.vaultId!!, it.cipherId)
                }
            })
        assertFalse("Remote root OTP must not appear as Monica local", shown.isLocalOnlyItem())
        assertEquals(source.bitwardenRevisionDate, shown.bitwardenRevisionDate)
        assertFiltered(model, TotpCategoryFilter.BitwardenVault(909), setOf(-id))
        assertFiltered(model, TotpCategoryFilter.BitwardenVaultUncategorized(909), setOf(-id))
        assertFiltered(model, TotpCategoryFilter.BitwardenVault(910), emptySet())
        assertFiltered(model, TotpCategoryFilter.Local, emptySet())
        assertFiltered(model, TotpCategoryFilter.LocalUncategorized, emptySet())
        assertEquals(listOf(source.copy(id = id)), db.passwordEntryDao().getActiveEntries().first())
        assertTrue(db.secureItemDao().getAllItems().first().isEmpty())
    }

    @Test fun pendingRootPasswordRetainsRemoteOwnershipBeforeCipherIdExists() = withModel { db, model ->
        val source = password("Pending root", cipher = null).copy(
            bitwardenRevisionDate = null, bitwardenLocalModified = true)
        val id = db.passwordEntryDao().insertPasswordEntry(source)
        val shown = awaitAll(model, setOf(-id)).single()
        assertTrue(shown.bitwardenLocalModified)
        assertEquals(SecureItemOwnership.Bitwarden(909, null), shown.resolveOwnership())
        assertFiltered(model, TotpCategoryFilter.BitwardenVaultUncategorized(909), setOf(-id))
        assertFiltered(model, TotpCategoryFilter.Local, emptySet())
    }

    @Test fun folderPasswordRetainsCipherIdentityAndFolderFilter() = withModel { db, model ->
        val source = password("Folder remote", cipher = "folder-cipher").copy(bitwardenFolderId = "folder-a")
        val id = db.passwordEntryDao().insertPasswordEntry(source)
        val shown = awaitAll(model, setOf(-id)).single()
        assertEquals(source.bitwardenCipherId, shown.bitwardenCipherId)
        assertEquals(source.bitwardenRevisionDate, shown.bitwardenRevisionDate)
        assertFiltered(model, TotpCategoryFilter.BitwardenFolderFilter("folder-a", 909), setOf(-id))
        assertFiltered(model, TotpCategoryFilter.BitwardenVaultUncategorized(909), emptySet())
        assertFiltered(model, TotpCategoryFilter.Local, emptySet())
    }

    @Test fun standaloneSyncedRootAuthenticatorAlreadyHasCorrectOwnership() = withModel { db, model ->
        val source = TotpMapper().fromCipherResponse(CipherApiResponse(
            id = "standalone-cipher", type = 1, name = "Standalone root", folderId = null,
            revisionDate = "2026-11-18T00:00:00Z",
            login = CipherLoginApiData(totp = SECRET)), 909)
        val id = db.secureItemDao().insertItem(source)
        assertEquals(SecureItemOwnership.Bitwarden(909, "standalone-cipher"),
            awaitAll(model, setOf(id)).single().resolveOwnership())
        assertFiltered(model, TotpCategoryFilter.BitwardenVaultUncategorized(909), setOf(id))
        assertFiltered(model, TotpCategoryFilter.Local, emptySet())
    }

    @Test fun matchingStoredCipherCollapsesVirtualOtpButKeepsIndependentLocalCopy() = withModel { db, model ->
        val source = password("Matched remote", cipher = "shared-cipher")
        db.passwordEntryDao().insertPasswordEntry(source)
        val data = Json.encodeToString(TotpData(secret = SECRET, issuer = source.website,
            accountName = source.username))
        val stored = SecureItem(itemType = ItemType.TOTP, title = source.title, itemData = data,
            bitwardenVaultId = 909, bitwardenCipherId = "shared-cipher", syncStatus = "SYNCED")
        val remoteId = db.secureItemDao().insertItem(stored)
        val localId = db.secureItemDao().insertItem(stored.asMonicaLocalCopy(null).copy(title = "Local copy"))
        awaitAll(model, setOf(remoteId, localId))
        assertFiltered(model, TotpCategoryFilter.BitwardenVault(909), setOf(remoteId))
        assertFiltered(model, TotpCategoryFilter.Local, setOf(localId))
    }

    @Test fun localAndKeePassPasswordsRemainInTheirOwnSources() = withModel { db, model ->
        val local = password("Local password", null).copy(bitwardenVaultId = null, bitwardenRevisionDate = null)
        val localId = db.passwordEntryDao().insertPasswordEntry(local)
        val keepassId = db.passwordEntryDao().insertPasswordEntry(local.copy(title = "KeePass password",
            keepassDatabaseId = 808, keepassEntryUuid = "native-entry"))
        awaitAll(model, setOf(-localId, -keepassId))
        assertFiltered(model, TotpCategoryFilter.Local, setOf(-localId))
        assertFiltered(model, TotpCategoryFilter.KeePassDatabase(808), setOf(-keepassId))
        assertFiltered(model, TotpCategoryFilter.BitwardenVault(909), emptySet())
    }

    private fun withModel(block: suspend (PasswordDatabase, TotpViewModel) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        // No application context means no persisted filter or Bitwarden singleton mutation.
        val model = TotpViewModel(SecureItemRepository(db.secureItemDao()),
            PasswordRepository(db.passwordEntryDao()), strings = AppLocaleStringResolver(context))
        try { block(db, model) } finally {
            model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
        }
    }

    private suspend fun awaitAll(model: TotpViewModel, ids: Set<Long>): List<SecureItem> =
        withTimeout(10_000) { model.allTotpItems.first { rows -> rows.map { it.id }.toSet() == ids } }

    private suspend fun assertFiltered(model: TotpViewModel, filter: TotpCategoryFilter, ids: Set<Long>) {
        // A nonempty All result is a barrier: an empty assertion cannot accept
        // an old empty emission from the previous source filter.
        val allIds = model.allTotpItems.value.map { it.id }.toSet()
        require(allIds.isNotEmpty())
        model.setCategoryFilter(TotpCategoryFilter.All)
        withTimeout(10_000) {
            model.parsedTotpState.first { it.isReady && it.items.map { row -> row.item.id }.toSet() == allIds }
        }
        model.setCategoryFilter(filter)
        val rows = withTimeout(10_000) {
            model.parsedTotpState.first { state -> state.isReady && state.items.map { it.item.id }.toSet() == ids }
        }.items.map { it.item }
        assertEquals(ids, rows.map { it.id }.toSet())
    }

    private fun password(title: String, cipher: String?) = PasswordEntry(title = title,
        website = "https://fixture.example", username = "fixture@example.test", password = "",
        authenticatorKey = SECRET, bitwardenVaultId = 909, bitwardenCipherId = cipher,
        bitwardenRevisionDate = "2026-11-18T00:00:00Z")

    companion object { private const val SECRET = "JBSWY3DPEHPK3PXP" }
}
