package takagi.ru.monica.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbxHistorySnapshotUiRegressionGuardTest {

    @Test
    fun pageContentDoesNotRepeatTopAppBarTitles() {
        val source = managerSource()
        val snapshotPage = source
            .substringAfter("private fun MdbxSnapshotPage(")
            .substringBefore("private fun MdbxCommitHistoryPage(")
        val historyPage = source
            .substringAfter("private fun MdbxCommitHistoryPage(")
            .substringBefore("private fun CommitHistoryListHeader(")

        assertFalse(
            "The snapshot page must let the top app bar own the page title.",
            snapshotPage.contains("MdbxSectionHeader(")
        )
        assertFalse(
            "The commit history page must let the top app bar own the page title.",
            historyPage.contains("MdbxSectionHeader(")
        )
        assertTrue(snapshotPage.contains("SnapshotCreationCard("))
        assertTrue(historyPage.contains("CommitHistoryListHeader("))
    }

    @Test
    fun snapshotContentUsesSingleLevelCardsAndProtectedDangerActions() {
        val source = managerSource()
        val snapshotPage = source
            .substringAfter("private fun MdbxSnapshotPage(")
            .substringBefore("private fun MdbxCommitHistoryPage(")
        val snapshotRow = source
            .substringAfter("private fun SnapshotRow(")
            .substringBefore("private fun SnapshotInfoPill(")

        assertFalse(source.contains("private fun SnapshotManagerPanel("))
        assertTrue(snapshotPage.contains("items = visibleState.snapshots.take(30)"))
        assertTrue(snapshotPage.contains("pendingRevertSnapshot?.let"))
        assertTrue(snapshotPage.contains("pendingDeleteSnapshot?.let"))
        assertTrue(snapshotPage.contains("showPruneAutomaticConfirmation"))
        assertTrue(snapshotRow.contains("DropdownMenu("))
        assertTrue(snapshotRow.contains("R.string.mdbx_ui_snapshot_more_actions"))
        assertTrue(snapshotRow.contains("surfaceContainerLow"))
    }

    @Test
    fun snapshotCreationExplainsMdbx2AndConfirmsUnchangedRequests() {
        val source = managerSource()
        val snapshotPage = source
            .substringAfter("private fun MdbxSnapshotPage(")
            .substringBefore("private fun MdbxCommitHistoryPage(")
        val creationCard = source
            .substringAfter("private fun SnapshotCreationCard(")
            .substringBefore("private fun SnapshotListHeader(")

        assertTrue(snapshotPage.contains("pendingNoChangesSnapshotRequest"))
        assertTrue(snapshotPage.contains("MdbxSnapshotCreateOutcome.NoChanges"))
        assertTrue(snapshotPage.contains("mdbx_snapshot_no_changes_title"))
        assertTrue(snapshotPage.contains("mdbx_snapshot_create_full_anyway"))
        assertTrue(creationCard.contains("engineAlwaysCreatesFullSnapshots"))
        assertTrue(creationCard.contains("mdbx_snapshot_create_when_changed"))
    }

    @Test
    fun historyCardsKeepTechnicalIdentifiersOutOfTheList() {
        val source = managerSource()
        val deltaRow = source
            .substringAfter("private fun DeltaRow(")
            .substringBefore("private fun MdbxSnapshotSummary.displayName(")

        assertTrue(deltaRow.contains("presentation.title"))
        assertTrue(deltaRow.contains("presentation.supportingText"))
        assertTrue(deltaRow.contains("presentation.objectCount"))
        assertTrue(deltaRow.contains("formatMdbxHistoryTime(delta.createdAt)"))
        assertFalse(deltaRow.contains("设备 ${'$'}{shortId(delta.deviceId)}"))
    }

    private fun managerSource(): String = projectFile(
        "app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt"
    ).readText()

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
