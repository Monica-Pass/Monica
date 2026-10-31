package takagi.ru.monica.autofill_ng

import android.app.ActivityOptions
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.RemoteException
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import takagi.ru.monica.autofill_ng.core.AutofillLogger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class AutofillSaveLaunchTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun intent(secret: String) = Intent(context, AutofillSaveTransparentActivity::class.java)
        .putExtra(AutofillSaveTransparentActivity.EXTRA_PASSWORD, secret)

    @Test fun systemRecipientCannotRetargetChangeValuesOrReplayTheSaveCapability() {
        val pending = AutofillSaveLaunch.pendingIntent(context, intent("captured-secret"), java.util.UUID.randomUUID().toString(), compatibilityMode = true)
        pending.send(context, 0, Intent().setComponent(ComponentName("other.app", "other.app.OtherActivity"))
            .putExtra(AutofillSaveTransparentActivity.EXTRA_PASSWORD, "replacement-secret"))
        val opened = requireNotNull(shadowOf(context).nextStartedActivity)
        assertEquals(ComponentName(context, AutofillSaveTransparentActivity::class.java), opened.component)
        assertEquals("captured-secret", opened.getStringExtra(AutofillSaveTransparentActivity.EXTRA_PASSWORD))
        assertTrue(opened.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(opened.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertThrows(PendingIntent.CanceledException::class.java) { pending.send() }
    }

    @Test fun twoOutstandingSavesKeepTheirOwnPayloadAndEachRemainsUsable() {
        val first = AutofillSaveLaunch.pendingIntent(context, intent("first-secret"), java.util.UUID.randomUUID().toString())
        val second = AutofillSaveLaunch.pendingIntent(context, intent("second-secret"), java.util.UUID.randomUUID().toString())
        assertNotEquals(first, second)
        first.send()
        assertEquals("first-secret", shadowOf(context).nextStartedActivity.getStringExtra(AutofillSaveTransparentActivity.EXTRA_PASSWORD))
        second.send()
        assertEquals("second-secret", shadowOf(context).nextStartedActivity.getStringExtra(AutofillSaveTransparentActivity.EXTRA_PASSWORD))
    }

    @Test fun rejectsCapabilitiesForOtherActivities() {
        assertThrows(IllegalArgumentException::class.java) {
            AutofillSaveLaunch.pendingIntent(context, Intent().setComponent(ComponentName(context, "other.Activity")), "unused")
        }
    }

    @Test fun creatorOptInIsAttachedToThisCapabilityOnlyOnSupportedAndroid() {
        val pending = AutofillSaveLaunch.pendingIntent(context, intent("synthetic-secret"), java.util.UUID.randomUUID().toString(), compatibilityMode = true)
        val options = shadowOf(pending).options
        if (Build.VERSION.SDK_INT >= 34) {
            // Bundle decoding is hidden from the SDK; only the test uses reflection.
            val restored = ActivityOptions::class.java.getDeclaredMethod("fromBundle", android.os.Bundle::class.java)
                .invoke(null, requireNotNull(options)) as ActivityOptions
            assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                restored.pendingIntentCreatorBackgroundActivityStartMode)
        } else assertNull(options)
    }

    @Test fun standardModeKeepsTheOriginalTaskAndBackgroundStartBehavior() {
        val pending = AutofillSaveLaunch.pendingIntent(context, intent("standard-secret"), java.util.UUID.randomUUID().toString())
        assertNull(shadowOf(pending).options)
        pending.send()
        val opened = requireNotNull(shadowOf(context).nextStartedActivity)
        assertEquals(0, opened.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        assertFalse(opened.getBooleanExtra(AutofillSaveLaunch.EXTRA_COMPATIBILITY_MODE, true))
        assertEquals("standard-secret", opened.getStringExtra(AutofillSaveTransparentActivity.EXTRA_PASSWORD))
    }

    @Test fun compatibilityPreferenceDefaultsOffAndCanBeReversed() = runBlocking {
        val preferences = AutofillPreferences(context)
        assertFalse(preferences.isHyperOsSaveCompatibilityEnabled.first())
        preferences.setHyperOsSaveCompatibilityEnabled(true)
        assertTrue(AutofillPreferences(context).isHyperOsSaveCompatibilityEnabled.first())
        preferences.setHyperOsSaveCompatibilityEnabled(false)
        assertFalse(AutofillPreferences(context).isHyperOsSaveCompatibilityEnabled.first())
    }

    @Test fun diagnosticsExcludeUntrustedIdsAndExceptionMessages() {
        AutofillLogger.clear()
        val secret = "do-not-log-this-captured-secret"
        AutofillSaveDiagnostics.record(context, secret, AutofillSaveDiagnostics.Event.REQUEST_FAILED,
            IllegalStateException(secret))
        val logs = AutofillLogger.exportLogs()
        assertTrue(logs.contains("REQUEST_FAILED"))
        assertTrue(logs.contains("errorKind=invalid_state"))
        assertFalse(logs.contains(secret))
    }

    @Test fun failingSystemCallbackIsNotCalledAgainAndDoesNotLeakItsMessage() {
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val service = Robolectric.buildService(MonicaAutofillServiceNg::class.java).get()
        val preferences = AutofillPreferences(context)
        runBlocking { preferences.setRequestSaveDataEnabled(true) }
        ReflectionHelpers.setField(service, "autofillPreferences", preferences)
        val request = ReflectionHelpers.newInstance(SaveRequest::class.java)
        ReflectionHelpers.setField(request, "mFillContexts", arrayListOf<Any>())
        val calls = mutableListOf<String>()
        val secret = "callback-message-must-not-be-logged"
        val callbackType = Class.forName("android.service.autofill.ISaveCallback")
        val proxy = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, method, _ ->
            if (method.name in setOf("onSuccess", "onFailure")) {
                calls += method.name
                throw RemoteException(secret)
            }
            null
        }
        val callback = SaveCallback::class.java.getDeclaredConstructor(callbackType).apply { isAccessible = true }
            .newInstance(proxy)
        AutofillLogger.clear()
        try {
            service.onSaveRequest(request, callback)
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!AutofillLogger.exportLogs().contains("CALLBACK_FAILED") && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals(listOf("onSuccess"), calls)
            assertTrue(AutofillLogger.exportLogs().contains("CALLBACK_FAILED"))
            assertFalse(AutofillLogger.exportLogs().contains(secret))
        } finally {
            ReflectionHelpers.getField<CoroutineScope>(service, "scope").cancel()
        }
    }
}
