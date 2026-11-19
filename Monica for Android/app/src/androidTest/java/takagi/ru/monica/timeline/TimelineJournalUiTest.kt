package takagi.ru.monica.timeline

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.security.*
import takagi.ru.monica.ui.screens.TimelineJournalScreen
import takagi.ru.monica.viewmodel.TimelineJournalViewModel
import java.io.File
import java.util.Locale

class TimelineJournalUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var model: TimelineJournalViewModel
    private lateinit var security: SecurityManager
    private var id = 0L
    private var previousUnlocked = false
    private var trashOpened = false
    @Before fun setup() = runBlocking {
        assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready)
        security = SecurityManager(context)
        previousUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        db = PasswordDatabase.getDatabase(context)
        val row = PasswordEntry(title = "Synthetic timeline mailbox", username = "Synthetic account", website = "https://example.invalid", password = security.encryptData("synthetic-old-secret"))
        id = db.passwordEntryDao().insertPasswordEntry(row)
        db.passwordEntryDao().updatePasswordEntry(row.copy(id = id, password = security.encryptData("synthetic-new-secret")))
        withContext(Dispatchers.Main) { model = TimelineJournalViewModel(context.applicationContext as Application) }
    }
    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { model.foreground(false); model.viewModelScope.cancel() }
        db.passwordEntryDao().deletePasswordEntryById(id)
        if (previousUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
    }
    private fun screen(zh: Boolean = false, large: Boolean = false, dark: Boolean = false, embedded: Boolean = false) {
        val configured = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(if (zh) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH) })
        compose.setContent {
            CompositionLocalProvider(LocalContext provides configured, LocalConfiguration provides configured.resources.configuration,
                LocalDensity provides Density(1f, if (large) 2f else 1f)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.width(if (large) 320.dp else 760.dp).fillMaxHeight()) {
                        TimelineJournalScreen(onTrash = { trashOpened = true }, model = model, bottomActionInset = if (embedded) 104.dp else 0.dp)
                        if (embedded) takagi.ru.monica.ui.components.SwipeableAddFab(
                            fabBottomOffset = 24.dp, onClick = {}, fabContent = { Text("Parent return") })
                    }
                }
            }
        }
        compose.waitUntil(20_000) { model.state.value.entries.any { it.record.action == "UPDATE" && it.targets.any { target -> target.itemId == id } } }
    }
    private fun shot(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.filesDir, "timeline-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun openEdit() {
        compose.runOnIdle {
            model.open(model.state.value.entries.first { it.record.action == "UPDATE" && it.targets.any { target -> target.itemId == id } })
        }
        compose.waitUntil(15_000) { !model.state.value.busy && model.state.value.detail != null }
    }
    @Test fun realListDetailPreviewCancelAndConfirmKeepSecretsHidden() {
        screen()
        shot("home-en-light")
        openEdit()
        compose.onNodeWithText("synthetic-old-secret").assertDoesNotExist()
        compose.onNodeWithText("synthetic-new-secret").assertDoesNotExist()
        shot("detail-en-light")
        compose.onNodeWithText("Preview recovery").performScrollTo().performClick()
        compose.waitUntil(15_000) { model.state.value.plan != null }
        shot("preview-en-light")
        compose.onNodeWithText(context.getString(R.string.cancel)).performScrollTo().performClick()
        assertNull(model.state.value.plan)
        assertEquals("synthetic-new-secret", runBlocking { security.decryptData(db.passwordEntryDao().getPasswordEntryById(id)!!.password) })
        compose.onNodeWithText("Preview recovery").performScrollTo().performClick()
        compose.waitUntil(15_000) { model.state.value.plan != null }
        compose.onNodeWithText("Restore 1 items").performScrollTo().performClick()
        compose.waitUntil(15_000) { model.state.value.restored }
        assertEquals("synthetic-old-secret", runBlocking { security.decryptData(db.passwordEntryDao().getPasswordEntryById(id)!!.password) })
        shot("restored-en-light")
    }
    @Test fun narrowChineseDoubleFontDarkPreviewRemainsReachableAndLockClearsIt() {
        screen(zh = true, large = true, dark = true)
        shot("home-zh-dark-320-2x")
        openEdit()
        compose.onNodeWithText("预览恢复").performScrollTo().performClick()
        compose.waitUntil(15_000) { model.state.value.plan != null }
        shot("preview-zh-dark-320-2x")
        compose.onNodeWithText("取消").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { SessionManager.markLocked() }
        compose.onNodeWithText("Synthetic timeline mailbox").assertDoesNotExist()
        compose.waitUntil { model.state.value.plan == null && model.state.value.detail == null }
        shot("locked-zh-dark")
    }
    @Test fun embeddedReturnFabDoesNotCoverRecoveryOrCancelActions() {
        screen(embedded = true); openEdit()
        compose.onNodeWithText("Preview recovery").performScrollTo().performClick()
        compose.waitUntil(15_000) { model.state.value.plan != null }
        val parent = compose.onNodeWithText("Parent return").onParent().fetchSemanticsNode().boundsInRoot
        for (label in listOf("Restore 1 items", "Cancel")) {
            val node = compose.onNodeWithText(label)
            node.performScrollTo().assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("Parent return FAB must not cover $label", bounds.bottom <= parent.top)
        }
        shot("embedded-preview-fab")
    }

    @Test fun backgroundDiscardsPreviewAndLateContent() {
        screen(); openEdit()
        compose.runOnIdle { model.preview() }
        compose.waitUntil(15_000) { model.state.value.plan != null }
        compose.runOnIdle { model.foreground(false) }
        assertNull(model.state.value.plan)
        assertNull(model.state.value.detail)
        assertTrue(model.state.value.entries.isEmpty())
    }

    @Test fun trashRestoresExactCurrentRowAndRejectsStaleOrLocked() = runBlocking {
        val trash = withContext(Dispatchers.Main) { takagi.ru.monica.viewmodel.TrashViewModel(context.applicationContext as Application) }
        try {
            db.passwordEntryDao().softDelete(id)
            val deleted = db.passwordEntryDao().getPasswordEntryById(id)!!
            fun target(row: PasswordEntry) = takagi.ru.monica.viewmodel.TrashItem(row.id, row.title, ItemType.PASSWORD,
                requireNotNull(row.deletedAt), -1, row)
            suspend fun restore(row: PasswordEntry): Boolean {
                val done = CompletableDeferred<Boolean>()
                trash.restoreItem(target(row)) { done.complete(it) }
                return withTimeout(10_000) { done.await() }
            }
            db.passwordEntryDao().updatePasswordEntry(deleted.copy(notes = "later edit"))
            assertFalse(restore(deleted))
            val current = db.passwordEntryDao().getPasswordEntryById(id)!!
            SessionManager.markLocked()
            assertFalse(restore(current))
            assertEquals(current, db.passwordEntryDao().getPasswordEntryById(id))
            SessionManager.markUnlocked()
            assertTrue(restore(current))
            assertFalse(db.passwordEntryDao().getPasswordEntryById(id)!!.isDeleted)
            assertEquals("later edit", db.passwordEntryDao().getPasswordEntryById(id)!!.notes)
            val events = db.timelineDao().page("ALL", "", 0, Long.MAX_VALUE, Long.MAX_VALUE, "~", 0, 100)
            assertTrue(events.any { it.action == "RESTORE_TRASH" && db.timelineDao().targets(it.id).any { t -> t.itemId == id } })
        } finally { withContext(Dispatchers.Main) { trash.viewModelScope.cancel() } }
    }
}
