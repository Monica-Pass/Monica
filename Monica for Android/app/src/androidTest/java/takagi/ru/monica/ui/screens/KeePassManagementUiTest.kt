package takagi.ru.monica.ui.screens

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.keepass.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.KeePassUriPermissionState
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.viewmodel.LocalKeePassViewModel

/** Real production components and local KDBX fixtures; cloud tests stop before sign-in or connection. */
@RunWith(AndroidJUnit4::class)
class KeePassManagementUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val frameWidth = mutableStateOf<Dp?>(null)
    private val fontScale = mutableFloatStateOf(1f)
    private val testLocale = mutableStateOf(Locale.SIMPLIFIED_CHINESE)
    private val dark = mutableStateOf(false)
    private var originalActivityConfiguration: Configuration? = null
    private val events = mutableListOf<String>()
    private val databases = (1L..40L).map { id -> LocalKeePassDatabase(
        id = id, name = listOf("个人密码", "工作账户", "家庭共享", "归档").getOrNull(id.toInt() - 1) ?: "示例数据库 $id",
        filePath = "/synthetic/database-$id.kdbx", isDefault = id == 1L, sortOrder = id.toInt()
    ) }

    private fun label(id: Int): String = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(testLocale.value) }).getString(id)

    private fun show(shell: Boolean = true, titleId: Int = R.string.mdbx_ui_local_databases, content: @Composable () -> Unit) {
        // Dialogs and popups create Android views from the Activity. Match the
        // Activity resources too, just as the real localized Activity does.
        compose.activityRule.scenario.onActivity { activity ->
            originalActivityConfiguration = Configuration(activity.resources.configuration)
            val configuration = Configuration(activity.resources.configuration).apply {
                setLocale(testLocale.value)
            }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        }
        compose.setContent {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(testLocale.value)
                fontScale = this@KeePassManagementUiTest.fontScale.floatValue
                frameWidth.value?.let { screenWidthDp = it.value.toInt() }
            }
            val localized = ContextThemeWrapper(compose.activity, 0).apply { applyOverrideConfiguration(configuration) }
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(density, fontScale.floatValue)) {
                MonicaTheme(darkTheme = dark.value) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), Alignment.TopStart) {
                        Box((frameWidth.value?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth())
                            .fillMaxHeight().testTag("keepass_test_frame")) {
                            if (shell) Scaffold(topBar = {
                                DatabaseManagementTopAppBar(title = { Text(label(titleId)) }, navigationIcon = {
                                    IconButton(onClick = { events += "back" }) { Icon(Icons.Default.ArrowBack, label(R.string.go_back)) }
                                })
                            }) { padding -> Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() } }
                            else content()
                        }
                    }
                }
            }
        }
    }

    @Composable private fun Source() = KeePassSourceManagementPage(
        KeePassManagementSource.LOCAL, databases, databases.associate { it.id to LocalKeePassViewModel.VerificationState.Verified(12, 21) },
        onCreateClick = { events += "create" }, onOpenClick = { events += "open" },
        onOpenDatabase = { events += "database:${it.id}" }, onResolveConflict = { events += "conflict:${it.id}" })

    @Test fun longListKeepsActionsReachableAndAlignedAtTwelveDp() {
        show { Source() }
        val frame = compose.onNodeWithTag("keepass_test_frame").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("keepass_database_1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("keepass_database_2").fetchSemanticsNode().boundsInRoot
        assertEquals(12f * compose.density.density, first.left - frame.left, 1f)
        assertEquals(12f * compose.density.density, frame.right - second.right, 1f)
        assertEquals(first.left, compose.onNodeWithTag("keepass_open_database").fetchSemanticsNode().boundsInRoot.left, 1f)
        assertEquals(second.right, compose.onNodeWithTag("keepass_create_database").fetchSemanticsNode().boundsInRoot.right, 1f)
        val bounds = compose.onNodeWithTag("keepass_source_actions").fetchSemanticsNode().boundsInRoot
        capture("tiles-light")
        compose.onNodeWithTag("keepass_database_grid").performScrollToIndex(40)
        compose.onNodeWithTag("keepass_database_40").assertIsDisplayed().performClick()
        assertEquals(bounds, compose.onNodeWithTag("keepass_source_actions").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("keepass_open_database").assertIsDisplayed().performClick()
        compose.onNodeWithTag("keepass_create_database").assertIsDisplayed().performClick()
        assertEquals(listOf("database:40", "open", "create"), events)
    }

    @Test fun narrowLargeFontActionsFitGermanPolishAndClassicalChinese() {
        frameWidth.value = 320.dp
        fontScale.floatValue = 1.8f
        dark.value = true
        show { Source() }
        listOf(Locale.GERMAN, Locale.forLanguageTag("pl"), Locale.forLanguageTag("lzh")).forEach { language ->
            compose.runOnIdle { testLocale.value = language }
            val first = compose.onNodeWithTag("keepass_database_1").fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithTag("keepass_database_2").fetchSemanticsNode().boundsInRoot
            assertEquals(first.left, second.left, 1f)
            assertTrue(second.top >= first.bottom)
            listOf("keepass_open_database" to R.string.attachment_open, "keepass_create_database" to R.string.create_new).forEach { (tag, id) ->
                compose.onNodeWithTag(tag).assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                val text = compose.onNode(hasText(label(id)) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val layout = layouts.single()
                assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
                assertFalse((0 until layout.lineCount).any { layout.isLineEllipsized(it) })
                assertTrue((0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) } <= layout.size.width + 1f)
                assertTrue(layout.multiParagraph.height <= layout.size.height + 1f)
                val textBounds = text.fetchSemanticsNode().boundsInRoot
                val button = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue(textBounds.left >= button.left && textBounds.right <= button.right)
            }
            capture("tiles-${language.language}-large")
        }
    }

    @Test fun hubKeepsSourceCountsAndExistingGoogleDriveDiscoverable() {
        val items = databases.take(2) + listOf(
            databases[2].copy(sourceType = KeePassDatabaseSourceType.REMOTE_WEBDAV),
            databases[3].copy(sourceType = KeePassDatabaseSourceType.REMOTE_ONEDRIVE),
            databases[4].copy(sourceType = KeePassDatabaseSourceType.REMOTE_GOOGLE_DRIVE))
        show(titleId = R.string.local_keepass_section_title) { KeePassManagementHub(items, googleDriveEnabled = false) { events += it.name } }
        capture("hub-light")
        listOf(label(R.string.mdbx_ui_local_databases), "WebDAV", "OneDrive", "Google Drive").forEach {
            compose.onNode(hasText(it) and hasClickAction()).assertIsDisplayed().performClick()
        }
        assertEquals(KeePassManagementSource.entries.map { it.name }, events)
    }

    @Test fun realManagerRoutesToLocalCreateAndBothCloudForms() {
        Fixture().use { fixture ->
            show(shell = false) { LocalKeePassScreen(fixture.model, {}, {}) }
            compose.onNodeWithText(label(R.string.mdbx_ui_local_databases)).performClick()
            compose.onNodeWithTag("keepass_create_database").performClick()
            compose.onNodeWithTag("keepass_create_form_actions").assertIsDisplayed()
            capture("create-light", system = true)
            Espresso.pressBack()
            compose.onNodeWithTag("keepass_database_grid").assertDoesNotExist() // Empty source still keeps actions.
            compose.onNodeWithTag("keepass_open_database").assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.go_back)).performClick()
            listOf("WebDAV" to "webdav", "OneDrive" to "onedrive").forEach { (title, tag) ->
                compose.onNodeWithText(title).performClick()
                listOf("keepass_open_database", "keepass_create_database").forEach { action ->
                    compose.onNodeWithTag(action).performClick()
                    compose.onNodeWithTag("keepass_${tag}_fields").assertIsDisplayed()
                    capture("$tag-connect", system = true)
                    Espresso.pressBack()
                    compose.onNodeWithTag(action).assertIsDisplayed()
                }
                compose.onNodeWithContentDescription(label(R.string.go_back)).performClick()
            }
        }
    }

    @Test fun actualManagerRestoresScrolledSourceAfterNativeBrowser() {
        Fixture().use { fixture ->
            val stored = fixture.createDatabase(seedBrowser = true)
            runBlocking { databases.forEach { fixture.dao.insertDatabase(stored.copy(id = it.id, name = it.name,
                sortOrder = it.sortOrder, isDefault = it.isDefault)) } }
            show(shell = false) { LocalKeePassScreen(fixture.model, {}, {}) }
            compose.onNodeWithText(label(R.string.mdbx_ui_local_databases)).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("keepass_database_grid").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("keepass_database_grid").performScrollToIndex(36)
            compose.onNodeWithTag("keepass_database_36").performClick()
            compose.onNodeWithTag("keepass_browse_database").performClick()
            compose.waitUntil(10_000) { fixture.model.activeNativeManagerDatabaseId.value == 36L }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("示例登录").fetchSemanticsNodes().isNotEmpty() }
            capture("native-browser", system = true)
            compose.onNodeWithText("个人").performClick()
            compose.onNodeWithText(label(R.string.keepass_native_empty_group)).assertIsDisplayed()
            Espresso.pressBack()
            compose.onNodeWithText("示例登录").assertIsDisplayed()
            Espresso.pressBack()
            compose.onNodeWithTag("keepass_database_36").assertIsDisplayed()
            compose.onNodeWithTag("keepass_create_database").assertIsDisplayed()
        }
    }

    @Composable private fun Detail(database: LocalKeePassDatabase, failed: Boolean = false) {
        KeePassDatabaseDetailBottomSheet(database,
            if (failed) LocalKeePassViewModel.VerificationState.Failed("Synthetic credential failure") else LocalKeePassViewModel.VerificationState.Verified(12, 21),
            KeePassUriPermissionState.READ_ONLY, LocalKeePassViewModel.KeyFileAccessState.AVAILABLE,
            onDismiss = {}, onSetDefault = { events += "default" }, onDelete = { events += "delete" },
            onTransferToInternal = { events += "internal" }, onTransferToExternal = { events += "external" },
            onVerifyPassword = { _, _, _ -> events += "verify" }, onSyncRemote = { events += "sync" },
            onResolveConflict = { events += "conflict" }, onExport = { events += "export" },
            onRepairPermission = { events += "permission" }, onKeepKeyFileCopy = { events += "key-copy" },
            onExportKeyFileCopy = { events += "key-export" }, onDeleteKeyFileCopy = { events += "key-delete" },
            onOpenNativeManager = { events += "browse" })
    }

    @Test fun detailsKeepStorageActionsConflictsAndRemovalConfirmation() {
        val variant = mutableIntStateOf(0)
        show(shell = false) {
            key(variant.intValue) {
                Detail(when (variant.intValue) {
                    1 -> databases.first().copy(sourceType = KeePassDatabaseSourceType.REMOTE_WEBDAV, lastSyncStatus = KeePassSyncStatus.CONFLICT, lastSyncError = "Synthetic concurrent change")
                    2 -> databases.first().copy(storageLocation = KeePassStorageLocation.EXTERNAL, sourceType = KeePassDatabaseSourceType.LOCAL_DOCUMENT_URI)
                    else -> databases.first()
                })
            }
        }
        capture("detail-light", system = true)
        compose.onNodeWithText(label(R.string.advanced_options)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.local_keepass_cipher_algorithm)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.remove_database)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.confirm_remove_internal_description)).assertIsDisplayed()
        assertFalse("delete" in events)
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        compose.onNodeWithText(label(R.string.export_to_external)).performScrollTo().performClick()
        compose.waitUntil(5_000) { "export" in events }
        compose.runOnIdle { variant.intValue = 1 }
        compose.onNodeWithText(label(R.string.keepass_conflict_review)).performScrollTo().performClick()
        compose.waitUntil(5_000) { "conflict" in events }
        assertFalse("sync" in events)
        compose.runOnIdle { variant.intValue = 2 }
        compose.onNodeWithText(label(R.string.keepass_file_permission_title)).performScrollTo().performClick()
        assertTrue("permission" in events)
        capture("detail-permissions", system = true)
    }

    @Test fun createFormPreservesValidationAndKeepsSubmitAboveRealKeyboard() {
        compose.activityRule.scenario.onActivity {
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        var created: String? = null
        show(shell = false) {
            CreateKeePassDatabaseBottomSheet({}, {}) { name, password, location, _, _, options, _ ->
                created = "$name:$password:$location:${options.kdfAlgorithm}"
            }
        }
        fun submit() = compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("keepass_create_form_actions")))
        fun fill(id: Int, value: String) {
            compose.onNode(hasSetTextAction() and hasText(label(id))).performScrollTo().performClick().performTextReplacement(value)
            submit().assertIsDisplayed()
        }
        submit().assertIsNotEnabled()
        fill(R.string.database_name, "UI example")
        fill(R.string.database_password, "Synthetic123!")
        fill(R.string.confirm_password, "Mismatch")
        submit().assertIsNotEnabled()
        fill(R.string.confirm_password, "Synthetic123!")
        submit().assertIsEnabled()
        compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
        val keyboardTop = compose.activity.window.decorView.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        assertTrue(submit().fetchSemanticsNode().boundsInWindow.bottom <= keyboardTop + 1f)
        capture("create-keyboard", system = true)
        submit().performClick()
        assertEquals("UI example:Synthetic123!:INTERNAL:ARGON2D", created)
        compose.activityRule.scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
    }

    @Test fun realSettingsSaveAndIntegrityInspectionRemainConnected() {
        val toolsPage = mutableStateOf(false)
        fontScale.floatValue = 1.3f
        dark.value = true
        Fixture().use { fixture ->
            val database = fixture.createDatabase()
            var saved = 0
            show(shell = false) {
                if (toolsPage.value) KeePassNativeDatabaseToolsScreen(database, fixture.model, { toolsPage.value = false }, {})
                else KeePassNativeDatabaseSettingsScreen(database, fixture.model, {}, { toolsPage.value = true }, { saved++ }, {})
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("keepass_settings_form_actions").fetchSemanticsNodes().isNotEmpty() }
            val submit = compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("keepass_settings_form_actions")))
            submit.assertIsDisplayed().assertIsEnabled()
            capture("settings-dark", system = true)
            compose.onNode(hasSetTextAction() and hasText(label(R.string.database_name))).performScrollTo().performTextReplacement("Updated UI example")
            submit.performClick()
            compose.waitUntil(15_000) { saved == 1 }
            compose.runOnIdle { toolsPage.value = true }
            compose.onNodeWithText(label(R.string.keepass_integrity_check)).assertIsDisplayed()
            capture("tools-dark", system = true)
            compose.onNodeWithText(label(R.string.keepass_integrity_check)).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(label(R.string.keepass_integrity_report)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(label(R.string.keepass_integrity_healthy)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.close)).performClick()
        }
    }

    @Test fun recoveryMenuHonorsVerificationAndBusyActions() {
        val valid = mutableStateOf(true)
        val record = KeePassRecoveryRecord(1, File("/synthetic/recovery.kdbx"), Instant.parse("2026-09-15T00:00:00Z"),
            KeePassSourceRevision("test", 4096), true, "test")
        show {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DatabaseToolCard(Icons.Default.Build, label(R.string.keepass_database_repair), "Busy example", busy = true, enabled = false) { events += "repair" }
                RecoveryCopyCard(record.copy(verified = valid.value), enabled = true,
                    onRestore = { events += "restore" }, onExport = { events += "export" }, onDelete = { events += "delete" })
            }
        }
        compose.onNodeWithText(label(R.string.keepass_database_repair)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(R.string.more_options)).performClick()
        compose.onNodeWithText(label(R.string.export)).assertIsEnabled().performClick()
        assertEquals(listOf("export"), events)
        compose.runOnIdle { valid.value = false }
        compose.onNodeWithContentDescription(label(R.string.more_options)).performClick()
        compose.onNodeWithText(label(R.string.restore)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.export)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.delete)).assertIsEnabled()
        capture("recovery-menu")
    }

    @Test fun heldFeedbackStaysOnTheSharedRoundedTilesInBothThemes() {
        show { Source() }
        listOf(false, true).forEach { isDark ->
            compose.runOnIdle { dark.value = isDark }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("keepass_database_1").performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(120)
            capture("tiles-${if (isDark) "dark" else "light"}-pressed")
            compose.onNodeWithTag("keepass_database_1").performTouchInput { up() }
            compose.mainClock.autoAdvance = true
        }
    }

    private fun capture(name: String, system: Boolean = false) {
        compose.waitForIdle()
        val bitmap = if (system) instrumentation.uiAutomation.takeScreenshot()
            else compose.onNodeWithTag("keepass_test_frame").captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "keepass-m3e-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun webDavConnectionStaysReachableAboveKeyboard() {
        testLocale.value = Locale.GERMAN
        fontScale.floatValue = 1.5f
        compose.activityRule.scenario.onActivity {
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        Fixture().use { fixture ->
            show(shell = false) { KeepassWebDavBrowserBottomSheet(fixture.model, onDismiss = {}) }
            val connect = compose.onNode(hasText(label(R.string.webdav_test_connection)) and
                hasAnyAncestor(hasTestTag("keepass_webdav_form_actions")))
            connect.assertIsDisplayed().assertIsNotEnabled()
            compose.onNode(hasSetTextAction() and hasText(label(R.string.webdav_server_url)))
                .performScrollTo().performClick().performTextReplacement("https://example.invalid/webdav")
            connect.assertIsDisplayed().assertIsEnabled()
            compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
            val keyboardTop = compose.activity.window.decorView.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            assertTrue(connect.fetchSemanticsNode().boundsInWindow.bottom <= keyboardTop + 1f)
            capture("webdav-keyboard", system = true)
            Espresso.pressBack()
        }
    }

    @Test fun nativeManagerSavesPropertiesAndAdvancesHotpThroughRealStorage() {
        Fixture().use { fixture ->
            val database = fixture.createDatabase()
            val created = runBlocking {
                val browser = fixture.model.openNativeBrowser(database.id).getOrThrow()
                fixture.model.createNativeEntry(database.id, browser.rootGroup.identity.groupUuid,
                    listOf(KeePassFieldChange("Title", "Device login"), KeePassFieldChange("UserName", "alice"),
                        KeePassFieldChange("Password", "Synthetic123!", true),
                        KeePassFieldChange("HmacOtp-Secret-Base32", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", true),
                        KeePassFieldChange("HmacOtp-Counter", "1")), browser.sourceRevision.sha256).getOrThrow()
            }
            show(shell = false) {
                KeePassNativeManagerScreen(database, fixture.model, {}, {})
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Device login").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Device login").performClick()
            compose.onNodeWithTag("native_hotp_next").performScrollTo().performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText(context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    setLocale(testLocale.value)
                }).getString(R.string.counter_value, 2)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(label(R.string.edit)).performClick()
            compose.onNode(hasText("Device login") and hasSetTextAction()).performScrollTo().performTextReplacement("Edited device login")
            compose.onNodeWithTag("native_entry_editor").performScrollToNode(hasTestTag("native_entry_tags"))
            compose.onNodeWithTag("native_entry_tags").performScrollTo().performTextReplacement("work\ncomma,inside")
            compose.onNodeWithTag("native_entry_expires").performScrollTo().performClick()
            compose.onNodeWithTag("native_entry_expiry_time").performScrollTo().performClick()
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1))
                .perform(androidx.test.espresso.action.ViewActions.click())
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1))
                .perform(androidx.test.espresso.action.ViewActions.click())
            compose.onNodeWithTag("native_entry_properties").performScrollTo().assertIsDisplayed()
            capture("native-entry-editor-properties", system = true)
            compose.onNodeWithContentDescription(label(R.string.save)).performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("native_hotp_next").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native_entry_properties_summary").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("work, comma, inside").assertIsDisplayed()
            capture("native-entry-properties", system = true)
            val saved = runBlocking { fixture.model.openNativeBrowser(database.id).getOrThrow().entry(created.identity)!! }
            assertEquals("Edited device login", saved.title)
            assertEquals(KeePassNativeEntryKind.PASSWORD, saved.kind)
            assertNull(saved.field(KeePassTemplateEngine.TEMPLATE_MARKER_FIELD))
            assertEquals(listOf("work", "comma", "inside"), saved.tags)
            assertEquals(true, saved.times?.expires)
            assertEquals("2", saved.field("HmacOtp-Counter")?.rawValue)
            assertEquals("alice", saved.field("UserName")?.rawValue)
            assertEquals("Synthetic123!", saved.field("Password")?.rawValue)
            val visiblePasswords = runBlocking {
                KeePassKdbxService(context, fixture.dao, SecurityManager(context)).readPasswordEntries(database.id).getOrThrow()
            }
            assertEquals("Edited device login", visiblePasswords.single().title)
        }
    }

    @Test fun hotpBusyFailureAndReadOnlyKeepThePersistedCounterVisible() {
        Fixture().use { fixture ->
            val database = fixture.createDatabase()
            val entry = runBlocking {
                val browser = fixture.model.openNativeBrowser(database.id).getOrThrow()
                fixture.model.createNativeEntry(database.id, browser.rootGroup.identity.groupUuid,
                    listOf(KeePassFieldChange("Title", "Offline HOTP"),
                        KeePassFieldChange("HmacOtp-Secret-Base32", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", true),
                        KeePassFieldChange("HmacOtp-Counter", "2")), browser.sourceRevision.sha256).getOrThrow()
            }
            val writable = mutableStateOf(true)
            var pending: ((String?) -> Unit)? = null
            show(shell = false) { TestNativeDetail(entry, writable.value) { _, result -> pending = result } }
            compose.onNodeWithTag("native_hotp_next").performScrollTo().performClick().assertIsNotEnabled()
            compose.runOnIdle { pending!!.invoke("Synthetic write failure") }
            compose.onNodeWithText("Synthetic write failure").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("native_hotp_next").assertIsEnabled()
            capture("native-hotp-failure", system = true)
            compose.runOnIdle { writable.value = false }
            compose.onNodeWithTag("native_hotp_next").assertIsNotEnabled()
            compose.onNodeWithContentDescription(label(R.string.edit)).assertIsNotEnabled()
            val saved = runBlocking { fixture.model.openNativeBrowser(database.id).getOrThrow().entry(entry.identity)!! }
            assertEquals("2", saved.field("HmacOtp-Counter")?.rawValue)
        }
    }

    @Test fun nativeDetailRefreshesResolvedFieldsForTheSameUuidAtLargeFont() {
        fontScale.floatValue = 1.8f
        frameWidth.value = 320.dp
        Fixture().use { fixture ->
            val database = fixture.createDatabase()
            val created = runBlocking {
                val browser = fixture.model.openNativeBrowser(database.id).getOrThrow()
                fixture.model.createNativeEntry(database.id, browser.rootGroup.identity.groupUuid,
                    listOf(KeePassFieldChange("Title", "Reference entry"), KeePassFieldChange("UserName", "{S:Account}"),
                        KeePassFieldChange("Account", "first-account")), browser.sourceRevision.sha256).getOrThrow()
            }
            val entry = mutableStateOf(created)
            show(shell = false) { TestNativeDetail(entry.value, false) }
            compose.onAllNodesWithText("first-account").onFirst().performScrollTo().assertIsDisplayed()
            val updated = runBlocking {
                val browser = fixture.model.openNativeBrowser(database.id).getOrThrow()
                fixture.model.replaceNativeEntryFields(database.id, created.identity.entryUuid,
                    listOf(KeePassFieldChange("Title", "Reference entry"), KeePassFieldChange("UserName", "{S:Account}"),
                        KeePassFieldChange("Account", "updated-account")), browser.sourceRevision.sha256).getOrThrow()
            }
            compose.runOnIdle { entry.value = updated }
            compose.onAllNodesWithText("updated-account").onFirst().performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("first-account").assertCountEquals(0)
            compose.onNodeWithText("{S:Account}").assertDoesNotExist()
            capture("native-entry-large-font", system = true)
        }
    }

    @Composable private fun TestNativeDetail(
        entry: KeePassNativeEntryRecord,
        writable: Boolean,
        advance: ((takagi.ru.monica.data.model.TotpData, (String?) -> Unit) -> Unit)? = null,
    ) = NativeEntryDetailScreen(
        entry = entry, modificationEnabled = writable, onBack = {}, onEdit = {},
        onAddAttachment = { _, result -> result(null) },
        onRenameAttachment = { _, _, result -> result(null) },
        onExportAttachment = { _, _, result -> result(null) },
        onDeleteAttachment = { _, result -> result(null) },
        onRestoreHistory = { _, result -> result(null) },
        onDeleteHistory = { _, result -> result(null) },
        onAdvanceHotp = advance,
    )

    @After fun restoreActivityResources() {
        originalActivityConfiguration?.let { original ->
            compose.activityRule.scenario.onActivity { activity ->
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(original, activity.resources.displayMetrics)
            }
        }
    }

    private inner class Fixture : AutoCloseable {
        private val room = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val dao = room.localKeePassDatabaseDao()
        val model = LocalKeePassViewModel(context.applicationContext as Application, dao, SecurityManager(context))
        private val ownedFiles = mutableListOf<File>()
        fun createDatabase(seedBrowser: Boolean = false): LocalKeePassDatabase {
            val name = "ui_m3e_${System.nanoTime()}"
            model.createDatabase(name, "Synthetic123!", KeePassStorageLocation.INTERNAL,
                creationOptions = KeePassDatabaseCreationOptions(kdfAlgorithm = KeePassKdfAlgorithm.AES_KDF, transformRounds = 1))
            val result = runBlocking { withTimeout(20_000) { model.operationState.first {
                it is LocalKeePassViewModel.OperationState.Success || it is LocalKeePassViewModel.OperationState.Error
            } } }
            check(result is LocalKeePassViewModel.OperationState.Success) { "Local fixture creation failed: $result" }
            val database = runBlocking { dao.getAllDatabases().first { it.isNotEmpty() }.single() }
            ownedFiles += File(context.filesDir, database.filePath)
            model.clearOperationState()
            if (seedBrowser) runBlocking {
                listOf("个人", "工作", "共享").forEach { groupName ->
                    val snapshot = model.openNativeBrowser(database.id).getOrThrow()
                    model.createNativeGroup(database.id, snapshot.rootGroup.identity.groupUuid,
                        groupName, snapshot.sourceRevision.sha256).getOrThrow()
                }
                val snapshot = model.openNativeBrowser(database.id).getOrThrow()
                model.createNativeEntry(database.id, snapshot.rootGroup.identity.groupUuid,
                    listOf(KeePassFieldChange("Title", "示例登录"), KeePassFieldChange("UserName", "example"),
                        KeePassFieldChange("Password", "Synthetic123!", protected = true)),
                    snapshot.sourceRevision.sha256).getOrThrow()
            }
            return database
        }
        override fun close() {
            runBlocking { model.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            room.close()
            ownedFiles.forEach { file ->
                check(file.canonicalPath.startsWith(File(context.filesDir, "keepass").canonicalPath + File.separator) && file.name.startsWith("ui_m3e_"))
                file.delete()
            }
        }
    }
}
