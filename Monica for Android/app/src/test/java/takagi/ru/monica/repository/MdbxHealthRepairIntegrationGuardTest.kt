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

        val verification = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/SecondaryPasswordVerificationDialog.kt"
        ).readText()
        val deleteVerification = manager
            .substringAfter("if (showHealthRepairDeleteVerification) {", missingDelimiterValue = "")
            .substringBefore("MdbxMigrationDialog(")
        val completion = deleteVerification.substringAfter("val completeDeleteChoice = {", missingDelimiterValue = "")
            .substringBefore("val biometricAction")
        val passwordAttempt = verification.substringAfter("val submitted = password", missingDelimiterValue = "")
            .substringBefore("confirmText = confirmText,")

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
        assertTrue("Health repair must use the shared password verifier with the current repair request.",
            deleteVerification.contains("SecondaryPasswordVerificationDialog(") &&
                deleteVerification.contains("manager = manager") &&
                deleteVerification.contains("onSuccess = { completeDeleteChoice() }") &&
                deleteVerification.contains("isRequestCurrent = { showHealthRepairDeleteVerification && healthRepairState == targetRepair }") &&
                deleteVerification.contains("destructiveConfirm = true") &&
                !Regex("requirePassword\\s*=\\s*false").containsMatchIn(deleteVerification))
        listOf("showHealthRepairDeleteVerification && latestRepair.value == targetRepair",
            "manager.isDataReadCurrent(generation)", "lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)",
            "isInteractive == true", "isKeyguardLocked == false", "active.compareAndSet(true, false)",
            "viewModel.chooseHealthRepairConflict(MdbxHealthRepairChoice.DELETE_OBJECT)").forEach { gate ->
            assertTrue("Repair deletion must retain its final gate: $gate", completion.contains(gate))
        }
        assertTrue("Biometric success must share the same final repair gate.",
            deleteVerification.contains("onSuccess = completeDeleteChoice"))
        assertTrue("The shared verifier must preserve real password confirmation and bounded cancellable verification.",
            verification.contains("requirePassword: Boolean = true") && verification.contains("M3IdentityVerifyDialog(") &&
                passwordAttempt.contains("BoundedSecureDataRead.readChecked(isCurrent = {") &&
                passwordAttempt.contains("current() && attempt.isCurrent()") &&
                passwordAttempt.contains("manager.verifyMasterPassword(submitted, attempt) { ensureActive() }") &&
                passwordAttempt.contains("if (!current() || pending !== attempt) return@launch") &&
                passwordAttempt.contains("if (!foreground() || !requestCurrent.value()) { close(); return@launch }") &&
                passwordAttempt.contains("if (verified == true && attempt.commit())") &&
                passwordAttempt.contains("success.value(entryGeneration)") &&
                passwordAttempt.indexOf("if (verified == true && attempt.commit())") <
                    passwordAttempt.indexOf("success.value(entryGeneration)"))
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
