package takagi.ru.monica.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import takagi.ru.monica.data.*
import takagi.ru.monica.R
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.*
import takagi.ru.monica.ui.screens.*
import takagi.ru.monica.ui.vaultv2.*
import takagi.ru.monica.utils.*
import takagi.ru.monica.viewmodel.*

/** Actual editor -> Room -> full vault pane -> actual detail, including retained return state. */
class VaultProjectCardTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = SettingsManager(context)
    private val models = mutableListOf<ViewModel>()
    private lateinit var db: PasswordDatabase
    private lateinit var security: SecurityManager
    private lateinit var passwordModel: PasswordViewModel
    private lateinit var oldFilter: SavedCategoryFilterState
    private var oldEditor = false
    private var savedId: Long? = null
    private var showVault by mutableStateOf(false)
    private var detailId by mutableStateOf<Long?>(null)
    private lateinit var state: VaultV2PaneState
    private val title = "Vault synthetic multi-password"
    private fun <T : ViewModel> keep(model: T): T = model.also { models += it }

    @Before fun setup(): Unit = runBlocking {
        assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready)
        oldFilter = settings.categoryFilterStateFlow("vault_v2").first()
        oldEditor = settings.settingsFlow.first().passwordContentEditorEnabled
        settings.updateCategoryFilterState("vault_v2", SavedCategoryFilterState(type = "local"))
        settings.updatePasswordContentEditorEnabled(true)
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        passwordModel = keep(PasswordViewModel(
            PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()), security,
            customFieldRepository = CustomFieldRepository(db.customFieldDao()), strings = AppLocaleStringResolver(context)))
        passwordModel.restoreAuthenticatedUiState()
    }

    @After fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
        runBlocking {
            settings.updateCategoryFilterState("vault_v2", oldFilter)
            settings.updatePasswordContentEditorEnabled(oldEditor)
        }
        db.close()
    }

    @Test fun flatAutoShowsOneProjectAndBothPasswordsInDetail() = exercise(false, false)
    @Test fun flatExpandedShowsOneProjectWithLargeText() = exercise(false, true)
    @Test fun overviewToListShowsOneProjectAndReturnsFromDetail() = exercise(true, false)
    @Test fun selectingOneProjectFavoritesBothPasswordsAndKeepsIndependentItem() = exercise(false, false, actions = true)
    @Test fun hierarchicalVaultShowsOneProject() = exercise(false, false, hierarchy = true)

    private fun exercise(overview: Boolean, expanded: Boolean, actions: Boolean = false, hierarchy: Boolean = false) {
        val items = SecureItemRepository(db.secureItemDao())
        val passwords = PasswordRepository(db.passwordEntryDao())
        val strings = AppLocaleStringResolver(context)
        val encryptor = SensitiveValueEncryptor { security.encryptData(it) }
        val totp = keep(TotpViewModel(items, passwords, strings = strings, sensitiveValueEncryptor = encryptor))
        val cards = keep(BankCardViewModel(items, strings = strings, sensitiveValueEncryptor = encryptor))
        val documents = keep(DocumentViewModel(items, strings = strings, sensitiveValueEncryptor = encryptor))
        val addresses = keep(BillingAddressViewModel(items))
        val notes = keep(NoteViewModel(items, strings = strings))
        val passkeys = keep(PasskeyViewModel(PasskeyRepository(db.passkeyDao()), strings = strings))
        val keepass = keep(LocalKeePassViewModel(context.applicationContext as Application, db.localKeePassDatabaseDao(), security))
        val mdbx = keep(MdbxViewModel(context.applicationContext as Application,
            db.localMdbxDatabaseDao(), db.mdbxRemoteSourceDao(), db.passwordEntryDao(), db.secureItemDao(),
            db.passkeyDao(), db.attachmentDao(), db.customFieldDao(), security))
        val settingsModel = keep(SettingsViewModel(settings))
        val appSettings = AppSettings(vaultOverviewEnabled = overview,
            vaultV2LayoutMode = if (hierarchy) VaultV2LayoutMode.HIERARCHICAL else VaultV2LayoutMode.CLASSIC,
            stackCardMode = if (expanded) "ALWAYS_EXPANDED" else "AUTO", passwordGroupMode = "smart",
            disablePasswordVerification = actions)
        compose.setContent {
            CompositionLocalProvider(LocalUiSecurityManager provides security,
                LocalDensity provides Density(LocalDensity.current.density, if (expanded) 1.5f else 1f)) {
                MaterialTheme(colorScheme = if (expanded) darkColorScheme() else lightColorScheme()) {
                    state = rememberVaultV2PaneState(remember { VaultV2RetainedState() })
                    when {
                        detailId != null -> PasswordDetailScreen(passwordModel, passwordId = requireNotNull(detailId),
                            biometricEnabled = false, onNavigateBack = { detailId = null }, onEditPassword = {})
                        !showVault -> AddEditPasswordScreen(passwordModel, passwordId = null, initialStorageExplicit = true,
                            initialDraft = AddEditPasswordInitialDraft(title = title, username = "synthetic-user", password = "first-synthetic"),
                            onSaveCompleted = { savedId = it }, onNavigateBack = {})
                        else -> VaultV2Pane(passwordModel, totp, cards, documents, addresses, notes, passkeys,
                            keepassDatabases = emptyList(), mdbxDatabases = emptyList(), bitwardenVaults = emptyList(),
                            localKeePassViewModel = keepass, mdbxViewModel = mdbx, settingsViewModel = settingsModel, state = state,
                            onOpenPassword = { detailId = it }, onOpenTotp = {}, onOpenBankCard = {}, onOpenDocument = {},
                            onOpenBillingAddress = {}, onOpenNote = {}, onOpenPasskey = {}, onOpenMdbxCommitHistory = {},
                            onOpenHistory = {}, onOpenTrashPage = {}, onOpenArchivePage = {}, onOpenCommonAccountTemplates = {},
                            appSettings = appSettings, securityManager = security, biometricEnabled = false,
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    }
                }
            }
        }
        compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag("password_editor_add_password"))
        compose.onNodeWithTag("password_editor_add_password").performClick()
        compose.onNodeWithTag("password_content_editor").performScrollToNode(hasTestTag("password_editor_value_1"))
        compose.onNodeWithTag("password_editor_value_1").performTextReplacement("second-synthetic")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(30_000) { savedId != null }
        val stored = runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }.sortedBy { it.id }
        assertEquals(2, stored.size)
        assertEquals(1, stored.map { it.passwordProjectKey() }.distinct().size)
        assertEquals(listOf("first-synthetic", "second-synthetic"), stored.map { security.decryptData(it.password) })
        val independentId = if (actions) runBlocking {
            // Clear both current and legacy membership when creating an independent control.
            db.passwordEntryDao().insertPasswordEntry(stored.first().copy(id = 0, title = "Independent same account",
                passwordGroupId = null, replicaGroupId = null))
        } else null
        if (actions) assertEquals(2, runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }
            .map { it.passwordProjectKey() }.distinct().size)
        compose.runOnIdle { showVault = true }
        if (overview) {
            compose.waitUntil(15_000) { state.overviewSnapshot?.items?.isNotEmpty() == true }
            compose.onNodeWithTag("overview_modules").performScrollToNode(hasTestTag("overview_type_PASSWORD"))
            compose.onNodeWithTag("overview_type_PASSWORD").performClick()
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val count = compose.onAllNodesWithText(title).fetchSemanticsNodes().size
        val opened = JSONArray()
        repeat(count) { index ->
            compose.onAllNodesWithText(title)[index].performClick()
            compose.runOnIdle {
                val selected = stored.single { it.id == detailId }
                val resolved = resolvePasswordDetailGroupPasswords(selected, stored).map { it.id }
                assertEquals(stored.map { it.id }, resolved)
                opened.put(JSONArray(resolved))
            }
            stored.forEach { row ->
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("detail_password_${row.id}"))
                compose.onNodeWithTag("detail_password_${row.id}").performScrollTo().assertIsDisplayed()
            }
            Espresso.pressBack()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().size == count }
        }
        val label = "overview-$overview-expanded-$expanded-actions-$actions-hierarchy-$hierarchy"
        val report = JSONObject().put("cardCount", count).put("databaseRows", stored.size + if (actions) 1 else 0)
            .put("projectCount", if (actions) 2 else 1).put("targetProjectRows", stored.size).put("openedIdsPerCard", opened)
        File(context.filesDir, "vault-project-$label.json").writeText(report.toString(2))
        File(context.filesDir, "vault-project-$label.png").outputStream().use {
            requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertEquals((stored.map { it.id } + listOfNotNull(independentId)).sorted(),
            runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }.map { it.id }.sorted())
        assertEquals("One editor project must have one vault-page entry: $label", 1, count)
        if (actions) {
            compose.onNodeWithText(title).performTouchInput { longClick() }
            compose.runOnIdle { assertEquals(1, state.selectionCount) }
            compose.onNodeWithContentDescription(context.getString(R.string.favorite)).performClick()
            compose.waitUntil(15_000) {
                runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }.count { it.isFavorite } == 2
            }
            val after = runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }
            assertEquals(stored.map { it.id }.toSet(), after.filter { it.isFavorite }.map { it.id }.toSet())
            assertFalse(after.single { it.id == independentId }.isFavorite)
            compose.onNodeWithText("Independent same account").assertExists()
            compose.onNodeWithText(title).performTouchInput { longClick() }
            compose.onNodeWithContentDescription(context.getString(R.string.delete)).performClick()
            compose.onNode(hasText(context.getString(R.string.cancel)) and hasAnyAncestor(isDialog())).performClick()
            assertTrue(runBlocking { stored.all { db.passwordEntryDao().getPasswordEntryById(it.id)?.isDeleted == false } })
            compose.onNodeWithContentDescription(context.getString(R.string.delete)).performClick()
            compose.onNode(hasText(context.getString(R.string.delete)) and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(15_000) {
                runBlocking { stored.all { db.passwordEntryDao().getPasswordEntryById(it.id)?.isDeleted == true } }
            }
            assertFalse(runBlocking { db.passwordEntryDao().getPasswordEntryById(requireNotNull(independentId))!!.isDeleted })
            compose.onAllNodesWithText(title).assertCountEquals(0)
            compose.onNodeWithText("Independent same account").assertExists()
        }
    }
}
