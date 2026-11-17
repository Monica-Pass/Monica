package takagi.ru.monica.security

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.autofill_ng.AutofillPickerActivityV2
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.ime.ImeUnlockActivity
import takagi.ru.monica.utils.SettingsManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EntrySettingsLifecycleActivityTest : DelayedEntryActivityChecks() { override val ime = false }
class ImeEntrySettingsActivityTest : DelayedEntryActivityChecks() { override val ime = true }

abstract class DelayedEntryActivityChecks {
    protected abstract val ime: Boolean
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        assertTrue(message, condition())
    }

    private fun withDelayedEntry(block: (ActivityScenario<Activity>, MutableSharedFlow<AppSettings>, Activity) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName.contains("perf328")) { "Isolated performance fixture required" }
        assertEquals("Test must run in the real target process", ime, Application.getProcessName().endsWith(":ime"))
        println("entry_process=${Application.getProcessName()} pid=${android.os.Process.myPid()}")
        runBlocking { assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready) }
        SessionManager.markLocked()
        val field = SettingsManager::class.java.getDeclaredField("sharedSettingsFlow").apply { isAccessible = true }
        val previous = field.get(null)
        val delayed = MutableSharedFlow<AppSettings>(replay = 1)
        field.set(null, delayed)
        var scenario: ActivityScenario<Activity>? = null
        try {
            val intent = if (ime) Intent(context, ImeUnlockActivity::class.java) else
                AutofillPickerActivityV2.getIntent(context, AutofillPickerActivityV2.Args(
                    applicationId = "org.example.entrylifecycle", interactionIdentifier = UUID.randomUUID().toString(),
                )).putExtra(AutofillPickerActivityV2.EXTRA_MANUAL_MODE, true)
            scenario = ActivityScenario.launch(intent)
            var activity: Activity? = null
            scenario.onActivity { activity = it }
            awaitCondition("Entry must be waiting for withheld settings") { delayed.subscriptionCount.value > 0 }
            block(scenario, delayed, requireNotNull(activity))
        } finally {
            delayed.tryEmit(AppSettings())
            scenario?.close()
            field.set(null, previous)
        }
    }

    @Test fun actualEntryKeepsMainResponsiveWhileSettingsAreDelayed() = withDelayedEntry { _, _, _ ->
        repeat(4) {
            val heartbeat = CountDownLatch(1)
            val start = SystemClock.elapsedRealtime()
            Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
            assertTrue("Entry settings blocked the actual process main looper", heartbeat.await(1_000, TimeUnit.MILLISECONDS))
            println("entry_settings_main_heartbeat_ms=${SystemClock.elapsedRealtime() - start}")
        }
    }

    @Test fun stoppingActualEntryRejectsLateDeveloperBypassSettings() = withDelayedEntry { scenario, settings, activity ->
        scenario.moveToState(Lifecycle.State.CREATED)
        settings.tryEmit(AppSettings(disablePasswordVerification = true))
        awaitCondition("Stopped entry must close") { activity.isDestroyed || activity.isFinishing }
        instrumentation.waitForIdleSync()
        assertFalse("Stopped request must not unlock", SessionManager.isUnlocked.value)
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
    }

    @Test fun lockingActualEntryRejectsLateDeveloperBypassSettings() = withDelayedEntry { _, settings, activity ->
        instrumentation.runOnMainSync { SessionManager.markLocked() }
        settings.tryEmit(AppSettings(disablePasswordVerification = true))
        awaitCondition("Locked entry must close") { activity.isDestroyed || activity.isFinishing }
        instrumentation.waitForIdleSync()
        assertFalse("Locked request must not unlock", SessionManager.isUnlocked.value)
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
    }

    @Suppress("DEPRECATION")
    @Test fun backWhileSettingsAreDelayedRejectsLateBypass() = withDelayedEntry { _, settings, activity ->
        instrumentation.runOnMainSync { activity.onBackPressed() }
        awaitCondition("Back must finish the entry") { activity.isFinishing || activity.isDestroyed }
        settings.tryEmit(AppSettings(disablePasswordVerification = true))
        instrumentation.waitForIdleSync()
        assertFalse(SessionManager.isUnlocked.value)
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
    }

    @Test fun recreationUsesANewRequestWithoutReplayingTheOldOne() = withDelayedEntry { scenario, settings, old ->
        scenario.recreate()
        var fresh: Activity? = null
        scenario.onActivity { fresh = it }
        assertNotSame(old, fresh)
        assertTrue(old.isDestroyed)
        settings.tryEmit(AppSettings(autoLockMinutes = 9, biometricEnabled = false))
        val target: Any? = if (ime) fresh else null
        val value = (if (ime) ImeUnlockActivity::class.java.getDeclaredField("startupAutoLockMinutes")
            else SessionManager::class.java.getDeclaredField("autoLockMinutes")).apply { isAccessible = true }
        awaitCondition("Recreated entry must use the new settings request") { value.getInt(target) == 9 }
        assertFalse(SessionManager.isUnlocked.value)
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
    }

    @Test fun actualEntryUsesTheDeliveredAutoLockSetting() = withDelayedEntry { _, settings, activity ->
        settings.tryEmit(AppSettings(autoLockMinutes = 7, biometricEnabled = false))
        val target: Any? = if (ime) activity else null
        val value = (if (ime) ImeUnlockActivity::class.java.getDeclaredField("startupAutoLockMinutes")
            else SessionManager::class.java.getDeclaredField("autoLockMinutes")).apply { isAccessible = true }
        awaitCondition("Actual entry did not use the delivered seven-minute setting") { value.getInt(target) == 7 }
    }
}
