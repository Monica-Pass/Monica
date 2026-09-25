package takagi.ru.monica.keepass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.SecureItem

class KeePassTotpProjectionMatcherTest {
    @Test
    fun oneLegacyProjectionCannotBeAssignedToTwoEntriesInTheSameRefresh() {
        val legacy = totp(10, "Firefox", "Root", null, "same-secret")
        val first = totp(0, "Firefox", "Root", "first-uuid", "same-secret")
        val second = first.copy(keepassEntryUuid = "second-uuid")
        val claimed = mutableSetOf<Long>()
        val matched = KeePassTotpProjectionMatcher.findExistingProjection(
            DATABASE_ID, first, listOf(legacy), null, legacy, "same-secret", SecureItem::itemData, claimed,
        )
        assertEquals(10L, matched?.id)
        claimed += requireNotNull(matched).id
        assertNull(KeePassTotpProjectionMatcher.findExistingProjection(
            DATABASE_ID, second, listOf(legacy), null, legacy, "same-secret", SecureItem::itemData, claimed,
        ))
    }

    @Test
    fun distinctNativeUuidsWithTheSameTitleAndGroupMustNotOverwriteEachOther() {
        val existing = totp(10, "Firefox", "Root", "first-uuid", "first-secret")
        val incoming = totp(0, "Firefox", "Root", "second-uuid", "second-secret")
        assertNull(KeePassTotpProjectionMatcher.findExistingProjection(
            DATABASE_ID, incoming, listOf(existing), null, null, "second-secret", SecureItem::itemData
        ))
    }

    @Test
    fun aCopiedMonicaIdMustNotStealTheProjectionOfAnotherDatabase() {
        val other = totp(10, "Firefox", "Root", "first-uuid", "same-secret").copy(keepassDatabaseId = 99)
        val incoming = totp(0, "Firefox", "Root", "second-uuid", "same-secret")
        assertNull(KeePassTotpProjectionMatcher.findExistingProjection(
            DATABASE_ID, incoming, listOf(other), null, other, "same-secret", SecureItem::itemData
        ))
    }

    @Test
    fun legacyProjectionWithoutEntryUuidMatchesByTotpIdentityBeforeTitlePathFallback() {
        val incoming = totp(
            id = 0,
            title = "Renamed account",
            groupPath = "Root/New",
            entryUuid = "entry-uuid",
            itemData = "same-secret-identity"
        )
        val legacyProjection = totp(
            id = 10,
            title = "Old account",
            groupPath = "Root/Old",
            entryUuid = null,
            itemData = "same-secret-identity"
        )
        val titlePathFallback = totp(
            id = 11,
            title = "Renamed account",
            groupPath = "Root/New",
            entryUuid = null,
            itemData = "different-secret-identity"
        )

        val matched = KeePassTotpProjectionMatcher.findExistingProjection(
            databaseId = DATABASE_ID,
            incoming = incoming,
            existingTotp = listOf(titlePathFallback, legacyProjection),
            existingByUuid = null,
            existingBySource = null,
            incomingIdentityKey = "same-secret-identity",
            identityKeyOf = SecureItem::itemData
        )

        assertEquals(legacyProjection.id, matched?.id)
    }

    @Test
    fun sourceEntryUuidWinsOverLegacyIdentityMatch() {
        val incoming = totp(
            id = 0,
            title = "Account",
            groupPath = "Root",
            entryUuid = "entry-uuid",
            itemData = "same-secret-identity"
        )
        val uuidMatch = totp(
            id = 20,
            title = "Account",
            groupPath = "Root",
            entryUuid = "entry-uuid",
            itemData = "changed-secret-identity"
        )
        val legacyIdentityMatch = totp(
            id = 21,
            title = "Old account",
            groupPath = "Root/Old",
            entryUuid = null,
            itemData = "same-secret-identity"
        )

        val matched = KeePassTotpProjectionMatcher.findExistingProjection(
            databaseId = DATABASE_ID,
            incoming = incoming,
            existingTotp = listOf(legacyIdentityMatch),
            existingByUuid = uuidMatch,
            existingBySource = null,
            incomingIdentityKey = "same-secret-identity",
            identityKeyOf = SecureItem::itemData
        )

        assertEquals(uuidMatch.id, matched?.id)
    }

    @Test
    fun titlePathFallbackCannotOverwriteAnUnrelatedKnownSecret() {
        val incoming = totp(
            id = 0,
            title = "Account",
            groupPath = "Root",
            entryUuid = "entry-uuid",
            itemData = "incoming-secret"
        )
        val titlePathFallback = totp(
            id = 30,
            title = "Account",
            groupPath = "Root",
            entryUuid = null,
            itemData = "different-secret"
        )

        val matched = KeePassTotpProjectionMatcher.findExistingProjection(
            databaseId = DATABASE_ID,
            incoming = incoming,
            existingTotp = listOf(titlePathFallback),
            existingByUuid = null,
            existingBySource = null,
            incomingIdentityKey = "incoming-secret",
            identityKeyOf = SecureItem::itemData
        )

        assertNull(matched)
    }

    private fun totp(
        id: Long,
        title: String,
        groupPath: String,
        entryUuid: String?,
        itemData: String
    ): SecureItem {
        return SecureItem(
            id = id,
            itemType = ItemType.TOTP,
            title = title,
            itemData = itemData,
            keepassDatabaseId = DATABASE_ID,
            keepassGroupPath = groupPath,
            keepassEntryUuid = entryUuid
        )
    }

    private companion object {
        const val DATABASE_ID = 7L
    }
}
