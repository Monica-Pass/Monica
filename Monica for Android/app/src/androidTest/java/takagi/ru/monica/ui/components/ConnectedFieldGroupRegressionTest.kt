package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.suggestions.CommonInfoTestActivity
import takagi.ru.monica.data.model.TemplateCredentialDraft

/** Exercises the same section/fields as the editors, with synthetic values and parent pixels. */
@RunWith(AndroidJUnit4::class)
class ConnectedFieldGroupRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<CommonInfoTestActivity>()

    @After fun clearContent() { showContent {} }

    private fun showContent(content: @Composable () -> Unit) {
        // MuMu may recreate the activity for an orientation/configuration update after launch.
        // This debug host reinstalls its current composition from onCreate.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CommonInfoTestActivity.content = content
        }
        compose.waitForIdle()
    }

    @Test fun apiTokenKeepsThreeConnectedRowsInsideItsSection() {
        showContent {
            TestPage {
                TemplateFormSection("") {
                    TemplateCredentialFields(
                        TemplateCredentialDraft("API_TOKEN", mapOf(
                            "provider" to "github", "api_base" to "https://example.invalid", "token" to "synthetic-token")),
                        onChange = {},
                    )
                }
            }
        }
        val tags = listOf("template_field_provider", "template_field_api_base", "template_field_token")
        val bitmap = capture("api-token")
        try {
            val rows = tags.map(::relativeBounds)
            assertConnectedRows(bitmap, rows)
        } finally { bitmap.recycle() }
    }

    @Test fun wifiPasswordSecurityAndHiddenNetworkShareOneGroupAfterScanButton() {
        var scans = 0
        showContent {
            TestPage {
                TemplateFormSection("") {
                    TemplateCredentialFields(
                        TemplateCredentialDraft("WIFI", mapOf("ssid" to "Synthetic network",
                            "password" to "synthetic-password", "security" to "WPA2_WPA3", "hiddenNetwork" to "false")),
                        onChange = {}, onScanWifi = { scans++ }, ssidInIdentity = true,
                    )
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scan = compose.onNodeWithText(context.getString(R.string.wifi_scan_qr))
        scan.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, scans) }
        compose.onNodeWithText(context.getString(R.string.wifi_hidden_network)).assertIsDisplayed()
        val bitmap = capture("wifi")
        try {
            val password = relativeBounds("template_field_password")
            val security = relativeBounds("wifi_security")
            // The final ListItem has no test-only wrapper in the old code. Locate its actual
            // blue background run in the parent image, rather than assuming its minimum height.
            val hidden = lastBackgroundRow(bitmap, security)
            assertConnectedRows(bitmap, listOf(password, security, hidden))
            val root = compose.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
            val scanBounds = scan.fetchSemanticsNode().boundsInRoot
            assertTrue("The scan action must precede, not be inserted within the field group",
                scanBounds.bottom - root.top < password.top)
        } finally { bitmap.recycle() }
    }

    @Composable
    private fun TestPage(content: @Composable ColumnScope.() -> Unit) {
        MaterialTheme(colorScheme = lightColorScheme(
            background = PAGE, surfaceContainer = FIELD, surfaceContainerHigh = FIELD,
        )) {
            Surface(Modifier.width(320.dp).testTag(ROOT), color = PAGE) {
                Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
            }
        }
    }

    private fun assertConnectedRows(bitmap: Bitmap, rows: List<Rect>) {
        val density = bitmap.width / 320f
        rows.forEachIndexed { index, row ->
            assertTrue("Row $index must have a positive visible height", row.height > 0)
            assertCorner(bitmap, row, top = true, start = true, outer = index == 0, density, "row $index top left")
            assertCorner(bitmap, row, top = true, start = false, outer = index == 0, density, "row $index top right")
            assertCorner(bitmap, row, top = false, start = true, outer = index == rows.lastIndex, density, "row $index bottom left")
            assertCorner(bitmap, row, top = false, start = false, outer = index == rows.lastIndex, density, "row $index bottom right")
            if (index > 0) {
                assertEquals("Rows ${index - 1} and $index must be separated by 2dp",
                    2f * density, row.top - rows[index - 1].bottom, 1.1f)
                assertEquals("Connected rows must align on the left", rows[0].left, row.left, 1.1f)
                assertEquals("Connected rows must align on the right", rows[0].right, row.right, 1.1f)
            }
        }
    }

    private fun assertCorner(bitmap: Bitmap, row: Rect, top: Boolean, start: Boolean,
        outer: Boolean, density: Float, label: String) {
        // (6dp, 6dp) is outside a 24dp round corner but well inside a 4dp corner.
        // It also avoids text, icons, the cursor and antialiasing at the actual outline.
        val inset = 6f * density
        val x = (if (start) row.left + inset else row.right - inset).roundToInt()
        val y = (if (top) row.top + inset else row.bottom - inset).roundToInt()
        val expected = if (outer) PAGE.toArgb() else FIELD.toArgb()
        val actual = bitmap.getPixel(x, y)
        assertTrue("$label must be ${if (outer) "a rounded outer" else "a compact inner"} corner: " +
            "expected=${expected.toUInt().toString(16)} actual=${actual.toUInt().toString(16)} at ($x,$y)",
            channelDistance(expected, actual) <= 12)
    }

    private fun lastBackgroundRow(bitmap: Bitmap, previous: Rect): Rect {
        val x = ((previous.left + previous.right) / 2f).roundToInt()
        var bottom = bitmap.height - 1
        while (bottom > previous.bottom && channelDistance(bitmap.getPixel(x, bottom), FIELD.toArgb()) > 12) bottom--
        assertTrue("The hidden-network row must be visible below security", bottom > previous.bottom)
        var top = bottom
        while (top > previous.bottom && channelDistance(bitmap.getPixel(x, top - 1), PAGE.toArgb()) > 12) top--
        return Rect(previous.left, top.toFloat(), previous.right, (bottom + 1).toFloat())
    }

    private fun relativeBounds(tag: String): Rect {
        val root = compose.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return Rect(node.left - root.left, node.top - root.top, node.right - root.left, node.bottom - root.top)
    }

    private fun capture(name: String): Bitmap {
        compose.waitForIdle()
        return compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap().also { bitmap ->
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "connected-fields-327")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    private fun channelDistance(a: Int, b: Int) = maxOf(
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
        abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
        abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)),
        abs(android.graphics.Color.alpha(a) - android.graphics.Color.alpha(b)),
    )

    private companion object {
        const val ROOT = "connected-fields-root"
        val PAGE = Color.White
        val FIELD = Color(0xFF89ABC1)
    }
}
