package takagi.ru.monica.ui.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxTigaMode

@RunWith(AndroidJUnit4::class)
class MdbxRetirementUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)

    @Test
    fun retiredDetailsExposeUpgradeAndNoOrdinaryDatabaseActions() {
        var upgrades = 0
        var ordinaryActions = 0
        val ordinary = { ordinaryActions++; Unit }
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    MdbxVaultDetailPage(
                        database = LocalMdbxDatabase(id = 12, name = "MDBX1 fixture", filePath = "fixture.mdbx",
                            sourceType = MdbxSourceType.REMOTE_WEBDAV.name),
                        isDefault = true, conflictCount = 1, diagnostics = null,
                        onSync = ordinary, onShowConflicts = ordinary, onShowHealth = ordinary,
                        onShowSnapshots = ordinary, onShowCommitHistory = ordinary,
                        onShowAttachments = ordinary, onShowMaintenance = ordinary,
                        onMigrate = { upgrades++ }, onSetDefault = ordinary, onDelete = {}
                    )
                }
            }
        }
        compose.onNodeWithText(label(R.string.mdbx_legacy_unavailable_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.mdbx_legacy_upgrade_action)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.mdbx_set_default)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.mdbx_sync_status_label)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.mdbx_ui_manager_history_title)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, upgrades); assertEquals(0, ordinaryActions) }
        saveImage("mdbx1-retirement-native")
        compose.onNodeWithText(label(R.string.mdbx_legacy_remote_copy_warning)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun creationOptionsOfferOnlyMdbx2() {
        compose.setContent {
            MaterialTheme {
                MdbxEngineTypeSection(MdbxEngineType.RUST_MDBX2, {}, remote = false, selectedTigaMode = MdbxTigaMode.SKY)
            }
        }
        compose.onNodeWithText(label(R.string.mdbx_ui_database_options)).performClick()
        compose.onNodeWithText(label(R.string.mdbx_legacy_creation_disabled)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.mdbx_legacy_import_option)).assertDoesNotExist()
    }

    @Test
    fun openOptionsClearlyKeepLegacyOnlyForUpgrade() {
        var selected = MdbxEngineType.RUST_MDBX2
        compose.setContent {
            MaterialTheme {
                MdbxEngineTypeSection(MdbxEngineType.RUST_MDBX2, { selected = it }, remote = true, allowLegacyImport = true)
            }
        }
        compose.onNodeWithText(label(R.string.mdbx_ui_database_options)).performClick()
        compose.onNodeWithText(label(R.string.mdbx_legacy_import_option)).performClick()
        compose.runOnIdle { assertEquals(MdbxEngineType.KOTLIN_MDBX1, selected) }
    }

    private fun saveImage(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
