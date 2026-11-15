package takagi.ru.monica.ui.screens

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.credentialexchange.ImportDestination
import takagi.ru.monica.credentialexchange.TransferFixture
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.repository.DatabaseManagerLocation
import takagi.ru.monica.repository.DatabaseManagerRepository
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.MdbxViewModel

/** Drives the full manager with actual local categories; names containing Bitwarden remain local. */
class DatabaseManagerLocalHierarchyUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun wake() {
        compose.activity.setShowWhenLocked(true)
        compose.activity.setTurnScreenOn(true)
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
    }

    private suspend fun withScreen(f: TransferFixture, body: suspend () -> Unit) {
        val app = f.context.applicationContext as android.app.Application
        val db = f.db
        val mdbx = MdbxViewModel(app, db.localMdbxDatabaseDao(), db.mdbxRemoteSourceDao(),
            db.passwordEntryDao(), db.secureItemDao(), db.passkeyDao(), db.attachmentDao(), db.customFieldDao(), f.security)
        val keepass = LocalKeePassViewModel(app, db.localKeePassDatabaseDao(), f.security)
        var show by mutableStateOf(true)
        try {
            compose.setContent {
                if (show) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                        MaterialTheme { DatabaseManagerScreen(ImportDestination.Local.key, mdbx, keepass, {}) }
                    }
                }
            }
            body()
        } finally {
            compose.runOnIdle { show = false }
            mdbx.viewModelScope.cancel()
            keepass.viewModelScope.cancel()
        }
    }

    private fun waitForRow(id: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("manager-row-0-$id").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("manager-row-0-$id").assertIsDisplayed()
    }

    private fun open(id: String) {
        waitForRow(id)
        compose.onNodeWithTag("manager-row-0-$id").performClick()
    }

    private fun assertPath(vararg names: String) {
        names.forEach { name ->
            compose.onNodeWithTag("manager-path-0").assert(hasAnyDescendant(hasText(name)))
        }
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "local-manager-folder-test").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun actualLocalScreenNavigatesParentsChildrenAndHistory() = runBlocking<Unit> {
        val f = TransferFixture()
        try {
            val root = "${f.prefix}-Bitwarden测试文件夹"
            val rootId = "local-path:$root"
            val childId = "local-path:$root/子目录"
            val leaf = f.db.categoryDao().insert(Category(name = "$root/子目录/第三层"))
            val leafId = "folder:$leaf"
            val record = f.db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "${f.prefix}-inside",
                website = "https://example.invalid", username = "synthetic", password = "hidden synthetic value", categoryId = leaf))
            val original = f.db.passwordEntryDao().getPasswordEntryById(record)
            val categories = f.db.categoryDao().getDeviceSyncCategories()
            withScreen(f) {
                waitForRow(rootId)
                compose.onNodeWithTag("manager-title-0-$rootId", useUnmergedTree = true).assertTextEquals(root)
                compose.onNodeWithTag("manager-row-0-$childId").assertDoesNotExist()
                compose.onNodeWithTag("manager-row-0-$leafId").assertDoesNotExist()
                screenshot("nested-root")
                open(rootId)
                waitForRow(childId)
                compose.onNodeWithTag("manager-title-0-$childId", useUnmergedTree = true).assertTextEquals("子目录")
                compose.onNodeWithTag("manager-menu-0-$childId").assertIsDisplayed()
                compose.onNodeWithTag("manager-row-0-$leafId").assertDoesNotExist()
                assertPath(root)
                screenshot("nested-child")
                open(childId)
                waitForRow(leafId)
                compose.onNodeWithTag("manager-title-0-$leafId", useUnmergedTree = true).assertTextEquals("第三层")
                assertPath(root, "子目录")
                open(leafId)
                waitForRow("password:$record")
                compose.onNodeWithText("hidden synthetic value").assertDoesNotExist()
                assertPath(root, "子目录", "第三层")
                screenshot("nested-entry")
                compose.onNodeWithTag("manager-action-parent").performClick()
                waitForRow(leafId)
                compose.onNodeWithTag("manager-action-back").assertIsEnabled().performClick()
                waitForRow("password:$record")
                compose.onNodeWithTag("manager-action-forward").assertIsEnabled().performClick()
                waitForRow(leafId)
                compose.onNodeWithTag("manager-action-parent").performClick()
                waitForRow(childId)
                compose.onNodeWithTag("manager-action-parent").performClick()
                waitForRow(rootId)
                compose.onNodeWithTag("manager-row-0-$leafId").assertDoesNotExist()
            }
            assertEquals(categories, f.db.categoryDao().getDeviceSyncCategories())
            assertEquals(original, f.db.passwordEntryDao().getPasswordEntryById(record))
        } finally { f.close() }
    }

    @Test fun refreshAfterImplicitFolderMaterializationPreservesLocationAndOldHistory() = runBlocking<Unit> {
        val f = TransferFixture()
        try {
            val repo = DatabaseManagerRepository(f.context)
            val local = DatabaseManagerLocation(ImportDestination.Local)
            val root = "${f.prefix}-materialized"
            val virtualId = "local-path:$root"
            val child = f.db.categoryDao().insert(Category(name = "$root/child"))
            val childId = "folder:$child"
            val record = f.db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "${f.prefix}-moving",
                website = "https://example.invalid", username = "synthetic", password = "hidden synthetic value"))
            val original = f.db.passwordEntryDao().getPasswordEntryById(record)!!
            withScreen(f) {
                open(virtualId)
                open(childId)
                // Retain the old virtual parent in navigation history before it gets a real row.
                compose.onNodeWithTag("manager-action-parent").performClick()
                waitForRow(childId)
                val moved = repo.transfer(local, local.copy(folderId = virtualId), setOf("password:$record"), false,
                    repo.browse(local).revision)
                assertEquals(moved.failures.toString(), 1, moved.completed)
                val actualParent = f.db.categoryDao().getDeviceSyncCategories().single { it.name == root }
                compose.onNodeWithContentDescription(f.context.getString(R.string.refresh)).performClick()
                waitForRow("password:$record")
                waitForRow(childId)
                assertPath(root)
                compose.onNodeWithTag("manager-row-0-folder:${actualParent.id}").assertDoesNotExist()
                screenshot("materialized-parent")
                compose.onNodeWithTag("manager-action-back").performClick()
                compose.onNodeWithTag("manager-row-0-password:$record").assertDoesNotExist()
                assertPath(root, "child")
                compose.onNodeWithTag("manager-action-back").performClick()
                waitForRow("password:$record")
                waitForRow(childId)
                assertPath(root)
                compose.onNodeWithTag("manager-action-forward").performClick()
                compose.onNodeWithTag("manager-row-0-password:$record").assertDoesNotExist()
                assertPath(root, "child")
                compose.onNodeWithTag("manager-action-parent").performClick()
                waitForRow("password:$record")
                assertEquals(original.copy(categoryId = actualParent.id), f.db.passwordEntryDao().getPasswordEntryById(record))
                assertEquals(1, f.db.categoryDao().getDeviceSyncCategories().count { it.name == root })
            }
        } finally { f.close() }
    }
}
