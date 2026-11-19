package takagi.ru.monica.ui.common.layout

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.PasswordVerificationContent
import java.io.File

class AdaptiveLayoutUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun shot(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.filesDir, "adaptive-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun resizingKeepsDraftAndListScrollWithoutRemountingSlots() {
        val width = mutableStateOf(1000)
        val selected = mutableStateOf(false)
        val locked = mutableStateOf(false)
        var listMounts = 0
        var detailMounts = 0
        var scrollIndex = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.width(width.value.dp).height(550.dp)) {
                        if (!locked.value) AdaptiveListDetailLayout(width.value < 840, 350.dp, selected.value,
                            list = {
                                val scroll = rememberLazyListState()
                                SideEffect { scrollIndex = scroll.firstVisibleItemIndex }
                                DisposableEffect(Unit) { listMounts++; onDispose {} }
                                LazyColumn(Modifier.testTag("items"), state = scroll) {
                                    items((0..80).toList()) { i -> TextButton(onClick = { selected.value = true }) { Text("Item $i") } }
                                }
                            }, detail = {
                                DisposableEffect(Unit) { detailMounts++; onDispose {} }
                                if (selected.value) {
                                    var draft by remember { mutableStateOf("") }
                                    Column {
                                        OutlinedTextField(draft, { draft = it }, Modifier.testTag("draft"))
                                        Button({ selected.value = false }) { Text("Back to list") }
                                    }
                                }
                            })
                    }
                }
            }
        }
        compose.onNodeWithTag("items").performScrollToIndex(30)
        compose.onNodeWithText("Item 30").performClick()
        compose.onNodeWithTag("draft").performTextInput("Synthetic resize draft")
        val before = scrollIndex
        shot("two-pane")
        for (size in listOf(720, 400, 1000, 320, 840)) {
            compose.runOnIdle { width.value = size }
            compose.onNodeWithTag("draft").assertTextContains("Synthetic resize draft").assertIsDisplayed()
            if (size < 840) compose.onNodeWithTag("items").assertIsNotDisplayed()
            assertEquals(before, scrollIndex)
            assertEquals(1, listMounts)
            assertEquals(1, detailMounts)
        }
        compose.runOnIdle { width.value = 400 }
        compose.onNodeWithText("Back to list").performClick()
        compose.onNodeWithText("Item 30").assertIsDisplayed()
        compose.runOnIdle { locked.value = true }
        compose.onNodeWithTag("draft").assertDoesNotExist()
        compose.onNodeWithTag("items").assertDoesNotExist()
    }

    @Test fun loginIsBoundedAndStillSubmitsAfterWidthChange() {
        val width = mutableStateOf(1000)
        var submitted = ""
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.width(width.value.dp).height(600.dp)) {
                        PasswordVerificationContent(onVerifyPassword = { submitted = it; false }, onSuccess = {})
                    }
                }
            }
        }
        val input = compose.onNode(hasSetTextAction())
        assertTrue((input.getUnclippedBoundsInRoot().right - input.getUnclippedBoundsInRoot().left).value <= 480.1f)
        input.performTextInput("SyntheticPassword329")
        compose.runOnIdle { width.value = 400 }
        input.assertTextContains("SyntheticPassword329")
        input.performImeAction()
        assertEquals("SyntheticPassword329", submitted)
        shot("login-compact")
        compose.runOnIdle { width.value = 1000 }
        shot("login-wide")
    }

    @Test fun shortWindowAndLargeTextKeepUnlockReachable() {
        var submitted = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(500.dp).height(300.dp)) {
                        PasswordVerificationContent(onVerifyPassword = { submitted = true; false }, onSuccess = {}, onForgotPassword = {})
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("SyntheticPassword329")
        compose.onNodeWithText(context.getString(R.string.unlock)).performScrollTo().performClick()
        assertTrue(submitted)
        compose.onNodeWithText(context.getString(R.string.forgot_password)).performScrollTo().assertIsDisplayed()
        shot("large-text-short")
    }

    @Test fun syntheticFoldRegionsAvoidHingeAndKeepContentState() {
        val fold = mutableStateOf<FoldRegion?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.width(1000.dp).height(600.dp)) {
                        AdaptiveAppRegion(true, fold.value) {
                            var draft by remember { mutableStateOf("") }
                            OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().testTag("fold-field"))
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("fold-field").performTextInput("Fold draft")
        compose.runOnIdle { fold.value = FoldRegion(490, 0, 510, 600) }
        compose.onNodeWithTag("fold-field").assertTextContains("Fold draft")
        assertTrue(compose.onNodeWithTag("fold-field").getUnclippedBoundsInRoot().right.value <= 490f)
        compose.runOnIdle { fold.value = FoldRegion(0, 290, 1000, 310) }
        assertTrue(compose.onNodeWithTag("fold-field").getUnclippedBoundsInRoot().bottom.value <= 290f)
        compose.onNodeWithTag("fold-field").assertTextContains("Fold draft")
        compose.runOnIdle { fold.value = null }
        compose.onNodeWithTag("fold-field").assertTextContains("Fold draft")
    }
}
