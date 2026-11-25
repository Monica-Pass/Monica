package takagi.ru.monica.credentialexchange

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.*
import takagi.ru.monica.ui.LocalUiSecurityManager
import takagi.ru.monica.ui.screens.*
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.PasswordViewModel

class LocalBitwardenMoveIssue189UiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realEditorMovesExistingLocalLoginAndReopensSameBitwardenRecord() = runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready)
        val manager = SecurityManager(context)
        assertTrue(if (manager.isMasterPasswordSet()) manager.unlockVaultWithPassword(LocalBitwardenMoveIssue189Test.MASTER)
            else manager.setMasterPassword(LocalBitwardenMoveIssue189Test.MASTER))
        val f = TransferFixture()
        val settings = SettingsManager(context)
        val oldMode = settings.settingsFlow.first().passwordContentEditorEnabled
        settings.updatePasswordContentEditorEnabled(true)
        val model = PasswordViewModel(PasswordRepository(f.db.passwordEntryDao(), categoryDao = f.db.categoryDao(),
            bitwardenFolderDao = f.db.bitwardenFolderDao()), f.security,
            customFieldRepository = CustomFieldRepository(f.db.customFieldDao()), context = context,
            strings = AppLocaleStringResolver(context))
        try {
            val target = f.bitwarden()
            val id = f.db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "${f.prefix}-Issue189 UI",
                website = "https://example.invalid/189", username = "synthetic-account",
                password = f.security.encryptData(LocalBitwardenMoveIssue189Test.SECRET), notes = "Synthetic retained note"))
            var savedId: Long? = null
            var open by mutableStateOf(true)
            var page by mutableIntStateOf(0)
            compose.setContent {
                CompositionLocalProvider(LocalUiSecurityManager provides f.security) {
                    MaterialTheme {
                        if (open) key(page) { AddEditPasswordScreen(model, passwordId = id,
                            onSaveCompleted = { savedId = it; open = false }, onNavigateBack = {}) }
                        else Text("Synthetic Issue189 save completed")
                    }
                }
            }
            fun capture(name: String) {
                val image = compose.onRoot().captureToImage().asAndroidBitmap()
                File(context.filesDir, "issue189-$name.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("password_editor_save").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.multi_storage_title)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.single_select)).assertIsEnabled().performClick()
            val vaultName = requireNotNull(f.db.bitwardenVaultDao().getVaultById(target.databaseId!!)).displayName!!
            compose.onNodeWithText(vaultName).performScrollTo().performClick()
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithText("$vaultName · ${context.getString(R.string.multi_storage_bitwarden_no_folder)}").assertExists()
            capture("editor-target-before-save")
            compose.onNodeWithTag("password_editor_save").assertIsEnabled().performClick()
            try { compose.waitUntil(10_000) { savedId != null } }
            finally { capture("editor-after-save") }
            assertEquals(id, savedId)
            val moved = requireNotNull(f.db.passwordEntryDao().getPasswordEntryById(id))
            assertEquals(target.databaseId, moved.bitwardenVaultId)
            assertEquals(LocalBitwardenMoveIssue189Test.SECRET, f.security.decryptData(moved.password))
            assertEquals("Synthetic retained note", moved.notes)
            compose.runOnIdle { page++; open = true; savedId = null }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("password_editor_save").fetchSemanticsNodes().isNotEmpty() }
            capture("editor-reopened")
            compose.onNodeWithTag("password_editor_save").performClick()
            compose.waitUntil(10_000) { savedId != null }
            assertEquals(id, savedId)
            assertEquals(listOf(id), f.importedPasswords(target).map { it.id })
        } finally { model.viewModelScope.cancel(); settings.updatePasswordContentEditorEnabled(oldMode); f.close() }
    }
}
