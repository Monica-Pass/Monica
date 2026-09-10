package takagi.ru.monica.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.viewmodel.MdbxViewModel

class MdbxHealthRepairIntegrationGuardTest {

    @Test
    fun reviewStateKeepsConcreteConflictAndPriorDecisions() {
        val first = conflictItem("repair-1", "entry", "entry-1")
        val second = conflictItem("repair-2", "attachment", "attachment-2")
        val plan = MdbxHealthRepairPlan(
            token = "plan-token",
            automaticItems = listOf(
                MdbxHealthRepairItem(
                    repairId = "auto-1",
                    kind = MdbxHealthRepairItemKind.DUPLICATE_TOMBSTONES,
                    objectType = "entry",
                    objectId = "entry-auto",
                    tombstoneCount = 2
                )
            ),
            conflictItems = listOf(first, second),
            blockers = emptyList(),
            canApply = true
        )

        val state = MdbxViewModel.MdbxHealthRepairState.Reviewing(
            databaseId = 7L,
            databaseName = "Vault",
            plan = plan,
            decisions = mapOf(first.repairId to MdbxHealthRepairChoice.KEEP_CONTENT),
            currentIndex = 1
        )

        assertEquals(second, state.currentItem)
        assertEquals(1, state.completedConflictCount)
        assertEquals(3, plan.repairableItemCount)
    }

    @Test
    fun androidRepositoryAndUiKeepRepairBehindMdbx2AndIdentityVerification() {
        val repository = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/Mdbx2Repository.kt"
        ).readText()
        val router = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxRepositoryRouter.kt"
        ).readText()
        val viewModel = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readText()
        val manager = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt"
        ).readText()
        val detail = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/MdbxDashboardDetailPages.kt"
        ).readText()

        assertTrue(repository.contains("vault.planHealthRepair()"))
        assertTrue(repository.contains("sessions.withMutatingVault(databaseId)"))
        assertTrue(repository.contains("vault.applyHealthRepair(planToken, operationId, nativeDecisions)"))
        assertTrue(router.contains("override suspend fun planHealthRepair"))
        assertTrue(router.contains("override suspend fun applyHealthRepair"))
        assertTrue(viewModel.contains("plan.conflictItems.isEmpty()"))
        assertTrue(viewModel.contains("MdbxHealthRepairState.Reviewing"))
        assertTrue(viewModel.contains("MdbxHealthRepairDecision("))
        assertTrue(viewModel.contains("importEntriesFromVault(databaseId)"))
        assertTrue(
            viewModel.indexOf("plan.repairableItemCount == 0") <
                viewModel.indexOf("!plan.canApply")
        )
        assertTrue(manager.contains("M3IdentityVerifyDialog("))
        assertTrue(manager.contains("verifyMasterPassword(healthRepairMasterPassword)"))
        assertTrue(manager.contains("onSuccess = completeDeleteChoice"))
        assertTrue(manager.contains("chooseHealthRepairConflict(MdbxHealthRepairChoice.DELETE_OBJECT)"))
        assertTrue(detail.contains("R.string.mdbx_ui_repair_keep_content"))
        assertTrue(detail.contains("R.string.mdbx_ui_repair_delete_content"))
        assertTrue(detail.contains("R.string.mdbx_ui_repair_cancel_description"))
    }

    private fun conflictItem(repairId: String, objectType: String, objectId: String) =
        MdbxHealthRepairItem(
            repairId = repairId,
            kind = MdbxHealthRepairItemKind.ACTIVE_OBJECT_TOMBSTONE_CONFLICT,
            objectType = objectType,
            objectId = objectId,
            tombstoneCount = 1
        )

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var directory: File? = File(System.getProperty("user.dir") ?: ".")
        while (directory != null) {
            candidates += File(directory, relativePath)
            directory = directory.parentFile
        }
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to find project file: $relativePath")
    }
}
