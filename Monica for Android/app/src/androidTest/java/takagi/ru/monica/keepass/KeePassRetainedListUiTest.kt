package takagi.ru.monica.keepass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.gestures.SwipeActions
import takagi.ru.monica.ui.password.*
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.KeePassKdbxService

class KeePassRetainedListUiTest {
    @get:Rule val compose = createComposeRule()
    private var target by mutableLongStateOf(98765001L)
    private var pending by mutableStateOf(false)
    private var locked by mutableStateOf(false)
    private var foreground by mutableStateOf(true)
    private var versions by mutableStateOf(KeePassKdbxService.nativeLockVersions.value)
    private var mounts = 0
    private var actions = 0
    private var oldAction: (() -> Unit)? = null
    private var wasUnlocked = false
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun start() {
        wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
    }
    @After fun finish() { if (!wasUnlocked) SessionManager.markLocked() }

    private fun show(externalInput: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { MonicaTheme { Column {
                if (externalInput) {
                    var query by remember { mutableStateOf("") }
                    androidx.compose.material3.TextField(query, { query = it }, Modifier.testTag("search-outside-list"))
                }
                val id = target
                val read = KeePassListRead(id, null, versions, SessionManager.lockGeneration.value,
                    foreground, locked, false, {}, {})
                val transition = if (pending || locked || !foreground)
                    KeePassListTransition("Target", null, locked, false, foreground, {}, {}) else null
                KeePassRetainedList(read, transition, object : KeePassListContent {
                    @Composable override fun Render() {
                        val instance = remember { ++mounts }
                        val allowed = LocalKeePassListInteraction.current
                        val action = { if (allowed()) actions++ }
                        SideEffect { if (oldAction == null) oldAction = action }
                        SwipeActions(onSwipeLeft = action, onSwipeRight = action) {
                            Text("Actual $id / $instance", Modifier.fillMaxWidth().height(100.dp)
                                .testTag("actual-row").clickable(onClick = action))
                        }
                    }
                })
            } } }
        }
        compose.waitForIdle()
    }

    @Test fun preparingFilteredRowsDoesNotStealFocusFromTheSearchField() {
        show(externalInput = true)
        compose.onNodeWithTag("search-outside-list").performClick().performTextInput("before")
        compose.onNodeWithTag("search-outside-list").assertIsFocused()
        compose.runOnIdle { pending = true }
        compose.onNodeWithTag("search-outside-list").assertIsFocused().performTextInput(" after")
        compose.onNodeWithTag("search-outside-list").assertTextEquals("before after")
    }

    @Test fun sameOriginalSubtreeStaysMountedWhileNewInputsArePending() {
        show()
        compose.onNodeWithText("Actual 98765001 / 1").assertExists()
        compose.runOnUiThread { target++; pending = true }
        compose.onNodeWithText("Actual 98765001 / 1", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Actual 98765002 / 1", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(1, mounts)
        compose.onNodeWithTag("actual-row").assertDoesNotExist()
        compose.runOnIdle { oldAction!!.invoke() }
        assertEquals(0, actions)
        compose.runOnIdle { pending = false }
        compose.onNodeWithText("Actual 98765002 / 1").assertExists()
        compose.runOnIdle { oldAction!!.invoke() }
        assertEquals("An old source callback cannot revive when the target becomes ready", 0, actions)
        compose.onNodeWithTag("actual-row").performClick()
        assertEquals(1, actions)
    }

    @Test fun rightSwipeReboundCannotSelectAfterSwitchingVault() {
        show()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("actual-row").performTouchInput { swipeRight(durationMillis = 200) }
        compose.runOnUiThread { target++; pending = true }
        compose.mainClock.advanceTimeBy(2000)
        assertEquals("In-flight rebound callback must be revoked", 0, actions)
        compose.runOnIdle { pending = false }
        compose.mainClock.advanceTimeBy(2000)
        assertEquals(0, actions)
        compose.mainClock.autoAdvance = true
    }

    @Test fun lockingPreviousSourceDropsItsActualRowsWhileTargetIsPending() {
        show()
        compose.runOnUiThread { target++; pending = true }
        compose.onNodeWithTag("actual-row", useUnmergedTree = true).assertExists()
        compose.runOnIdle { versions = versions + (98765001L to 1L) }
        compose.onNodeWithTag("actual-row", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun backgroundClearsRowsAndReturningDoesNotRestoreThemBeforeReadCompletes() {
        show()
        compose.runOnIdle {
            target++; pending = true; foreground = false
            owner.registry.currentState = Lifecycle.State.CREATED
        }
        compose.onNodeWithTag("actual-row", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { foreground = true; owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("actual-row", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { pending = false }
        compose.onNodeWithTag("actual-row").assertExists()
    }
}
