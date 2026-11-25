package takagi.ru.monica.security

import android.content.res.Configuration
import android.graphics.Bitmap
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import android.view.KeyEvent
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.UnrecoverableKeyException
import java.util.Locale
import javax.crypto.AEADBadTagException
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.domain.provider.PasswordSource
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.ui.LocalUiSecurityManager
import takagi.ru.monica.ui.components.UnreadablePasswordField
import takagi.ru.monica.ui.model.PasswordReadFailure
import takagi.ru.monica.ui.model.SecretValueState
import takagi.ru.monica.ui.screens.PasswordDetailScreen
import takagi.ru.monica.ui.screens.PasswordItemRow
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel

class PasswordReadHelpInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun androidCausesAreSpecificWithoutGuessingExternalTools() {
        assertEquals(PasswordReadFailure.AUTH_REQUIRED, PasswordReadFailure.from(Exception(UserNotAuthenticatedException())))
        assertEquals(PasswordReadFailure.KEY_INVALIDATED, PasswordReadFailure.from(Exception(KeyPermanentlyInvalidatedException())))
        assertEquals(PasswordReadFailure.KEY_UNAVAILABLE, PasswordReadFailure.from(UnrecoverableKeyException()))
        assertEquals(PasswordReadFailure.AUTHENTICATION_FAILED, PasswordReadFailure.from(Exception(AEADBadTagException())))
        assertEquals(PasswordReadFailure.UNKNOWN, PasswordReadFailure.from(Exception("OMK Keybox")))
    }

    @Test fun realDamagedRecordReachesDetailDialogWithoutChangingOriginal() {
        runBlocking { assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready) }
        RecoveryTestFixture().use { f ->
            val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
            val model = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()), f.manager,
                customFieldRepository = CustomFieldRepository(db.customFieldDao()), strings = AppLocaleStringResolver(context))
            try {
                val bytes = Base64.decode(f.record.removePrefix("MDK|"), Base64.NO_WRAP)
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                val damaged = "MDK|" + Base64.encodeToString(bytes, Base64.NO_WRAP)
                val entry = PasswordEntry(title = "Synthetic read failure", username = "synthetic", password = damaged, website = "")
                val id = runBlocking { db.passwordEntryDao().insertPasswordEntry(entry) }
                val before = f.prefs.all.toMap()
                val result = model.inspectDetailSecretState(entry.copy(id = id)) as SecretValueState.Unreadable
                assertEquals(PasswordReadFailure.AUTHENTICATION_FAILED, result.reason)
                assertTrue(model.inspectDetailSecretState(entry.copy(password = f.record)) is SecretValueState.Available)
                compose.setContent { CompositionLocalProvider(LocalUiSecurityManager provides f.manager) {
                    MaterialTheme { Surface { PasswordDetailScreen(model, passwordId = id, biometricEnabled = false,
                        onNavigateBack = {}, onEditPassword = {}) } }
                } }
                compose.waitUntil(20000) { compose.onAllNodesWithTag("unreadable_password_help").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("unreadable_password_help").performScrollTo().performClick()
                compose.onNodeWithTag("password_read_reason").assertTextEquals(context.getString(R.string.password_read_auth_failed))
                compose.onNodeWithTag("password_read_environment_hint").assertExists()
                compose.onNodeWithText(damaged).assertDoesNotExist()
                compose.onNodeWithTag("password_read_help_close").performClick()
                assertEquals(damaged, runBlocking { db.passwordEntryDao().getPasswordEntryById(id) }!!.password)
                assertEquals(before, f.prefs.all)
                compose.runOnIdle { }
            } finally { model.viewModelScope.cancel(); db.close() }
        }
    }

    @Test fun chineseLargeTextDialogClosesAndCanReopen() = largeText("zh-CN", false)
    @Test fun englishLargeTextDarkDialogClosesAndCanReopen() = largeText("en", true)

    private fun largeText(language: String, dark: Boolean) {
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        })
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.fillMaxSize()) { Box(Modifier.padding(16.dp)) {
                        UnreadablePasswordField(localized.getString(R.string.password), PasswordSource.Local,
                            PasswordReadFailure.AUTHENTICATION_FAILED)
                    } }
                }
            }
        }
        compose.onNodeWithTag("unreadable_password_help").performClick()
        compose.onNodeWithTag("password_read_help_close").assertIsDisplayed()
        compose.onNodeWithTag("password_read_environment_hint").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.password_read_data_retained)).performScrollTo().assertIsDisplayed()
        File(context.filesDir, "password-read-help-$language.png").outputStream().use {
            compose.onNodeWithTag("password_read_help_dialog").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("password_read_help_close").performClick()
        compose.onNodeWithTag("password_read_help_dialog").assertDoesNotExist()
        compose.onNodeWithTag("unreadable_password_help").performClick()
        compose.onNodeWithTag("password_read_help_dialog").assertIsDisplayed()
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = android.os.SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(downTime, android.os.SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0).apply {
                source = android.view.InputDevice.SOURCE_KEYBOARD
            }
            assertTrue("System Back must be delivered", automation.injectInputEvent(event, true))
        }
        compose.onNodeWithTag("password_read_help_dialog").assertDoesNotExist()
    }

    @Test fun lockAndChangedReasonDismissStaleHelp() {
        var reason by mutableStateOf(PasswordReadFailure.UNKNOWN)
        compose.setContent { MaterialTheme { Surface {
            UnreadablePasswordField("Synthetic", PasswordSource.Local, reason)
        } } }
        compose.onNodeWithTag("unreadable_password_help").performClick()
        compose.runOnIdle { SessionManager.markLocked() }
        compose.onNodeWithTag("password_read_help_dialog").assertDoesNotExist()
        compose.onNodeWithTag("unreadable_password_help").performClick()
        compose.runOnIdle { reason = PasswordReadFailure.KEY_INVALIDATED }
        compose.onNodeWithTag("password_read_help_dialog").assertDoesNotExist()
        compose.onNodeWithTag("unreadable_password_help").performClick()
        compose.onNodeWithTag("password_read_reason").assertTextEquals(context.getString(R.string.password_read_key_invalidated))
    }

    @Test fun authenticationRequiredDoesNotSuggestKeyEnvironmentChanges() {
        compose.setContent { MaterialTheme { Surface {
            UnreadablePasswordField("Synthetic", PasswordSource.Local, PasswordReadFailure.AUTH_REQUIRED)
        } } }
        compose.onNodeWithTag("unreadable_password_help").performClick()
        compose.onNodeWithTag("password_read_reason").assertTextEquals(context.getString(R.string.password_read_auth_required))
        compose.onNodeWithTag("password_read_environment_hint").assertDoesNotExist()
    }

    @Test fun backgroundDismissesHelpAfterReasonChanges() {
        lateinit var owner: androidx.lifecycle.LifecycleOwner
        lateinit var testLifecycle: androidx.lifecycle.LifecycleRegistry
        var reason by mutableStateOf(PasswordReadFailure.UNKNOWN)
        compose.runOnIdle {
            owner = object : androidx.lifecycle.LifecycleOwner {
                override val lifecycle get() = testLifecycle
            }
            testLifecycle = androidx.lifecycle.LifecycleRegistry(owner)
            testLifecycle.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        }
        compose.setContent {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                MaterialTheme { UnreadablePasswordField("Synthetic", PasswordSource.Local, reason) }
            }
        }
        compose.runOnIdle { reason = PasswordReadFailure.KEY_UNAVAILABLE }
        compose.onNodeWithTag("unreadable_password_help").performClick()
        compose.runOnIdle { testLifecycle.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.onNodeWithTag("password_read_help_dialog").assertDoesNotExist()
        compose.runOnIdle { testLifecycle.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.onNodeWithTag("password_read_help_dialog").assertDoesNotExist()
    }

    @Test fun groupedPasswordsKeepTheReasonOfTheClickedRow() {
        val rows = (1L..2L).map { PasswordEntry(id = it, title = "Synthetic", username = "", password = "", website = "") }
        compose.setContent { MaterialTheme { Surface {
            takagi.ru.monica.ui.screens.ProjectCredentialDetailCard(rows, emptyMap(), emptyMap(),
                rows.associate { it.id to PasswordSource.Local }, context, takagi.ru.monica.data.AppSettings(),
                onCreateSend = null, onEdit = {}, onDelete = {}, onResyncUnreadable = {}, isResyncingUnreadable = false,
                passwordReadFailures = mapOf(1L to PasswordReadFailure.UNKNOWN, 2L to PasswordReadFailure.KEY_UNAVAILABLE))
        } } }
        compose.onAllNodesWithTag("unreadable_password_help")[1].performClick()
        compose.onNodeWithTag("password_read_reason").assertTextEquals(context.getString(R.string.password_read_key_unavailable))
        compose.onNodeWithTag("password_read_help_close").performClick()
        compose.onAllNodesWithTag("unreadable_password_help")[0].performClick()
        compose.onNodeWithTag("password_read_reason").assertTextEquals(context.getString(R.string.password_read_unknown))
    }

    @Test fun nonlocalReasonsRemainSourceSpecificAndResyncStillWorks() {
        var source by mutableStateOf<PasswordSource>(PasswordSource.Bitwarden(3, "synthetic-cipher"))
        var resync = 0
        compose.setContent { MaterialTheme { Surface {
            PasswordItemRow(PasswordEntry(id = 42, title = "Synthetic", username = "", password = "", website = ""),
                displayPassword = "", unavailableSource = source, onResyncUnreadable = { resync++ },
                isResyncingUnreadable = false, showSecurityAnalysis = true, index = 1, showIndex = false,
                onDelete = {}, context = context, canDelete = false, onCreateSend = null)
        } } }
        for ((s, message) in listOf(
            PasswordSource.Bitwarden(3, "synthetic-cipher") to R.string.bitwarden_password_unreadable_display,
            PasswordSource.KeePass(3, "synthetic-entry") to R.string.password_read_source_unavailable,
            PasswordSource.Mdbx(3) to R.string.password_read_source_unavailable,
            PasswordSource.Conflict(true, true) to R.string.password_owner_conflict_display)) {
            compose.runOnIdle { source = s }
            compose.onNodeWithTag("unreadable_password_help").performClick()
            compose.onNodeWithTag("password_read_reason").assertTextEquals(context.getString(message))
            compose.onNodeWithTag("password_read_environment_hint").assertDoesNotExist()
            compose.onNodeWithTag("password_read_help_close").performClick()
        }
        compose.runOnIdle { source = PasswordSource.Bitwarden(3, "synthetic-cipher") }
        compose.onNodeWithText(context.getString(R.string.bitwarden_password_resync_action)).performClick()
        compose.runOnIdle { assertEquals(1, resync) }
    }

    @Test fun readableAndEmptyPasswordsDoNotExposeFailureHelp() {
        var value by mutableStateOf("Synthetic readable value")
        compose.setContent { MaterialTheme { Surface {
            PasswordItemRow(PasswordEntry(id = 43, title = "Synthetic", username = "", password = "", website = ""),
                displayPassword = value, unavailableSource = null, onResyncUnreadable = {},
                isResyncingUnreadable = false, showSecurityAnalysis = true, index = 1, showIndex = false,
                onDelete = {}, context = context, canDelete = false, onCreateSend = null)
        } } }
        compose.onNodeWithTag("unreadable_password_help").assertDoesNotExist()
        compose.runOnIdle { value = "" }
        compose.onNodeWithTag("unreadable_password_help").assertDoesNotExist()
    }

    @Test fun allSupportedLanguagesHaveEveryHelpMessage() {
        val ids = listOf(R.string.password_read_help_title, R.string.password_read_help_inline,
            R.string.password_read_auth_required, R.string.password_read_key_invalidated,
            R.string.password_read_key_unavailable, R.string.password_read_auth_failed,
            R.string.password_read_unknown, R.string.password_read_source_unavailable,
            R.string.password_read_environment_hint, R.string.password_read_data_retained)
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        for (tag in listOf("zh", "zh-CN", "zh-TW", "zh-Hant", "lzh", "de", "es", "fr", "id", "it", "ja", "ko", "pl", "pt-PT", "pt-BR", "ru", "tr", "vi", "zh-NY", "zh-XB")) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            for (id in ids) {
                assertTrue(tag, localized.getString(id).isNotBlank())
                assertNotEquals("$tag / $id", english.getString(id), localized.getString(id))
            }
            val hint = localized.getString(R.string.password_read_environment_hint)
            assertTrue(tag, hint.contains("OMK") && hint.contains("OhMyKeymint") && hint.contains("Keybox"))
        }
    }
}
