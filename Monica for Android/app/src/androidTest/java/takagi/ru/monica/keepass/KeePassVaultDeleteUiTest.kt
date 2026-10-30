package takagi.ru.monica.keepass

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.vaultv2.*
import takagi.ru.monica.utils.*
import takagi.ru.monica.viewmodel.*

/** Real long press -> select all -> confirm path, backed by a disposable external KDBX. */
class KeePassVaultDeleteUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun vaultSelectAllDeletes300PasswordsInOneNativeSave() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { passwordModel, service, id, room, uri ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val settings = SettingsManager(context)
            val oldFilter = settings.categoryFilterStateFlow("vault_v2").first()
            settings.updateCategoryFilterState("vault_v2", SavedCategoryFilterState(type="keepass_database", primaryId=id))
            val security = SecurityManager(context)
            val registrationDao = PasswordDatabase.getDatabase(context).localKeePassDatabaseDao()
            val registration = checkNotNull(registrationDao.getDatabaseById(id))
            val passwords = PasswordRepository(room.passwordEntryDao())
            val items = SecureItemRepository(room.secureItemDao())
            val models = mutableListOf<ViewModel>()
            fun <T: ViewModel> keep(value: T): T = value.also { models += it }
            val strings = AppLocaleStringResolver(context)
            val totp = keep(TotpViewModel(items, passwords, strings=strings))
            val cards = keep(BankCardViewModel(items, strings=strings))
            val documents = keep(DocumentViewModel(items, strings=strings))
            val addresses = keep(BillingAddressViewModel(items))
            val notes = keep(NoteViewModel(items, strings=strings))
            val passkeys = keep(PasskeyViewModel(PasskeyRepository(room.passkeyDao()), strings=strings))
            val keepass = keep(LocalKeePassViewModel(context.applicationContext as Application, registrationDao, security))
            val settingsModel = keep(SettingsViewModel(settings))
            var show by mutableStateOf(true)
            lateinit var state: VaultV2PaneState
            try {
                compose.setContent {
                    if (show) MaterialTheme {
                        state = rememberVaultV2PaneState(remember { VaultV2RetainedState() })
                        VaultV2Pane(passwordModel, totp, cards, documents, addresses, notes, passkeys,
                            keepassDatabases=listOf(registration), mdbxDatabases=emptyList(), bitwardenVaults=emptyList(),
                            localKeePassViewModel=keepass, settingsViewModel=settingsModel, state=state,
                            onOpenPassword={}, onOpenTotp={}, onOpenBankCard={}, onOpenDocument={},
                            onOpenBillingAddress={}, onOpenNote={}, onOpenPasskey={}, onOpenMdbxCommitHistory={},
                            onOpenHistory={}, onOpenTrashPage={}, onOpenArchivePage={}, onOpenCommonAccountTemplates={},
                            appSettings=AppSettings(vaultOverviewEnabled=false, disablePasswordVerification=true),
                            securityManager=security, biometricEnabled=false, modifier=Modifier.fillMaxSize())
                    }
                }
                // Selected-vault rows use in-memory UUID adapter IDs, never Room IDs.
                val nativeRow = SemanticsMatcher("Selected original-vault password row") {
                    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("vault_item_password:") == true
                }
                var visibleTag: String? = null
                compose.waitForIdle()
                compose.waitUntil(30_000) {
                    visibleTag = compose.onAllNodes(nativeRow).fetchSemanticsNodes(atLeastOneRootRequired=false)
                        .firstOrNull()?.config?.getOrNull(SemanticsProperties.TestTag)
                    visibleTag != null
                }
                val tag = checkNotNull(visibleTag)
                compose.onNodeWithTag(tag).performTouchInput { longClick() }
                compose.onNodeWithContentDescription(context.getString(R.string.select_all)).performClick()
                compose.runOnIdle { assertEquals(300, state.selectionCount) }
                compose.onNodeWithContentDescription(context.getString(R.string.delete)).performClick()
                compose.onNode(hasText(context.getString(R.string.delete)) and hasAnyAncestor(isDialog())).performClick()
                compose.waitUntil(30_000) {
                    compose.onAllNodes(nativeRow).fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty()
                }
                assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
                compose.waitUntil(10_000) { state.selectionCount == 0 }
                compose.onNodeWithTag(tag).assertDoesNotExist()
                KeePassKdbxService.invalidateProcessCache(id)
                val after = service.readPasswordEntries(id).getOrThrow()
                assertEquals(300, after.count { it.isInRecycleBin })
                assertEquals(0, after.count { !it.isInRecycleBin })
                context.contentResolver.query(uri, arrayOf("commits"), null, null, null)!!.use {
                    it.moveToFirst(); assertEquals("One creation plus one native batch save", 2, it.getInt(0))
                }
            } finally {
                compose.runOnIdle { show=false }
                compose.waitForIdle()
                models.forEach { it.viewModelScope.cancel() }
                settings.updateCategoryFilterState("vault_v2", oldFilter)
            }
        }
    }
}
