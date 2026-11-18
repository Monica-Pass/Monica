package takagi.ru.monica.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.TotpCategoryFilter
import takagi.ru.monica.viewmodel.TotpViewModel

class BitwardenTotpSourceUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rootOtpCardFollowsBitwardenSource() = checkSourceCards(1f)
    @Test fun rootOtpCardFollowsBitwardenSourceWithLargeText() = checkSourceCards(1.5f)

    private fun checkSourceCards(fontScale: Float) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(takagi.ru.monica.security.SecureStorageStartup.prepareForEntry(context) is
            takagi.ru.monica.security.SecureStartupResult.Ready)
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val passwords = PasswordRepository(db.passwordEntryDao())
        val items = SecureItemRepository(db.secureItemDao())
        val strings = AppLocaleStringResolver(context)
        val model = TotpViewModel(items, passwords, strings = strings)
        val passwordModel = PasswordViewModel(passwords, SecurityManager(context), items, strings = strings)
        val visible = mutableStateOf(true)
        val localTitle = "Issue185 local OTP"
        val remoteTitle = "Issue185 Bitwarden root OTP"
        try {
            val base = PasswordEntry(title = localTitle, website = "https://fixture.example",
                username = "fixture@example.test", password = "", authenticatorKey = "JBSWY3DPEHPK3PXP")
            val localId = db.passwordEntryDao().insertPasswordEntry(base)
            val remoteId = db.passwordEntryDao().insertPasswordEntry(base.copy(title = remoteTitle,
                bitwardenVaultId = 909, bitwardenCipherId = "ui-root-cipher",
                bitwardenRevisionDate = "2026-11-18T00:00:00Z"))
            withTimeout(10_000) { model.allTotpItems.first { it.size == 2 } }
            model.setCategoryFilter(TotpCategoryFilter.Local)
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    MonicaTheme {
                        if (visible.value) TotpListContent(model, passwordModel,
                            AppSettings(biometricEnabled = false, disablePasswordVerification = true,
                                iconCardsEnabled = false, validatorVibrationEnabled = false),
                            onAuthenticatorLayoutModeChange = {}, onTotpClick = {}, onDeleteTotp = {},
                            onQuickScanTotp = {}, onScanFidoQr = {},
                            onSelectionModeChange = { _, _, _, _, _, _ -> })
                    }
                }
            }
            awaitRows(model, setOf(-localId))
            awaitText(localTitle)
            compose.onNodeWithText(remoteTitle).assertDoesNotExist()
            model.setCategoryFilter(TotpCategoryFilter.BitwardenVaultUncategorized(909))
            awaitRows(model, setOf(-remoteId))
            awaitText(remoteTitle)
            compose.onNodeWithText(localTitle).assertDoesNotExist()
            model.setCategoryFilter(TotpCategoryFilter.Local)
            awaitRows(model, setOf(-localId))
            awaitText(localTitle)
            compose.onNodeWithText(remoteTitle).assertDoesNotExist()
        } finally {
            compose.runOnIdle { visible.value = false }
            model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            passwordModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
        }
    }

    private suspend fun awaitRows(model: TotpViewModel, ids: Set<Long>) {
        withTimeout(10_000) {
            model.parsedTotpState.first { it.isReady && it.items.map { row -> row.item.id }.toSet() == ids }
        }
    }
    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
