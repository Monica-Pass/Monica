package takagi.ru.monica.keepass

import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem

object KeePassTotpProjectionMatcher {
    fun findExistingProjection(
        databaseId: Long,
        incoming: SecureItem,
        existingTotp: List<SecureItem>,
        existingByUuid: SecureItem?,
        existingBySource: SecureItem?,
        incomingIdentityKey: String?,
        identityKeyOf: (SecureItem) -> String?,
        claimedProjectionIds: Set<Long> = emptySet(),
    ): SecureItem? {
        fun belongsToIncomingEntry(candidate: SecureItem): Boolean =
            candidate.id !in claimedProjectionIds &&
                candidate.itemType == ItemType.TOTP && candidate.keepassDatabaseId == databaseId &&
                (candidate.keepassEntryUuid.isNullOrBlank() ||
                    candidate.keepassEntryUuid.equals(incoming.keepassEntryUuid, ignoreCase = true))

        existingByUuid
            ?.takeIf(::belongsToIncomingEntry)
            ?.let { return it }

        existingBySource
            ?.takeIf(::belongsToIncomingEntry)
            ?.let { return it }

        incomingIdentityKey?.let { identityKey ->
            existingTotp.firstOrNull { candidate ->
                candidate.id !in claimedProjectionIds && candidate.itemType == ItemType.TOTP &&
                    candidate.keepassDatabaseId == databaseId &&
                    candidate.keepassEntryUuid.isNullOrBlank() &&
                    identityKeyOf(candidate) == identityKey
            }?.let { return it }
        }

        return existingTotp.singleOrNull {
            it.id !in claimedProjectionIds && it.itemType == ItemType.TOTP &&
                it.keepassDatabaseId == databaseId &&
                it.keepassEntryUuid.isNullOrBlank() &&
                it.keepassGroupPath == incoming.keepassGroupPath &&
                it.title == incoming.title &&
                (incomingIdentityKey == null || identityKeyOf(it) == null || identityKeyOf(it) == incomingIdentityKey)
        }
    }
}
