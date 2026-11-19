package takagi.ru.monica.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.ui.password.*
import takagi.ru.monica.ui.screens.*
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.PasswordViewModel
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

/** Actual editor/Room -> production grouping/list model -> actual card renderer. */
class MultiPasswordProjectCardTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var model: PasswordViewModel
    private lateinit var security: SecurityManager
    private var oldEditor = false
    private var rows by mutableStateOf<List<PasswordEntry>>(emptyList())
    private var savedId: Long? = null
    private var rendered = false
    private var editId by mutableStateOf<Long?>(null)
    private var editorEpoch by mutableStateOf(0)
    private var detailId by mutableStateOf<Long?>(null)
    private var openedIds = emptyList<Long>()
    private val title = "Synthetic two-password project"

    @Before fun setup() {
        runBlocking { assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready) }
        runBlocking {
            val settings = SettingsManager(context)
            oldEditor = settings.settingsFlow.first().passwordContentEditorEnabled
            settings.updatePasswordContentEditorEnabled(true)
        }
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        model = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()),
            security, customFieldRepository = CustomFieldRepository(db.customFieldDao()),
            strings = AppLocaleStringResolver(context))
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        db.close()
        runBlocking { SettingsManager(context).updatePasswordContentEditorEnabled(oldEditor) }
    }

    @Test fun ordinaryAutoKeepsOneCardAfterAddingSecondPassword() = exercise(false, false, false)
    @Test fun alwaysExpandedKeepsOneCardAfterAddingSecondPassword() = exercise(false, true, false)
    @Test fun localOnlyKeepsOneCardAfterAddingSecondPassword() = exercise(true, true, false)
    @Test fun rendererKeepsProjectWholeEvenWithoutProjection() = exercise(false, true, true)
    @Test fun existingSinglePasswordEditedToTwoKeepsOneCardInAuto() = exercise(false, false, false, editExisting = true)
    @Test fun existingSinglePasswordEditedToTwoKeepsOneCardWhenExpanded() = exercise(false, true, false, editExisting = true)
    @Test fun expandedProjectOpensBothPasswordsInRealDetailAndReturnsWithLargeText() =
        exercise(false, true, false, openDetail = true)

    @Test fun expandedMixedStackPreservesProjectAndIndependentCardInSelectionMode() {
        val project = listOf(PasswordEntry(id = 501, title = title, username = "same", password = "", website = "", passwordGroupId = "project"),
            PasswordEntry(id = 502, title = title, username = "same", password = "", website = "", passwordGroupId = "project"))
        val independent = project.first().copy(id = 503, passwordGroupId = null)
        val all = project + independent
        var selected by mutableStateOf(emptySet<String>())
        compose.setContent {
            MaterialTheme {
                Column {
                    StackedPasswordGroup(all, isExpanded = true, stackCardMode = StackCardMode.ALWAYS_EXPANDED,
                        onToggleExpand = {}, onPasswordClick = {}, onSwipeLeft = {}, onSwipeRight = {},
                        onGroupSwipeRight = {}, onToggleFavorite = {}, onToggleGroupCover = {},
                        isSelectionMode = true, selectedPasswords = selectedPasswordIds(selected),
                        onToggleSelection = { id -> selected = expandPasswordProjectSelection(setOf(passwordSelectionKey(id)), all) },
                        onOpenMultiPasswordDialog = {}, onLongClick = {}, enableSharedBounds = false)
                }
            }
        }
        compose.onAllNodesWithText(title).assertCountEquals(2)
        compose.onNodeWithTag("password_project_card").performClick()
        compose.runOnIdle { assertEquals(setOf(501L, 502L), selectedPasswordIds(selected)) }
        compose.onAllNodesWithText(title)[1].performClick()
        compose.runOnIdle { assertEquals(setOf(503L), selectedPasswordIds(selected)) }
    }

    private fun exercise(localOnly: Boolean, expanded: Boolean, bypassProjection: Boolean, editExisting: Boolean = false,
                         openDetail: Boolean = false) {
        val mode = if (expanded) StackCardMode.ALWAYS_EXPANDED else StackCardMode.AUTO
        compose.setContent {
            CompositionLocalProvider(LocalUiSecurityManager provides security,
                LocalDensity provides Density(LocalDensity.current.density, if (openDetail) 1.5f else 1f)) {
                MaterialTheme(colorScheme = if (openDetail) darkColorScheme() else lightColorScheme()) {
                    if (detailId != null) {
                        PasswordDetailScreen(model, passwordId = requireNotNull(detailId), biometricEnabled = false,
                            onNavigateBack = { detailId = null }, onEditPassword = {})
                    } else if (rows.isEmpty()) {
                        key(editId, editorEpoch) {
                            AddEditPasswordScreen(model, passwordId = editId, initialStorageExplicit = true,
                                initialDraft = if (editId == null) AddEditPasswordInitialDraft(title = title, username = "synthetic-user", password = "first-synthetic") else null,
                                onSaveCompleted = { savedId = it }, onNavigateBack = {})
                        }
                    } else {
                        val config = PasswordGroupingConfig(localOnly, mode, "smart", "strict", emptySet(), emptyMap(), "Untitled")
                        val groups = if (bypassProjection) mapOf("project" to rows) else buildGroupedPasswordsForEntries(rows, config)
                        val items = buildPasswordPageListItems(setOf(PasswordPageContentType.PASSWORD), groups, emptyList(), "smart")
                        Column {
                            items.filterIsInstance<PasswordGroupListItemUi>().forEach { item ->
                                StackedPasswordGroup(item.passwords, isExpanded = expanded, stackCardMode = mode,
                                    onToggleExpand = {}, onPasswordClick = { entry ->
                                        openedIds = resolvePasswordDetailGroupPasswords(entry, rows).map { it.id }
                                        if (openDetail) detailId = entry.id
                                    }, onSwipeLeft = {}, onSwipeRight = {}, onGroupSwipeRight = {},
                                    onToggleFavorite = {}, onToggleGroupCover = {}, isSelectionMode = false,
                                    selectedPasswords = emptySet(), onToggleSelection = {},
                                    onOpenMultiPasswordDialog = { group ->
                                        openedIds = resolvePasswordDetailGroupPasswords(group.first(), rows).map { it.id }
                                        if (openDetail) detailId = group.first().id
                                    }, onLongClick = {}, enableSharedBounds = false)
                            }
                        }
                        SideEffect { rendered = true }
                    }
                }
            }
        }
        var originalId: Long? = null
        if (editExisting) {
            compose.onNodeWithTag("password_editor_save").performClick()
            compose.waitUntil(30000) { savedId != null }
            originalId = savedId
            assertEquals(1, runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }.size)
            compose.runOnIdle { editId = originalId; savedId = null; editorEpoch++ }
        }
        compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag("password_editor_add_password"))
        compose.onNodeWithTag("password_editor_add_password").performClick()
        compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag("password_editor_value_1"))
        compose.onNodeWithTag("password_editor_value_1").performTextReplacement("second-synthetic")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(30000) { savedId != null }
        val stored = runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }.sortedBy { it.id }
        assertEquals(2, stored.size)
        assertEquals(1, stored.map { it.passwordProjectKey() }.distinct().size)
        assertEquals(listOf("first-synthetic", "second-synthetic"), stored.map { security.decryptData(it.password) })
        if (editExisting) {
            assertEquals(originalId, stored.first().id)
            repeat(2) {
                compose.runOnIdle { editId = stored.last().id; savedId = null; editorEpoch++ }
                compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag("password_editor_value_1"))
                compose.onNodeWithTag("password_editor_value_1").assertTextContains("second-synthetic")
                compose.onNodeWithTag("password_editor_save").performClick()
                compose.waitUntil(30000) { savedId != null }
                val after = runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }.sortedBy { it.id }
                assertEquals(stored.map { it.id }, after.map { it.id })
                assertEquals(stored.map { it.passwordProjectKey() }, after.map { it.passwordProjectKey() })
            }
        }
        compose.runOnIdle { rows = stored }
        compose.waitUntil { rendered }
        compose.waitForIdle()
        val count = compose.onAllNodesWithText(title).fetchSemanticsNodes().size
        val opened = JSONArray()
        repeat(count) { i ->
            compose.onAllNodesWithText(title)[i].performClick()
            compose.runOnIdle { assertEquals(stored.map { it.id }, openedIds); opened.put(JSONArray(openedIds)) }
            if (openDetail) {
                stored.forEach { row ->
                    compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("detail_password_${row.id}"))
                    compose.onNodeWithTag("detail_password_${row.id}").performScrollTo().assertIsDisplayed()
                }
                androidx.test.espresso.Espresso.pressBack()
                compose.onAllNodesWithText(title).assertCountEquals(1)
                assertEquals(stored.map { it.id }, runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }.map { it.id }.sorted())
            }
        }
        val label = "local-$localOnly-expanded-$expanded-bypass-$bypassProjection" +
            (if (editExisting) "-edit-existing" else "") + (if (openDetail) "-detail-large-text" else "")
        val report = JSONObject().put("label", label).put("cardCount", count).put("databaseRows", stored.size)
            .put("projectCount", stored.map { it.passwordProjectKey() }.distinct().size)
            .put("openedIdsPerCard", opened)
        File(context.filesDir, "project-card-$label.json").writeText(report.toString(2))
        File(context.filesDir, "project-card-$label.png").outputStream().use { output ->
            requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
        assertEquals("One editor-created project must be one list card: $label", 1, count)
    }
}
