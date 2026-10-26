package takagi.ru.monica.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.ui.components.TotpCodeCard
import takagi.ru.monica.ui.theme.MonicaTheme
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@RunWith(AndroidJUnit4::class)
class AuthenticatorCardGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tilesWithoutAccountsDoNotReserveEmptySubtitle() = verify(tile = true, scale = 1f, digits = 6)
    @Test fun compactTilesShrinkNextBeforeHiding() = verify(tile = true, scale = 1.3f)
    @Test fun releaseTilesKeepCompleteCodesWithLargeText() = verify(tile = true, scale = 1.5f)
    @Test fun listTitlesDoNotChangeCardHeight() = verify(tile = false, scale = 1f, digits = 6)
    @Test fun largeTextListKeepsCodeOnOneLine() = verify(tile = false, scale = 1.5f)
    @Test fun listReservesBothConfiguredMetadataRows() = verify(tile = false, scale = 1.5f,
        fields = listOf(AuthenticatorCardDisplayField.ISSUER, AuthenticatorCardDisplayField.ACCOUNT_NAME))

    @Test fun mixedHotpTilesKeepOriginalHeight() = verifyMixedTiles(1f)
    @Test fun mixedHotpTilesKeepOriginalHeightAtMediumText() = verifyMixedTiles(1.3f)
    @Test fun mixedHotpTilesKeepOriginalHeightAtLargeText() = verifyMixedTiles(1.5f)
    @Test fun draggingHotpAcrossRowsKeepsOriginalHeights() = verifyMixedTiles(1f, dragHotp = true)

    private fun verifyMixedTiles(scale: Float, dragHotp: Boolean = false) {
        val counter = mutableLongStateOf(0L)
        val selection = mutableStateOf(false)
        val generatedIds = mutableListOf<Long>()
        var copied = ""
        val types = listOf(OtpType.TOTP, OtpType.TOTP, OtpType.HOTP, OtpType.STEAM, OtpType.TOTP, OtpType.TOTP)
        val orderedItems = mutableStateOf(types.indices.map { index ->
            SecureItem(id = index.toLong(), itemType = ItemType.TOTP,
                title = listOf("Github", "TOTP", "HOTP", "Steam", "GitLab", "Stripe")[index], itemData = "")
        })
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MonicaTheme(darkTheme = true) {
                    Surface(Modifier.width(360.dp).testTag("mixed")) {
                        val dataById = types.mapIndexed { index, type ->
                            index.toLong() to TotpData(secret = "JBSWY3DPEHPK3PXP", otpType = type,
                                accountName = if (index % 2 == 0) "account@example.test" else "",
                                digits = 8, counter = counter.longValue)
                        }.toMap()
                        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                        val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
                            orderedItems.value = orderedItems.value.toMutableList().apply {
                                add(to.index, removeAt(from.index))
                            }
                        }
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            state = gridState,
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                            modifier = Modifier.wrapContentHeight(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(orderedItems.value.size, key = { orderedItems.value[it].id }) { position ->
                                val item = orderedItems.value[position]
                                val index = item.id.toInt()
                                ReorderableItem(reorderState, key = item.id, enabled = selection.value) { _ ->
                                    val dragModifier = if (selection.value) Modifier.longPressDraggableHandle()
                                        else Modifier
                                    Box(dragModifier) {
                                        TotpCodeCard(
                                            item = item,
                                            parsedTotpData = dataById[item.id],
                                            modifier = Modifier.testTag("mixed$index"),
                                            compactTile = true,
                                            uniformAuthenticatorLayout = true,
                                            sharedTickSeconds = 1_700_000_010L,
                                            isSelectionMode = selection.value,
                                            onToggleSelect = {},
                                            onCopyCode = { copied = it },
                                            onGenerateNext = { id -> generatedIds += id; counter.longValue++ },
                                            appSettings = AppSettings(iconCardsEnabled = true,
                                                validatorSmoothProgress = false,
                                                validatorUnifiedProgressBar = UnifiedProgressBarMode.ENABLED)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val heights = types.indices.map { compose.onNodeWithTag("mixed$it").fetchSemanticsNode().boundsInRoot.height }
        types.indices.forEach { compose.onNodeWithTag("mixed$it").assertHeightIsEqualTo(142.dp) }
        heights.forEach { assertEquals("All mixed tiles retain the original size: $heights", heights[0], it, 1f) }
        val otpText = SemanticsMatcher("complete OTP") { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any {
                it.text.replace(" ", "").matches(Regex("[0-9]{8}|[A-Z2-9]{5}"))
            }
        }
        val codeNodes = compose.onAllNodes(otpText, useUnmergedTree = true)
        assertTrue("Every current code is visible", codeNodes.fetchSemanticsNodes().size >= types.size)
        codeNodes.fetchSemanticsNodes().indices.forEach { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            codeNodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Mixed codes stay on one line without clipping", layouts.all { !it.hasVisualOverflow && it.lineCount == 1 })
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val action = compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.generate_next))
        val actionBounds = action.fetchSemanticsNode().boundsInRoot
        val hotpBounds = compose.onNodeWithTag("mixed2").fetchSemanticsNode().boundsInRoot
        assertTrue("Generation action is inside the HOTP card", hotpBounds.contains(actionBounds.topLeft) &&
            hotpBounds.contains(actionBounds.bottomRight))
        action.assertIsEnabled()
        val actionLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.generate_next), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(actionLayouts) }
        // Capture before interactions so ripple animations cannot obscure the visual check.
        val out = File(context.getExternalFilesDir(null), "authenticator-mixed-$scale.png")
        out.outputStream().use { compose.onNodeWithTag("mixed").captureToImage().asAndroidBitmap()
            .compress(Bitmap.CompressFormat.PNG, 100, it) }
        val labelMeasurements = actionLayouts.map {
            "size=${it.size}, lines=${it.lineCount}, widthOverflow=${it.didOverflowWidth}, " +
                "paragraphWidth=${it.multiParagraph.width}, heightOverflow=${it.didOverflowHeight}, " +
                "constraints=${it.layoutInput.constraints}"
        }
        File(context.getExternalFilesDir(null), "authenticator-mixed-$scale.txt")
            .writeText("fontScale=$scale\ncardHeightsPx=$heights\nbuttonBoundsPx=$actionBounds\nlabel=$labelMeasurements\n")
        assertTrue("The complete generation label fits, including when it wraps: $labelMeasurements",
            actionLayouts.isNotEmpty() && actionLayouts.all { !it.hasVisualOverflow && it.lineCount == 1 })
        val before = compose.onNodeWithTag("mixed2").fetchSemanticsNode().config[SemanticsProperties.Text]
        action.performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(listOf(2L), generatedIds)
        assertEquals("Generating must not copy", "", copied)
        val after = compose.onNodeWithTag("mixed2").fetchSemanticsNode().config[SemanticsProperties.Text]
        assertNotEquals("Generation updates the current code", before, after)
        compose.onNodeWithTag("mixed2").performTouchInput { click() }
        assertTrue(copied.matches(Regex("[0-9]{8}")))
        assertEquals("Copy must not advance the counter", listOf(2L), generatedIds)
        compose.runOnIdle { selection.value = true }
        action.assertIsNotEnabled()
        types.indices.forEach { index ->
            assertEquals("Selection keeps tile geometry", heights[index],
                compose.onNodeWithTag("mixed$index").fetchSemanticsNode().boundsInRoot.height, 1f)
        }
        assertEquals(listOf(2L), generatedIds)
        if (dragHotp) {
            fun verifyRows() {
                compose.waitForIdle()
                orderedItems.value.chunked(2).forEach { row ->
                    val bounds = row.map { compose.onNodeWithTag("mixed${it.id}").fetchSemanticsNode().boundsInRoot }
                    if (bounds.size == 2) {
                        assertEquals("Row tops align after drag", bounds[0].top, bounds[1].top, 1f)
                        assertEquals("Row heights align after drag", bounds[0].height, bounds[1].height, 1f)
                    }
                    row.forEach { compose.onNodeWithTag("mixed${it.id}").assertHeightIsEqualTo(142.dp) }
                }
                File(context.getExternalFilesDir(null), "authenticator-drag-order.txt")
                    .appendText("order=${orderedItems.value.map { it.id }}\n" +
                        "heightsPx=${orderedItems.value.map { compose.onNodeWithTag("mixed${it.id}").fetchSemanticsNode().boundsInRoot.height }}\n")
                action.assertIsNotEnabled()
                assertEquals("Dragging does not generate another code", listOf(2L), generatedIds)
            }
            fun dragTo(target: Int) {
                val hotp = compose.onNodeWithTag("mixed2")
                val distance = compose.onNodeWithTag("mixed$target").fetchSemanticsNode().boundsInRoot.center -
                    hotp.fetchSemanticsNode().boundsInRoot.center
                hotp.performTouchInput {
                    // Start near the title, clear of the menu, checkbox and generation button.
                    down(Offset(center.x * 0.6f, 18.dp.toPx()))
                    advanceEventTime(700)
                    moveBy(distance * 0.6f, 180)
                }
                compose.waitForIdle()
                hotp.performTouchInput { moveBy(distance * 0.5f, 180); up() }
                compose.waitForIdle()
            }
            File(context.getExternalFilesDir(null), "authenticator-drag-order.txt").writeText("")
            dragTo(0)
            compose.waitUntil(10_000) { orderedItems.value.indexOfFirst { it.id == 2L } < 2 }
            verifyRows()
            dragTo(4)
            compose.waitUntil(10_000) { orderedItems.value.indexOfFirst { it.id == 2L } >= 4 }
            verifyRows()
        }
    }

    @Test fun hiddenTileMasksCurrentAndNextCodeUntilTapped() {
        compose.setContent {
            val density = LocalDensity.current
            // Both codes fit at normal text size; the compact preview is intentionally
            // hidden at large sizes. Do not inherit the shared device's font setting.
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                MonicaTheme {
                    Box(Modifier.width(170.dp)) {
                        TileFixture(0, hidden = true) {}
                    }
                }
            }
        }
        val numeric = SemanticsMatcher("visible OTP") { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .any { it.text.replace(" ", "").matches(Regex("[0-9]{8}")) }
        }
        compose.onAllNodes(numeric, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("card0").performClick()
        compose.onAllNodes(numeric, useUnmergedTree = true).assertCountEquals(2)
    }

    private fun verify(tile: Boolean, scale: Float, digits: Int = 8,
        fields: List<AuthenticatorCardDisplayField> = AuthenticatorCardDisplayField.DEFAULT_ORDER) {
        var copied = ""
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MonicaTheme(darkTheme = scale > 1f) {
                    Surface(Modifier.width(360.dp).testTag("fixture")) {
                        val cards: @Composable () -> Unit = {
                            (0..1).forEach { index ->
                                val title = if (index == 0) "Github: a very long account title" else "stripe"
                                val data = TotpData(secret = "JBSWY3DPEHPK3PXP",
                                    issuer = if (index == 0 && AuthenticatorCardDisplayField.ISSUER in fields) "Github" else "",
                                    accountName = if (index == 0) "account@example.test" else "", digits = digits)
                                TotpCodeCard(
                                    item = SecureItem(id = index.toLong(), itemType = ItemType.TOTP,
                                        title = title, itemData = ""),
                                    parsedTotpData = data,
                                    modifier = Modifier.testTag("card$index"),
                                    onCopyCode = { copied = it },
                                    compactTile = tile,
                                    uniformAuthenticatorLayout = true,
                                    sharedTickSeconds = 1_700_000_010L,
                                    appSettings = AppSettings(iconCardsEnabled = false,
                                        authenticatorCardDisplayFields = fields,
                                        validatorSmoothProgress = false,
                                        validatorUnifiedProgressBar = UnifiedProgressBarMode.ENABLED)
                                )
                            }
                        }
                        if (tile) {
                            // The actual grid constraints: 12dp outer margins and an 8dp gutter.
                            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                                modifier = Modifier.wrapContentHeight(),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item { TileFixture(0, digits = digits) { copied = it } }
                                item { TileFixture(1, digits = digits) { copied = it } }
                            }
                        } else Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { cards() }
                    }
                }
            }
        }
        compose.waitForIdle()
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "authenticator-${if (tile) "tile" else "list"}-$scale-${fields.size}.png")
        out.outputStream().use { compose.onNodeWithTag("fixture").captureToImage().asAndroidBitmap()
            .compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (tile) {
            // Original compact TOTP tile size; equal but inflated tiles are a regression.
            compose.onNodeWithTag("card0").assertHeightIsEqualTo(142.dp)
            compose.onNodeWithTag("card1").assertHeightIsEqualTo(142.dp)
        }
        val first = compose.onNodeWithTag("card0").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("card1").fetchSemanticsNode().boundsInRoot
        assertEquals("Cards keep equal outer heights regardless of account text", first.height, second.height, 1f)
        val numeric = SemanticsMatcher("OTP text") { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                .any { it.text.replace(" ", "").matches(Regex("[0-9]{$digits}")) }
        }
        val codes = compose.onAllNodes(numeric, useUnmergedTree = true)
        val expectedCodes = if (tile && scale >= 1.5f) 2 else 4
        assertEquals("Current codes remain visible; tight tiles may hide only the preview",
            expectedCodes, codes.fetchSemanticsNodes().size)
        val baselines = mutableListOf<Float>()
        repeat(expectedCodes) { i ->
            val layouts = mutableListOf<TextLayoutResult>()
            codes[i].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("All digits must fit: node=$i layouts=" + layouts.map {
                "size=${it.size}, lines=${it.lineCount}, widthOverflow=${it.didOverflowWidth}, heightOverflow=${it.didOverflowHeight}, style=${it.layoutInput.style}"
            }, layouts.all {
                it.lineCount == 1 && !it.hasVisualOverflow
            })
            if (tile && scale == 1.3f && i % 2 == 1) {
                assertTrue("The preview shrinks before it is hidden",
                    layouts.single().layoutInput.style.fontSize.value < 14f)
            }
            baselines += codes[i].fetchSemanticsNode().boundsInRoot.top + layouts.single().firstBaseline
        }
        if (tile) assertTrue("No account means no empty subtitle or forced code alignment",
            baselines[expectedCodes / 2] < baselines[0] - 1f)
        compose.onNodeWithTag("card1").performClick()
        assertTrue("Copy still returns a complete code", copied.matches(Regex("[0-9]{$digits}")))
    }

    @Composable private fun TileFixture(index: Int, hidden: Boolean = false, digits: Int = 8, onCopy: (String) -> Unit) {
        TotpCodeCard(
            item = SecureItem(id = index.toLong(), itemType = ItemType.TOTP,
                title = if (index == 0) "Github: a very long account title" else "stripe", itemData = ""),
            parsedTotpData = TotpData(secret = "JBSWY3DPEHPK3PXP", issuer = "",
                accountName = if (index == 0) "account@example.test" else "", digits = digits),
            modifier = Modifier.testTag("card$index"), onCopyCode = onCopy,
            compactTile = true, uniformAuthenticatorLayout = true, sharedTickSeconds = 1_700_000_010L,
            appSettings = AppSettings(iconCardsEnabled = false, validatorSmoothProgress = false,
                authenticatorCardHideCodeByDefault = hidden,
                validatorUnifiedProgressBar = UnifiedProgressBarMode.ENABLED)
        )
    }
}
