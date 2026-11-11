package takagi.ru.monica.ui

import takagi.ru.monica.utils.AppLocaleStringResolver

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.HistoryTab
import takagi.ru.monica.ui.screens.TimelineScreen
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.SavedCategoryFilterState
import takagi.ru.monica.viewmodel.*
import java.util.Date
import java.io.File
import java.util.UUID

/** Exercises the real main-screen menu, navigation state, and recycle-bin query. */
@RunWith(AndroidJUnit4::class)
class TrashNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val restoration by lazy { StateRestorationTester(compose) }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val application get() = context.applicationContext as Application
    private val settings = SettingsManager(context)
    private val database get() = PasswordDatabase.getDatabase(context)
    private lateinit var originalSettings: AppSettings
    private lateinit var originalVaultFilter: SavedCategoryFilterState
    private val models = mutableListOf<ViewModel>()
    private val insertedPasswords = mutableListOf<Long>()
    private val prefix = "Trash regression ${UUID.randomUUID()}"
    private lateinit var passwordModel: PasswordViewModel
    private lateinit var settingsModel: SettingsViewModel
    private var darkTheme by mutableStateOf(false)

    @Before fun prepare() = runBlocking {
        originalSettings = settings.settingsFlow.first()
        originalVaultFilter = settings.categoryFilterStateFlow("vault_v2").first()
        settings.updateCategoryFilterState("vault_v2", SavedCategoryFilterState(type = "local"))
        settings.updateBottomNavOrder(listOf(BottomNavContentTab.VAULT_V2, BottomNavContentTab.PASSWORDS) +
            BottomNavContentTab.entries.filter { it != BottomNavContentTab.VAULT_V2 && it != BottomNavContentTab.PASSWORDS })
        settings.updateTrashEnabled(true)
        settings.updateTrashAutoDeleteDays(0)
        settings.updateBottomNavVisibility(BottomNavContentTab.PASSWORDS, true)
        settings.updateBottomNavVisibility(BottomNavContentTab.VAULT_V2, true)
        settings.updateHideFabOnScroll(false)
    }

    @After fun restore() {
        models.forEach { it.viewModelScope.cancel() }
        runBlocking {
            insertedPasswords.forEach { database.passwordEntryDao().deletePasswordEntryById(it) }
            settings.updateTrashEnabled(originalSettings.trashEnabled)
            settings.updateTrashAutoDeleteDays(originalSettings.trashAutoDeleteDays)
            BottomNavContentTab.entries.forEach { tab ->
                settings.updateBottomNavVisibility(tab, originalSettings.bottomNavVisibility.isVisible(tab))
            }
            settings.updateHideFabOnScroll(originalSettings.hideFabOnScroll)
            settings.updateAutoHideBottomNavWhenSingleTab(originalSettings.autoHideBottomNavWhenSingleTab)
            settings.updateVaultOverviewEnabled(originalSettings.vaultOverviewEnabled)
            settings.updateBottomNavOrder(originalSettings.bottomNavOrder)
            settings.updateCategoryFilterState("vault_v2", originalVaultFilter)
        }
    }

    @Test fun classicPasswordMenuKeepsTheLocalDatabaseWhenOpeningTrash() {
        addDeletedPassword("local")
        addDeletedPassword("other database", bitwardenVaultId = Long.MAX_VALUE)
        showMain()
        compose.runOnIdle { passwordModel.setCategoryFilter(CategoryFilter.Local) }
        compose.mainClock.advanceTimeBy(250)

        openTrash()

        awaitText("$prefix local")
        compose.onNodeWithText("$prefix other database").assertDoesNotExist()
    }

    @Test fun trashKeepsItsDatabaseScopeBeforeDatabaseMetadataArrives() {
        addDeletedPassword("local")
        addDeletedPassword("bitwarden", bitwardenVaultId = Long.MAX_VALUE)
        addDeletedPassword("keepass", keepassDatabaseId = Long.MAX_VALUE)
        addDeletedPassword("mdbx", mdbxDatabaseId = Long.MAX_VALUE)
        val timeline = keep(TimelineViewModel(application))
        val trash = keep(TrashViewModel(application))
        var scopeKey by mutableStateOf("bitwarden_${Long.MAX_VALUE}")
        compose.setContent {
            MaterialTheme {
                TimelineScreen(viewModel = timeline, trashViewModel = trash,
                    initialTab = HistoryTab.TRASH, initialTrashScopeKey = scopeKey,
                    enableTabSwitch = false, showBackButton = true)
            }
        }
        listOf("bitwarden", "keepass", "mdbx", "local").forEach { source ->
            compose.runOnIdle { scopeKey = if (source == "local") "local" else "${source}_${Long.MAX_VALUE}" }
            awaitText("$prefix $source")
            listOf("local", "bitwarden", "keepass", "mdbx").filter { it != source }.forEach {
                compose.onNodeWithText("$prefix $it").assertDoesNotExist()
            }
        }
    }

    @Test fun returnFabAndSharedSelectionActionsWorkOnThePasswordPage() {
        verifyReturnAndSelection(startTab = 1)
    }



    @Test fun returnFabMatchesAddFabWithTheBottomNavigationHidden() {
        runBlocking {
            BottomNavContentTab.entries.forEach { settings.updateBottomNavVisibility(it, it == BottomNavContentTab.PASSWORDS) }
            settings.updateAutoHideBottomNavWhenSingleTab(true)
        }
        verifyReturnAndSelection(startTab = 1)
    }

    @Test fun ordinaryNavigationRetainsOrderSelectionAndFabAfterStateRestoration() {
        fun step(name: String) { File(context.filesDir, "navigation-progress.txt").appendText(name + "\n") }
        File(context.filesDir, "navigation-progress.txt").writeText("start\n")
        runBlocking {
            BottomNavContentTab.entries.forEach {
                settings.updateBottomNavVisibility(it, it == BottomNavContentTab.VAULT_V2 || it == BottomNavContentTab.PASSWORDS)
            }
            settings.updateAutoHideBottomNavWhenSingleTab(false)
            settings.updateVaultOverviewEnabled(false)
        }
        showMain()
        step("main shown")
        settleFrames()
        fun nav(label: Int) = compose.onNode(hasText(context.getString(label)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
        val vault = nav(R.string.nav_v2_vault_short)
        val password = nav(R.string.nav_passwords_short)
        password.assertIsSelected()
        assertTrue(vault.fetchSemanticsNode().boundsInRoot.left < password.fetchSemanticsNode().boundsInRoot.left)
        val fab = action(R.string.add).fetchSemanticsNode().boundsInRoot
        assertTrue("FAB must stay above navigation", fab.bottom <= password.fetchSemanticsNode().boundsInRoot.top)
        capture("ordinary-navigation")
        step("navigation and FAB verified")
        vault.performClick()
        settleFrames()
        vault.assertIsSelected()
        step("vault selected")
        restoration.emulateSavedInstanceStateRestore()
        step("state restored")
        settleFrames()
        vault.assertIsSelected()
        password.performClick()
        settleFrames()
        password.assertIsSelected()
        action(R.string.add).assertIsDisplayed()

    }

    private fun verifyReturnAndSelection(startTab: Int) {
        val first = addDeletedPassword("first")
        val second = addDeletedPassword("second")
        val other = addDeletedPassword("other database", bitwardenVaultId = Long.MAX_VALUE)
        showMain(startTab = startTab)
        if (startTab == 1) compose.runOnIdle { passwordModel.setCategoryFilter(CategoryFilter.Local) }
        settleFrames()
        val addBounds = action(R.string.add).fetchSemanticsNode().boundsInRoot
        openTrash()
        awaitText("$prefix first")
        settleFrames()
        val backBounds = action(R.string.back).fetchSemanticsNode().boundsInRoot
        assertEquals("Return FAB uses the add FAB bounds", addBounds, backBounds)
        action(R.string.add).assertIsNotDisplayed()
        capture(if (startTab == 0) "trash-return-vault" else "trash-return-password")

        // Only this test's items may participate in a bulk operation on the shared test database.
        action(R.string.search).performClick()
        settleFrames()
        compose.onNode(hasSetTextAction()).performTextInput(prefix)
        settleFrames()
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("$prefix first").performTouchInput { longClick() }
        settleFrames()
        action(R.string.back).assertDoesNotExist()
        action(R.string.select_all).performClick()
        settleFrames()
        compose.onNodeWithText("2").assertIsDisplayed()
        action(R.string.restore).assertIsDisplayed()
        action(R.string.close).assertIsDisplayed()
        action(R.string.close).performClick()
        settleFrames()
        action(R.string.back).assertIsDisplayed()
        compose.onNodeWithText("$prefix first").performTouchInput { longClick() }
        settleFrames()
        Espresso.pressBack()
        settleFrames()
        action(R.string.back).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextEquals(prefix)
        compose.onNodeWithText("$prefix first").performTouchInput { longClick() }
        settleFrames()
        action(R.string.select_all).performClick()
        settleFrames()

        action(R.string.delete).performClick()
        settleFrames()
        compose.onNodeWithText(context.getString(R.string.timeline_permanent_delete_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        settleFrames()
        if (startTab == 1 && !originalSettings.autoHideBottomNavWhenSingleTab) {
            capture("trash-selection-light")
            compose.runOnIdle { darkTheme = true }
            settleFrames()
            action(R.string.restore).performTouchInput { down(center) }
            settleFrames()
            capture("trash-selection-dark-pressed")
            action(R.string.restore).performTouchInput { cancel() }
        }

        action(R.string.restore).performClick()
        compose.waitUntil(10_000) {
            settleFrames()
            runBlocking { database.passwordEntryDao().getPasswordEntryById(first)?.isDeleted == false &&
                database.passwordEntryDao().getPasswordEntryById(second)?.isDeleted == false }
        }
        assertTrue(runBlocking { database.passwordEntryDao().getPasswordEntryById(other)!!.isDeleted })
        action(R.string.back).assertIsDisplayed().performClick()
        settleFrames()
        action(R.string.add).assertIsDisplayed()
        // The placement contract is checked above, before the IME can change window insets.
        if (startTab == 1) {
            compose.runOnIdle { assertEquals(CategoryFilter.Local, passwordModel.categoryFilter.value) }
            compose.runOnIdle { passwordModel.setCategoryFilter(CategoryFilter.All) }
            settleFrames()
            openTrash()
            awaitText("$prefix other database")
            action(R.string.back).performClick()
            settleFrames()
        } else {
            addDeletedPassword("after return")
            openTrash()
            awaitText("$prefix after return")
            compose.onNodeWithText("$prefix other database").assertDoesNotExist()
        }
    }

    private fun action(label: Int) = compose.onNode(hasClickAction() and hasContentDescription(context.getString(label)))

    private fun settleFrames() {
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun addDeletedPassword(name: String, bitwardenVaultId: Long? = null,
        keepassDatabaseId: Long? = null, mdbxDatabaseId: Long? = null) = runBlocking {
        database.passwordEntryDao().insertPasswordEntry(
            PasswordEntry(title = "$prefix $name", website = "", username = "demo", password = "",
                isDeleted = true, deletedAt = Date(), bitwardenVaultId = bitwardenVaultId,
                keepassDatabaseId = keepassDatabaseId, mdbxDatabaseId = mdbxDatabaseId)
        ).also { insertedPasswords += it }
    }

    private fun <T : ViewModel> keep(model: T): T = model.also { models += it }

    private fun showMain(startTab: Int = 1) {
        val security = SecurityManager(context)
        val passwords = PasswordRepository(database.passwordEntryDao(), categoryDao = database.categoryDao(),
            bitwardenFolderDao = database.bitwardenFolderDao(), passwordArchiveSyncMetaDao = database.passwordArchiveSyncMetaDao())
        val items = SecureItemRepository(database.secureItemDao())
        passwordModel = keep(PasswordViewModel(passwords, security, strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext))).also { it.restoreAuthenticatedUiState() }
        settingsModel = keep(SettingsViewModel(settings))
        val totp = keep(TotpViewModel(items, passwords, strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it }))
        val cards = keep(BankCardViewModel(items, strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it }))
        val documents = keep(DocumentViewModel(items, strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext), sensitiveValueEncryptor = takagi.ru.monica.security.SensitiveValueEncryptor { it }))
        val addresses = keep(BillingAddressViewModel(items))
        val notes = keep(NoteViewModel(items, strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)))
        val passkeys = keep(PasskeyViewModel(PasskeyRepository(database.passkeyDao()), strings = AppLocaleStringResolver(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)))
        val keepass = keep(LocalKeePassViewModel(application, database.localKeePassDatabaseDao(), security))
        val mdbx = keep(MdbxViewModel(application, database.localMdbxDatabaseDao(), database.mdbxRemoteSourceDao(),
            database.passwordEntryDao(), database.secureItemDao(), database.passkeyDao(),
            database.attachmentDao(), database.customFieldDao(), security))
        val bitwarden = keep(takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel(application))
        val generator = keep(GeneratorViewModel())
        // The main screen also hosts continuously updating clocks and callback state.
        // Advance bounded frames instead of asking Espresso to settle the entire app.
        compose.mainClock.autoAdvance = false
        restoration.setContent {
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                SimpleMainScreen(passwordViewModel = passwordModel, settingsViewModel = settingsModel,
                    totpViewModel = totp, bankCardViewModel = cards, documentViewModel = documents,
                    billingAddressViewModel = addresses, noteViewModel = notes, passkeyViewModel = passkeys,
                    localKeePassViewModel = keepass, mdbxViewModel = mdbx, bitwardenViewModel = bitwarden,
                    generatorViewModel = generator, securityManager = security,
                    onNavigateToAddPassword = {}, onNavigateToAddTotp = {}, onNavigateToQuickTotpScan = {},
                    onNavigateToFidoQrScan = {}, onNavigateToAddBankCard = {}, onNavigateToAddDocument = {},
                    onNavigateToAddBillingAddress = {}, onNavigateToWalletAdd = {}, onNavigateToAddNote = {},
                    onNavigateToPasskeyDetail = {}, onNavigateToBankCardDetail = {}, onNavigateToDocumentDetail = {},
                    onNavigateToBillingAddressDetail = {}, onClearAllData = { _, _, _, _, _, _ -> }, initialTab = startTab)
            }
        }
        compose.waitUntil(15_000) {
            compose.mainClock.advanceTimeBy(32)
            compose.onAllNodesWithContentDescription(
                context.getString(R.string.more_options)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.more_options)).assertIsDisplayed()
    }

    private fun openTrash() {
        compose.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText(context.getString(R.string.timeline_trash_title)).performClick()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) {
            if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeBy(32)
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
