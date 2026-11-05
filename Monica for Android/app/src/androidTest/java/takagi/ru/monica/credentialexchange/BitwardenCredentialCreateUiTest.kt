package takagi.ru.monica.credentialexchange

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.LocalUiSecurityManager
import takagi.ru.monica.ui.screens.*
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.PasswordViewModel
import java.util.UUID

class BitwardenCredentialCreateUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun titleOnlyInBitwardenFolderSavesOnceAndReopensWithoutCreatingAnother() = runBlocking {
        val field = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val previousMdk = field.get(null)
        val synthetic = ByteArray(32) { (it + 41).toByte() }
        field.set(null, synthetic)
        try {
            val fixture = TransferFixture()
            val settings = SettingsManager(fixture.context)
            val contentBefore = settings.settingsFlow.first().passwordContentEditorEnabled
            settings.updatePasswordContentEditorEnabled(true)
            val model = PasswordViewModel(PasswordRepository(fixture.db.passwordEntryDao(),
                categoryDao = fixture.db.categoryDao(), bitwardenFolderDao = fixture.db.bitwardenFolderDao()),
                fixture.security, customFieldRepository = CustomFieldRepository(fixture.db.customFieldDao()),
                context = fixture.context, strings = AppLocaleStringResolver(fixture.context))
            try {
                val target = fixture.bitwarden()
                val folder = UUID.randomUUID().toString()
                fixture.db.bitwardenFolderDao().insert(BitwardenFolder(vaultId = target.databaseId!!,
                    bitwardenFolderId = folder, name = "Synthetic folder", revisionDate = "2026-01-01T00:00:00Z"))
                val title = "${fixture.prefix}-title-only"
                var savedId: Long? = null
                var editId by mutableStateOf<Long?>(null)
                var open by mutableStateOf(true)
                compose.setContent {
                    CompositionLocalProvider(LocalUiSecurityManager provides fixture.security) {
                        MaterialTheme {
                            if (open) AddEditPasswordScreen(model, passwordId = editId,
                                initialStorageExplicit = true, initialBitwardenVaultId = target.databaseId,
                                initialBitwardenFolderId = folder,
                                initialDraft = if (editId == null) AddEditPasswordInitialDraft(title = title) else null,
                                onSaveCompleted = { savedId = it; open = false }, onNavigateBack = {})
                            else Text("Synthetic save completed")
                        }
                    }
                }
                fun clickSave() {
                    compose.waitUntil(20_000) { compose.onAllNodesWithTag("password_content_editor").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("password_editor_save").assertIsEnabled().performClick()
                    compose.waitUntil(30_000) { savedId != null }
                    compose.onNodeWithText("Synthetic save completed").assertExists()
                }
                clickSave()
                val id = requireNotNull(savedId)
                val created = fixture.importedPasswords(target).single()
                assertEquals(id, created.id)
                assertEquals(folder, created.bitwardenFolderId)
                assertEquals("", fixture.security.decryptData(created.password))
                compose.runOnIdle { editId = id; savedId = null; open = true }
                clickSave()
                assertEquals(listOf(id), fixture.importedPasswords(target).map { it.id })
                val vault = requireNotNull(fixture.db.bitwardenVaultDao().getVaultById(target.databaseId!!))
                takagi.ru.monica.bitwarden.service.BitwardenSyncService(fixture.context)
                    .uploadLocalEntries(vault, fixture.accessToken, fixture.vaultKey)
                assertEquals(1, fixture.remote.created.size)
            } finally {
                model.viewModelScope.cancel()
                settings.updatePasswordContentEditorEnabled(contentBefore)
                fixture.close()
            }
        } finally { field.set(null, previousMdk); synthetic.fill(0) }
    }
}
