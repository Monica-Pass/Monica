package takagi.ru.monica.data.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import takagi.ru.monica.data.PasskeyEntry

class DedupMergeModelsTest {
    @Test fun passkeysArePreservedWithoutResolvingMaterialsOrComparingCredentialIds() = runBlocking {
        val first = PasskeyEntry(id = 1, credentialId = "same", rpId = "example.invalid", rpName = "Example",
            userId = "user", userName = "User", userDisplayName = "User", publicKey = "unreadable",
            privateKeyAlias = "not-a-private-key")
        val sources = listOf(first, first.copy(id = 2, privateKeyAlias = "different-unreadable-key"))
        val rows = DedupPasskeyPlanner().build(sources) { "Source" }
        assertEquals(sources, rows.map { it.entry })
        assertTrue(rows.all { !it.writable && !it.existsInTarget && it.skipReason == DedupPasskeySkipReason.PRESERVED })
        val forged = rows.first().copy(skipReason = null, sourceEntryIds = listOf(1, 2))
        assertTrue(!forged.writable)
        val plan = DedupMergePlan(previewPasskeys = listOf(forged), totalSourcePasskeys = 2)
        assertEquals(0, plan.writableItems); assertEquals(0, plan.consolidatedCopies)
        assertEquals(0, plan.duplicateGroupsTotal); assertEquals(0, plan.targetExistingPasskeys)
    }

    @Test
    fun planSeparatesConflictsFromDuplicatesAndUnsupportedItems() {
        val plan = DedupMergePlan(
            uniquePasswords = 5,
            uniqueSecureItems = 2,
            duplicateGroups = 2,
            duplicateSecureItemGroups = 1,
            targetExistingDuplicates = 2,
            targetExistingSecureItems = 1,
            unsupportedSourcePasskeys = 4,
            passwordConflictGroups = 1,
            secureItemConflictGroups = 2
        )

        assertEquals(4, plan.writableItems)
        assertEquals(3, plan.duplicateGroupsTotal)
        assertEquals(3, plan.conflictGroupsTotal)
        assertEquals(7, plan.skippedItems)
    }

    @Test
    fun executionResultRetainsItemLevelFailures() {
        val result = DedupMergeExecutionResult(
            insertedPasswords = 2,
            insertedSecureItems = 1,
            skippedExistingPasswords = 3,
            failedPasswords = 1,
            failedSecureItems = 1,
            targetLabel = "Target",
            failures = listOf(
                DedupMergeFailure(DedupMergeItemKind.PASSWORD, "Mail", "write failed"),
                DedupMergeFailure(DedupMergeItemKind.SECURE_ITEM, "Card", "write failed")
            )
        )

        assertEquals(3, result.insertedItems)
        assertEquals(2, result.failedItems)
        assertTrue(result.hasPartialFailure)
        assertEquals(2, result.failures.size)
    }
}
