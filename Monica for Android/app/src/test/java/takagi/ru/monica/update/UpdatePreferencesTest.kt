package takagi.ru.monica.update

import android.app.Application
import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UpdatePreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearOnlyUpdateFixture() {
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun preferencesPersistAcrossInstancesAndDoNotChangeOtherSettings() = runBlocking {
        val other = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        other.edit().putString("fixture_existing", "retained").commit()
        val first = UpdatePreferences(context)
        assertEquals(UpdatePreferencesState(), first.read())
        first.setAutoCheckEnabled(false)
        first.recordAttempt(12345L)
        first.skipRelease("standard:V1.0.328b")
        val second = UpdatePreferences(context)
        assertEquals(UpdatePreferencesState(false, 12345L, "standard:V1.0.328b"), second.read())
        second.clearSkippedRelease()
        assertEquals(UpdatePreferencesState(false, 12345L), first.read())
        assertEquals("retained", other.getString("fixture_existing", null))
    }

    @Test fun concurrentIndependentWritesPreserveEveryPreference() = runBlocking {
        val first = UpdatePreferences(context)
        val second = UpdatePreferences(context)
        listOf(async { first.setAutoCheckEnabled(false) }, async { second.recordAttempt(987L) },
            async { first.skipRelease("fdroid:35") }).awaitAll()
        assertEquals(UpdatePreferencesState(false, 987L, "fdroid:35"), second.read())
    }
}
