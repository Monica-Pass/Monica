package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.backup.FolderBackupRunner
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.localization.LocaleTestActivity
import takagi.ru.monica.repository.MdbxVaultDiagnostics
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.theme.MonicaTheme
import java.io.File
import java.util.Locale

class FolderBackupUiTest {
    @get:Rule val compose = createAndroidComposeRule<LocaleTestActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var backCalls = 0

    @Before fun unlock() = runBlocking {
        check(context.packageName.contains("folderbackup"))
        withContext(Dispatchers.IO) {
            val security = SecurityManager(context)
            if (!security.isMasterPasswordSet()) assertTrue(security.setMasterPassword("Synthetic local master 332!"))
            assertTrue(security.unlockVaultWithPassword("Synthetic local master 332!"))
            SessionManager.markUnlocked()
            security.removeProtectedString("folder_backup_v1")
            FolderBackupRunner.foreground = true
        }
    }

    private fun show(tag: String, dark: Boolean, large: Boolean) {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        val localized = context.createConfigurationContext(config)
        compose.setContent {
            val registryOwner = checkNotNull(androidx.activity.compose.LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides registryOwner,
                LocalContext provides localized, LocalConfiguration provides config,
                LocalLayoutDirection provides if (tag == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(LocalDensity.current.density, if (large) 1.5f else 1f)) {
                MonicaTheme(darkTheme = dark) {
                    Box(Modifier.width(if (large) 320.dp else 390.dp).fillMaxHeight()) {
                        FolderBackupScreen({ backCalls++ }, { error("Setup must not restore") })
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("folder_backup_password").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun chineseSetupRequiresFolderAndConfirmationAndBackWorks() {
        show("zh-CN", false, false)
        compose.onNodeWithTag("folder_backup_password").performScrollTo().performTextInput("Synthetic archive!")
        compose.onNodeWithTag("folder_backup_password_confirm").performScrollTo().performTextInput("wrong")
        compose.onNodeWithTag("cloud_backup_primary").assertIsNotEnabled()
        compose.onNodeWithTag("folder_backup_password_confirm").performTextReplacement("Synthetic archive!")
        compose.onNodeWithTag("cloud_backup_primary").assertIsNotEnabled() // No directory grant yet.
        capture("folder-backup-zh.png")
        compose.onNodeWithTag("cloud_backup_back").performClick()
        assertEquals(1, backCalls)
    }

    @Test fun arabicLargeDarkSetupScrollsAndImmediatelyHidesOnLock() {
        show("ar", true, true)
        compose.onNodeWithTag("folder_backup_password_confirm").performScrollTo().assertIsDisplayed()
        capture("folder-backup-ar-large-dark.png")
        compose.runOnIdle { SessionManager.markLocked() }
        compose.onNodeWithTag("folder_backup_password").assertDoesNotExist()
        compose.onNodeWithTag("folder_backup_password_confirm").assertDoesNotExist()
    }

    @Test fun currentDatabaseFailureAndRecoveryOverrideStaleDiagnosticsTogether() {
        var row by mutableStateOf(LocalMdbxDatabase(id = 332_191, name = "Synthetic provider database", filePath = "content://synthetic/vault",
            sourceType = MdbxSourceType.LOCAL_EXTERNAL.name, engineType = MdbxEngineType.RUST_MDBX2.name,
            lastSyncStatus = MdbxSyncStatus.FAILED.name, lastSyncError = "Synthetic safe write lease unavailable"))
        var diagnostics by mutableStateOf(MdbxVaultDiagnostics(row.id, null, true, 1024, true,
            integrityOk = true, lastSyncStatus = MdbxSyncStatus.IN_SYNC.name))
        compose.setContent { MonicaTheme {
            MdbxVaultDetailPage(database = row, isDefault = false, conflictCount = 0, diagnostics = diagnostics,
                onSync = {}, onShowConflicts = {}, onShowHealth = {}, onShowSnapshots = {}, onShowCommitHistory = {},
                onShowAttachments = {}, onShowMaintenance = {}, onMigrate = null, onSetDefault = {}, onDelete = {})
        } }
        compose.onNodeWithText(context.getString(mdbxSyncStatusLabel(MdbxSyncStatus.FAILED.name))).assertExists()
        compose.onNodeWithText(context.getString(mdbxSyncStatusLabel(MdbxSyncStatus.IN_SYNC.name))).assertDoesNotExist()
        compose.onNodeWithText("Synthetic safe write lease unavailable").assertExists()
        capture("folder-backup-mdbx-failure.png")
        compose.runOnIdle {
            diagnostics = diagnostics.copy(lastSyncStatus = MdbxSyncStatus.FAILED.name, lastSyncError = row.lastSyncError)
            row = row.copy(lastSyncStatus = MdbxSyncStatus.IN_SYNC.name, lastSyncError = null)
        }
        compose.onNodeWithText(context.getString(mdbxSyncStatusLabel(MdbxSyncStatus.IN_SYNC.name))).assertExists()
        compose.onNodeWithText("Synthetic safe write lease unavailable").assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
