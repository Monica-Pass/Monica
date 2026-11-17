package takagi.ru.monica.security

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.autofill_ng.AutofillPickerActivityV2
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.utils.SettingsManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Uses the real picker Activity, with only the initial DataStore emission delayed. */
class EntrySettingsActivityTest {
    @Test fun pickerSettingsWaitKeepsMainResponsive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.contains("perf328")) { "Isolated performance fixture required" }
        runBlocking { assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready) }
        val settingsField = SettingsManager::class.java.getDeclaredField("sharedSettingsFlow").apply { isAccessible = true }
        val previous = settingsField.get(null)
        val delayed = MutableSharedFlow<AppSettings>(replay = 1)
        val starter = Executors.newSingleThreadExecutor()
        var scenario: ActivityScenario<AutofillPickerActivityV2>? = null
        settingsField.set(null, delayed)
        val intent = AutofillPickerActivityV2.getIntent(context, AutofillPickerActivityV2.Args(
            applicationId = "org.example.entrysettings",
            interactionIdentifier = UUID.randomUUID().toString(),
        )).putExtra(AutofillPickerActivityV2.EXTRA_MANUAL_MODE, true)
        val launched = starter.submit<ActivityScenario<AutofillPickerActivityV2>> { ActivityScenario.launch(intent) }
        try {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (delayed.subscriptionCount.value == 0 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
            assertTrue("The actual entry must wait for the withheld settings", delayed.subscriptionCount.value > 0)
            repeat(4) {
                val started = SystemClock.elapsedRealtime()
                val heartbeat = CountDownLatch(1)
                Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
                val responsive = heartbeat.await(1_000, TimeUnit.MILLISECONDS)
                val millis = SystemClock.elapsedRealtime() - started
                println("entry_settings_main_heartbeat_ms=$millis")
                val stack = if (responsive) "" else Looper.getMainLooper().thread.stackTrace
                    .take(18).joinToString("\n") { frame -> "${frame.className}.${frame.methodName}:${frame.lineNumber}" }
                assertTrue("Settings waiting blocked the real main looper.\n$stack", responsive)
                Thread.sleep(100)
            }
        } finally {
            delayed.tryEmit(AppSettings(autoLockMinutes = 7))
            try { scenario = launched.get(30, TimeUnit.SECONDS) }
            finally {
                scenario?.close()
                settingsField.set(null, previous)
                starter.shutdownNow()
            }
        }
    }
}
