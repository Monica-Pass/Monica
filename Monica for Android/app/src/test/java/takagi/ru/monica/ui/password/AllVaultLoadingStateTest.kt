package takagi.ru.monica.ui.password

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.sync.*

class AllVaultLoadingStateTest {
    private fun status(target: SyncTarget, phase: SyncPhase) = SyncTaskStatus(target.stableKey, target, phase)

    @Test fun `multiple tasks in one database count once across providers`() {
        val tasks = listOf(
            status(SyncTarget.KeePassDatabase(1), SyncPhase.RUNNING),
            status(SyncTarget.KeePassCompatibilityIndex(1, setOf(SyncItemKind.PASSWORD)), SyncPhase.QUEUED),
            status(SyncTarget.BitwardenVault(1), SyncPhase.RUNNING),
            status(SyncTarget.MdbxVault(1), SyncPhase.QUEUED),
        )
        assertEquals(3, resolveAllVaultLoadingState(tasks, false)?.databaseCount)
    }

    @Test fun `completion failure lock cancellation and backups never keep loader alive`() {
        val finished = SyncPhase.entries.filter { it != SyncPhase.QUEUED && it != SyncPhase.RUNNING }
            .mapIndexed { i, phase -> status(SyncTarget.KeePassDatabase(i.toLong()), phase) }
        assertNull(resolveAllVaultLoadingState(finished, false))
        assertNull(resolveAllVaultLoadingState(listOf(status(
            SyncTarget.AutofillSave(SyncAutofillDestination.MONICA_LOCAL, null), SyncPhase.RUNNING)), false))
    }

    @Test fun `unknown batch and local computation show no invented total`() {
        assertEquals(0, resolveAllVaultLoadingState(emptyList(), true)?.databaseCount)
        assertEquals(0, resolveAllVaultLoadingState(listOf(status(
            SyncTarget.KeePassCompatibilityIndex(null, setOf(SyncItemKind.PASSWORD)), SyncPhase.RUNNING)), false)?.databaseCount)
        assertNull(resolveAllVaultLoadingState(emptyList(), false))
    }
}
