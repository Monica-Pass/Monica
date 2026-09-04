package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteBitwardenAutoSyncGuardTest {

    @Test
    fun notesOnlyEnableBitwardenAutoSyncForAnExplicitVaultFilter() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteListScreen.kt"
        ).readText().replace("\r\n", "\n")
        val effectCall = source
            .substringAfter("BitwardenAutoSyncEffect(")
            .substringBefore("\n    )")

        assertTrue(
            "The notes page must not start the all-vault Bitwarden sync for local/KeePass/MDBX or All views.",
            effectCall.contains("enabled = hasRestoredCategoryFilter && selectedBitwardenVaultId != null")
        )
    }

    private fun projectFile(path: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            dir.parentFile != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile!!
        }
        return File(dir, path)
    }
}
