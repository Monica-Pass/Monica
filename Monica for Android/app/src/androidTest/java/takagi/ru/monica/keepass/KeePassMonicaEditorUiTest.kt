package takagi.ru.monica.keepass

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import androidx.compose.ui.graphics.asAndroidBitmap

/** The real Monica editor must remain in use for an original-vault password. */
class KeePassMonicaEditorUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun standardEditorSavesOriginalVaultAndRevokesDraftWhenLocked() = runBlocking {
        val customIconUuid = UUID.randomUUID()
        val customIconBytes = java.io.ByteArrayOutputStream().use { out ->
            val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { source ->
            source.modifyCustomIcons { it + (customIconUuid to app.keemobile.kotpass.models.CustomIcon(data = customIconBytes, name = "Test", lastModified = null)) }
                .modifyParentGroup { copy(entries = entries.map { it.copy(customIconUuid = customIconUuid) }) }
        }) { model, service, id, room, uri ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val security = SecurityManager(context)
            val dao = PasswordDatabase.getDatabase(context).localKeePassDatabaseDao()
            val libraries = LocalKeePassViewModel(context.applicationContext as android.app.Application, dao, security)
            val repository = SecureItemRepository(room.secureItemDao())
            val banks = BankCardViewModel(repository, context, dao, security, strings = AppLocaleStringResolver(context))
            val notes = NoteViewModel(repository, context = context, localKeePassDatabaseDao = dao, securityManager = security, strings = AppLocaleStringResolver(context))
            val visible = mutableStateOf(true)
            var backCount = 0
            model.holdNativeKeePassRead(id).use {
                withContext(Dispatchers.Main) {
                    val field = PasswordViewModel::class.java.getDeclaredField("_categoryFilter").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    (field.get(model) as MutableStateFlow<CategoryFilter>).value = CategoryFilter.KeePassDatabase(id)
                }
                withTimeout(30000) { while (model.passwordEntries.value.size != 300) delay(25) }
                val row = model.passwordEntries.value.first()
                try {
                    compose.setContent { MonicaTheme {
                        if (visible.value) AddEditPasswordScreen(model, bankCardViewModel = banks, noteViewModel = notes, localKeePassViewModel = libraries,
                            passwordId = row.id, onSaveCompleted = {}, onNavigateBack = { backCount++; visible.value = false })
                    } }
                    compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("password_editor_title") and hasText(row.title)).fetchSemanticsNodes().isNotEmpty() }
                    compose.onAllNodesWithTag("native_entry_editor").assertCountEquals(0)
                    compose.onNodeWithTag("entry_icon_picker").performScrollTo()
                    compose.waitUntil(15000) { compose.onAllNodesWithTag("keepass_custom_icon", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                    java.io.File(context.getExternalFilesDir(null), "keepass-custom-icons-editor.png").outputStream().use {
                        compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    compose.onNodeWithTag("password_editor_title").performScrollTo().performTextReplacement("Monica UI original vault")
                    compose.onNodeWithTag("password_editor_save").assertIsEnabled().performClick()
                    try { compose.waitUntil(30000) { backCount > 0 } }
                    catch (error: Throwable) {
                        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                            val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.joinToString("\n") { index -> compose.onAllNodes(isRoot())[index].printToString() }
                            putString("stream", "MONICA_EDITOR_SYNTHETIC_TREE\n" + roots + "\n")
                        })
                        throw error
                    }
                    assertEquals("Monica UI original vault", service.openNativeBrowser(id, true).getOrThrow().entry(
                        KeePassNativeEntryIdentity(id, UUID.fromString(row.keepassEntryUuid)))!!.title)
                    assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
                    val saved = service.openNativeBrowser(id).getOrThrow().entry(
                        KeePassNativeEntryIdentity(id, UUID.fromString(row.keepassEntryUuid)))!!
                    assertEquals(customIconUuid, saved.customIconUuid)
                    assertArrayEquals(customIconBytes, saved.customIcon!!.data)
                    compose.runOnIdle { visible.value = true }
                    compose.waitUntil(15000) { compose.onAllNodesWithTag("password_editor_title").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("password_editor_title").performScrollTo().performTextReplacement("Unsaved draft")
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    compose.runOnIdle { service.lockDatabase(id) }
                    compose.waitUntil(10000) { backCount == 2 }
                    assertFalse(service.isDatabaseUnlocked(id))
                    assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                } finally {
                    compose.runOnIdle { visible.value = false }
                    banks.viewModelScope.cancel(); notes.viewModelScope.cancel(); libraries.viewModelScope.cancel()
                }
            }
        }
    }
}
