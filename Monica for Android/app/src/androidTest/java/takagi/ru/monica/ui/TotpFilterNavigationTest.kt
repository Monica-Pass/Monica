package takagi.ru.monica.ui

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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.SavedCategoryFilterState
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.TotpCategoryFilter
import takagi.ru.monica.viewmodel.TotpViewModel

class TotpFilterNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun returningAndRecreatingAuthenticatorKeepDatabaseFilter(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsManager(context)
        val filterScope = SettingsManager.CategoryFilterScope.TOTP
        val previous = settings.categoryFilterStateFlow(filterScope).first()
        settings.updateCategoryFilterState(filterScope, SavedCategoryFilterState(type = "local"))
        val database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val passwords = PasswordRepository(database.passwordEntryDao())
        val items = SecureItemRepository(database.secureItemDao())
        val models = mutableListOf<TotpViewModel>()
        fun newModel() = TotpViewModel(items, passwords, context = context,
            strings = AppLocaleStringResolver(context)).also { models += it }
        val model = mutableStateOf(newModel())
        val passwordModel = PasswordViewModel(passwords, SecurityManager(context), items,
            strings = AppLocaleStringResolver(context))
        val screen = mutableStateOf("list")
        val local = "Filter local OTP fixture"
        val remote = "Filter remote OTP fixture"
        try {
            listOf(local, remote).forEachIndexed { index, title ->
                database.secureItemDao().insertItem(SecureItem(itemType = ItemType.TOTP, title = title,
                    itemData = Json.encodeToString(TotpData(secret = "JBSWY3DPEHPK3PXP", issuer = title,
                        accountName = "fixture@filter.test")), bitwardenVaultId = if (index == 0) null else 909L,
                    bitwardenCipherId = if (index == 0) null else "remote-fixture-cipher",
                    syncStatus = if (index == 0) "NONE" else "SYNCED"))
            }
            withTimeout(10_000) { model.value.categoryFilter.first { it == TotpCategoryFilter.Local } }
            compose.setContent {
                MonicaTheme {
                    when (screen.value) {
                        "list" -> TotpListContent(model.value, passwordModel,
                            AppSettings(biometricEnabled = false, disablePasswordVerification = true,
                                iconCardsEnabled = false, validatorVibrationEnabled = false),
                            onAuthenticatorLayoutModeChange = {}, onTotpClick = { screen.value = "detail" },
                            onDeleteTotp = {}, onQuickScanTotp = {}, onScanFidoQr = {},
                            onSelectionModeChange = { _, _, _, _, _, _ -> })
                        "detail" -> Button(onClick = { screen.value = "list" }) { Text("Return to OTP fixture list") }
                    }
                }
            }
            repeat(3) {
                awaitText(local)
                compose.onNodeWithText(remote).assertDoesNotExist()
                val bounds = compose.onNode(hasText(local) and hasClickAction()).fetchSemanticsNode().boundsInRoot
                compose.onAllNodesWithContentDescription(context.getString(R.string.more_options))
                    .filterToOne(SemanticsMatcher("inside authenticator card") { bounds.contains(it.boundsInRoot.center) })
                    .performClick()
                compose.onNodeWithText(context.getString(R.string.edit)).performClick()
                awaitText("Return to OTP fixture list")
                compose.onNodeWithText("Return to OTP fixture list").performClick()
            }
            compose.runOnIdle { screen.value = "closed" }
            model.value.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            val recreated = newModel()
            withTimeout(10_000) { recreated.categoryFilter.first { it == TotpCategoryFilter.Local } }
            compose.runOnIdle { model.value = recreated; screen.value = "list" }
            awaitText(local)
            compose.onNodeWithText(remote).assertDoesNotExist()
            assertEquals("local", settings.categoryFilterStateFlow(filterScope).first().type)
        } finally {
            compose.runOnIdle { screen.value = "closed" }
            models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            passwordModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
            settings.updateCategoryFilterState(filterScope, previous)
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
}
