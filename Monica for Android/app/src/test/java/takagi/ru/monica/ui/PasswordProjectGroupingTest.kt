package takagi.ru.monica.ui

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.passwordProjectKey
import takagi.ru.monica.ui.password.StackCardMode

class PasswordProjectGroupingTest {
    private fun entry(id: Long, project: String? = null) = PasswordEntry(
        id = id, title = "Same title", username = "same-account", password = "unused",
        website = "https://example.invalid", passwordGroupId = project, sortOrder = id.toInt(),
    )
    private val expanded = PasswordGroupingConfig(false, StackCardMode.ALWAYS_EXPANDED,
        "smart", "strict", emptySet(), emptyMap(), "Untitled")

    @Test fun expandedAndLocalOnlyKeepProjectTogetherWithoutMergingIdenticalIndependentItems() {
        val rows = listOf(entry(1, "project"), entry(2), entry(3, "project"), entry(4))
        for (config in listOf(expanded, expanded.copy(isLocalOnlyView = true))) {
            val groups = buildKotlinPasswordGroups(rows, config)
            assertEquals(listOf(listOf(1L, 3L), listOf(2L), listOf(4L)), groups.values.map { it.map(PasswordEntry::id) })
            assertEquals(listOf("entry_1", "entry_2", "entry_4"), groups.keys.toList())
        }
    }

    @Test fun sharedGroupIdCannotMergeDifferentStorageSourcesOrDatabaseIds() {
        val rows = listOf(entry(1, "shared"), entry(2, "shared"),
            entry(3, "shared").copy(keepassDatabaseId = 1), entry(4, "shared").copy(keepassDatabaseId = 2),
            entry(5, "shared").copy(bitwardenVaultId = 1), entry(6, "shared").copy(mdbxDatabaseId = 1))
        val groups = buildKotlinPasswordGroups(rows, expanded)
        assertEquals(5, groups.size)
        assertEquals(listOf(1L, 2L), groups.values.first().map { it.id })
        assertTrue(groups.values.all { members -> members.map { it.passwordProjectKey() }.distinct().size == 1 })
    }

    @Test fun legacyExplicitIdentityIsKeptButInvalidReplicaTextDoesNotCombineItems() {
        val legacy = "11111111-1111-4111-8111-111111111111"
        val rows = listOf(entry(1).copy(replicaGroupId = legacy), entry(2).copy(replicaGroupId = legacy),
            entry(3).copy(replicaGroupId = "not-a-project"), entry(4).copy(replicaGroupId = "not-a-project"),
            entry(5).copy(replicaGroupId = legacy, mdbxDatabaseId = 1),
            entry(6).copy(replicaGroupId = legacy, mdbxDatabaseId = 1))
        val groups = buildKotlinPasswordGroups(rows, expanded.copy(isLocalOnlyView = true))
        assertEquals(listOf(listOf(1L, 2L), listOf(3L), listOf(4L), listOf(5L), listOf(6L)),
            groups.values.map { it.map(PasswordEntry::id) })
    }

    @Test fun expandedNativeDecodeCombinesProjectMembersAcrossBucketsAfterValidation() {
        val rows = listOf(entry(1, "project"), entry(2), entry(3, "project"))
        val valid = intArrayOf(1, 2, 0, 2, 0, 1, 1, 1, 2)
        val groups = requireNotNull(decodePasswordGrouping(valid, rows, listOf("a", "b"), true))
        assertEquals(listOf(listOf(1L, 3L), listOf(2L)), groups.values.map { it.map(PasswordEntry::id) })
        // Duplicate or missing native membership must still fail before project consolidation.
        assertNull(decodePasswordGrouping(intArrayOf(1, 2, 0, 2, 0, 1, 1, 1, 1), rows, listOf("a", "b"), true))
        assertNull(decodePasswordGrouping(intArrayOf(1, 1, 0, 2, 0, 1), rows, listOf("a"), true))
    }
}
