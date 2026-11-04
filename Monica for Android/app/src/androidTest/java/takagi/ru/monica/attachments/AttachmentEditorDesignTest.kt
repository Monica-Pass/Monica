package takagi.ru.monica.attachments

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.attachments.ui.*
import takagi.ru.monica.ui.components.EntryContentPanel
import takagi.ru.monica.ui.components.PasswordContentSection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import takagi.ru.monica.credentialexchange.TransferFixture
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.attachments.model.AttachmentOwner

class AttachmentEditorDesignTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "attachment-editor-ui/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onNodeWithTag("content_detail_ATTACHMENTS").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun groupedFilesAndErrorRemainUsableAtLargeFont() {
        var removed = false
        var added = false
        var error by mutableStateOf<String?>("网络不可用，请检查连接后重新选择文件。")
        compose.setContent { MaterialTheme { Surface {
            EntryContentPanel(PasswordContentSection.ATTACHMENTS, "", true, {}) {
                AttachmentEditorContent(listOf(
                    AttachmentEditorItem("pdf", "使用说明.pdf", "246 KB") { removed = true },
                    AttachmentEditorItem("image", "设备照片.jpg", "1 MB") {},
                    AttachmentEditorItem("zip", "配置备份.zip", "待保存 · 64 KB") {}
                ), false, error, { error = null }, { added = true })
            }
        } } }
        compose.onNodeWithTag("attachment_error").assertExists()
        screenshot("files-error-large")
        compose.onNodeWithTag("attachment_remove_pdf").performScrollTo().performClick()
        compose.onNodeWithTag("attachment_add").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(removed); assertTrue(added) }
    }

    @Test fun processingDisablesRepeatedActions() {
        compose.setContent { MaterialTheme {
            AttachmentEditorContent(listOf(AttachmentEditorItem("file", "说明.pdf", "246 KB") {}),
                true, null, {}, {})
        } }
        compose.onNodeWithTag("attachment_add").assertIsNotEnabled()
        compose.onNodeWithTag("attachment_remove_file").assertIsNotEnabled()
    }

    @Test fun selectedFileIsEncryptedStoredAndRemovableFromEditor() {
        val fixture = TransferFixture()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(fixture.context.cacheDir, "temp_share/${fixture.prefix}.txt")
        file.parentFile!!.mkdirs()
        file.writeText("Synthetic attachment editor test")
        val ownerId = runBlocking { fixture.db.passwordEntryDao().insertPasswordEntry(
            PasswordEntry(title = fixture.prefix, username = "", password = "", website = "")) }
        val owner = AttachmentOwner.password(ownerId)
        val facade = AttachmentContainer.facade(fixture.context)
        val uri = androidx.core.content.FileProvider.getUriForFile(fixture.context, "${fixture.context.packageName}.fileprovider", file)
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        }
        val monitor = instrumentation.addMonitor(filter,
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, android.content.Intent().setData(uri)), true)
        try {
            compose.setContent { MaterialTheme {
                EntryContentPanel(PasswordContentSection.ATTACHMENTS, "", true, {}) {
                    AttachmentsEditSection(passwordId = ownerId, isPlusActivated = true)
                }
            } }
            compose.onNodeWithTag("attachment_add").performScrollTo().performClick()
            compose.waitUntil(15_000) { runBlocking { facade.observe(owner).first().size == 1 } }
            val attachment = runBlocking { facade.observe(owner).first().single() }
            assertEquals(file.name, attachment.fileName)
            assertEquals(file.length(), attachment.sizeBytes)
            assertNotNull(attachment.wrappedCek)
            compose.onNodeWithTag("attachment_remove_saved:${attachment.id}").performScrollTo().performClick()
            compose.waitUntil(15_000) { runBlocking { facade.observe(owner).first().isEmpty() } }
        } finally {
            instrumentation.removeMonitor(monitor)
            file.delete()
            runBlocking { fixture.close() }
        }
    }

    @Test fun draftRemovalAndSystemPickerCancellationPreserveRemainingFiles() {
        val drafts = mutableStateListOf(
            AttachmentPendingDraft(Uri.parse("content://synthetic/one"), "说明.pdf", 2048),
            AttachmentPendingDraft(Uri.parse("content://synthetic/two"), "照片.jpg", 4096))
        compose.setContent { MaterialTheme { Surface {
            EntryContentPanel(PasswordContentSection.ATTACHMENTS, "", true, {}) {
                AttachmentsEditSection(passwordId = -1, isPlusActivated = false, pendingDrafts = drafts)
            }
        } } }
        compose.onNodeWithTag("attachment_remove_draft:0").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("照片.jpg"), drafts.map { it.fileName }) }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun pickerVisible(): Boolean = automation.rootInActiveWindow?.packageName?.toString()?.endsWith(".documentsui") == true ||
            automation.windows.any { (it.isActive || it.isFocused) && it.root?.packageName?.toString()?.endsWith(".documentsui") == true }
        fun pressBack() = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input keyevent 4")).use { it.readBytes(); Unit }
        try {
            compose.onNodeWithTag("attachment_add").performScrollTo().performClick()
            val deadline = android.os.SystemClock.uptimeMillis() + 30_000
            while (!pickerVisible() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
            assertTrue("File picker did not become visible: active=${automation.rootInActiveWindow?.packageName}, " +
                "windows=${automation.windows.map { Triple(it.isActive, it.isFocused, it.root?.packageName) }}", pickerVisible())
            pressBack()
            compose.waitUntil(10_000) { !pickerVisible() && compose.onAllNodesWithTag("attachment_add").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(listOf("照片.jpg"), drafts.map { it.fileName }) }
        } finally {
            if (pickerVisible()) pressBack()
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
        compose.onNodeWithTag("attachment_remove_draft:0").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(drafts.isEmpty()) }
        screenshot("empty-large")
    }
}
