package takagi.ru.monica.utils

import android.app.Application
import android.os.Build
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34], manifest = Config.NONE, application = Application::class)
class RetiredFeaturesMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val drag = booleanPreferencesKey("use_draggable_bottom_nav")
    private val bypass = booleanPreferencesKey("passkey_hyperos_biometric_bypass_enabled")
    private val order = stringPreferencesKey("bottom_nav_order")
    private val hideFab = booleanPreferencesKey("hide_fab_on_scroll")
    private val other = preferencesOf(order to "NOTES,PASSWORDS,AUTHENTICATOR", hideFab to true)

    @Test fun enabledAndDisabledLegacySettingsAreRemovedWithoutChangingOtherPreferences() = runBlocking {
        for (enabled in listOf(true, false)) {
            val before = other.toMutablePreferences().apply { this[drag] = enabled; this[bypass] = enabled }
            assertTrue(RetiredFeaturesMigration.shouldMigrate(before))
            val after = RetiredFeaturesMigration.migrate(before)
            assertEquals(other, after)
            assertFalse(RetiredFeaturesMigration.shouldMigrate(after))
            assertEquals(after, RetiredFeaturesMigration.migrate(after))
        }
    }

    @Test fun absentKeysRequireNoMigration() = runBlocking {
        assertFalse(RetiredFeaturesMigration.shouldMigrate(other))
        assertEquals(other, RetiredFeaturesMigration.migrate(other))
    }

    @Test fun obsoleteValuesWithUnexpectedTypesAreStillSafelyRemoved() = runBlocking {
        val before = other.toMutablePreferences().apply { this[stringPreferencesKey(drag.name)] = "old-format" }
        assertEquals(other, RetiredFeaturesMigration.migrate(before))
    }

    @Test fun coldOpeningOldPreferencesMigratesOnceAndSurvivesReopening() = runBlocking {
        val file = temporary.root.resolve("old.preferences_pb")
        suspend fun open(migrate: Boolean, action: suspend (androidx.datastore.core.DataStore<Preferences>) -> Unit) {
            assertTrue("DataStore must run against a real supported Android API", Build.VERSION.SDK_INT >= 26)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = PreferenceDataStoreFactory.create(
                    migrations = if (migrate) listOf(RetiredFeaturesMigration) else emptyList(),
                    scope = scope, produceFile = { file },
                )
                action(store)
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        open(false) { store -> store.updateData { other.toMutablePreferences().apply { this[drag] = true; this[bypass] = true } } }
        repeat(2) { open(true) { store -> assertEquals(other, store.data.first()) } }
    }

    @Test fun retiredLanguagesMigrateOnDiskWithoutChangingOtherPreferences() = runBlocking {
        val language = stringPreferencesKey("language")
        for (retired in listOf("NYA", "SNOW_LEOPARD")) {
            val file = temporary.root.resolve("$retired.preferences_pb")
            suspend fun open(migrate: Boolean, action: suspend (androidx.datastore.core.DataStore<Preferences>) -> Unit) {
                assertTrue("DataStore must run against a real supported Android API", Build.VERSION.SDK_INT >= 26)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    action(PreferenceDataStoreFactory.create(
                        migrations = if (migrate) listOf(RetiredFeaturesMigration) else emptyList(),
                        scope = scope, produceFile = { file },
                    ))
                } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
            }
            open(false) { store -> store.updateData { other.toMutablePreferences().apply { this[language] = retired } } }
            val expected = other.toMutablePreferences().apply { this[language] = "CHINESE" }
            repeat(2) { open(true) { store ->
                assertEquals(expected, store.data.first())
                assertFalse(RetiredFeaturesMigration.shouldMigrate(store.data.first()))
            } }
        }
        val supported = other.toMutablePreferences().apply { this[language] = "ITALIAN" }
        assertFalse(RetiredFeaturesMigration.shouldMigrate(supported))
        assertEquals(supported, RetiredFeaturesMigration.migrate(supported))
    }
}
