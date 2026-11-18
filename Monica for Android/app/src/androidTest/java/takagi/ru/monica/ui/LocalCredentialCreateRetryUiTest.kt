package takagi.ru.monica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.*
import takagi.ru.monica.ui.screens.*
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.PasswordViewModel
import java.io.IOException

class LocalCredentialCreateRetryUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var model: PasswordViewModel
    private lateinit var security: SecurityManager
    @Volatile private var failNextFieldWrite = false
    @Volatile private var savedId: Long? = null
    private var previousContentEditor = false
    private var previouslyUnlocked = false

    @Before fun setup() = runBlocking {
        assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready)
        security = SecurityManager(context)
        previouslyUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        val settings = SettingsManager(context)
        previousContentEditor = settings.settingsFlow.first().passwordContentEditorEnabled
        settings.updatePasswordContentEditorEnabled(true)
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val actual = db.customFieldDao()
        fun injectFailure() {
            if (failNextFieldWrite) {
                failNextFieldWrite = false
                throw IOException("Synthetic UI field failure")
            }
        }
        val fields = object : CustomFieldDao by actual {
            override suspend fun replaceFieldsForEntry(entryId: Long, newFields: List<CustomField>) {
                injectFailure()
                actual.replaceFieldsForEntry(entryId, newFields)
            }
            override suspend fun deleteByEntryId(entryId: Long) {
                injectFailure()
                actual.deleteByEntryId(entryId)
            }
        }
        model = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()), security,
            customFieldRepository = CustomFieldRepository(fields), strings = AppLocaleStringResolver(context))
    }
    @After fun cleanup() {
        model.viewModelScope.cancel()
        db.close()
        runBlocking { SettingsManager(context).updatePasswordContentEditorEnabled(previousContentEditor) }
        if (previouslyUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
    }
    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    @Test fun failedSaveThenSameButtonRetryCreatesOnlyOneProject() {
        compose.setContent {
            CompositionLocalProvider(LocalUiSecurityManager provides security) {
                MaterialTheme {
                    AddEditPasswordScreen(model, passwordId = null, initialStorageExplicit = true,
                        initialDraft = AddEditPasswordInitialDraft(title = "Synthetic retry", username = "demo", password = "synthetic-password"),
                        onSaveCompleted = { savedId = it }, onNavigateBack = {})
                }
            }
        }
        scroll("password_editor_add_password").performClick()
        scroll("password_editor_value_1").performTextReplacement("synthetic-password")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.runOnIdle { failNextFieldWrite = true }
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(30_000) {
            !failNextFieldWrite && compose.onAllNodes(hasTestTag("password_editor_save") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(savedId)
        assertTrue("Failed UI save must leave no records", runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }.isEmpty())
        assertTrue(runBlocking { db.customFieldDao().getAllFieldsSync() }.isEmpty())
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(30_000) { savedId != null }
        val rows = runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }
        assertEquals(2, rows.size)
        assertEquals(1, rows.map { it.passwordProjectKey() }.distinct().size)
        assertTrue(rows.all { it.username == "demo" && security.decryptData(it.password) == "synthetic-password" })
    }
}
