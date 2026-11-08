package takagi.ru.monica.bitwarden

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.*
import takagi.ru.monica.bitwarden.ui.BitwardenSettingsScreen
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.ui.theme.MonicaTheme

@RunWith(AndroidJUnit4::class)
class BitwardenSyncIssuesUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun actualSettingsOpensSearchableAccountIssuesAndLockRevokesDialogAndLateWriter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = BitwardenRepository.getInstance(context)
        val database = PasswordDatabase.getDatabase(context)
        val uuid = UUID.randomUUID().toString()
        val id = runBlocking { database.bitwardenVaultDao().insert(BitwardenVault(
            email = "$uuid@example.invalid", accountKey = uuid, displayName = "Synthetic sync account",
            isLocked = false, serverUrl = "http://127.0.0.1:1", apiUrl = "http://127.0.0.1:1",
            identityUrl = "http://127.0.0.1:1")) }
        @Suppress("UNCHECKED_CAST")
        fun <T> cache(name: String) = repository.javaClass.getDeclaredField(name).apply { isAccessible = true }
            .get(repository) as ConcurrentHashMap<Long, T>
        val key = SymmetricCryptoKey(ByteArray(32) { 51 }, ByteArray(32) { 52 })
        cache<SymmetricCryptoKey>("symmetricKeyCache")[id] = key
        cache<String>("accessTokenCache")[id] = "synthetic-issues-token"
        val reports = repository.javaClass.getDeclaredField("syncIssues").apply { isAccessible = true }
            .get(repository) as BitwardenSyncIssues
        val attempt = reports.begin(id)
        attempt.record("first-remote-id", SyncIssueKind.CIPHER, SyncIssuePhase.DOWNLOAD, SyncIssueReason.DECRYPTION, "Synthetic first item")
        attempt.record("second-remote-id", SyncIssueKind.CIPHER, SyncIssuePhase.UPDATE, SyncIssueReason.CONFLICT, "Synthetic second item")
        attempt.finish()
        val other = reports.begin(id + 100000)
        other.record("other-account-id", SyncIssueKind.CIPHER, SyncIssuePhase.DOWNLOAD, SyncIssueReason.REJECTED, "Other private item")
        other.finish()
        val viewModels = ViewModelStore()
        val previousAutoSync = repository.isAutoSyncEnabled
        repository.isAutoSyncEnabled = false
        try {
            assertTrue("The synthetic account must be unlocked before opening settings", repository.isVaultUnlocked(id))
            assertEquals(2, repository.syncIssueReports.value[id]?.items?.size)
            val localized = android.view.ContextThemeWrapper(compose.activity, compose.activity.theme).apply {
                applyOverrideConfiguration(Configuration(compose.activity.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) })
            }
            val model = ViewModelProvider(viewModels,
                ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))[BitwardenViewModel::class.java]
            compose.setContent {
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                    MonicaTheme { Surface {
                        BitwardenSettingsScreen(model, onNavigateBack = {}, onNavigateToLogin = {},
                            onNavigateToVault = {}, onNavigateToAccount = {})
                    } }
                }
            }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("Synthetic sync account").fetchSemanticsNodes().isNotEmpty()
            }
            // Account actions are inside the collapsed card, as in the real settings flow.
            compose.onNodeWithText("Synthetic sync account").performScrollTo().performClick()
            val button = localized.getString(R.string.bitwarden_sync_issues_button, 2)
            compose.waitUntil(10000) { compose.onAllNodesWithText(button).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(button).performScrollTo().performClick()
            compose.onNodeWithTag("bitwarden_sync_issues").assertIsDisplayed()
            compose.onNodeWithTag("bitwarden_sync_issues_search").performScrollTo().performTextInput("second-remote-id")
            compose.onNodeWithText("Synthetic second item").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Synthetic first item").assertDoesNotExist()
            compose.onNodeWithText("Other private item").assertDoesNotExist()
            if (InstrumentationRegistry.getArguments().getString("syncIssuesScreenshots") == "true") {
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                compose.waitForIdle()
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val folder = java.io.File(context.getExternalFilesDir(null), "sync-issues-$uuid").apply { mkdirs() }
                    java.io.File(folder, "actual-issues-sheet.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bitmap.recycle() }
            }
            val generation = repository.getVaultSessionGeneration(id)
            val late = reports.begin(id)
            runBlocking { repository.forceLock(id) }
            late.record("late", SyncIssueKind.CIPHER, SyncIssuePhase.DOWNLOAD, SyncIssueReason.REJECTED, "Late private item")
            late.finish()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("bitwarden_sync_issues").fetchSemanticsNodes().isEmpty() }
            assertNull(repository.syncIssueReports.value[id])
            assertTrue(repository.getVaultSessionGeneration(id) > generation)
        } finally {
            compose.runOnIdle { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)); viewModels.clear() }
            runBlocking { repository.forceLock(id); database.bitwardenVaultDao().deleteById(id) }
            reports.clear(id + 100000)
            repository.isAutoSyncEnabled = previousAutoSync
            key.clear()
        }
    }
}
