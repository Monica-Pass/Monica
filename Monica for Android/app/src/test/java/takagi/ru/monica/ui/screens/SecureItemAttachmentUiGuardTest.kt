package takagi.ru.monica.ui.screens

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureItemAttachmentUiGuardTest {

    @Test
    fun noteCardAndDocumentEditorsUseSecureItemOwnersAndDraftFlush() {
        val editorFiles = listOf(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditNoteScreen.kt",
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditBankCardScreen.kt",
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditDocumentScreen.kt"
        )

        editorFiles.forEach { path ->
            val source = projectFile(path).readText()
            assertTrue("$path must render the reusable attachment editor", source.contains("AttachmentsEditSection("))
            assertTrue("$path must bind attachments to a SecureItem owner", source.contains("AttachmentOwner.secureItem"))
            assertTrue("$path must retain drafts until a new item id exists", source.contains("pendingAttachmentDrafts"))
            assertTrue(
                "$path must flush drafts after the primary item is persisted",
                source.contains("onPrimaryCreated") || source.contains("onPrimarySaved")
            )
        }
    }

    @Test
    fun noteCardAndDocumentDetailsRenderSecureItemAttachments() {
        val detailFiles = listOf(
            "app/src/main/java/takagi/ru/monica/ui/screens/NoteDetailScreen.kt",
            "app/src/main/java/takagi/ru/monica/ui/screens/BankCardDetailScreen.kt",
            "app/src/main/java/takagi/ru/monica/ui/screens/DocumentDetailScreen.kt"
        )

        detailFiles.forEach { path ->
            val source = projectFile(path).readText()
            assertTrue("$path must render the reusable attachment detail section", source.contains("AttachmentsDetailSection("))
            assertTrue("$path must bind attachments to a SecureItem owner", source.contains("AttachmentOwner.secureItem"))
            assertTrue("$path must reconcile KeePass entry binaries", source.contains("keepassReconciler(context).reconcile("))
        }
    }

    @Test
    fun cardAndDocumentScreensExcludeReservedPhotoBinaries() {
        val files = listOf(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditBankCardScreen.kt",
            "app/src/main/java/takagi/ru/monica/ui/screens/BankCardDetailScreen.kt",
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditDocumentScreen.kt",
            "app/src/main/java/takagi/ru/monica/ui/screens/DocumentDetailScreen.kt"
        )

        files.forEach { path ->
            val source = projectFile(path).readText()
            assertTrue(
                "$path must hide Monica's dedicated front/back photo binaries from generic attachments",
                source.contains("KeePassSecureItemPhotoAttachments.managedFileNames") &&
                    source.contains("excludedFileNames")
            )
        }
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            directory.parentFile != null &&
            !File(directory, "settings.gradle").exists() &&
            !File(directory, "settings.gradle.kts").exists()
        ) {
            directory = directory.parentFile!!.canonicalFile
        }
        return File(directory, relativePath)
    }
}
