package takagi.ru.monica.autofill_ng

import android.app.Application
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutofillScopePreferencesTest {
    private val preferences get() = AutofillPreferences(RuntimeEnvironment.getApplication())
    @Before @After fun reset(): Unit = runBlocking { preferences.setV2DefaultScope(AutofillScope.All) }

    @Test fun legacyKeysRestoreOneScopeWithoutMigration(): Unit = runBlocking {
        preferences.setV2DefaultSourceFilter(AutofillPreferences.AutofillDefaultSourceFilter.KEEPASS)
        preferences.setV2DefaultKeepassDatabaseId(31)
        preferences.setV2DefaultBitwardenVaultId(99)
        assertEquals(AutofillScope.KeePass(31), preferences.v2DefaultScope.first())
        preferences.setV2DefaultSourceFilter(AutofillPreferences.AutofillDefaultSourceFilter.BITWARDEN)
        assertEquals(AutofillScope.Bitwarden(99), preferences.v2DefaultScope.first())
    }

    @Test fun sourceAndDatabasePublishInOneSnapshotAndClearUnrelatedKeys(): Unit = runBlocking {
        val observed = Channel<AutofillScope>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            preferences.v2DefaultScope.collect { observed.send(it) }
        }
        try {
            assertEquals(AutofillScope.All, withTimeout(5_000) { observed.receive() })
            val changes = listOf(AutofillScope.KeePass(41), AutofillScope.Bitwarden(42),
                AutofillScope.Mdbx(43), AutofillScope.Mdbx(null), AutofillScope.Local, AutofillScope.All)
            for (scope in changes) {
                preferences.setV2DefaultScope(scope)
                assertEquals(scope, withTimeout(5_000) { observed.receive() })
                assertEquals(scope, preferences.v2DefaultScope.first())
            }
            assertTrue(observed.tryReceive().isFailure)
            assertNull(preferences.v2DefaultKeepassDatabaseId.first())
            assertNull(preferences.v2DefaultBitwardenVaultId.first())
            assertNull(preferences.v2DefaultMdbxDatabaseId.first())
        } finally { collector.cancelAndJoin(); observed.close() }
    }

    @Test fun missingAndInvalidSelectedIdsRemainRestricted(): Unit = runBlocking {
        for (id in listOf(9187L, 0L, -1L)) {
            preferences.setV2DefaultScope(AutofillScope.Mdbx(id))
            assertEquals(AutofillScope.Mdbx(id), preferences.v2DefaultScope.first())
        }
    }

    @Test fun lateDatabaseChoiceCannotSwitchBackToPreviousSource(): Unit = runBlocking {
        preferences.setV2DefaultScope(AutofillScope.KeePass(7))
        preferences.selectV2DefaultSourceFilter(AutofillPreferences.AutofillDefaultSourceFilter.MDBX)
        preferences.selectV2DefaultDatabase(AutofillPreferences.AutofillDefaultSourceFilter.KEEPASS, 8)
        assertEquals(AutofillScope.Mdbx(), preferences.v2DefaultScope.first())
        preferences.selectV2DefaultDatabase(AutofillPreferences.AutofillDefaultSourceFilter.MDBX, 9)
        assertEquals(AutofillScope.Mdbx(9), preferences.v2DefaultScope.first())
        preferences.selectV2DefaultSourceFilter(AutofillPreferences.AutofillDefaultSourceFilter.MDBX)
        assertEquals(AutofillScope.Mdbx(9), preferences.v2DefaultScope.first())
    }
}
