package takagi.ru.monica.ui.vaultv2

import takagi.ru.monica.utils.AppLocaleStringResolver

import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Group
import java.util.UUID
import takagi.ru.monica.keepass.KeePassDeleteInvestigationTest
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenFolder
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.PageAdjustmentSettingsSnapshot
import takagi.ru.monica.utils.SavedCategoryFilterState
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.*

/** Exercise the real pane and its retained/classic-list pipeline with an isolated Room vault. */
@RunWith(AndroidJUnit4::class)
class VaultOverviewPaneTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = SettingsManager(context)
    private lateinit var originalSettings: PageAdjustmentSettingsSnapshot
    private lateinit var originalFilter: SavedCategoryFilterState
    private lateinit var database: PasswordDatabase
    private val models = mutableListOf<ViewModel>()
    private lateinit var state: VaultV2PaneState
    private var appSettings by mutableStateOf(AppSettings())
    private var openedPassword: Long? = null
    private var darkTheme by mutableStateOf(false)
    private var keyboardVisible = false
    private var testKeePassDatabases by mutableStateOf<List<LocalKeePassDatabase>>(emptyList())
    private lateinit var testKeepassModel: LocalKeePassViewModel
    private var fixtureFontScale by mutableFloatStateOf(1f)
    private var testBitwardenVaults by mutableStateOf<List<BitwardenVault>>(emptyList())

    @Before fun prepareVault(): Unit = runBlocking {
        originalSettings = settings.exportPageAdjustmentSettings()
        originalFilter = settings.categoryFilterStateFlow("vault_v2").first()
        settings.updateCategoryFilterState("vault_v2", SavedCategoryFilterState(type = "all"))
        database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        repeat(24) { index ->
            database.passwordEntryDao().insertPasswordEntry(PasswordEntry(id = index + 1L,
                title = "Overview password ${index + 1}", website = "", username = "demo", password = "", isFavorite = index < 3))
        }
        database.passwordEntryDao().insertPasswordEntry(PasswordEntry(id = 30L, title = "Archived local",
            website = "", username = "", password = "", isArchived = true))
    }

    @After fun restore() {
        models.forEach { it.viewModelScope.cancel() }
        runBlocking {
            settings.importPageAdjustmentSettings(originalSettings)
            settings.updateCategoryFilterState("vault_v2", originalFilter)
        }
        database.close()
    }

    private fun showPane(followOverviewSettings: Boolean = false, expectedOverviewCount: Int? = 24) {
        val security = SecurityManager(context)
        val passwords = PasswordRepository(database.passwordEntryDao(), categoryDao = database.categoryDao(),
            bitwardenFolderDao = database.bitwardenFolderDao(), passwordArchiveSyncMetaDao = database.passwordArchiveSyncMetaDao())
        val items = SecureItemRepository(database.secureItemDao())
        fun <T : ViewModel> keep(model: T): T = model.also { models += it }
        val passwordModel = keep(PasswordViewModel(passwords, security,
            context = context.takeIf { testKeePassDatabases.isNotEmpty() },
            localKeePassDatabaseDao = PasswordDatabase.getDatabase(context).localKeePassDatabaseDao(), strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext))).also { it.restoreAuthenticatedUiState() }
        val totp = keep(TotpViewModel(items, passwords, strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)))
        val cards = keep(BankCardViewModel(items, strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)))
        val documents = keep(DocumentViewModel(items, strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)))
        val addresses = keep(BillingAddressViewModel(items))
        val notes = keep(NoteViewModel(items, strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)))
        val passkeys = keep(PasskeyViewModel(PasskeyRepository(database.passkeyDao()), strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)))
        val keepass = keep(LocalKeePassViewModel(context.applicationContext as Application, (if (testKeePassDatabases.isEmpty()) database else PasswordDatabase.getDatabase(context)).localKeePassDatabaseDao(), security))
        testKeepassModel = keepass
        // The app always supplies this model, including when there are no MDBX vaults.
        // Overview -> list navigation changes the native-token collector from null to this model.
        val mdbx = keep(MdbxViewModel(context.applicationContext as Application,
            database.localMdbxDatabaseDao(), database.mdbxRemoteSourceDao(), database.passwordEntryDao(),
            database.secureItemDao(), database.passkeyDao(), database.attachmentDao(), database.customFieldDao(), security))
        val settingsModel = keep(SettingsViewModel(settings))
        compose.setContent {
            val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            SideEffect { keyboardVisible = imeVisible }
            LaunchedEffect(settingsModel, followOverviewSettings) {
                if (followOverviewSettings) settingsModel.settings.collect { persisted ->
                    appSettings = appSettings.copy(vaultOverviewConfig = persisted.vaultOverviewConfig, vaultListSort = persisted.vaultListSort)
                }
            }
            CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(LocalDensity.current.density, fixtureFontScale)) {
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                state = rememberVaultV2PaneState(remember { VaultV2RetainedState() })
                VaultV2Pane(passwordModel, totp, cards, documents, addresses, notes, passkeys,
                    keepassDatabases = testKeePassDatabases, mdbxDatabases = emptyList(), bitwardenVaults = testBitwardenVaults,
                    localKeePassViewModel = keepass, mdbxViewModel = mdbx, settingsViewModel = settingsModel, state = state,
                    onOpenPassword = { openedPassword = it }, onOpenTotp = {}, onOpenBankCard = {}, onOpenDocument = {},
                    onOpenBillingAddress = {}, onOpenNote = {}, onOpenPasskey = {}, onOpenMdbxCommitHistory = {},
                    onOpenHistory = {}, onOpenTrashPage = {}, onOpenArchivePage = {}, onOpenCommonAccountTemplates = {},
                    appSettings = appSettings, securityManager = security, biometricEnabled = false,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }
            }
        }
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("vault_overview_screen").assertExists() }.isSuccess
        }
        compose.waitUntil(15_000) { state.overviewSnapshot?.let { expectedOverviewCount == null || it.items.size == expectedOverviewCount } == true }
        compose.onNodeWithTag("vault_overview_screen").assertIsDisplayed()
    }

    @Test fun editedLocalEntryRefreshesRecentInOpenAndReturnedOverview() {
        val oldTime = System.currentTimeMillis() - 30 * 86_400_000L
        runBlocking {
            (1L..24L).forEach { id ->
                val entry = requireNotNull(database.passwordEntryDao().getPasswordEntryById(id))
                database.passwordEntryDao().updatePasswordEntry(entry.copy(
                    createdAt = java.util.Date(oldTime + id * 1000),
                    updatedAt = java.util.Date(oldTime + id * 1000)))
            }
        }
        showPane()
        compose.runOnIdle { assertEquals(24L, state.overviewSnapshot?.recentItems?.first()?.passwordEntry?.id) }
        fun edit(id: Long) {
            runBlocking {
                val entry = requireNotNull(database.passwordEntryDao().getPasswordEntryById(id))
                database.passwordEntryDao().updatePasswordEntry(entry.copy(updatedAt = java.util.Date()))
            }
        }
        edit(1L)
        compose.waitUntil(15_000) { state.overviewSnapshot?.recentItems?.firstOrNull()?.passwordEntry?.id == 1L }
        openOverviewNode("overview_type_PASSWORD")
        edit(2L)
        Espresso.pressBack()
        compose.waitUntil(15_000) { state.overviewSnapshot?.recentItems?.firstOrNull()?.passwordEntry?.id == 2L }
        compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag("overview_recent_password:2"))
        compose.onNodeWithTag("overview_recent_password:2").assertIsDisplayed()
    }

    @Test fun typeNavigationUsesTheRealListAndBackRestoresTheOverview() {
        showPane()
        compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag("overview_type_PASSWORD"))
        compose.onNodeWithTag("overview_type_PASSWORD").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Overview password 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Overview password 1").performClick()
        compose.runOnIdle { assertEquals(1L, openedPassword); assertEquals("PASSWORD", state.overviewItemType) }
        Espresso.pressBack()
        compose.onNodeWithTag("vault_overview_screen").assertIsDisplayed()
        compose.runOnIdle { assertFalse(state.overviewListOpen); assertEquals("all", state.storageFilterType) }
    }

    private fun prepareVaultwardenFeedbackFixture(config: VaultOverviewConfig) {
        val vault = BitwardenVault(id = 136, email = "feedback@example.test", displayName = "Test Vaultwarden",
            serverUrl = "https://vaultwarden.example.test", isLocked = false, syncEnabled = false)
        runBlocking {
            database.passwordEntryDao().deleteAllPasswordEntries()
            database.bitwardenVaultDao().insert(vault)
            repeat(515) { index ->
                database.passwordEntryDao().insertPasswordEntry(PasswordEntry(id = 1000L + index,
                    title = "Vaultwarden login $index", username = "fixture", website = "", password = "",
                    bitwardenVaultId = vault.id, bitwardenCipherId = "fixture-$index"))
            }
            repeat(5) { index ->
                database.secureItemDao().insertItem(SecureItem(id = index + 1L, itemType = ItemType.DOCUMENT,
                    title = "Fixture document $index", itemData = "{}",
                    bitwardenVaultId = vault.id.takeIf { index > 0 },
                    bitwardenCipherId = "document-$index".takeIf { index > 0 }))
            }
            settings.updateVaultOverviewConfig { config }
        }
        testBitwardenVaults = listOf(vault)
        appSettings = appSettings.copy(vaultOverviewConfig = config)
    }

    @Test fun upgradedVaultwardenOnlyAccountShowsPasswordsAndSearchResults() {
        prepareVaultwardenFeedbackFixture(VaultOverviewConfig.decode("{}"))
        showPane(expectedOverviewCount = null)
        compose.runOnIdle {
            assertEquals(515, state.overviewSnapshot?.typeCounts?.get(VaultV2ItemType.PASSWORD))
            assertEquals(5, state.overviewSnapshot?.typeCounts?.get(VaultV2ItemType.DOCUMENT))
        }
        openOverviewNode("overview_type_PASSWORD")
        awaitListText("Vaultwarden login 0")
        Espresso.pressBack()
        compose.onNodeWithTag("overview_search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Vaultwarden login 514")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(5_000) { !keyboardVisible }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("vault_item_password:1514").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("vault_item_password:1000").fetchSemanticsNodes().isEmpty()
        }
        sortScreenshot("vaultwarden-search.png")
        compose.onNodeWithTag("vault_item_password:1514").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1514L, openedPassword) }
    }

    @Test fun explicitLocalScopeReproducesFeedbackAndSelectingAllRestoresPasswords() {
        prepareVaultwardenFeedbackFixture(VaultOverviewConfig(scope = "local"))
        showPane(followOverviewSettings = true, expectedOverviewCount = 1)
        compose.runOnIdle {
            assertEquals(0, state.overviewSnapshot?.typeCounts?.get(VaultV2ItemType.PASSWORD))
            assertEquals(1, state.overviewSnapshot?.typeCounts?.get(VaultV2ItemType.DOCUMENT))
        }
        compose.onNodeWithTag("overview_search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Vaultwarden login 514")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(5_000) { !keyboardVisible }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.no_results)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("vault_item_password:1514").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.topbar_close_search)).performClick()
        compose.onNodeWithTag("overview_scope").performClick()
        compose.onNodeWithText(context.getString(R.string.category_all)).performClick()
        compose.waitUntil(15_000) { state.overviewSnapshot?.items?.size == 520 }
        assertEquals("all", runBlocking { settings.settingsFlow.first().vaultOverviewConfig.scope })
        compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag("overview_type_PASSWORD"))
        sortScreenshot("vaultwarden-all.png")
        compose.runOnIdle { testBitwardenVaults = testBitwardenVaults.map { it.copy(isLocked = true) } }
        compose.waitUntil(15_000) { state.overviewSnapshot?.items?.size == 1 }
        compose.runOnIdle { assertEquals(0, state.overviewSnapshot?.typeCounts?.get(VaultV2ItemType.PASSWORD)) }
    }

    @Test fun bitwardenOverviewNavigationUsesTheClassicList() = verifyBitwardenNavigation(VaultV2LayoutMode.CLASSIC)

    @Test fun bitwardenOverviewNavigationUsesTheHierarchicalList() = verifyBitwardenNavigation(VaultV2LayoutMode.HIERARCHICAL)

    private fun verifyBitwardenNavigation(layout: VaultV2LayoutMode) {
        val vault = BitwardenVault(id = 136, email = "issue136@example.test", isLocked = false, syncEnabled = false)
        val folder = BitwardenFolder(vaultId = vault.id, bitwardenFolderId = "issue136-folder",
            name = "Issue 136 folder", revisionDate = "2026-09-14T00:00:00Z")
        runBlocking {
            database.bitwardenVaultDao().insert(vault)
            database.bitwardenFolderDao().insert(folder)
            listOf(
                PasswordEntry(id = 1361, title = "Bitwarden folder login", website = "", username = "demo", password = "",
                    bitwardenVaultId = vault.id, bitwardenCipherId = "issue136-folder-login", bitwardenFolderId = folder.bitwardenFolderId),
                PasswordEntry(id = 1362, title = "Bitwarden unfiled favorite", website = "", username = "demo", password = "",
                    bitwardenVaultId = vault.id, bitwardenCipherId = "issue136-favorite", isFavorite = true),
                PasswordEntry(id = 1363, title = "Bitwarden archived login", website = "", username = "demo", password = "",
                    bitwardenVaultId = vault.id, bitwardenCipherId = "issue136-archive", isArchived = true),
            ).forEach { database.passwordEntryDao().insertPasswordEntry(it) }
        }
        testBitwardenVaults = listOf(vault)
        appSettings = appSettings.copy(vaultV2LayoutMode = layout, vaultOverviewConfig = VaultOverviewConfig(scope = "bitwarden:${vault.id}"))
        showPane(expectedOverviewCount = 2)
        compose.waitUntil(10_000) { state.overviewSnapshot?.folders?.any { it.name == folder.name } == true }
        val folderKey = state.overviewSnapshot!!.folders.first { it.name == folder.name }.key
        openOverviewNode("overview_folder_$folderKey")
        awaitListText("Bitwarden folder login")
        compose.onNodeWithText("Overview password 1").assertDoesNotExist()
        backToBitwardenOverview(vault.id)

        openOverviewNode("overview_type_PASSWORD")
        awaitListText("Bitwarden unfiled favorite")
        compose.runOnIdle { assertEquals("PASSWORD", state.overviewItemType) }
        backToBitwardenOverview(vault.id)

        compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag("overview_favorites"))
        compose.onNode(hasText(context.getString(R.string.vault_overview_view_all)) and
            hasAnyAncestor(hasTestTag("overview_favorites"))).performClick()
        awaitListText("Bitwarden unfiled favorite")
        compose.onNodeWithText("Bitwarden folder login").assertDoesNotExist()
        backToBitwardenOverview(vault.id)

        openOverviewNode("overview_archive")
        awaitListText("Bitwarden archived login")
        compose.onNodeWithText("Archived local").assertDoesNotExist()
        backToBitwardenOverview(vault.id)

        openOverviewNode("overview_all_items")
        awaitListText("Bitwarden unfiled favorite")
        compose.onNodeWithText("Overview password 1").assertDoesNotExist()
        backToBitwardenOverview(vault.id)
    }

    private fun openOverviewNode(tag: String) {
        compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun awaitListText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.runOnIdle { assertTrue(state.overviewListOpen) }
    }

    private fun backToBitwardenOverview(vaultId: Long) {
        Espresso.pressBack()
        compose.onNodeWithTag("vault_overview_screen").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(state.overviewListOpen)
            assertFalse(state.isArchiveView)
            assertEquals("bitwarden:$vaultId", state.overviewScope)
        }
    }

    @Test fun archiveBackKeepsScrollAndRestoresLiveItems() {
        showPane()
        compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag("overview_archive"))
        var position = 0 to 0
        compose.runOnIdle { position = state.overviewScrollIndex to state.overviewScrollOffset }
        compose.onNodeWithTag("overview_archive").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Archived local").fetchSemanticsNodes().isNotEmpty() }
        Espresso.pressBack()
        compose.waitUntil(10_000) { state.overviewSnapshot?.items?.size == 24 }
        compose.onNodeWithTag("vault_overview_screen").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(state.isArchiveView)
            assertEquals(position, state.overviewScrollIndex to state.overviewScrollOffset)
            assertEquals(1, state.overviewSnapshot?.archiveCount)
        }
    }

    @Test fun disablingOverviewRestoresSavedClassicScopeAndContent() {
        showPane()
        compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag("overview_type_PASSWORD"))
        compose.onNodeWithTag("overview_type_PASSWORD").performClick()
        compose.runOnIdle { appSettings = appSettings.copy(vaultOverviewEnabled = false) }
        compose.waitUntil(10_000) { state.overviewScope == null && state.storageFilterType == VAULT_V2_STORAGE_FILTER_ALL }
        compose.onNodeWithTag("vault_overview_screen").assertDoesNotExist()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Overview password 1").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertFalse(state.overviewListOpen); assertNull(state.overviewItemType) }
    }

    @Test fun sharedTopBarSearchKeepsResultsAfterImeActionAndRestoresTheOverview() {
        showPane()
        compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag("overview_archive"))
        var position = 0 to 0
        compose.runOnIdle { position = state.overviewScrollIndex to state.overviewScrollOffset }
        compose.onNodeWithTag("overview_search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("password 24")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Overview password 24").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(hasSetTextAction()).assertTextEquals("password 24")
        compose.onNodeWithText("Overview password 24").performClick()
        compose.runOnIdle { assertFalse(state.overviewListOpen); assertEquals(24L, openedPassword) }
        compose.onNodeWithTag("overview_search_results").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.topbar_close_search)).performClick()
        compose.onNodeWithTag("overview_top_bar").assertIsDisplayed()
        compose.onNodeWithTag("overview_more").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(state.overviewListOpen)
            assertEquals("all", state.storageFilterType)
            assertEquals(position, state.overviewScrollIndex to state.overviewScrollOffset)
        }
    }

    @Test fun groupedSearchResultsKeepSelectionSwipeAndSingleResultNavigation() {
        showPane()
        compose.onNodeWithTag("overview_search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("password 2")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("vault_item_password:24").fetchSemanticsNodes().isNotEmpty() }
        val ids = listOf(2, 20, 21, 22, 23, 24)
        ids.zipWithNext { firstId, secondId ->
            val first = compose.onNodeWithTag("vault_item_password:$firstId").fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithTag("vault_item_password:$secondId").fetchSemanticsNode().boundsInRoot
            val gap = second.top - first.bottom
            assertTrue("Filtered rows must remain separated", gap >= 1f && gap <= with(compose.density) { 4.dp.toPx() })
        }
        capture("vault-grouped-light.png")
        compose.runOnIdle { darkTheme = true }
        capture("vault-grouped-dark.png")
        compose.onNodeWithTag("vault_item_password:20").performTouchInput { longClick() }
        compose.onNodeWithTag("vault_item_password:24").performClick()
        compose.runOnIdle { assertNull(openedPassword) }
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.waitUntil(5_000) { !keyboardVisible }
        Espresso.pressBack()
        compose.runOnIdle { assertEquals(0, state.selectionCount) }
        compose.onNodeWithContentDescription(context.getString(R.string.select_all)).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertTextEquals("password 2")
        compose.runOnIdle { assertFalse(state.overviewListOpen) }
        compose.onNodeWithTag("vault_item_password:2").performTouchInput { swipeRight() }
        compose.onNodeWithContentDescription(context.getString(R.string.select_all)).assertIsDisplayed()
        Espresso.pressBack()
        compose.onNode(hasSetTextAction()).assertTextEquals("password 2")
        compose.onNodeWithTag("vault_item_password:2").performTouchInput { swipeLeft() }
        compose.onNodeWithText(context.getString(R.string.cancel)).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("password 24")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("vault_item_password:2").fetchSemanticsNodes().isEmpty() }
        capture("vault-grouped-single.png")
        compose.onNodeWithTag("vault_item_password:24").performClick()
        compose.runOnIdle { assertEquals(24L, openedPassword) }
    }

    private fun showSelectableOverview(pinCount: Int = 4) {
        val config = runBlocking {
            val identities = (1L..pinCount.toLong()).map { database.passwordEntryDao().getPasswordEntryById(it)!!.vaultOverviewKey() }
            VaultOverviewConfig(
                order = listOf(VaultOverviewModule.ITEMS.name, VaultOverviewModule.FAVORITES.name),
                hidden = VaultOverviewModule.entries.filterNot { it == VaultOverviewModule.ITEMS || it == VaultOverviewModule.FAVORITES }
                    .mapTo(hashSetOf()) { it.name },
                pinnedItems = identities, recommendItems = false,
            ).normalized().also { initial -> settings.updateVaultOverviewConfig { initial } }
        }
        appSettings = appSettings.copy(vaultOverviewConfig = config, disablePasswordVerification = true)
        showPane(followOverviewSettings = true)
        compose.waitUntil(15_000) { state.overviewSnapshot?.frequentItems?.size == minOf(pinCount, OVERVIEW_PREVIEW_LIMIT) }
    }

    private fun overviewRow(module: String, id: Int) = compose.onNode(
        hasTestTag("overview_item_password:$id") and hasAnyAncestor(hasTestTag("overview_$module")),
    )

    @Test fun overviewLongPressScopesTheSharedActionBarAndBackExitsSelection() {
        showSelectableOverview()
        overviewRow("items", 1).performTouchInput { longClick() }
        overviewRow("items", 2).performClick()
        compose.runOnIdle { assertEquals(2, state.selectionCount); assertNull(openedPassword) }
        overviewRow("items", 1).assertIsSelected()
        overviewRow("items", 2).assertIsSelected()
        compose.onNodeWithContentDescription(context.getString(R.string.vault_overview_remove_frequent_items)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.delete)).assertDoesNotExist()
        capture("overview-frequent-selection.png")
        compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag("overview_favorites"))
        overviewRow("favorites", 1).assertIsNotSelected().performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, state.selectionCount) }
        compose.onNodeWithContentDescription(context.getString(R.string.vault_overview_remove_frequent_items)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.delete)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.select_all)).performClick()
        compose.runOnIdle { assertEquals(3, state.selectionCount) }
        Espresso.pressBack()
        compose.onNodeWithContentDescription(context.getString(R.string.select_all)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, state.selectionCount); assertFalse(state.overviewListOpen) }
        overviewRow("favorites", 2).performClick()
        compose.runOnIdle { assertEquals(2L, openedPassword) }
    }

    @Test fun overviewSwipesSelectAndRemoveWithoutDeletingAndPickerCanRestoreTheItem() {
        showSelectableOverview()
        overviewRow("items", 4).performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(1, state.selectionCount) }
        overviewRow("items", 4).assertIsSelected().performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(0, state.selectionCount) }
        overviewRow("items", 4).performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { state.overviewSnapshot?.frequentItems?.none { it.key == "password:4" } == true }
        val entry = runBlocking { database.passwordEntryDao().getPasswordEntryById(4)!! }
        assertFalse(entry.isDeleted)
        assertTrue(runBlocking { settings.settingsFlow.first().vaultOverviewConfig.excludedFrequentItems.contains(entry.vaultOverviewKey()) })
        compose.runOnIdle { assertEquals(24, state.overviewSnapshot?.items?.size) }
        compose.onNodeWithTag("overview_pin_items").performClick()
        compose.onNodeWithTag("overview_pin_list").performScrollToNode(hasTestTag("overview_pin_row_password:4"))
        compose.onNodeWithTag("overview_pin_row_password:4").performClick()
        compose.onNodeWithTag("overview_pin_done").performClick()
        compose.waitUntil(10_000) { state.overviewSnapshot?.frequentItems?.any { it.key == "password:4" } == true }
        assertFalse(runBlocking { settings.settingsFlow.first().vaultOverviewConfig.excludedFrequentItems.contains(entry.vaultOverviewKey()) })
    }

    @Test fun overviewBulkRemovalOnlyTouchesTheEightFrequentItems() {
        showSelectableOverview(pinCount = 8)
        overviewRow("items", 1).performTouchInput { longClick() }
        compose.onNodeWithContentDescription(context.getString(R.string.select_all)).performClick()
        compose.runOnIdle { assertEquals(8, state.selectionCount) }
        compose.onNodeWithContentDescription(context.getString(R.string.vault_overview_remove_frequent_items)).performClick()
        compose.waitUntil(10_000) { state.overviewSnapshot?.frequentItems?.isEmpty() == true }
        compose.runOnIdle {
            assertEquals(0, state.selectionCount)
            assertEquals(24, state.overviewSnapshot?.items?.size)
            assertEquals(3, state.overviewSnapshot?.favorites?.size)
        }
        val saved = runBlocking { settings.settingsFlow.first().vaultOverviewConfig }
        assertEquals(8, saved.excludedFrequentItems.size)
        assertTrue(saved.pinnedItems.isEmpty())
        runBlocking {
            (1L..24L).forEach { assertFalse(database.passwordEntryDao().getPasswordEntryById(it)!!.isDeleted) }
        }
    }

    @Test fun overviewFavoriteSwipeUsesTheExistingDeleteConfirmationAndTrash() {
        showSelectableOverview()
        compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag("overview_favorites"))
        overviewRow("favorites", 1).performTouchInput { swipeLeft() }
        compose.onNode(isDialog()).assertIsDisplayed()
        assertFalse(runBlocking { database.passwordEntryDao().getPasswordEntryById(1)!!.isDeleted })
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertFalse(runBlocking { database.passwordEntryDao().getPasswordEntryById(1)!!.isDeleted })
        overviewRow("favorites", 1).performTouchInput { swipeLeft() }
        compose.onNode(hasText(context.getString(R.string.delete)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { runBlocking { database.passwordEntryDao().getPasswordEntryById(1)?.isDeleted == true } }
        compose.waitUntil(10_000) { state.overviewSnapshot?.favorites?.none { it.key == "password:1" } == true }
        assertFalse(runBlocking { database.passwordEntryDao().getPasswordEntryById(2)!!.isDeleted })
    }

    @Test fun sortingChangesRealRowsAndSurvivesOverviewReturn() {
        prepareSortEntries()
        showPane(followOverviewSettings = true, expectedOverviewCount = 3)
        openOverviewNode("overview_all_items")
        awaitSortOrder(1, 3, 2)
        openSortSheet()
        sortScreenshot("vault-sort-sheet.png")
        Espresso.pressBack()
        awaitSortOrder(1, 3, 2)
        listOf(
            VaultListSort.TITLE_DESC to listOf(2, 3, 1),
            VaultListSort.CREATED_DESC to listOf(1, 2, 3),
            VaultListSort.CREATED_ASC to listOf(3, 2, 1),
            VaultListSort.UPDATED_DESC to listOf(2, 3, 1),
            VaultListSort.UPDATED_ASC to listOf(1, 3, 2),
        ).forEach { (mode, ids) ->
            chooseSort(mode)
            awaitSortOrder(*ids.toIntArray())
        }
        chooseSort(VaultListSort.CREATED_DESC)
        awaitSortOrder(1, 2, 3)
        sortScreenshot("vault-sort-created.png")
        Espresso.pressBack()
        compose.onNodeWithTag("vault_overview_screen").assertIsDisplayed()
        openOverviewNode("overview_all_items")
        awaitSortOrder(1, 2, 3)
        assertEquals(VaultListSort.CREATED_DESC, runBlocking { SettingsManager(context).settingsFlow.first().vaultListSort })
        runBlocking {
            assertEquals(3000L, database.passwordEntryDao().getPasswordEntryById(1)!!.createdAt.time)
            assertEquals(1000L, database.passwordEntryDao().getPasswordEntryById(1)!!.updatedAt.time)
        }
    }

    @Test fun folderAndSearchUseSavedSortInHierarchicalLayout() {
        prepareSortEntries()
        runBlocking {
            database.categoryDao().insert(Category(id = 144, name = "Sort fixture"))
            listOf(1L, 2L).forEach { id ->
                val entry = database.passwordEntryDao().getPasswordEntryById(id)!!
                database.passwordEntryDao().updatePasswordEntry(entry.copy(categoryId = 144))
            }
        }
        appSettings = appSettings.copy(vaultV2LayoutMode = VaultV2LayoutMode.HIERARCHICAL)
        showPane(followOverviewSettings = true, expectedOverviewCount = 3)
        compose.waitUntil(10_000) { state.overviewSnapshot?.folders?.any { it.name == "Sort fixture" } == true }
        val key = state.overviewSnapshot!!.folders.first { it.name == "Sort fixture" }.key
        openOverviewNode("overview_folder_$key")
        chooseSort(VaultListSort.UPDATED_DESC)
        awaitSortOrder(2, 1)
        compose.onNodeWithTag("vault_item_password:3").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("fixture")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(5_000) { !keyboardVisible }
        awaitSortOrder(2, 1)
        compose.onNodeWithTag("vault_item_password:3").assertDoesNotExist()
        sortScreenshot("vault-sort-folder-search.png")
    }

    private fun prepareFolderNavigationFixture() = runBlocking {
        database.passwordEntryDao().deleteAllPasswordEntries()
        listOf(1760L to "Parent", 1761L to "Parent/zebra", 1762L to "Parent/Alpha",
            1763L to "Parent/Alpha/Grandchild", 1764L to "Other").forEach { (id, name) ->
            database.categoryDao().insert(Category(id = id, name = name))
        }
        listOf(1L to 1760L, 2L to 1761L, 3L to 1763L).forEach { (id, folder) ->
            database.passwordEntryDao().insertPasswordEntry(PasswordEntry(id = id,
                title = "Layer entry $id", website = "", username = "", password = "", categoryId = folder))
        }
        settings.updateVaultListSort(VaultListSort.TITLE_ASC)
        val fixtureConfig = VaultOverviewConfig(scope = "local", order = listOf(VaultOverviewModule.FOLDERS.name))
        settings.updateVaultOverviewConfig { fixtureConfig }
        appSettings = appSettings.copy(vaultV2LayoutMode = VaultV2LayoutMode.CLASSIC,
            vaultListSort = VaultListSort.TITLE_ASC, vaultOverviewConfig = fixtureConfig)
    }

    @Test fun overviewFolderListShowsOnlyTopLevelFolders() {
        prepareFolderNavigationFixture()
        showPane(expectedOverviewCount = 3)
        compose.waitUntil(10_000) { state.overviewSnapshot?.folders?.size == 5 }
        compose.onNodeWithTag("overview_folder_local/1760").assertIsDisplayed()
        compose.onNodeWithTag("overview_folder_local/1764").assertIsDisplayed()
        compose.onNodeWithTag("overview_folder_local/1761").assertDoesNotExist()
        compose.onNodeWithTag("overview_folder_local/1762").assertDoesNotExist()
        compose.onNodeWithTag("overview_folder_local/1763").assertDoesNotExist()
    }

    @Test fun overviewFolderLargeFontDarkKeepsFoldersAboveEntries() {
        fixtureFontScale = 1.5f
        darkTheme = true
        overviewFolderEntryKeepsAllSortedChildFoldersTogetherAboveDirectEntries()
    }

    @Test fun overviewFolderEntryKeepsAllSortedChildFoldersTogetherAboveDirectEntries() {
        prepareFolderNavigationFixture()
        showPane(followOverviewSettings = true, expectedOverviewCount = 3)
        openOverviewNode("overview_folder_local/1760")
        fun awaitFolderOrder(vararg titles: String) {
            compose.waitUntil(15_000) {
                val nodes = (titles.toList() + "Layer entry 1").map { title ->
                    compose.onAllNodesWithText(title).fetchSemanticsNodes().singleOrNull()
                }
                nodes.all { it != null } && nodes.map { it!!.boundsInRoot.top }.zipWithNext().all { (a, b) -> a < b }
            }
            titles.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
            compose.onNodeWithText("Layer entry 1").assertIsDisplayed()
            compose.onNodeWithText("Layer entry 2").assertDoesNotExist()
            compose.onNodeWithText("Layer entry 3").assertDoesNotExist()
        }
        awaitFolderOrder("Alpha", "zebra")
        chooseSort(VaultListSort.TITLE_DESC)
        awaitFolderOrder("zebra", "Alpha")
        capture(if (darkTheme) "folder-navigation-176-desc-dark-large.png" else "folder-navigation-176-desc.png")
        compose.onNodeWithText("Alpha").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Grandchild").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("zebra").assertDoesNotExist()
        compose.onNodeWithText("Layer entry 3").assertDoesNotExist()
        compose.onNodeWithText("Grandchild").performClick()
        awaitListText("Layer entry 3")
        Espresso.pressBack()
        compose.onNodeWithText("Grandchild").assertIsDisplayed()
        Espresso.pressBack()
        awaitFolderOrder("zebra", "Alpha")
        Espresso.pressBack()
        compose.onNodeWithTag("vault_overview_screen").assertIsDisplayed()
        compose.runOnIdle { assertEquals(VaultV2LayoutMode.CLASSIC, appSettings.vaultV2LayoutMode) }
    }

    @Test fun kdbxOverviewNavigatesDuplicateParentsByUuidAndLocksWithoutWriting() = runBlocking {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val ac = UUID.randomUUID(); val bc = UUID.randomUUID()
        KeePassDeleteInvestigationTest().scenario(databaseTransform = { file ->
            file.modifyParentGroup {
                val fixture = entries.take(4).mapIndexed { index, entry ->
                    entry.copy(fields = entry.fields + ("Title" to app.keemobile.kotpass.models.EntryValue.Plain("KDBX layer $index")))
                }
                copy(entries = listOf(fixture[0]), groups = listOf(
                    Group(uuid = a, name = "Same", entries = listOf(fixture[1]), groups = listOf(
                        Group(uuid = ac, name = "Alpha", entries = listOf(fixture[2])))),
                    Group(uuid = b, name = "Same", groups = listOf(
                        Group(uuid = bc, name = "Beta", entries = listOf(fixture[3])))),
                    Group(uuid = UUID.randomUUID(), name = "A/B")))
            }
        }) { model, service, id, room, uri ->
            val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val snapshot = service.loadWorkspace(id).getOrThrow()
            assertTrue(model.applyKeePassWorkspaceSnapshot(id, snapshot, true, false))
            database.passwordEntryDao().deleteAllPasswordEntries()
            room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).forEach {
                database.passwordEntryDao().insertPasswordEntry(it)
            }
            val config = VaultOverviewConfig(scope = "keepass:$id", order = listOf(VaultOverviewModule.FOLDERS.name))
            settings.updateVaultOverviewConfig { config }
            appSettings = appSettings.copy(vaultV2LayoutMode = VaultV2LayoutMode.CLASSIC, vaultOverviewConfig = config)
            testKeePassDatabases = listOf(PasswordDatabase.getDatabase(context).localKeePassDatabaseDao().getDatabaseById(id)!!)
            showPane(expectedOverviewCount = 4)
            compose.waitUntil(15_000) { state.overviewSnapshot?.folders?.size == 5 }
            compose.onNodeWithTag("overview_folder_keepass:$id/$a").assertIsDisplayed()
            compose.onNodeWithTag("overview_folder_keepass:$id/$b").assertIsDisplayed()
            compose.onNodeWithTag("overview_folder_keepass:$id/$ac").assertDoesNotExist()
            openOverviewNode("overview_folder_keepass:$id/$a")
            awaitListText("KDBX layer 1")
            compose.onNodeWithTag("vault_folder_hierarchy:keepass:$id/$ac").assertIsDisplayed().performClick()
            awaitListText("KDBX layer 2")
            compose.onNodeWithText("KDBX layer 3").assertDoesNotExist()
            Espresso.pressBack()
            awaitListText("KDBX layer 1")
            compose.onNodeWithTag("vault_folder_hierarchy:keepass:$id/$bc").assertDoesNotExist()
            Espresso.pressBack()
            compose.onNodeWithTag("vault_overview_screen").assertIsDisplayed()
            openOverviewNode("overview_folder_keepass:$id/$b")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("vault_folder_hierarchy:keepass:$id/$bc").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("vault_folder_hierarchy:keepass:$id/$bc").performClick()
            awaitListText("KDBX layer 3")
            compose.runOnIdle { testKeepassModel.lockKeePassDatabase(id) }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("KDBX layer 3").fetchSemanticsNodes().isEmpty() }
            assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertEquals(VaultV2LayoutMode.CLASSIC, appSettings.vaultV2LayoutMode)
        }
    }

    private fun prepareSortEntries() = runBlocking {
        settings.updateVaultListSort(VaultListSort.TITLE_ASC)
        database.passwordEntryDao().deleteAllPasswordEntries()
        listOf(
            Triple("Alpha", 3, 1), Triple("Zebra", 2, 3), Triple("Beta", 1, 2),
        ).forEachIndexed { index, (title, created, updated) ->
            database.passwordEntryDao().insertPasswordEntry(PasswordEntry(
                id = index + 1L, title = title, username = "fixture", website = "", password = "",
                createdAt = java.util.Date(created * 1000L), updatedAt = java.util.Date(updated * 1000L)))
        }
    }

    private fun openSortSheet() {
        compose.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
        compose.onNodeWithTag("vault_sort_menu").performClick()
        compose.onNodeWithTag("vault_sort_sheet").assertIsDisplayed()
    }

    private fun chooseSort(mode: VaultListSort) {
        openSortSheet()
        compose.onNodeWithTag("vault_sort_${mode.name}").performScrollTo().performClick()
        compose.waitUntil(10_000) { appSettings.vaultListSort == mode }
    }

    private fun awaitSortOrder(vararg ids: Int) {
        compose.waitUntil(15_000) {
            val nodes = ids.map { id -> compose.onAllNodesWithTag("vault_item_password:$id").fetchSemanticsNodes().singleOrNull() }
            nodes.all { it != null } && nodes.map { it!!.boundsInRoot.top }.zipWithNext().all { (a,b) -> a < b }
        }
        ids.forEach { compose.onNodeWithTag("vault_item_password:$it").assertIsDisplayed() }
    }

    private fun sortScreenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        File(context.filesDir, name).outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        File(context.cacheDir, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
