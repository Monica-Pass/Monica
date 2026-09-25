package takagi.ru.monica.keepass

import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.TotpData
import java.util.Locale

/** A matching secret is not an identity: copied entries and different vaults remain visible. */
internal object KeePassTotpDisplaySource {
    fun storedKeys(item: SecureItem, data: TotpData): Set<String> = sourceKeys(
        item.keepassDatabaseId, item.keepassEntryUuid, item.bitwardenVaultId,
        item.bitwardenCipherId, item.mdbxDatabaseId, data.boundPasswordId,
        includeLegacyBinding = false,
    )

    fun passwordKeys(password: PasswordEntry): Set<String> = sourceKeys(
        password.keepassDatabaseId, password.keepassEntryUuid, password.bitwardenVaultId,
        password.bitwardenCipherId, password.mdbxDatabaseId, password.id,
        includeLegacyBinding = true,
    )

    fun collapseDuplicateBoundStoredTotps(
        items: List<SecureItem>,
        dataForItem: (SecureItem) -> TotpData?,
        otpIdentity: (TotpData) -> String,
    ): List<SecureItem> {
        val seen = mutableSetOf<Triple<Long, Set<String>, String>>()
        return items.filter { item ->
            val data = dataForItem(item) ?: return@filter true
            val boundId = data.boundPasswordId?.takeIf { it > 0 } ?: return@filter true
            seen.add(Triple(boundId, storedKeys(item, data), otpIdentity(data)))
        }
    }

    private fun sourceKeys(
        keepassId: Long?, entryUuid: String?, bitwardenId: Long?, cipherId: String?,
        mdbxId: Long?, passwordId: Long?,
        includeLegacyBinding: Boolean,
    ): Set<String> = buildSet {
        val scope = "$keepassId|$bitwardenId|$mdbxId"
        if (keepassId != null && !entryUuid.isNullOrBlank()) {
            add("$scope|kdbx:${entryUuid.lowercase(Locale.ROOT)}")
        }
        if (bitwardenId != null && !cipherId.isNullOrBlank()) add("$scope|cipher:$cipherId")
        // A copied native entry may retain another entry's old Monica binding.
        // Only legacy stored rows without a native identity may fall back to it.
        if ((includeLegacyBinding || isEmpty()) && passwordId != null && passwordId > 0) {
            add("$scope|password:$passwordId")
        }
    }
}
