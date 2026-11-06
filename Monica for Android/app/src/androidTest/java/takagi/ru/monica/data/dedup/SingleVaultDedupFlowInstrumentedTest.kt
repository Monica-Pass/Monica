package takagi.ru.monica.data.dedup

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.ui.screens.SingleVaultDedupScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.viewmodel.*

/** Production screen/VM/service/Room; only synthetic in-memory records, never a user's database. */
@RunWith(AndroidJUnit4::class)
class SingleVaultDedupFlowInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var vm: SingleVaultDedupViewModel
    private var wasUnlocked = false

    @Before fun setup() = runBlocking {
        wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        val security = SecurityManager(context)
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val values = listOf(listOf("1", "2", "3"), listOf("1", "2"), listOf("2", "3", "4"))
        values.forEachIndexed { index, fields ->
            val id = index + 1L
            db.passwordEntryDao().insertPasswordEntry(PasswordEntry(id = id, title = "Synthetic project", website = "https://example.invalid",
                username = "test", password = security.encryptData("synthetic-password"), createdAt = Date(1), updatedAt = Date(1)))
            db.customFieldDao().insertAll(fields.mapIndexed { order, value -> CustomField(entryId = id,
                title = "Content $value", value = security.encryptData("Value $value"), isProtected = true, sortOrder = order) })
        }
        val service = SingleVaultDedupService(db) { listOf(SingleVaultDedupLocalAdapter(context, db, security, "Synthetic local database")) }
        compose.runOnUiThread { vm = SingleVaultDedupViewModel(service) }
        compose.setContent {
            val state by vm.uiState.collectAsState()
            MonicaTheme { SingleVaultDedupScreen(state, {}, vm::refresh, vm::selectSource, vm::setIncludeArchived,
                vm::analyze, vm::toggleCandidate, vm::reviewSelection, vm::dismissReview, vm::confirmCleanup,
                vm::reviewRecovery, vm::confirmRecovery, vm::cancel) }
        }
        compose.waitUntil(15_000) { !vm.uiState.value.isLoading && vm.uiState.value.sources.isNotEmpty() }
    }

    @After fun cleanup() {
        if (::vm.isInitialized) compose.runOnUiThread { vm.invalidate() }
        if (::db.isInitialized) db.close()
        if (!wasUnlocked) SessionManager.markLocked()
    }

    @Test fun actualScanReviewCleanupAndRestorePreserveAAndC() {
        compose.onNodeWithTag("dedup_entry_notice").assertIsDisplayed()
        compose.onNodeWithTag("single_dedup_primary").assertDoesNotExist()
        assertNull(vm.uiState.value.analysis)
        assertFalse(vm.uiState.value.isAnalyzing)
        assertFalse(vm.uiState.value.isExecuting)
        assertTrue(vm.uiState.value.recoveryBatches.isEmpty())
        runBlocking {
            assertTrue(db.singleVaultDedupLocalDao().passwords().none { it.isDeleted })
            assertEquals(8, db.singleVaultDedupLocalDao().fields().size)
        }
        compose.onNodeWithTag("dedup_entry_confirm").performClick()
        compose.onNodeWithTag("single_dedup_choose_source").performClick()
        compose.onNodeWithTag("single_dedup_source_local").performClick()
        compose.onNodeWithTag("single_dedup_primary").assertIsEnabled().performClick()
        compose.waitUntil(20_000) { vm.uiState.value.analysis != null && !vm.uiState.value.isAnalyzing }
        val analysis = vm.uiState.value.analysis!!
        assertEquals(listOf("password:2"), analysis.candidates.map { it.candidateKey })
        assertEquals("password:1", analysis.candidates.single().keeperKey)
        assertTrue(vm.uiState.value.selectedProjectIds.isEmpty())
        capture("01-analysis")
        compose.onNodeWithTag("single_dedup_check_password:2").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("single_dedup_primary").performClick()
        compose.onNodeWithTag("single_dedup_confirmation").assertExists()
        assertFalse(runBlocking { db.passwordEntryDao().getPasswordEntryById(2)!!.isDeleted })
        capture("02-confirmation")
        compose.onNodeWithTag("single_dedup_confirm").performClick()
        compose.waitUntil(20_000) { !vm.uiState.value.isExecuting && vm.uiState.value.outcome != null }
        assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, vm.uiState.value.outcome!!.status)
        runBlocking {
            assertFalse(db.passwordEntryDao().getPasswordEntryById(1)!!.isDeleted)
            assertTrue(db.passwordEntryDao().getPasswordEntryById(2)!!.isDeleted)
            assertFalse(db.passwordEntryDao().getPasswordEntryById(3)!!.isDeleted)
        }
        capture("03-cleaned")
        compose.waitUntil(15_000) { vm.uiState.value.recoveryBatches.size == 1 }
        val batch = vm.uiState.value.recoveryBatches.single()
        compose.onNodeWithTag("single_dedup_recovery").performScrollTo().performClick()
        compose.onNodeWithTag("single_dedup_restore_${batch.id}").performScrollTo().performClick()
        compose.onNodeWithTag("single_dedup_restore_confirm").assertExists()
        capture("04-restore-confirmation")
        compose.onNodeWithTag("single_dedup_restore_confirm").performClick()
        compose.waitUntil(20_000) { !vm.uiState.value.isExecuting && vm.uiState.value.recoveryBatches.isEmpty() }
        assertEquals(SingleVaultDedupUiOutcomeStatus.SUCCESS, vm.uiState.value.outcome!!.status)
        runBlocking {
            assertTrue(db.singleVaultDedupLocalDao().passwords().none { it.isDeleted })
            assertEquals(8, db.singleVaultDedupLocalDao().fields().size)
            assertEquals("RESTORED", db.singleVaultDedupRecoveryDao().get(batch.id)!!.state)
        }
        capture("05-restored")
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureDedup") != "true") return
        // StateFlow can advance before its frame is drawn. Assert the actual composition, then
        // finish Compose and platform transitions before collecting visual evidence.
        when (name) {
            "01-analysis" -> compose.onNodeWithTag("single_dedup_check_password:2").assertExists()
            "02-confirmation" -> compose.onNodeWithTag("single_dedup_confirmation").assertIsDisplayed()
            "03-cleaned", "05-restored" -> compose.onNodeWithTag("single_dedup_outcome").assertIsDisplayed()
            "04-restore-confirmation" -> compose.onNodeWithTag("single_dedup_restore_confirm").assertIsDisplayed()
        }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(250)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val dir = File(context.filesDir, "dedup-proof").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
