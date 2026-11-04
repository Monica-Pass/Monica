package takagi.ru.monica.keepass

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.model.ProjectCredentialGroup
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.viewmodel.LocalKeePassViewModel

class KeePassMinimalFieldsEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualEditorCreatesReopensAddsAndClearsCredentialNoteWithoutDefaultNoise() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = takagi.ru.monica.utils.SettingsManager(context)
        val previousMode = settings.settingsFlow.first().passwordContentEditorEnabled
        settings.updatePasswordContentEditorEnabled(true)
        try {
            KeePassDeleteInvestigationTest().scenario { model, service, id, _, _ ->
                val libraries = LocalKeePassViewModel(context.applicationContext as Application,
                    PasswordDatabase.getDatabase(context).localKeePassDatabaseDao(), SecurityManager(context))
                var visible by mutableStateOf(true)
                var epoch by mutableStateOf(0)
                var editingId by mutableStateOf<Long?>(null)
                var savedId: Long? = null
                model.holdNativeKeePassRead(id).use {
                    try {
                        compose.setContent { key(epoch) { MonicaTheme {
                            if (visible) AddEditPasswordScreen(model, localKeePassViewModel = libraries,
                                passwordId = editingId, initialStorageExplicit = true, initialKeePassDatabaseId = id,
                                initialDraft = if (editingId == null) AddEditPasswordInitialDraft(
                                    title = "Issue177 UI", username = "synthetic", password = "test secret", website = "https://example.test") else null,
                                onSaveCompleted = { savedId = it }, onNavigateBack = { visible = false })
                        } } }
                        fun awaitEditor() = compose.waitUntil(20000) {
                            compose.onAllNodes(hasTestTag("password_editor_title") and hasText("Issue177 UI")).fetchSemanticsNodes().isNotEmpty()
                        }
                        fun save() {
                            androidx.test.espresso.Espresso.closeSoftKeyboard()
                            compose.onNodeWithTag("password_editor_save").assertIsEnabled().performClick()
                            compose.waitUntil(30000) { !visible }
                            assertNotNull(savedId)
                        }
                        suspend fun record(): KeePassNativeEntryRecord {
                            val row = requireNotNull(model.monicaKeePassStore!!.get(requireNotNull(savedId)))
                            return service.openNativeBrowser(id, true).getOrThrow().entries.single { it.identity.entryUuid.toString() == row.keepassEntryUuid }
                        }
                        fun assertNoDefaults(entry: KeePassNativeEntryRecord) {
                            for (name in listOf("MonicaLocalId", "MonicaIsFavorite", "MonicaIsArchived", "MonicaSortOrder", "MonicaIsGroupCover",
                                "MonicaCategoryId", "MonicaSsoRefEntryUuid", "MonicaBoundNoteUuid", "MonicaBoundNoteId", "MonicaPasskeyBindings",
                                "MonicaCustomIconType", "MonicaCustomIconValue")) assertNull(name, entry.field(name))
                            assertEquals("test secret", entry.field("Password")!!.rawValue)
                        }
                        fun reopen() = compose.runOnIdle { editingId = requireNotNull(savedId); epoch++; visible = true }
                        awaitEditor(); save()
                        val initial = record()
                        assertNoDefaults(initial)
                        assertNull(initial.field(ProjectCredentialGroup.FIELD))
                        assertNull(initial.field("monica.content.order"))
                        reopen(); awaitEditor()
                        compose.onNodeWithTag("password_editor_credential_note").performScrollTo().performTextReplacement("test Notiz")
                        save()
                        val withNote = record()
                        assertEquals(initial.identity, withNote.identity)
                        assertNoDefaults(withNote)
                        assertEquals("test Notiz", ProjectCredentialGroup.parse(withNote.field(ProjectCredentialGroup.FIELD)!!.rawValue)!!.note)
                        reopen(); awaitEditor()
                        // Title and account metadata load in separate suspend reads.
                        compose.waitUntil(20000) {
                            compose.onAllNodes(hasTestTag("password_editor_credential_note") and hasText("test Notiz"))
                                .fetchSemanticsNodes().isNotEmpty()
                        }
                        compose.onNodeWithTag("password_editor_credential_note").performScrollTo().assertTextContains("test Notiz").performTextReplacement("")
                        save()
                        val cleared = record()
                        assertNoDefaults(cleared)
                        val metadata = requireNotNull(ProjectCredentialGroup.parse(cleared.field(ProjectCredentialGroup.FIELD)!!.rawValue))
                        assertEquals("", metadata.note)
                        assertFalse("note" in metadata.raw)
                    } finally {
                        compose.runOnIdle { visible = false }
                        libraries.viewModelScope.cancel()
                    }
                }
            }
        } finally { settings.updatePasswordContentEditorEnabled(previousMode) }
    }
}
