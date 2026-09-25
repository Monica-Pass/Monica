package takagi.ru.monica.ime

import android.content.res.Configuration
import android.graphics.Bitmap
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ThemeMode

@RunWith(AndroidJUnit4::class)
class MonicaImeUiTest {
    @get:Rule val compose = createComposeRule()

    private val panels = listOf(
        MonicaImePanel.PASSWORDS, MonicaImePanel.AUTHENTICATORS, MonicaImePanel.DOCUMENTS
    )
    private var state by mutableStateOf(sampleState())
    private lateinit var editor: EditText
    private var density = 1f
    private var contentLocale = Locale.SIMPLIFIED_CHINESE

    private fun showKeyboard(
        width: Dp = 360.dp,
        fontScale: Float = 1f,
        dark: Boolean = false,
        language: Locale = Locale.SIMPLIFIED_CHINESE
    ) {
        contentLocale = language
        compose.setContent {
            val context = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(language) }
            val localizedContext = context.createConfigurationContext(configuration)
            density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, fontScale)
            ) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    AndroidView(
                        factory = { viewContext ->
                            EditText(viewContext).apply {
                                editor = this
                                inputType = InputType.TYPE_CLASS_TEXT
                                showSoftInputOnFocus = false
                                setSingleLine()
                                requestFocus()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    )
                    Box(Modifier.width(width).testTag("ime_test_keyboard")) {
                        MonicaImeContent(
                            settings = AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT),
                            uiState = state,
                            onDatabaseScopeSelected = { state = state.copy(selectedDatabaseScope = it) },
                            onInsertPassword = { commit(it.password) },
                            onInsertUsername = { commit(it.username) },
                            onInsertWebsite = { commit(it.website) },
                            onSmartFillPassword = {},
                            onInsertAuthenticatorCode = { commit(it.code) },
                            onInsertCardWalletValue = { commit(it.value) },
                            onSmartFillCardWallet = {},
                            onKeyPressed = { value ->
                                if (state.isSearchEditing) {
                                    state = state.copy(query = appendImeSearchQuery(state.query, value))
                                } else commit(value)
                            },
                            onBackspace = { state = state.copy(query = removeLastImeSearchCharacter(state.query)) },
                            onDeleteAll = {}, onUndoDeleteAll = {},
                            onEnter = { state = state.copy(isSearchEditing = false) },
                            onSpace = {},
                            onShiftToggle = { state = state.copy(isUppercase = !state.isUppercase) },
                            onKeyboardModeChange = { state = state.copy(keyboardMode = it) },
                            onOpenUnlockApp = {}, onOpenAutofillSettings = {},
                            onSearchEditRequested = { state = state.startVaultSearch() },
                            onSearchEditFinished = { state = state.copy(isSearchEditing = false) },
                            onSearchCleared = { state = state.copy(query = "") },
                            onPanelSelected = { state = state.selectVaultPanel(it, isLoading = false) },
                            onSwitchInputMethod = {}, onDismiss = {}
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("ime_vault_pane").assertIsDisplayed()
    }

    private fun commit(value: String) {
        checkNotNull(editor.onCreateInputConnection(EditorInfo())).commitText(value, 1)
    }

    private fun text(id: Int): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(context.resources.configuration).apply { setLocale(contentLocale) }
        return context.createConfigurationContext(config).getString(id)
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertControlsAlignWithList() {
        val filter = bounds("ime_vault_database_filter")
        val search = bounds("ime_vault_search")
        val rail = bounds("ime_vault_scroll_rail")
        val card = bounds("ime_vault_entry_1")
        assertTrue("Search must stop before the reserved rail area", search.right <= rail.left - 8f * density + 1f)
        assertTrue("Cards need a gap before the rail", card.right <= rail.left - 8f * density + 1f)
        assertEquals(40f * density, rail.width, 1f)
        assertEquals("The filter starts at the card edge", card.left, filter.left, 1f)
        assertEquals("Search ends at the card edge", card.right, search.right, 1f)
        assertEquals("Filter and search share the row evenly", filter.width, search.width, 1f)
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("ime_test_keyboard").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir("ime-ui-tests"), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun threeListsShareGeometryAndLeaveSpaceForFastScrolling() {
        showKeyboard()
        val expectedCard = bounds("ime_vault_entry_1")
        val expectedSearch = bounds("ime_vault_search")
        val expectedFilter = bounds("ime_vault_database_filter")
        panels.forEach { panel ->
            compose.runOnIdle { state = state.selectVaultPanel(panel, isLoading = false) }
            capture(panel.name.lowercase())
            assertControlsAlignWithList()
            val card = bounds("ime_vault_entry_1")
            assertEquals(expectedCard.width, card.width, 1f)
            assertEquals(expectedCard.height, card.height, 1f)
            assertEquals(expectedCard.top, card.top, 1f)
            assertEquals(expectedSearch, bounds("ime_vault_search"))
            assertEquals(expectedFilter, bounds("ime_vault_database_filter"))
        }
    }

    @Test fun everySearchButtonOpensTheKeyboardAndUsesItsOwnResultCount() {
        showKeyboard()
        panels.forEach { panel ->
            compose.runOnIdle {
                state = state.selectVaultPanel(panel, isLoading = false).copy(query = "")
                editor.setText("untouched")
            }
            compose.onNodeWithTag("ime_vault_search").assertHasClickAction().performClick()
            compose.onNodeWithTag("ime_search_toolbar").assertIsDisplayed()
            compose.onNodeWithTag("ime_search_result_count").assertTextEquals(state.activeEntryCount.toString())
            compose.onNodeWithTag("ime_key_letter_q").performClick()
            compose.runOnIdle {
                assertEquals("q", state.query)
                assertEquals("untouched", editor.text.toString())
                assertEquals(panel, state.activePanel)
            }
            compose.onNodeWithContentDescription(text(R.string.confirm)).performClick()
            compose.onNodeWithTag("ime_vault_search").assertIsDisplayed()
        }
    }

    @Test fun databaseSelectionWorksInsideEveryPanel() {
        showKeyboard()
        panels.forEach { panel ->
            compose.runOnIdle {
                state = state.selectVaultPanel(panel, isLoading = false)
                    .copy(selectedDatabaseScope = MonicaImeDatabaseScope.All)
            }
            compose.onNodeWithTag("ime_vault_database_filter").performClick()
            val option = compose.onNodeWithText("Demo KeePass")
            option.assertIsDisplayed()
            val optionBounds = option.fetchSemanticsNode().boundsInRoot
            val pane = bounds("ime_vault_pane")
            assertTrue(optionBounds.top >= pane.top && optionBounds.bottom <= pane.bottom)
            option.performClick()
            compose.runOnIdle { assertEquals(MonicaImeDatabaseScope.KeePass(7), state.selectedDatabaseScope) }
        }
    }

    @Test fun websiteActionFillsTheSavedAddressAndKeepsTheEntryExpanded() {
        showKeyboard()
        compose.onNodeWithText("Aster Mail").performClick()
        compose.onNodeWithText(text(R.string.website)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(state.entries.first().website, editor.text.toString()) }
        compose.onNodeWithText(text(R.string.website)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.username)).assertHasClickAction()
        compose.onNodeWithText(text(R.string.password)).assertHasClickAction()
        capture("website-expanded")
        assertEquals(
            "Website stays alongside the other fill actions at a regular width",
            compose.onNodeWithText(text(R.string.ime_quick_fill)).fetchSemanticsNode().boundsInRoot.top,
            compose.onNodeWithText(text(R.string.website)).fetchSemanticsNode().boundsInRoot.top,
            1f
        )
        capture("website-expanded")
    }

    @Test fun blankWebsitesDoNotExposeAnEmptyFillAction() {
        state = state.copy(entries = listOf(state.entries.first().copy(website = " \n ")))
        showKeyboard()
        compose.onNodeWithText("Aster Mail").performClick()
        compose.onNodeWithText(text(R.string.website)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.username)).assertIsDisplayed()
    }

    @Test fun narrowScreenWrapsWebsiteActionWithoutCrowdingTheRail() {
        showKeyboard(width = 320.dp, fontScale = 1.3f)
        compose.onNodeWithText("Aster Mail").performClick()
        capture("narrow-website")
        compose.onNodeWithText(text(R.string.website)).assertIsDisplayed()
        assertControlsAlignWithList()
        val quickFill = compose.onNodeWithText(text(R.string.ime_quick_fill)).fetchSemanticsNode().boundsInRoot
        val website = compose.onNodeWithText(text(R.string.website)).fetchSemanticsNode().boundsInRoot
        val rail = bounds("ime_vault_scroll_rail")
        assertTrue("The extra action wraps on narrow screens", website.top > quickFill.top)
        assertTrue(website.right < rail.left)
    }

    @Test fun longEnglishLabelsAndLargeTextStayWithinTheContentArea() {
        state = state.copy(
            query = "a very long search query for a saved account",
            databaseOptions = state.databaseOptions.mapIndexed { index, option ->
                if (index == 0) option.copy(label = "All databases with a very long name") else option
            }
        )
        showKeyboard(width = 320.dp, fontScale = 1.5f, dark = true, language = Locale.US)
        assertControlsAlignWithList()
        compose.onNodeWithText("Aster Mail").performClick()
        compose.onNodeWithText(text(R.string.website)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(state.entries.first().website, editor.text.toString()) }
        capture("english-large-text-dark")
    }

    @Test fun theSharedScrollRailNavigatesEachList() {
        val original = state
        state = state.copy(
            entries = ('A'..'Z').mapIndexed { i, letter -> original.entries.first().copy(id = i + 1L, title = "$letter Demo") },
            authenticatorEntries = ('A'..'Z').mapIndexed { i, letter -> original.authenticatorEntries.first().copy(id = i + 1L, title = "$letter Demo") },
            cardWalletEntries = ('A'..'Z').mapIndexed { i, letter -> original.cardWalletEntries.first().copy(id = i + 1L, title = "$letter Demo") }
        )
        showKeyboard()
        panels.forEach { panel ->
            compose.runOnIdle { state = state.selectVaultPanel(panel, isLoading = false) }
            compose.onNodeWithTag("ime_vault_scroll_rail").performTouchInput {
                swipe(Offset(center.x, height * 0.1f), Offset(center.x, height * 0.98f), 500)
            }
            compose.onNodeWithText("Z Demo").assertIsDisplayed()
        }
    }

    private fun sampleState(): MonicaImeUiState {
        val titles = listOf("Aster Mail", "Birch Account", "Cedar Notes", "Dune Vault", "Elm Card")
        return MonicaImeUiState(
            unlocked = true, activePanel = MonicaImePanel.PASSWORDS, isAutofillPanelVisible = true,
            entries = titles.take(3).mapIndexed { index, title ->
                MonicaImePasswordEntry(
                    id = index + 1L, title = title, username = "demo@example.com",
                    website = "https://example.com/账户?q=a%20b#details", packageName = "",
                    password = "Demo-only-value", isFavorite = false, sourceLabel = "Local"
                )
            },
            authenticatorEntries = titles.take(4).mapIndexed { index, title ->
                MonicaImeAuthenticatorEntry(index + 1L, title, "Demo", "demo@example.com", "123456", 24, false, "Local")
            },
            cardWalletEntries = titles.mapIndexed { index, title ->
                MonicaImeCardWalletEntry(
                    index + 1L, title, "•••• 4242", "Visa", false, "Local",
                    listOf(MonicaImeCardWalletField("Card number", "4242424242424242"))
                )
            },
            databaseOptions = listOf(
                MonicaImeDatabaseOption(MonicaImeDatabaseScope.All, "全部数据库"),
                MonicaImeDatabaseOption(MonicaImeDatabaseScope.Local, "Monica"),
                MonicaImeDatabaseOption(MonicaImeDatabaseScope.KeePass(7), "Demo KeePass")
            )
        )
    }
}
