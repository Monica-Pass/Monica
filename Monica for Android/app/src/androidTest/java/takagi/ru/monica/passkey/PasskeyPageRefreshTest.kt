package takagi.ru.monica.passkey

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.security.KeyPairGenerator
import java.util.Base64
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.repository.PasskeyRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.sync.SyncTaskRunner
import takagi.ru.monica.ui.components.PasskeyPortabilityBadges
import takagi.ru.monica.ui.components.rememberPasskeyPortability
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.SavedCategoryFilterState
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.PasskeyViewModel

class PasskeyPageRefreshTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun resetCache() { PasskeyPortabilityCache.shared.clear() }
    @After fun clearCache() { PasskeyPortabilityCache.shared.clear() }

    @Test fun localFilterDoesNotEnqueueKeePassRefresh(): Unit = runBlocking {
        val settings = SettingsManager(context)
        val scope = SettingsManager.CategoryFilterScope.PASSKEY
        val previous = settings.categoryFilterStateFlow(scope).first()
        settings.updateCategoryFilterState(scope, SavedCategoryFilterState("local"))
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        db.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(name = "Unselected fixture", filePath = "unused-refresh-fixture"))
        val before = SyncTaskRunner.statuses.first()
        val model = PasskeyViewModel(PasskeyRepository(db.passkeyDao()), context,
            db.localKeePassDatabaseDao(), SecurityManager(context), AppLocaleStringResolver(context))
        try {
            model.isCategoryFilterReady.first { it }
            withContext(Dispatchers.Main.immediate) { model.refreshKeePassPasskeys("PASSKEY_PAGE_ENTER") }
            assertEquals("A local filter must not submit even an empty compatibility sync", before, SyncTaskRunner.statuses.first())
        } finally {
            model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
            settings.updateCategoryFilterState(scope, previous)
        }
    }

    @Test fun returningShowsKnownBadgeOnFirstComposition() {
        val visible = mutableStateOf(true)
        val observed = mutableListOf<PasskeyPortability?>()
        val item = PasskeyEntry(credentialId = "refresh-fixture", rpId = "example.invalid", rpName = "Fixture",
            userId = "fixture", userName = "fixture", userDisplayName = "Fixture", publicKey = "",
            privateKeyAlias = "missing-refresh-fixture-key")
        compose.setContent {
            if (visible.value) MonicaTheme {
                val status = rememberPasskeyPortability(item)
                SideEffect { observed += status }
                PasskeyPortabilityBadges(item, status)
            }
        }
        compose.waitUntil(10_000) { observed.lastOrNull() == PasskeyPortability.KEY_UNAVAILABLE }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { observed.clear(); visible.value = true }
        compose.waitUntil(10_000) { observed.isNotEmpty() }
        compose.runOnIdle { assertNotNull("Returning must not drop an already resolved badge", observed.first()) }
    }

    @Test fun emptyAllFilterDoesNotEnqueueKeePassRefresh(): Unit = runBlocking {
        val settings = SettingsManager(context)
        val scope = SettingsManager.CategoryFilterScope.PASSKEY
        val previous = settings.categoryFilterStateFlow(scope).first()
        settings.updateCategoryFilterState(scope, SavedCategoryFilterState())
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val model = PasskeyViewModel(PasskeyRepository(db.passkeyDao()), context,
            db.localKeePassDatabaseDao(), SecurityManager(context), AppLocaleStringResolver(context))
        try {
            model.isCategoryFilterReady.first { it }
            val before = SyncTaskRunner.statuses.first()
            withContext(Dispatchers.Main.immediate) { model.refreshKeePassPasskeys("PASSKEY_PAGE_ENTER") }
            assertEquals(before, SyncTaskRunner.statuses.first())
        } finally {
            model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
            settings.updateCategoryFilterState(scope, previous)
        }
    }

    @Test fun restrictedBadgeIsCachedAndKeyRemovalAndRestoreRefreshIt() {
        val material = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC")
            .apply { initialize(256) }.generateKeyPair().private.encoded)
        val raw = PasskeyEntry(credentialId = "cache-restore-fixture", rpId = "example.invalid", rpName = "Fixture",
            userId = "fixture", userName = "fixture", userDisplayName = "Fixture", publicKey = "",
            privateKeyAlias = material, backupEligible = false)
        val item = PasskeyPrivateKeyStore.protectPasskey(context, raw)
        val visible = mutableStateOf(true)
        val observed = mutableListOf<PasskeyPortability?>()
        try {
            compose.setContent {
                if (visible.value) MonicaTheme {
                    val status = rememberPasskeyPortability(item)
                    SideEffect { observed += status }
                    PasskeyPortabilityBadges(item, status)
                }
            }
            compose.waitUntil(10_000) { observed.lastOrNull() == PasskeyPortability.BACKUP_RESTRICTED }
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { observed.clear(); visible.value = true }
            compose.waitUntil(10_000) { observed.isNotEmpty() }
            compose.runOnIdle { assertEquals(PasskeyPortability.BACKUP_RESTRICTED, observed.first()) }
            PasskeyPrivateKeyStore.removeIfProtectedReference(context, item.privateKeyAlias)
            compose.waitUntil(10_000) { observed.lastOrNull() == PasskeyPortability.KEY_UNAVAILABLE }
            assertEquals(item.privateKeyAlias, PasskeyPrivateKeyStore.protectPasskey(context, raw).privateKeyAlias)
            compose.waitUntil(10_000) { observed.lastOrNull() == PasskeyPortability.BACKUP_RESTRICTED }
        } finally {
            PasskeyPrivateKeyStore.removeIfProtectedReference(context, item.privateKeyAlias)
        }
    }
}
