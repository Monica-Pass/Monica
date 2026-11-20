package takagi.ru.monica.ui.screens

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.ui.components.LocalExpansionAnimationsEnabled
import takagi.ru.monica.ui.components.SelectiveBackupCard
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.BackupFile
import takagi.ru.monica.utils.FileSourceEntry
import java.io.File
import java.util.Date
import java.util.Locale

/** Production backup components and an isolated disconnected WebDAV route; no cloud account is used. */
@RunWith(AndroidJUnit4::class)
class CloudBackupUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext
    private val events = mutableListOf<String>()
    private var frameWidth: Dp? = null
    private var fontScale = 1f
    private var locale = Locale.SIMPLIFIED_CHINESE
    private var dark = false
    private var originalConfiguration: Configuration? = null
    private var room: PasswordDatabase? = null
    private val preferenceNames = mutableSetOf<String>()
    private val preferencePrefix = "cloud_backup_ui_${System.nanoTime()}_"
    private var encrypted by mutableStateOf(true)
    private var encryptionPassword by mutableStateOf("Synthetic UI passphrase")
    private var passwordVisible by mutableStateOf(false)
    private var preferences by mutableStateOf(BackupPreferences())
    private var selectedBackup by mutableStateOf<BackupFile?>(null)
    private var merge by mutableStateOf(true)
    private var globalDedup by mutableStateOf(false)
    private var loading by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var provider by mutableStateOf("WebDAV")
    private var editingConnection by mutableStateOf(false)
    private val backups = (1..60).map { i ->
        BackupFile("monica_20260915_${i}_permanent.enc.zip", "/backups/$i", 2_490_368, Date(1_789_477_800_000L))
    }

    private fun label(id: Int) = targetContext.createConfigurationContext(
        Configuration(targetContext.resources.configuration).apply {
            setLocale(this@CloudBackupUiTest.locale)
        }).getString(id)

    private fun show(content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity { activity ->
            originalConfiguration = Configuration(activity.resources.configuration)
            val configuration = Configuration(activity.resources.configuration).apply {
                setLocale(this@CloudBackupUiTest.locale)
                fontScale = this@CloudBackupUiTest.fontScale
            }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        }
        compose.setContent {
            val config = Configuration(targetContext.resources.configuration).apply {
                setLocale(this@CloudBackupUiTest.locale)
                fontScale = this@CloudBackupUiTest.fontScale
                frameWidth?.let { screenWidthDp = it.value.toInt() }
            }
            val localized = remember {
                object : ContextThemeWrapper(compose.activity, 0) {
                    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                        val isolatedName = preferencePrefix + name
                        preferenceNames += isolatedName
                        return super.getSharedPreferences(isolatedName, mode)
                    }
                    override fun getApplicationContext(): Context = this
                }.apply { applyOverrideConfiguration(config) }
            }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MonicaTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), Alignment.TopStart) {
                        Box((frameWidth?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth())
                            .fillMaxHeight().testTag("backup_test_frame")) { content() }
                    }
                }
            }
        }
    }

    @Composable private fun Page(
        files: List<BackupFile> = backups,
        configured: Boolean = true,
        allowConnectionEditing: Boolean = false,
    ) {
        CloudBackupPage(
            title = if (provider == "WebDAV") stringResourceCompat(R.string.webdav_backup) else stringResourceCompat(R.string.onedrive_backup_title),
            configured = configured && !editingConnection, backups = files, loading = loading, refreshEnabled = true,
            onRefresh = { events += "refresh" }, onNavigateBack = { events += "back" }, errorMessage = error,
            onCancelConnection = if (editingConnection) ({ editingConnection = false }) else null,
            primaryAction = { CloudBackupPrimaryButton(stringResourceCompat(R.string.webdav_create_new_backup),
                onClick = { events += "backup:$provider" }, enabled = !loading) },
            location = { CloudBackupLocationCard(if (provider == "WebDAV") "dav.example.net" else "个人备份",
                "/Monica/Backups", supporting = "example@example.net", onClick = {
                    events += "location"
                    if (allowConnectionEditing) editingConnection = true
                }) },
            connection = { Text("Connection editor", Modifier.testTag("backup_connection_editor")) },
            settings = {
                if (allowConnectionEditing) {
                    TextButton(onClick = { editingConnection = true }, modifier = Modifier.testTag("backup_edit_connection")) {
                        Text(stringResourceCompat(R.string.webdav_reconfigure))
                    }
                }
                CloudBackupEncryptionSettings(encrypted, encryptionPassword, passwordVisible,
                    { encrypted = it }, { encryptionPassword = it }, { passwordVisible = !passwordVisible })
                SelectiveBackupCard(preferences, { preferences = it }, 24, 8, 2, 2, 4,
                    trashCount = 3, passkeyCount = 0, localKeePassCount = 0, isWebDavConfigured = provider == "WebDAV")
            },
            backupRow = { index, backup ->
                CloudBackupFileRow(backup, "2.4 MB", index, files.size,
                    onRestore = { selectedBackup = backup },
                    onTogglePermanent = { events += "permanent:${backup.path}" },
                    onDelete = { events += "delete:${backup.path}" })
            },
        )
        selectedBackup?.let { backup ->
            CloudBackupRestoreSheet(backup, "2.4 MB", merge, globalDedup,
                { merge = it }, { globalDedup = it }, { selectedBackup = null },
                { events += "restore:${backup.path}:$merge:$globalDedup"; selectedBackup = null })
        }
    }

    @Composable private fun stringResourceCompat(id: Int) = androidx.compose.ui.res.stringResource(id)

    @Test fun settingsToolbarBackReturnsToHistoryBeforeLeavingRoute() {
        show { Page() }
        compose.onNodeWithTag("cloud_backup_content").performScrollToIndex(30)
        val before = compose.onNodeWithTag("cloud_backup_file_/backups/29").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("cloud_backup_tab_1").performClick()
        compose.onNodeWithTag("cloud_backup_back").performClick()
        compose.onNodeWithTag("cloud_backup_tab_0").assertIsSelected()
        assertEquals(before, compose.onNodeWithTag("cloud_backup_file_/backups/29").fetchSemanticsNode().boundsInRoot)
        assertTrue(events.isEmpty())
        compose.onNodeWithTag("cloud_backup_back").performClick()
        assertEquals(listOf("back"), events)
    }

    @Test fun settingsSystemBackReturnsToHistoryForBothProviders() {
        show { Page() }
        for (name in listOf("WebDAV", "OneDrive")) {
            compose.runOnIdle { provider = name }
            compose.onNodeWithTag("cloud_backup_tab_1").performClick()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("cloud_backup_tab_0").assertIsSelected()
            assertTrue(events.isEmpty())
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf("back"), events)
    }

    @Test fun connectionEditorReturnsToHistoryFromEitherEntryPoint() {
        show { Page(allowConnectionEditing = true) }
        compose.onNodeWithText("dav.example.net").performClick()
        compose.onNodeWithTag("backup_connection_editor").assertIsDisplayed()
        compose.onNodeWithTag("cloud_backup_back").performClick()
        compose.onNodeWithTag("cloud_backup_tab_0").assertIsSelected()
        compose.onNodeWithTag("cloud_backup_tab_1").performClick()
        compose.onNodeWithTag("backup_edit_connection").performClick()
        compose.onNodeWithTag("backup_connection_editor").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("cloud_backup_tab_0").assertIsSelected()
        assertFalse(events.contains("back"))
    }

    @Test fun initialConnectionBackStillLeavesTheRoute() {
        show { Page(configured = false) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf("back"), events)
    }

    @Test fun bothProvidersKeepCreationAtTwelveDpWhileBrowsingLongHistory() {
        show { Page() }
        compose.onNodeWithText("WebDAV 备份").assertIsDisplayed()
        val frame = compose.onNodeWithTag("backup_test_frame").fetchSemanticsNode().boundsInRoot
        val file = compose.onNodeWithTag("cloud_backup_file_/backups/1").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("cloud_backup_primary").fetchSemanticsNode().boundsInRoot
        assertEquals(12f * compose.density.density, file.left - frame.left, 1f)
        assertEquals(12f * compose.density.density, frame.right - file.right, 1f)
        assertEquals(file.left, button.left, 1f)
        assertEquals(file.right, button.right, 1f)
        capture("webdav-history-light")
        compose.onNodeWithTag("cloud_backup_content").performScrollToIndex(55)
        assertEquals(button, compose.onNodeWithTag("cloud_backup_primary").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("cloud_backup_primary").assertIsDisplayed().performClick()
        compose.runOnIdle { provider = "OneDrive" }
        compose.onNodeWithTag("cloud_backup_primary").assertIsDisplayed().performClick()
        assertEquals(listOf("backup:WebDAV", "backup:OneDrive"), events)
        compose.onNodeWithTag("cloud_backup_content").performScrollToIndex(0)
        capture("onedrive-history-light")
    }

    @Test fun settingsAndHistoryRetainEditsExpansionAndScrollPosition() {
        show { Page() }
        compose.onNodeWithTag("cloud_backup_content").performScrollToIndex(30)
        val visibleBefore = compose.onAllNodes(hasTestTag("cloud_backup_file_/backups/29")).fetchSemanticsNodes().single().boundsInRoot
        compose.onNodeWithTag("cloud_backup_tab_1").performClick()
        compose.onNodeWithTag("cloud_backup_encryption_password").performScrollTo().performTextReplacement("Edited synthetic passphrase")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("cloud_backup_content_toggle").performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.backup_content_notes)).performScrollTo().performClick()
        assertFalse(preferences.includeNotes)
        compose.onNodeWithTag("cloud_backup_tab_0").performClick()
        assertEquals(visibleBefore, compose.onNodeWithTag("cloud_backup_file_/backups/29").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("cloud_backup_tab_1").performClick()
        compose.onNodeWithText(label(R.string.backup_content_notes)).performScrollTo().assertIsDisplayed()
        assertEquals("Edited synthetic passphrase", encryptionPassword)
        assertTrue(events.isEmpty())
    }

    @Test fun backupMenuTargetsTheSelectedFileAndRequiresRestoreConfirmation() {
        show { Page() }
        compose.onNodeWithTag("cloud_backup_menu_/backups/2").performClick()
        compose.onNodeWithText(label(R.string.webdav_unmark_permanent)).performClick()
        assertEquals(listOf("permanent:/backups/2"), events)
        compose.onNodeWithTag("cloud_backup_menu_/backups/2").performClick()
        compose.onNodeWithText(label(R.string.delete)).performClick()
        assertEquals("delete:/backups/2", events.last())
        compose.onNodeWithTag("cloud_backup_file_/backups/1").performClick()
        compose.onNodeWithTag("cloud_backup_restore_mode_0").assertIsSelected()
        assertEquals(2, events.size)
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        assertEquals(2, events.size)
    }

    @Test fun replacementWarningIsReadableAndConfirmationStaysVisibleWithLargeText() {
        locale = Locale.GERMAN
        fontScale = 1.7f
        dark = true
        show { CloudBackupRestoreSheet(backups.first(), "2.4 MB", merge, globalDedup,
            { merge = it }, { globalDedup = it }, { events += "cancel" }, { events += "restore:$merge:$globalDedup" }) }
        assertEquals("Einstellungen", label(R.string.settings))
        compose.onNodeWithText("Backup wiederherstellen").assertIsDisplayed()
        assertEquals(fontScale, compose.onNodeWithTag("cloud_backup_primary")
            .fetchSemanticsNode().layoutInfo.density.fontScale, 0.001f)
        compose.onNodeWithTag("cloud_backup_restore_mode_1").performScrollTo().performClick()
        compose.onNodeWithTag("cloud_backup_replace_warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cloud_backup_primary").assertIsDisplayed().assertIsEnabled()
        capture("restore-replace-german-dark-large")
        compose.onNodeWithTag("cloud_backup_primary").performClick()
        assertEquals(listOf("restore:false:false"), events)
    }

    @Test fun mergeDedupCanBeChosenWithoutAffectingReplacementPolicy() {
        show { CloudBackupRestoreSheet(backups.first(), "2.4 MB", merge, globalDedup,
            { merge = it }, { globalDedup = it }, {}, { events += "restore:$merge:$globalDedup" }) }
        compose.onNodeWithTag("cloud_backup_global_dedup").performScrollTo().performClick()
        assertTrue(globalDedup)
        compose.onNodeWithTag("cloud_backup_restore_mode_1").performScrollTo().performClick()
        compose.onNodeWithTag("cloud_backup_global_dedup").assertDoesNotExist()
        compose.onNodeWithTag("cloud_backup_restore_mode_0").performScrollTo().performClick()
        compose.onNodeWithTag("cloud_backup_global_dedup").performScrollTo().assertIsOn()
        capture("restore-merge-light")
    }

    @Test fun contentSelectionPreservesLegacyFlagsAndUnavailableSources() {
        show { Page() }
        compose.onNodeWithTag("cloud_backup_tab_1").performClick()
        compose.onNodeWithTag("cloud_backup_content_toggle").performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.deselect_all)).performScrollTo().performClick()
        assertFalse(preferences.hasAnyEnabled())
        assertFalse(preferences.includeGeneratorHistory)
        assertFalse(preferences.includeTrash)
        compose.onNodeWithText(label(R.string.select_all)).performScrollTo().performClick()
        assertTrue(preferences.includeTrashAndHistory)
        assertTrue(preferences.includeGeneratorHistory)
        assertFalse(preferences.includePasskeys)
        assertFalse(preferences.includeLocalKeePass)
        compose.onNodeWithText(label(R.string.backup_content_passkeys)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.backup_content_trash_and_history)).performScrollTo().performClick()
        assertFalse(preferences.includeTrashAndHistory)
        assertFalse(preferences.includeGeneratorHistory)
        assertFalse(preferences.includeTrash)
    }

    @Test fun oneDriveKeepsWebDavConfigurationOutOfItsContentChoices() {
        provider = "OneDrive"
        show { Page() }
        compose.onNodeWithTag("cloud_backup_tab_1").performClick()
        compose.onNodeWithTag("cloud_backup_content_toggle").performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.backup_content_local_keepass)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.backup_content_webdav_config)).assertDoesNotExist()
        capture("onedrive-content-light")
    }

    @Test fun errorStateOffersRefreshWithoutMisreportingAnEmptyHistory() {
        error = "Synthetic network failure"
        show { Page(emptyList()) }
        compose.onNodeWithText("Synthetic network failure").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.webdav_no_backups)).assertDoesNotExist()
        compose.onNodeWithTag("cloud_backup_refresh").performClick()
        assertEquals(listOf("refresh"), events)
        compose.runOnIdle { loading = true }
        compose.onNodeWithTag("cloud_backup_refresh").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_backup_primary").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_backup_loading").assertIsDisplayed()
    }

    @Test fun folderBrowsingNeverSavesUntilConfirmationAndKeepsSaveReachable() {
        var path by mutableStateOf("/Monica")
        val folders = (1..60).map { FileSourceEntry(name = "Folder $it", path = "/Monica/$it", isDirectory = true) }
        show { CloudBackupFolderSheet("example@example.net", path, folders, false, true, null,
            { path = "/" }, { events += "refresh" }, { events += "new-folder" },
            { path = it.path }, { events += "saved:$path" }, { events += "cancel" }) }
        compose.onNodeWithTag("cloud_backup_folders").performScrollToIndex(59)
        compose.onNodeWithTag("cloud_backup_primary").assertIsDisplayed()
        compose.onNodeWithTag("cloud_backup_folder_/Monica/60").performClick()
        assertTrue(events.isEmpty())
        compose.onNodeWithTag("cloud_backup_folder_path").assertTextEquals("/Monica/60")
        capture("onedrive-folder-light")
        compose.onNodeWithTag("cloud_backup_primary").performClick()
        assertEquals(listOf("saved:/Monica/60"), events)
    }

    @Test fun failedOrLoadingFolderCannotReplaceTheSavedDestination() {
        var valid by mutableStateOf(false)
        var busy by mutableStateOf(false)
        show { CloudBackupFolderSheet("example@example.net", "/Old folder", emptyList(), busy, valid,
            if (!valid) "Synthetic connection failure" else null, {}, { events += "retry" }, {}, {},
            { events += "saved" }, { events += "cancel" }) }
        compose.onNodeWithTag("cloud_backup_primary").assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(R.string.onedrive_refresh_folder)).performClick()
        compose.runOnIdle { valid = true; busy = true }
        compose.onNodeWithTag("cloud_backup_primary").assertIsNotEnabled()
        compose.runOnIdle { busy = false }
        compose.onNodeWithTag("cloud_backup_primary").assertIsEnabled()
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        assertEquals(listOf("retry", "cancel"), events)
    }

    @Test fun narrowPolishLayoutKeepsSettingsAndActionsInsideTheFrame() {
        frameWidth = 320.dp
        fontScale = 1.8f
        locale = Locale.forLanguageTag("pl")
        dark = true
        show { Page() }
        compose.onNodeWithText("Kopia WebDAV").assertIsDisplayed()
        assertEquals("Ustawienia", label(R.string.settings))
        compose.onNodeWithTag("cloud_backup_tab_1").performClick()
        compose.onNodeWithTag("cloud_backup_encryption_password").performScrollTo().assertIsDisplayed()
        val frame = compose.onNodeWithTag("backup_test_frame").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("cloud_backup_primary").fetchSemanticsNode().boundsInRoot
        assertTrue(action.left >= frame.left && action.right <= frame.right)
        assertTrue(action.bottom <= frame.bottom)
        capture("settings-polish-dark-large")
    }

    @Test fun encryptionExpansionHonorsReducedMotionAndKeepsTypedText() {
        encrypted = false
        show { CompositionLocalProvider(LocalExpansionAnimationsEnabled provides false) { Page() } }
        compose.onNodeWithTag("cloud_backup_tab_1").performClick()
        compose.onNodeWithTag("cloud_backup_encryption_password").assertDoesNotExist()
        compose.onNodeWithTag("cloud_backup_encryption").performClick()
        compose.onNodeWithTag("cloud_backup_encryption_password").performScrollTo().performTextReplacement("Synthetic edited key")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("cloud_backup_encryption").performScrollTo().performClick()
        compose.onNodeWithTag("cloud_backup_encryption_password").assertDoesNotExist()
        compose.onNodeWithTag("cloud_backup_encryption").performClick()
        assertEquals("Synthetic edited key", encryptionPassword)
        capture("webdav-settings-light")
    }

    @Test fun realWebDavConnectionFormKeepsTestButtonAboveTheKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        room = Room.inMemoryDatabaseBuilder(targetContext, PasswordDatabase::class.java).build()
        show { WebDavBackupScreen(PasswordRepository(room!!.passwordEntryDao()), SecureItemRepository(room!!.secureItemDao()), {}) }
        compose.onNodeWithTag("cloud_backup_primary").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_backup_server_url").performScrollTo().performTextInput("https://example.invalid/webdav")
        compose.onNodeWithTag("cloud_backup_connection_password").performScrollTo().performClick().performTextInput("Synthetic password")
        compose.onNodeWithTag("cloud_backup_primary").assertIsDisplayed().assertIsEnabled()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitUntil(5_000) {
            val decor = compose.activity.window.decorView
            val keyboardHeight = ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            compose.onNodeWithTag("cloud_backup_primary").fetchSemanticsNode().boundsInWindow.bottom <=
                decor.height - keyboardHeight + 1f
        }
        val field = compose.onNodeWithTag("cloud_backup_connection_password").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("cloud_backup_primary").fetchSemanticsNode().boundsInRoot
        assertTrue(action.top >= field.bottom)
        capture("webdav-connect-keyboard")
        Espresso.closeSoftKeyboard()
    }

    @Test fun darkPressedBackupUsesRoundedFeedback() {
        dark = true
        show { Page() }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("cloud_backup_file_/backups/1").performTouchInput { down(center); advanceEventTime(180) }
        compose.mainClock.advanceTimeBy(180)
        capture("history-dark-pressed")
        compose.onNodeWithTag("cloud_backup_file_/backups/1").performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
    }

    private fun capture(name: String, wait: Boolean = true) {
        if (wait) compose.waitForIdle()
        val directory = File(targetContext.getExternalFilesDir(null), "cloud-backup-ui").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: kotlin.error("Screenshot unavailable")
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @After fun cleanUp() {
        compose.mainClock.autoAdvance = true
        Espresso.closeSoftKeyboard()
        room?.close()
        preferenceNames.forEach { targetContext.deleteSharedPreferences(it) }
        originalConfiguration?.let { config ->
            compose.activityRule.scenario.onActivity { activity ->
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            }
        }
    }
}
