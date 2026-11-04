package takagi.ru.monica.credentialexchange

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore
import takagi.ru.monica.bitwarden.service.BitwardenSyncService
import takagi.ru.monica.bitwarden.service.UploadResult
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.ui.LocalUiSecurityManager
import takagi.ru.monica.ui.bringAboveFloatingActions
import takagi.ru.monica.ui.closeFocusedKeyboard
import takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

/** Actual password screen, with a synthetic OpenDocument result rather than a real system picker. */
@RunWith(AndroidJUnit4::class)
class PasswordAttachmentEditorRouteDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var fixture: TransferFixture
    private lateinit var model: PasswordViewModel
    private lateinit var notes: NoteViewModel
    private lateinit var file: File
    private lateinit var monitor: Instrumentation.ActivityMonitor
    private var visible by mutableStateOf(true)
    private val payload = "Synthetic password editor attachment\n正文".toByteArray()

    @Before fun setup() {
        fixture = TransferFixture()
        model = PasswordViewModel(fixture.passwords, fixture.security,
            customFieldRepository = CustomFieldRepository(fixture.db.customFieldDao()), context = fixture.context,
            localKeePassDatabaseDao = fixture.db.localKeePassDatabaseDao(), strings = AppLocaleStringResolver(fixture.context))
        notes = NoteViewModel(fixture.secureItems, strings = AppLocaleStringResolver(fixture.context))
        file = File(fixture.context.cacheDir, "temp_share/${fixture.prefix}.bin").also { it.parentFile!!.mkdirs(); it.writeBytes(payload) }
        val uri = FileProvider.getUriForFile(fixture.context, "${fixture.context.packageName}.fileprovider", file)
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
        monitor = InstrumentationRegistry.getInstrumentation().addMonitor(filter,
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)), true)
    }

    @After fun cleanup() {
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().removeMonitor(monitor)
        file.delete()
        notes.viewModelScope.cancel()
        model.viewModelScope.cancel()
        runBlocking { fixture.close() }
    }

    private fun open(vaultId: Long, id: Long? = null, saved: (Long?) -> Unit = {}) {
        compose.setContent {
            if (visible) CompositionLocalProvider(LocalUiSecurityManager provides fixture.security) {
                MaterialTheme {
                    AddEditPasswordScreen(model, noteViewModel = notes, passwordId = id,
                        initialStorageExplicit = true, initialBitwardenVaultId = vaultId,
                        initialDraft = AddEditPasswordInitialDraft(title = fixture.prefix, username = "synthetic",
                            password = "synthetic password"), onSaveCompleted = saved, onNavigateBack = {})
                }
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("password_content_editor").fetchSemanticsNodes().isNotEmpty() }
        closeFocusedKeyboard()
        val editor = compose.onNodeWithTag("password_content_editor")
        editor.performScrollToNode(hasTestTag("password_content_add"))
        compose.bringAboveFloatingActions(compose.onNodeWithTag("password_content_add"), editor)
        compose.onNodeWithTag("password_content_add").performClick()
        compose.onNodeWithTag("password_content_choose_ATTACHMENTS").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("attachment_add").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("attachment_add").performScrollTo().performClick()
    }

    @Test fun newPasswordPickerSavePersistsUploadIntentBeforeRemoteParentExists() {
        val vaultId = runBlocking { fixture.bitwarden().databaseId }
        BitwardenVaultPremiumStore.setPremium(fixture.context, vaultId, true)
        var savedId: Long? = null
        open(vaultId, saved = { savedId = it })
        compose.waitUntil(10_000) { compose.onAllNodesWithText(file.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("content_detail_back").performClick()
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(30_000) { savedId != null }
        runBlocking {
            val id = requireNotNull(savedId)
            val entry = fixture.db.passwordEntryDao().getPasswordEntryById(id)!!
            assertEquals(vaultId, entry.bitwardenVaultId)
            assertNull(entry.bitwardenCipherId)
            val facade = AttachmentContainer.facade(fixture.context)
            val attachment = facade.list(AttachmentOwner.password(id)).single()
            assertEquals(AttachmentSource.LOCAL, attachment.sourceEnum)
            assertNotNull(attachment.wrappedCek)
            assertArrayEquals(payload, facade.readAttachmentBytes(attachment.id, 8192))
            assertEquals(setOf(attachment.id.toString()), fixture.context.getSharedPreferences("credential_import_attachments_v1", 0)
                .getStringSet("vault:$vaultId", emptySet()))
            val vault = fixture.db.bitwardenVaultDao().getVaultById(vaultId)!!
            val sync = BitwardenSyncService(fixture.context)
            assertEquals(0, (sync.uploadLocalEntries(vault, fixture.accessToken, fixture.vaultKey) as UploadResult.Success).failed)
            sync.uploadModifiedEntries(vault, fixture.accessToken, fixture.vaultKey)
            assertEquals(AttachmentSource.BITWARDEN, facade.getById(attachment.id)!!.sourceEnum)
        }
    }

    @Test fun existingPasswordPickerUploadsAndRemoteDeleteUsesOwningCipher() {
        val vaultId = runBlocking { fixture.bitwarden().databaseId }
        BitwardenVaultPremiumStore.setPremium(fixture.context, vaultId, true)
        val id = runBlocking { fixture.db.passwordEntryDao().insertPasswordEntry(PasswordEntry(
            title = fixture.prefix, website = "https://example.invalid", username = "synthetic",
            password = fixture.security.encryptData("synthetic password"), bitwardenVaultId = vaultId, bitwardenLocalModified = true)) }
        val deleted = java.util.concurrent.CopyOnWriteArrayList<String>()
        fixture.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "DELETE" && request.path.orEmpty().contains("/attachment/")) {
                    deleted += request.path!!
                    return MockResponse().setResponseCode(200)
                }
                return fixture.remote.dispatch(request)
            }
        }
        open(vaultId, id)
        val facade = AttachmentContainer.facade(fixture.context)
        val owner = AttachmentOwner.password(id)
        compose.waitUntil(15_000) { runBlocking { facade.list(owner).size == 1 } }
        val uploaded = runBlocking {
            val vault = fixture.db.bitwardenVaultDao().getVaultById(vaultId)!!
            val sync = BitwardenSyncService(fixture.context)
            assertEquals(0, (sync.uploadLocalEntries(vault, fixture.accessToken, fixture.vaultKey) as UploadResult.Success).failed)
            sync.uploadModifiedEntries(vault, fixture.accessToken, fixture.vaultKey)
            facade.list(owner).single().also { assertEquals(AttachmentSource.BITWARDEN, it.sourceEnum) }
        }
        compose.onNodeWithTag("attachment_remove_saved:${uploaded.id}").performScrollTo().performClick()
        compose.waitUntil(15_000) { runBlocking { facade.list(owner).isEmpty() } }
        val cipherId = runBlocking { fixture.db.passwordEntryDao().getPasswordEntryById(id)!!.bitwardenCipherId }
        assertEquals(listOf("/ciphers/$cipherId/attachment/${uploaded.bitwardenAttachmentId}"), deleted.toList())
    }
}
