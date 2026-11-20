package takagi.ru.monica.ui.scanner

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.ui.screens.ScannerFrame

@RunWith(AndroidJUnit4::class)
class ScannerFrameUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun transparentFrameHasOnlyFourGreenCornersInLightTheme() = verify(false)
    @Test fun transparentFrameHasOnlyFourGreenCornersInDarkTheme() = verify(true)

    private fun verify(dark: Boolean) {
        val backdrop = Color(0xFF713B59)
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.fillMaxSize().background(backdrop), contentAlignment = Alignment.Center) { ScannerFrame() }
            }
        }
        val bitmap = compose.onNodeWithTag("scanner_frame").captureToImage().asAndroidBitmap()
        fun at(x: Int, y: Int) = bitmap.getPixel(x * bitmap.width / 268, y * bitmap.height / 268)
        val background = android.graphics.Color.rgb(113, 59, 89)
        // All edge midpoints and the centre must reveal the exact backing pixels.
        listOf(134 to 134, 134 to 1, 1 to 134, 266 to 134, 134 to 266, 134 to 15).forEach { (x, y) ->
            assertEquals("Unexpected frame border/fill at $x,$y", background, at(x, y))
        }
        val green = android.graphics.Color.rgb(128, 203, 196)
        listOf(25 to 16, 243 to 16, 25 to 251, 243 to 251).forEach { (x, y) -> assertEquals(green, at(x, y)) }
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "scanner-frame-${if (dark) "dark" else "light"}.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
