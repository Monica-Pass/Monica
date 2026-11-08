package takagi.ru.monica.bitwarden

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.navigation.easyNotesScreenEnter
import takagi.ru.monica.ui.navigation.easyNotesScreenExit
import takagi.ru.monica.ui.screens.AddEditSendScreen
import takagi.ru.monica.ui.theme.MonicaTheme

/** Real NavBackStackEntry lifecycle and production transitions catch a second pop on ON_STOP. */
@RunWith(AndroidJUnit4::class)
class BitwardenSendNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var nav: NavHostController
    private val callbacks = AtomicInteger()
    private val cleanups = AtomicInteger()
    private var wasUnlocked = false

    @Before fun openSyntheticHost() {
        val user = android.os.Process.myUid() / 100000
        check(user > 0 && InstrumentationRegistry.getArguments().getString("legacyVaultwardenUser") == user.toString())
        wasUnlocked = SessionManager.isUnlocked.value
        compose.runOnIdle { SessionManager.markUnlocked() }
        compose.setContent {
            nav = rememberNavController()
            MonicaTheme { Surface {
                NavHost(navController = nav, startDestination = "home",
                    enterTransition = { easyNotesScreenEnter() }, exitTransition = { easyNotesScreenExit() },
                    popEnterTransition = { easyNotesScreenEnter() }, popExitTransition = { easyNotesScreenExit() }) {
                    composable("home") {
                        Button(onClick = { nav.navigate("send-editor") }, modifier = Modifier.testTag("send-home")) {
                            Text("Open synthetic Send")
                        }
                    }
                    composable("send-editor") {
                        AddEditSendScreen(sendState = BitwardenViewModel.SendState.Idle,
                            initialTitle = "Synthetic Send title", initialText = "Synthetic private Send text",
                            onNavigateBack = { callbacks.incrementAndGet(); nav.popBackStack() },
                            onLeaveForeground = { cleanups.incrementAndGet() },
                            onCreate = { _, _, _, _, _, _, _, _, _ -> },
                            onCreateFile = { _, _, _, _, _, _, _, _, _ -> })
                    }
                }
            } }
        }
        openEditor()
    }

    @After fun restoreSession() {
        compose.runOnIdle {
            compose.activity.setContentView(android.widget.FrameLayout(compose.activity))
            if (wasUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
        }
    }

    @Test fun toolbarBackReturnsExactlyOnceAndHomeRemainsVisibleAfterExit() {
        pressToolbarBack()
        assertHomeAndOneExit()
    }

    @Test fun systemBackReturnsExactlyOnceAndCanReopenEditor() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertHomeAndOneExit()
        openEditor()
        pressToolbarBack()
        assertHomeAndOneExit(expectedExits = 2)
    }

    @Test fun lockRevocationCannotPopTheHomeScreenDuringEditorExit() {
        compose.runOnIdle { SessionManager.markLocked() }
        assertHomeAndOneExit()
        compose.onNodeWithText("Synthetic private Send text").assertDoesNotExist()
    }

    @Test fun backgroundRevocationClearsEditorAndReturnsOnlyOnce() {
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertHomeAndOneExit()
        compose.onNodeWithText("Synthetic private Send text").assertDoesNotExist()
    }

    private fun openEditor() {
        compose.onNodeWithTag("send-home").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("send-editor", nav.currentDestination?.route) }
        compose.onNodeWithText("Synthetic Send title").assertIsDisplayed()
    }

    private fun pressToolbarBack() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).performClick()
        compose.waitForIdle()
    }

    private fun assertHomeAndOneExit(expectedExits: Int = 1) {
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("The exiting Send must not pop the destination underneath it", expectedExits, callbacks.get())
            assertEquals("home", nav.currentDestination?.route)
            assertEquals("Cleanup must run once for each editor", expectedExits, cleanups.get())
        }
        compose.onNodeWithTag("send-home").assertIsDisplayed()
    }
}
