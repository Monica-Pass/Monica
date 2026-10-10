package takagi.ru.monica.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.semantics.SemanticsNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.ui.PasswordCategoryActionButtons
import takagi.ru.monica.ui.PasswordCategoryActionButtonsParams
import takagi.ru.monica.steam.ui.SteamStorageSourceMenu
import takagi.ru.monica.steam.ui.SteamRootTopBar
import takagi.ru.monica.steam.data.SteamStorageSource

@RunWith(AndroidJUnit4::class)
class UnifiedFilterMenusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val keepass = (1..16).map { LocalKeePassDatabase(it.toLong(), "Vault $it", "test-$it") }
    private val mdbx = listOf(LocalMdbxDatabase(77, "MDBX fixture", "test-mdbx"))
    @Before fun awake() { compose.runOnUiThread {
        compose.activity.setTurnScreenOn(true); compose.activity.setShowWhenLocked(true)
        compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } }
    private fun snapshot(name: String) {
        val path = File(context.filesDir, "filter-panel-316/$name.png").apply { parentFile!!.mkdirs() }
        compose.onNodeWithTag("filter_menu_content").captureToImage().asAndroidBitmap()
            .compress(Bitmap.CompressFormat.PNG, 100, path.outputStream())
    }
    private fun screenBounds(node: SemanticsNode) = Rect(node.positionOnScreen, node.size.toSize())
    @Test fun pageSpecificSectionsKeepSelectionAndFixedActions() {
        var page by mutableStateOf("totp")
        var selection by mutableStateOf<UnifiedCategoryFilterSelection>(UnifiedCategoryFilterSelection.Local)
        var tag by mutableStateOf(false)
        var type by mutableStateOf(false)
        var editing by mutableStateOf(false)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * 0.75f, base.fontScale)) {
                MaterialTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) { Box(Modifier.size(48.dp)) {
                    UnifiedCategoryFilterChipMenuDropdown(true, {}) {
                        key(page) { UnifiedCategoryFilterChipMenu(true, {}, selection, { selection = it },
                            categories = listOf(Category(id = 1, name = "Work")),
                            keepassDatabases = keepass, mdbxDatabases = mdbx, bitwardenVaults = emptyList(),
                            getBitwardenFolders = { flowOf(emptyList()) },
                            showQuickFilters = page != "passkey",
                            quickFilterTitle = if (page == "note") context.getString(R.string.note_tags) else null,
                            typeQuickFilters = if (page == "wallet") listOf(UnifiedTypeQuickFilter(
                                R.string.nav_bank_cards_short, Icons.Default.CreditCard, type, { type = !type })) else emptyList(),
                            quickFilterContent = if (page == "note") ({
                                Row { MonicaExpressiveFilterChip(tag, { tag = !tag }, "#work") }
                            }) else null,
                            trailingContent = { PasswordCategoryActionButtons(PasswordCategoryActionButtonsParams(
                                true, true, editing, {}, { editing = !editing })) }) }
                    }
                } } }
            }
        }
        for (profile in listOf("totp", "wallet", "note", "passkey")) {
            compose.runOnIdle { page = profile; selection = UnifiedCategoryFilterSelection.Local }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Work").fetchSemanticsNodes().isNotEmpty() }
            val frame = compose.onNodeWithTag("filter_menu_frame").fetchSemanticsNode().boundsInRoot
            val content = compose.onNodeWithTag("filter_menu_content").fetchSemanticsNode().boundsInRoot
            assertEquals(frame.width, content.width, 1f)
            compose.onNodeWithText(context.getString(R.string.add_category)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.edit)).assertIsDisplayed().performClick()
            compose.onNodeWithText(context.getString(R.string.filter_panel_done)).performClick()
            if (profile == "note") {
                compose.onNodeWithText(context.getString(R.string.note_tags)).assertExists()
                compose.onNodeWithText("#work").performScrollTo().performClick()
                compose.runOnIdle { assertTrue(tag) }
            } else if (profile == "wallet") {
                compose.onNodeWithText(context.getString(R.string.nav_bank_cards_short)).performScrollTo().performClick()
                compose.runOnIdle { assertTrue(type) }
            } else if (profile == "passkey") {
                compose.onNodeWithText(context.getString(R.string.category_selection_menu_quick_filters)).assertDoesNotExist()
            } else {
                compose.onNodeWithText(context.getString(R.string.filter_starred)).performScrollTo().performClick()
                compose.runOnIdle { assertEquals(UnifiedCategoryFilterSelection.LocalStarred, selection) }
            }
            snapshot("unified-$profile")
            compose.onNodeWithText("Work").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(UnifiedCategoryFilterSelection.Custom(1), selection) }
            compose.onNodeWithTag("database_filter_mdbx:77").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(UnifiedCategoryFilterSelection.MdbxDatabaseFilter(77), selection) }
        }
    }
    @Test fun overviewKeepsExpandedDatabaseMenuWhileSteamUsesSharedSections() {
        var steam by mutableStateOf(false)
        var selection by mutableStateOf<UnifiedCategoryFilterSelection>(UnifiedCategoryFilterSelection.Local)
        var source by mutableStateOf<UnifiedCategoryFilterSelection>(UnifiedCategoryFilterSelection.Local)
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) { Box(Modifier.size(48.dp)) {
            if (steam) SteamStorageSourceMenu(true, {}, source, emptyList(), mdbx, keepass, emptyList(),
                { flowOf(emptyList()) }, { flowOf(emptyList()) }, { flowOf(emptyList()) }, { source = it })
            else UnifiedCategoryFilterChipMenuDropdown(true, {}) {
                UnifiedDatabaseFilterChipMenu(selection, { selection = it }, keepass, mdbx, emptyList())
            }
        } } } }
        for (isSteam in listOf(false, true)) {
            compose.runOnIdle { steam = isSteam }
            if (isSteam) {
                compose.onNodeWithTag("database_row").assertIsDisplayed()
                compose.onNodeWithTag("database_expand_toggle").performClick()
                compose.onNodeWithTag("database_filter_all").assertDoesNotExist()
            } else {
                compose.onNodeWithTag("database_expand_toggle").assertDoesNotExist()
                compose.onNodeWithTag("database_row").assertDoesNotExist()
                compose.onNodeWithTag("database_static_header").assertIsDisplayed()
            }
            compose.onNodeWithTag("database_expanded").assertExists()
            compose.onNodeWithTag("database_filter_keepass:16").performScrollTo().performClick()
            compose.runOnIdle {
                if (isSteam) assertEquals(UnifiedCategoryFilterSelection.KeePassDatabaseFilter(16), source)
                else assertEquals(UnifiedCategoryFilterSelection.KeePassDatabaseFilter(16), selection)
            }
            snapshot(if (isSteam) "unified-steam" else "unified-overview")
        }
    }

    @Test fun steamMenuUsesTrailingTopBarAnchorAtEveryInterfaceScale() {
        var scale by mutableFloatStateOf(1f)
        var expanded by mutableStateOf(false)
        var source by mutableStateOf<UnifiedCategoryFilterSelection>(UnifiedCategoryFilterSelection.Local)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize().testTag("steam_topbar_fixture")) {
                        Column {
                            SteamRootTopBar(
                                title = "Steam", searchQuery = "", onSearchQueryChange = {},
                                isSearchExpanded = false, onSearchExpandedChange = {}, searchHint = "Search",
                                pendingConfirmationCount = 2, onOpenSearch = {},
                                onOpenStorageSourceMenu = { expanded = true },
                                storageSourceMenu = {
                                    SteamStorageSourceMenu(expanded, { expanded = false }, source, emptyList(),
                                        mdbx, keepass, emptyList(), { flowOf(emptyList()) },
                                        { flowOf(emptyList()) }, { flowOf(emptyList()) }, { source = it; expanded = false })
                                },
                                onOpenTopActionsMenu = {}, topActionsMenu = {},
                            )
                        }
                    }
                }
            }
        }
        for (factor in listOf(1f, 0.65f, 0.85f, 1.3f)) {
            compose.runOnIdle { scale = factor }
            compose.onNodeWithContentDescription(context.getString(R.string.database_source_label)).performClick()
            compose.waitForIdle()
            val window = screenBounds(compose.onNodeWithTag("steam_topbar_fixture").fetchSemanticsNode())
            val frame = screenBounds(compose.onNodeWithTag("filter_menu_frame").fetchSemanticsNode())
            val trailingAction = screenBounds(compose.onNodeWithContentDescription(context.getString(R.string.more_options))
                .fetchSemanticsNode())
            // Use screen coordinates: popup and activity are in separate windows.
            // The old folder anchor left a visible gap on the right at every scale.
            compose.onNodeWithTag("database_expand_toggle").assertExists()
            compose.onNodeWithTag("database_row").assertIsDisplayed()
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val path = File(context.filesDir, "filter-panel-316/steam-topbar-$factor.png")
            path.parentFile!!.mkdirs()
            path.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            assertEquals("Right edge at scale $factor", window.right, frame.right, 2f)
            assertTrue("Menu must stay below the action pill", frame.top >= trailingAction.bottom)
            // The source selection still closes the popup; reopening preserves the choice.
            compose.onNodeWithTag("database_expand_toggle").performClick()
            compose.onNodeWithTag("database_filter_keepass:16").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(UnifiedCategoryFilterSelection.KeePassDatabaseFilter(16), source); assertFalse(expanded) }
            compose.onNodeWithTag("filter_menu_frame").assertDoesNotExist()
            compose.onNodeWithContentDescription(context.getString(R.string.database_source_label)).performClick()
            compose.onNodeWithTag("database_expand_toggle").performClick()
            compose.onNodeWithTag("database_filter_keepass:16").performScrollTo().assertIsSelected()
            androidx.test.espresso.Espresso.pressBack()
            compose.runOnIdle { assertFalse(expanded) }
            compose.onNodeWithTag("filter_menu_frame").assertDoesNotExist()
        }
    }
}
