package takagi.ru.monica.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassNativeEntryEditorGuardTest {

    @Test
    fun `new entry opens an in memory editor and is written only on save`() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/KeePassNativeManagerScreen.kt",
        ).readText()

        assertTrue(source.contains("creatingEntryParent"))
        assertTrue(source.contains("onCreateEntry = { creatingEntryParent = it }"))
        assertTrue(source.contains("entry = editing"))
        val coordinator = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/KeePassNativeEntrySaveCoordinator.kt",
        ).readText()
        assertTrue(coordinator.contains("viewModel.saveNativeEntryDraft("))
        assertTrue(coordinator.contains("fields = fields"))
        assertFalse(source.contains("if (showCreateEntry)"))
    }

    @Test
    fun `entry editor reuses shared custom field components`() {
        val editor = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/KeePassNativeEntryEditorScreen.kt",
        ).readText()

        assertTrue(editor.contains("CustomFieldSectionHeader"))
        assertTrue(editor.contains("CustomFieldEditCard"))
        assertTrue(editor.contains("NativeEntryCredentialEditorCard"))
        assertTrue(editor.contains("NativeTotpEditorCard"))
        assertTrue(editor.contains("OpenMultipleDocuments"))
        assertTrue(editor.contains("NativePendingAttachmentsCard"))
        assertFalse(editor.contains("ArrowUpward"))
        assertFalse(editor.contains("ArrowDownward"))
    }

    @Test
    fun `predefined icon picker displays a visual grid`() {
        val editor = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/KeePassNativeEntryEditorScreen.kt",
        ).readText()

        val picker = editor
            .substringAfter("internal fun NativePredefinedIconPickerDialog(")
            .substringBefore("internal fun KeePassCustomIconNameDialog(")

        assertTrue(picker.contains("LazyVerticalGrid"))
        assertTrue(picker.contains("keepassPredefinedIconVector(icon)"))
        assertTrue(picker.contains("predefinedIconPickerItems(selectedIcon)"))
        assertFalse(picker.contains("Icon(Icons.Default.Edit"))
    }

    @Test
    fun `entry detail keeps notes in their own section and localizes standard labels`() {
        val detail = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/KeePassNativeEntryDetailScreen.kt",
        ).readText()

        assertTrue(detail.contains("filter { it != NativeEntryStandardSlot.NOTES }"))
        assertTrue(detail.contains("private fun nativeEntryDetailLabel"))
        assertTrue(detail.contains("NativeEntryStandardSlot.TITLE -> stringResource(R.string.title)"))
        assertTrue(detail.contains("NativeEntryStandardSlot.URL -> stringResource(R.string.website_url)"))
        assertFalse(detail.contains("NativeEntryStandardSlot.TITLE -> \"Title\""))
    }

    private fun projectFile(relativePath: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".")
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Unable to find project file: $relativePath")
    }
}
