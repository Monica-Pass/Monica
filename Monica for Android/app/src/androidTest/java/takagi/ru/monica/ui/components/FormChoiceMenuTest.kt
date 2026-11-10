package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import takagi.ru.monica.ui.theme.MonicaTheme

@RunWith(AndroidJUnit4::class)
class FormChoiceMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val selected = mutableStateOf(0)
    private val expanded = mutableStateOf(false)
    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(dark: Boolean, largeFont: Boolean, labels: List<String>) {
        compose.runOnUiThread {
            compose.activity.setTurnScreenOn(true)
            compose.activity.setShowWhenLocked(true)
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeFont) 1.3f else 1f)) {
                MonicaTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 64.dp)) {
                            ExposedDropdownMenuBox(expanded.value, { expanded.value = it }) {
                                OutlinedTextField(value = labels[selected.value], onValueChange = {}, readOnly = true,
                                    label = { Text("卡类型") }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                                MonicaExposedChoiceMenu(expanded.value, { expanded.value = false },
                                    labels.mapIndexed { i, label -> MonicaMenuChoice(i, label) }, selected.value,
                                    { selected.value = it })
                            }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { expanded.value = true }
    }
    private fun snapshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "form-choice-menu-316/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { compose.onNode(isPopup()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun bankTypeSelectionShowsCheckAndClosesInDarkTheme() {
        show(true, false, listOf("借记卡", "信用卡", "预付卡"))
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        snapshot("bank-dark")
        compose.onAllNodesWithText("信用卡").onLast().performClick()
        compose.runOnIdle { assertEquals(1, selected.value); assertFalse(expanded.value) }
        compose.runOnIdle { expanded.value = true }
        snapshot("bank-selected")
    }
    @Test fun documentChoicesRemainReachableWithLargeFont() {
        show(false, true, listOf("身份证", "护照", "驾驶证", "社会保障卡", "其他证件"))
        compose.onNodeWithText("其他证件").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(4, selected.value); assertFalse(expanded.value) }
        compose.runOnIdle { expanded.value = true }
        snapshot("document-large")
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(4, selected.value); assertFalse(expanded.value) }
    }
}
