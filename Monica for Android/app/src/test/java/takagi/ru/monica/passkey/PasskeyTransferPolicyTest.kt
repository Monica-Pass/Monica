package takagi.ru.monica.passkey

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.ui.components.UnifiedMoveAction
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget

class PasskeyTransferPolicyTest {
    private fun row(id: Long) = PasskeyEntry(id = id, credentialId = "credential-$id", rpId = "example.invalid",
        rpName = "Example", userId = "user", userName = "Account $id", userDisplayName = "Account",
        publicKey = "", privateKeyAlias = "fixture")

    @Test fun mixedBatchIsPlannedWithoutExecutingAnyWrites() = runBlocking {
        val plan = PasskeyTransferPolicy.plan(listOf(row(1), row(2), row(3).copy(boundPasswordId = 9)),
            UnifiedMoveCategoryTarget.MdbxFolderTarget(4, "games"), UnifiedMoveAction.COPY) {
            if (it.id == 1L) PasskeyPortability.PORTABLE else PasskeyPortability.KEY_UNAVAILABLE
        }
        assertEquals(listOf(1L), plan.eligible.map { it.id })
        assertEquals(listOf(PasskeyMoveIssueReason.KEY_UNAVAILABLE, PasskeyMoveIssueReason.BOUND_PASSWORD), plan.issues.map { it.reason })
        assertFalse(plan.issues.toString().contains("fixture"))
    }

    @Test fun deviceBoundKeysCanBeOrganizedLocallyButCannotBeCopied() = runBlocking {
        val target = UnifiedMoveCategoryTarget.MonicaCategory(9)
        val moving = PasskeyTransferPolicy.plan(listOf(row(1)), target, UnifiedMoveAction.MOVE) {
            fail("Local organization does not export a key"); PasskeyPortability.UNKNOWN
        }
        assertEquals(1, moving.eligible.size)
        val copying = PasskeyTransferPolicy.plan(listOf(row(1)), target, UnifiedMoveAction.COPY) { PasskeyPortability.DEVICE_ONLY }
        assertTrue(copying.eligible.isEmpty())
    }

    @Test fun copyingWithinUniqueCredentialStoresCannotOverwriteTheSource() = runBlocking {
        for ((entry, target) in listOf(
            row(1).copy(mdbxDatabaseId = 5) to UnifiedMoveCategoryTarget.MdbxFolderTarget(5, "other"),
            row(2).copy(keepassDatabaseId = 5) to UnifiedMoveCategoryTarget.KeePassGroupTarget(5, "other"))) {
            val plan = PasskeyTransferPolicy.plan(listOf(entry), target, UnifiedMoveAction.COPY) { PasskeyPortability.PORTABLE }
            assertTrue(plan.eligible.isEmpty())
            assertEquals(PasskeyMoveIssueReason.SAME_DATABASE_COPY, plan.issues.single().reason)
        }
    }
}
