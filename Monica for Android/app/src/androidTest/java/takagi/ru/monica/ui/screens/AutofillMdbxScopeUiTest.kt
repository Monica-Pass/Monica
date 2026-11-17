package takagi.ru.monica.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.AutofillPreferences.AutofillDefaultSourceFilter
import takagi.ru.monica.autofill_ng.ui.PasswordActionDialog
import takagi.ru.monica.autofill_ng.ui.PasswordItemAction
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.ui.theme.MonicaTheme

/** Pure synthetic UI metadata; no database file, account, vault unlock or writes. */
class AutofillMdbxScopeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun mdbxSourceAndExactDatabaseCanBeSelected() {
        var source by mutableStateOf(AutofillDefaultSourceFilter.ALL)
        var databaseId by mutableStateOf<Long?>(null)
        compose.setContent {
            MonicaTheme {
                AutofillSourceSettings(source, null, null, emptyList(), emptyList(),
                    onSourceSelected = { source = it }, onKeepassSelected = {}, onBitwardenSelected = {},
                    mdbxId = databaseId,
                    mdbxDatabases = listOf(LocalMdbxDatabase(id = 18, name = "Synthetic MDBX 18", filePath = "unused")),
                    onMdbxSelected = { databaseId = it })
            }
        }
        compose.onNodeWithTag("autofill-source-setting").performClick()
        compose.onNodeWithText("MDBX", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(AutofillDefaultSourceFilter.MDBX, source) }
        compose.onNodeWithTag("autofill-mdbx-setting").performClick()
        compose.onNodeWithText("Synthetic MDBX 18", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(18L, databaseId) }
        compose.onNodeWithText("Synthetic MDBX 18", substring = true).assertExists()
    }

    @Test fun missingSelectedDatabaseIsNotShownAsAllDatabases() {
        val unavailable = compose.activity.getString(R.string.autofill_selected_source_unavailable)
        val allDatabases = compose.activity.getString(R.string.password_picker_all_databases)
        var selected: Long? = 812
        compose.setContent {
            MonicaTheme {
                AutofillSourceSettings(AutofillDefaultSourceFilter.MDBX, null, null, emptyList(), emptyList(),
                    onSourceSelected = {}, onKeepassSelected = {}, onBitwardenSelected = {},
                    mdbxId = 812, mdbxDatabases = emptyList(), onMdbxSelected = { selected = it })
            }
        }
        compose.onNodeWithText(unavailable, substring = true).assertExists()
        compose.onNodeWithText(allDatabases).assertDoesNotExist()
        compose.runOnIdle { assertEquals(812L, selected) }
    }

    @Test fun nativeMdbxKeepsFillAndCopyButDoesNotOfferUriBinding() {
        var action: PasswordItemAction? = null
        val entry = PasswordEntry(id = -91, title = "Synthetic MDBX login", website = "",
            username = "synthetic", password = "synthetic", mdbxDatabaseId = 18)
        compose.setContent {
            MonicaTheme {
                PasswordActionDialog(entry, "synthetic", null, false,
                    onDismiss = {}, onAction = { action = it })
            }
        }
        compose.onNodeWithTag("autofill_action_bind").assertDoesNotExist()
        compose.onNodeWithTag("autofill_action_default").assertExists().performClick()
        compose.runOnIdle { assertTrue(action is PasswordItemAction.Autofill) }
        compose.onNodeWithTag("autofill_action_copy_password").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(action is PasswordItemAction.CopyPassword) }
    }
}
