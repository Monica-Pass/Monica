package takagi.ru.monica.ui.common.selection

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.ColorScheme as AppPalette
import takagi.ru.monica.ui.screens.NoteSelectionActionBar
import takagi.ru.monica.ui.theme.MonicaTheme

class SelectionActionBarInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var palette by mutableStateOf(AppPalette.DEFAULT)
    private var dark by mutableStateOf(false)
    private var oled by mutableStateOf(false)
    private var selected by mutableIntStateOf(16)
    private val actions = mutableListOf<String>()
    private lateinit var scheme: ColorScheme

    private fun show(narrow: Boolean = false, rtl: Boolean = false, note: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, if (narrow) 2f else 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MonicaTheme(darkTheme = dark, oledPureBlackEnabled = oled, colorScheme = palette,
                    customPrimaryColor = 0xFF008577, customSecondaryColor = 0xFF777777,
                    customTertiaryColor = 0xFF666666, customNeutralColor = 0xFF111111) {
                    val current = MaterialTheme.colorScheme
                    SideEffect { scheme = current }
                    Surface(Modifier.width(if (narrow) 280.dp else 400.dp).height(400.dp).testTag("scene")) {
                        Box {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                repeat(4) { index ->
                                    Surface(Modifier.fillMaxWidth().height(76.dp), RoundedCornerShape(20.dp),
                                        color = if (index == 1) current.secondaryContainer else current.surfaceContainerHighest) {
                                        Text("Sample ${index + 1}", Modifier.padding(16.dp))
                                    }
                                }
                            }
                            if (note) NoteSelectionActionBar(
                                Modifier.align(Alignment.BottomCenter).padding(12.dp), selected,
                                { actions += "close" }, { actions += "all" },
                                { actions += "move" }, { actions += "delete" },
                            ) else SelectionActionBar(
                                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                                selectedCount = selected,
                                onExit = { actions += "close" }, onSelectAll = { actions += "all" },
                                onFavorite = { actions += "favorite" }, onMoveToCategory = { actions += "move" },
                                onStack = { actions += "stack" }, onDelete = { actions += "delete" },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun label(id: Int) = context.getString(id)
    private fun contrast(fg: Color, bg: Color) = ColorUtils.calculateContrast(fg.toArgb(), bg.toArgb())
    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("scene").captureToImage().asAndroidBitmap()
        File(context.filesDir, "selection-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun allAppPalettesHaveReadableActionsAndSeparateSurfaces() {
        show()
        val record = mutableListOf<String>()
        for (choice in AppPalette.entries) for (night in listOf(false, true)) {
            compose.runOnIdle { palette = choice; dark = night }
            compose.waitForIdle()
            compose.runOnIdle {
                val c = selectionBarColors(scheme)
                val ordinary = contrast(c.actionContent, c.container)
                val destructive = contrast(c.deleteContent, c.container)
                assertTrue("$choice/$night ordinary $ordinary", ordinary >= 4.5)
                assertTrue("$choice/$night delete $destructive", destructive >= 4.5)
                assertTrue(contrast(c.container, scheme.surfaceContainerHighest) >= 1.249)
                assertTrue(contrast(readableSelectionContent(scheme.onPrimary, scheme.primary), scheme.primary) >= 4.5)
                record += "$choice dark=$night ordinary=$ordinary destructive=$destructive"
            }
            if (choice in listOf(AppPalette.DEFAULT, AppPalette.CUSTOM, AppPalette.BLACK_MAMBA)) {
                capture("${choice.name.lowercase()}-$night")
            }
        }
        compose.runOnIdle { oled = true; dark = true; palette = AppPalette.CUSTOM }
        compose.waitForIdle()
        capture("oled")
        File(context.filesDir, "selection-contrast.txt").writeText(record.joinToString("\n"))
    }

    @Test fun compactUnfilledActionsKeepTheirCallbacks() {
        show()
        val all = compose.onNodeWithContentDescription(label(R.string.select_all))
        all.assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
        val bitmap = all.captureToImage().asAndroidBitmap()
        val expected = compose.runOnIdle {
            selectionBarColors(scheme).container.toArgb()
        }
        var filled = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) == expected) filled++
        }
        assertTrue("The toolbar surface must remain visible around the unfilled icon",
            filled > bitmap.width * bitmap.height * 0.7)
        for (id in listOf(R.string.select_all, R.string.favorite, R.string.move_to_category,
            R.string.batch_stack, R.string.delete)) {
            compose.onNodeWithContentDescription(label(id)).performScrollTo().performTouchInput { click() }
        }
        compose.onNodeWithContentDescription(label(R.string.close)).performClick()
        compose.runOnIdle { assertEquals(listOf("all", "favorite", "move", "stack", "delete", "close"), actions) }
    }

    @Test fun narrowLargeRtlKeepsCloseVisibleAndEveryActionReachable() {
        selected = 12345
        show(narrow = true, rtl = true)
        val close = compose.onNodeWithContentDescription(label(R.string.close))
        close.assertIsDisplayed().assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
        compose.onNodeWithContentDescription(label(R.string.delete)).performScrollTo().assertIsDisplayed().performClick()
        close.assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(label(R.string.select_all)).performScrollTo().assertIsDisplayed().performClick()
        capture("narrow-rtl-large")
        compose.runOnIdle { assertEquals(listOf("delete", "close", "all"), actions) }
    }

    @Test fun noteUsesSameButtonsAndOptionalActionsStayAbsent() {
        show(note = true)
        compose.onNodeWithContentDescription(label(R.string.favorite)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.batch_stack)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.move_to_category)).performClick()
        compose.onNodeWithContentDescription(label(R.string.delete)).performClick()
        compose.onNodeWithContentDescription(label(R.string.close)).performClick()
        compose.runOnIdle { assertEquals(listOf("move", "delete", "close"), actions) }
    }
}
