package takagi.ru.monica.passkey

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.repository.PasskeyRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.PasskeyListScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.SavedCategoryFilterState
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.PasskeyViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

class PasskeyFilterNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun returningFromEntryKeepsSelectedDatabase(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsManager(context)
        val previous = settings.categoryFilterStateFlow(SettingsManager.CategoryFilterScope.PASSKEY).first()
        settings.updateCategoryFilterState(SettingsManager.CategoryFilterScope.PASSKEY, SavedCategoryFilterState())
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        var model = PasskeyViewModel(PasskeyRepository(database.passkeyDao()), context = context,
            strings = AppLocaleStringResolver(context))
        val passwordModel = PasswordViewModel(PasswordRepository(database.passwordEntryDao()),
            SecurityManager(context), SecureItemRepository(database.secureItemDao()), strings = AppLocaleStringResolver(context))
        val screen = mutableStateOf("list")
        val local = "Filter local fixture"
        val remote = "Filter Bitwarden fixture"
        try {
            database.passkeyDao().insert(fixture("local", local))
            database.passkeyDao().insert(fixture("bitwarden", remote).copy(bitwardenVaultId = 909L))
            compose.setContent {
                MonicaTheme {
                    when (screen.value) {
                        "list" -> PasskeyListScreen(model, onScanFidoQr = {}, passwordViewModel = passwordModel,
                            onPasskeyClick = { assertEquals("local", it.credentialId); screen.value = "detail" })
                        "detail" -> Button(onClick = { screen.value = "list" }) { Text("Return to fixture list") }
                    }
                }
            }
            awaitText(remote)
            compose.onNodeWithContentDescription(context.getString(R.string.category)).performClick()
            compose.onNodeWithText(context.getString(R.string.filter_monica)).performClick()
            if (compose.onAllNodes(isPopup()).fetchSemanticsNodes().isNotEmpty()) {
                androidx.test.espresso.Espresso.pressBack()
            }
            compose.onNodeWithText(remote).assertDoesNotExist()
            withTimeout(10_000) {
                settings.categoryFilterStateFlow(SettingsManager.CategoryFilterScope.PASSKEY).first { it.type == "local" }
            }
            repeat(3) {
                compose.onNode(hasText(local) and hasClickAction()).performClick()
                compose.onNodeWithText("Return to fixture list").performClick()
                awaitText(local)
                compose.onNodeWithText(remote).assertDoesNotExist()
                assertEquals("local", settings.categoryFilterStateFlow(SettingsManager.CategoryFilterScope.PASSKEY).first().type)
            }
            compose.runOnIdle { screen.value = "closed" }
            model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            model = PasskeyViewModel(PasskeyRepository(database.passkeyDao()), context = context,
                strings = AppLocaleStringResolver(context))
            withTimeout(10_000) { model.categoryFilter.first { it.type == "local" } }
            compose.runOnIdle { screen.value = "list" }
            awaitText(local)
            compose.onNodeWithText(remote).assertDoesNotExist()
        } finally {
            compose.runOnIdle { screen.value = "closed" }
            model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            passwordModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
            settings.updateCategoryFilterState(SettingsManager.CategoryFilterScope.PASSKEY, previous)
        }
    }

    @Test fun returningKeepsBitwardenFolder() = verifySavedFolder(
        SavedCategoryFilterState(type = "bitwarden_folder", primaryId = 909L, text = "selected-folder"),
        fixture("selected", "Selected folder fixture").copy(bitwardenVaultId = 909L, bitwardenFolderId = "selected-folder"),
        fixture("other", "Other folder fixture").copy(bitwardenVaultId = 909L, bitwardenFolderId = "other-folder")
    )

    @Test fun returningKeepsMdbxFolder() = verifySavedFolder(
        SavedCategoryFilterState(type = "mdbx_folder", primaryId = 909L, text = "selected-folder"),
        fixture("selected", "Selected folder fixture").copy(mdbxDatabaseId = 909L, mdbxFolderId = "selected-folder"),
        fixture("other", "Other folder fixture").copy(mdbxDatabaseId = 909L, mdbxFolderId = "other-folder")
    )

    private fun verifySavedFolder(saved: SavedCategoryFilterState, selected: PasskeyEntry, other: PasskeyEntry): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsManager(context)
        val filterScope = SettingsManager.CategoryFilterScope.PASSKEY
        val previous = settings.categoryFilterStateFlow(filterScope).first()
        settings.updateCategoryFilterState(filterScope, saved)
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val model = PasskeyViewModel(PasskeyRepository(database.passkeyDao()), context = context,
            strings = AppLocaleStringResolver(context))
        val passwordModel = PasswordViewModel(PasswordRepository(database.passwordEntryDao()),
            SecurityManager(context), SecureItemRepository(database.secureItemDao()), strings = AppLocaleStringResolver(context))
        val screen = mutableStateOf("list")
        try {
            database.localMdbxDatabaseDao().insertDatabase(LocalMdbxDatabase(id = 909L,
                name = "Filter fixture", filePath = "unused-filter-fixture", engineType = "RUST_MDBX2"))
            database.passkeyDao().insertAll(listOf(selected, other, fixture("local", "Unselected local fixture")))
            withTimeout(10_000) { model.categoryFilter.first { it == saved } }
            compose.setContent {
                MonicaTheme {
                    when (screen.value) {
                        "list" -> PasskeyListScreen(model, onScanFidoQr = {}, passwordViewModel = passwordModel,
                            onPasskeyClick = { assertEquals("selected", it.credentialId); screen.value = "detail" })
                        "detail" -> Button(onClick = { screen.value = "list" }) { Text("Return to folder fixture") }
                    }
                }
            }
            repeat(3) {
                awaitText(selected.rpName)
                compose.onNodeWithText(other.rpName).assertDoesNotExist()
                compose.onNodeWithText("Unselected local fixture").assertDoesNotExist()
                compose.onNode(hasText(selected.rpName) and hasClickAction()).performClick()
                compose.onNodeWithText("Return to folder fixture").performClick()
            }
            awaitText(selected.rpName)
            compose.onNodeWithText(other.rpName).assertDoesNotExist()
            compose.onNodeWithText("Unselected local fixture").assertDoesNotExist()
            assertEquals(saved, settings.categoryFilterStateFlow(filterScope).first())
        } finally {
            compose.runOnIdle { screen.value = "closed" }
            model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            passwordModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
            settings.updateCategoryFilterState(filterScope, previous)
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun fixture(id: String, title: String) = PasskeyEntry(
        credentialId = id, rpId = "filter.test", rpName = title, userId = "fixture",
        userName = "$id@filter.test", userDisplayName = title, publicKey = "", privateKeyAlias = ""
    )
}
