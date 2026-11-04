package takagi.ru.monica.ui

import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.EntryContentPanel
import takagi.ru.monica.ui.components.PasswordContentSection
import takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel

class EntryContentWindowStabilityTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun noteWindowSurvivesItsCardLeavingTheViewport() {
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        var open by mutableStateOf(false)
        var note by mutableStateOf("keep this draft")
        compose.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                LazyColumn(Modifier.fillMaxWidth().height(220.dp), state = list) {
                    item(key = "notes") {
                        EntryContentPanel(PasswordContentSection.NOTES, note, open, { open = it }) {
                            OutlinedTextField(note, { note = it }, Modifier.testTag("note_draft"))
                        }
                    }
                    items(50) { Text("Other field $it", Modifier.height(80.dp)) }
                }
            }
        }
        compose.onNodeWithTag("content_panel_NOTES").performClick()
        compose.onNodeWithTag("note_draft").assertIsNotFocused()
        val windowsBefore = mutableSetOf<View>()
        compose.runOnIdle { windowsBefore.addAll(WindowInspector.getGlobalWindowViews()) }
        // Mirrors parent scrolling/re-layout while its content editor is open.
        runBlocking(Dispatchers.Main) { list.scrollToItem(40) }
        compose.waitForIdle()
        compose.onNodeWithTag("content_detail_NOTES").assertIsDisplayed()
        compose.onNodeWithTag("note_draft").assertTextContains("keep this draft")
        compose.runOnIdle {
            assertEquals("Editing must keep the same native window", windowsBefore,
                WindowInspector.getGlobalWindowViews().toSet())
        }
        compose.onNodeWithTag("content_detail_back").performClick()
        compose.onNodeWithTag("content_detail_NOTES").assertDoesNotExist()
        // Releasing the editor must release the lazy item too.
        compose.onNodeWithTag("content_panel_NOTES").assertDoesNotExist()
        runBlocking(Dispatchers.Main) { list.scrollToItem(0) }
        compose.onNodeWithTag("content_panel_NOTES").performClick()
        compose.onNodeWithTag("note_draft").assertTextContains("keep this draft")
    }

    @Test fun addingNotesFromPasswordMenuCreatesOnlyOneEditorWindow() {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val security = SecurityManager(context)
        val model = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()), security,
            customFieldRepository = CustomFieldRepository(db.customFieldDao()), strings = AppLocaleStringResolver(context))
        var visible by mutableStateOf(true)
        val observed = linkedSetOf<View>()
        val ignored = mutableSetOf<View>()
        val events = mutableListOf<String>()
        var sampling = true
        val probe = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                WindowInspector.getGlobalWindowViews().filterNot { it in ignored }.forEach { view ->
                    if (observed.add(view)) events += "${SystemClock.uptimeMillis()}: ${view.javaClass.name} #${System.identityHashCode(view)}"
                }
                if (sampling) Choreographer.getInstance().postFrameCallback(this)
            }
        }
        try {
            compose.setContent {
                if (visible) CompositionLocalProvider(LocalUiSecurityManager provides security) {
                    MaterialTheme {
                        AddEditPasswordScreen(viewModel = model, passwordId = null, initialStorageExplicit = true,
                            initialDraft = AddEditPasswordInitialDraft(title = "Window fixture", username = "alice", password = "synthetic-password"),
                            onNavigateBack = {})
                    }
                }
            }
            val editor = compose.onNodeWithTag("password_content_editor")
            editor.performScrollToNode(hasTestTag("password_content_add"))
            bringAboveSave(compose.onNodeWithTag("password_content_add"), editor)
            compose.onNodeWithTag("password_content_add").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("password_content_choose_NOTES").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("password_content_choose_NOTES").performScrollTo()
            compose.runOnIdle {
                ignored.addAll(WindowInspector.getGlobalWindowViews())
                Choreographer.getInstance().postFrameCallback(probe)
            }
            compose.onNodeWithTag("password_content_choose_NOTES").performClick()
            SystemClock.sleep(3000)
            compose.onNodeWithTag("password_content_notes").assertIsDisplayed()
            compose.runOnIdle {
                File(context.filesDir, "note-window-trace.txt").writeText(events.joinToString("\n"))
                assertEquals("Adding notes must not repeatedly recreate its native window: $events", 1, observed.size)
            }
            compose.onNodeWithTag("password_content_notes").performTextReplacement("draft survives opening")
            compose.onNodeWithTag("content_detail_back").performClick()
            editor.performScrollToNode(hasTestTag("content_panel_NOTES"))
            bringAboveSave(compose.onNodeWithTag("content_panel_NOTES"), editor)
            compose.onNodeWithTag("content_panel_NOTES").performClick()
            compose.onNodeWithTag("password_content_notes").assertTextContains("draft survives opening")
        } finally {
            compose.runOnIdle {
                sampling = false
                Choreographer.getInstance().removeFrameCallback(probe)
                visible = false
            }
            compose.waitForIdle()
            model.viewModelScope.cancel()
            db.close()
        }
    }

    private fun bringAboveSave(target: SemanticsNodeInteraction, editor: SemanticsNodeInteraction) {
        // ScrollTo only makes a lazy item intersect the viewport; it may still sit beneath Save.
        compose.bringAboveFloatingActions(target, editor)
        target.assertIsDisplayed()
        val bounds = target.fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("password_editor_save").fetchSemanticsNode().boundsInRoot
        assertFalse("Real touch must reach the content control, not the floating Save button",
            minOf(bounds.right, save.right) > maxOf(bounds.left, save.left) &&
                minOf(bounds.bottom, save.bottom) > maxOf(bounds.top, save.top))
    }
}
