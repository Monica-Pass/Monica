package takagi.ru.monica.keepass

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.TotpData

class KeePassTotpDisplaySourceTest {
    private val otp = TotpData(secret = "JBSWY3DPEHPK3PXP")
    private val item = SecureItem(id = 1, itemType = ItemType.TOTP, title = "Copied", itemData = "",
        keepassDatabaseId = 1, keepassEntryUuid = "entry-a")
    private val password = PasswordEntry(id = 8, title = "Copied", username = "alice", password = "",
        website = "", keepassDatabaseId = 1, keepassEntryUuid = "entry-a")

    @Test fun identicalSecretsInDifferentDatabasesOrNativeEntriesDoNotHideEachOther() {
        val stored = KeePassTotpDisplaySource.storedKeys(item, otp)
        assertTrue(stored.intersect(KeePassTotpDisplaySource.passwordKeys(password.copy(keepassDatabaseId = 2))).isEmpty())
        assertTrue(stored.intersect(KeePassTotpDisplaySource.passwordKeys(password.copy(keepassEntryUuid = "entry-b"))).isEmpty())
        assertTrue(stored.intersect(KeePassTotpDisplaySource.passwordKeys(password)).isNotEmpty())
    }

    @Test fun legacyBindingsRemainScopedToTheirStorage() {
        val data = otp.copy(boundPasswordId = password.id)
        val stored = KeePassTotpDisplaySource.storedKeys(item.copy(keepassEntryUuid = null), data)
        assertTrue(stored.intersect(KeePassTotpDisplaySource.passwordKeys(password)).isNotEmpty())
        assertTrue(stored.intersect(KeePassTotpDisplaySource.passwordKeys(password.copy(keepassDatabaseId = 2))).isEmpty())
    }

    @Test fun copiedBindingCannotHideAnotherNativeEntry() {
        val stored = KeePassTotpDisplaySource.storedKeys(item, otp.copy(boundPasswordId = password.id))
        assertTrue(stored.intersect(KeePassTotpDisplaySource.passwordKeys(password.copy(keepassEntryUuid = "entry-b"))).isEmpty())
        assertTrue(stored.intersect(KeePassTotpDisplaySource.passwordKeys(password.copy(keepassEntryUuid = "ENTRY-A"))).isNotEmpty())
    }

    @Test fun repeatedBindingsCollapseOnlyWithinTheSameNativeEntryAndDatabase() {
        val items = listOf(
            item,
            item.copy(id = 2, keepassEntryUuid = "ENTRY-A"),
            item.copy(id = 3, keepassEntryUuid = "entry-b"),
            item.copy(id = 4, keepassDatabaseId = 2),
            item.copy(id = 5, keepassEntryUuid = null),
            item.copy(id = 6, keepassEntryUuid = null),
        )
        val result = KeePassTotpDisplaySource.collapseDuplicateBoundStoredTotps(
            items, { otp.copy(boundPasswordId = password.id) }, { it.secret },
        )
        assertEquals(listOf(1L, 3L, 4L, 5L), result.map { it.id })
    }

    @Test fun unboundUnreadableAndDifferentSecretsStayVisible() {
        val items = (1L..5L).map { item.copy(id = it) }
        val data = mapOf(
            1L to otp,
            2L to otp,
            3L to null,
            4L to otp.copy(boundPasswordId = 8),
            5L to otp.copy(boundPasswordId = 8, secret = "DIFFERENT"),
        )
        assertEquals(items, KeePassTotpDisplaySource.collapseDuplicateBoundStoredTotps(
            items, { data[it.id] }, { it.secret },
        ))
    }

    @Test fun localMdbxAndBitwardenBindingsDoNotCollapseAcrossVaultsOrNativeCiphers() {
        val local = item.copy(keepassDatabaseId = null, keepassEntryUuid = null)
        val items = listOf(
            local,
            local.copy(id = 2),
            local.copy(id = 3, mdbxDatabaseId = 1),
            local.copy(id = 4, mdbxDatabaseId = 2),
            local.copy(id = 5, bitwardenVaultId = 1, bitwardenCipherId = "cipher-a"),
            local.copy(id = 6, bitwardenVaultId = 1, bitwardenCipherId = "cipher-b"),
            local.copy(id = 7, bitwardenVaultId = 2, bitwardenCipherId = "cipher-a"),
        )
        val result = KeePassTotpDisplaySource.collapseDuplicateBoundStoredTotps(
            items, { otp.copy(boundPasswordId = 8) }, { it.secret },
        )
        assertEquals(listOf(1L, 3L, 4L, 5L, 6L, 7L), result.map { it.id })
    }
}
