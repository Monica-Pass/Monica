package takagi.ru.monica.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.internal.BiometricViewModel
import androidx.biometric.utils.BiometricErrorData
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real AndroidX 1.4.0-beta01 FragmentManager + retained ViewModel interaction.
 * Reflection seeds real ViewModel events. For deterministic callback tests, the real ViewModel's
 * prompt-showing flag prevents native hardware dispatch; its actual LiveData observers deliver the
 * injected results. No framework exception or Fragment is mocked.
 * These tests do not claim physical fingerprint enrollment or authentication coverage.
 */
@RunWith(AndroidJUnit4::class)
@SuppressLint("RestrictedApi")
class BiometricPromptAttachmentInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun androidXPendingCancellationDetachesNewFragmentBeforeAuthenticate() {
        ActivityScenario.launch(BiometricPromptTestActivity::class.java).use { scenario ->
            val events = Events()
            lateinit var trace: FragmentTrace
            scenario.onActivity { activity ->
                trace = FragmentTrace().also {
                    activity.supportFragmentManager.registerFragmentLifecycleCallbacks(it, false)
                }
                val prompt = rawPrompt(activity, events)
                val model = seedPendingError(activity)

                val failure = runCatching { prompt.authenticate(promptInfo()) }.exceptionOrNull()

                assertNotNull("The unguarded AndroidX path must reproduce the reported crash", failure)
                assertTrue(failure is IllegalStateException)
                assertTrue(failure!!.message.orEmpty().contains("not attached to a context"))
                assertEquals(1, trace.attached.size)
                assertEquals(1, trace.detached)
                assertNull(trace.attached.single().context)
                assertNull(activity.supportFragmentManager.findFragmentByTag(BIOMETRIC_TAG))
                assertNull(readPendingEvent(model, "getAuthenticationError"))
            }
            drainMainQueue()
            scenario.onActivity {
                assertEquals(0, events.successes)
                assertEquals(listOf(BiometricPrompt.ERROR_CANCELED to OLD_ERROR), events.errors)
            }
        }
    }

    @Test
    fun androidXPendingSuccessCanReachNewCallbackEvenThoughLaunchThrows() {
        ActivityScenario.launch(BiometricPromptTestActivity::class.java).use { scenario ->
            val events = Events()
            scenario.onActivity { activity ->
                val prompt = rawPrompt(activity, events)
                val model = seedPendingSuccess(activity)
                val failure = runCatching { prompt.authenticate(promptInfo()) }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertTrue(failure!!.message.orEmpty().contains("not attached to a context"))
                assertNull(readPendingEvent(model, "getAuthenticationResult"))
            }
            drainMainQueue()
            scenario.onActivity {
                // This is the unsafe upstream control, not the application's accepted behavior.
                assertEquals(1, events.successes)
                assertTrue(events.errors.isEmpty())
            }
        }
    }

    @Test
    fun helperQuarantinesActivityCancellationAndUsesOneIndependentHost() {
        checkHelperRejectsPendingEvent(success = false)
    }

    @Test
    fun helperQuarantinesActivitySuccessWithoutAuthorizingNewRequest() {
        checkHelperRejectsPendingEvent(success = true)
    }

    @Test
    fun cancelBeforeDeferredLaunchRevokesCallbacksAndDoesNotCreatePrompt() {
        ActivityScenario.launch(BiometricPromptTestActivity::class.java).use { scenario ->
            val events = Events()
            lateinit var trace: FragmentTrace
            scenario.onActivity { activity ->
                trace = FragmentTrace().also {
                    activity.supportFragmentManager.registerFragmentLifecycleCallbacks(it, true)
                }
                val helper = BiometricAuthHelper(activity)
                authenticate(helper, activity, events)
                helper.cancelAuthentication()
            }
            drainMainQueue()
            scenario.onActivity {
                assertTrue(trace.attached.isEmpty())
                assertTrue(trace.hosts.isEmpty())
                assertEquals(0, events.total)
            }
        }
    }

    @Test
    fun cancellationDuringHostAttachmentPreventsNestedPromptAndCallbacks() {
        ActivityScenario.launch(BiometricPromptTestActivity::class.java).use { scenario ->
            val events = Events()
            lateinit var trace: FragmentTrace
            scenario.onActivity { activity ->
                val helper = BiometricAuthHelper(activity)
                trace = FragmentTrace(onHostCreated = { helper.cancelAuthentication() }).also {
                    activity.supportFragmentManager.registerFragmentLifecycleCallbacks(it, true)
                }
                authenticate(helper, activity, events)
            }
            drainMainQueue()
            scenario.onActivity {
                assertEquals(1, trace.hosts.size)
                assertTrue(trace.attached.isEmpty())
                assertNull(trace.hosts.single().context)
                assertEquals(0, events.total)
            }
        }
    }

    @Test
    fun requestFromStoppedActivityCannotCreatePromptOrBecomeAuthorizedOnResume() {
        ActivityScenario.launch(BiometricPromptTestActivity::class.java).use { scenario ->
            val events = Events()
            lateinit var activity: BiometricPromptTestActivity
            lateinit var trace: FragmentTrace
            scenario.onActivity { host ->
                activity = host
                trace = FragmentTrace().also {
                    host.supportFragmentManager.registerFragmentLifecycleCallbacks(it, true)
                }
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            lateinit var helper: BiometricAuthHelper
            instrumentation.runOnMainSync {
                assertFalse(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                helper = BiometricAuthHelper(activity)
                authenticate(helper, activity, events)
            }
            drainMainQueue()
            scenario.moveToState(Lifecycle.State.RESUMED)
            drainMainQueue()
            scenario.onActivity {
                assertTrue(trace.attached.isEmpty())
                assertTrue(trace.hosts.isEmpty())
                assertEquals(0, events.successes)
                assertTrue(events.total <= 1)
                helper.cancelAuthentication()
            }
        }
    }

    @Test
    fun currentStrongResultAuthorizesExactlyOnceAndRemovesHost() {
        withControlledPrompt { scenario, _, events, trace ->
            scenario.onActivity {
                val model = trace.models.single()
                val callback = model.clientCallback
                setSuccess(model)
                // A duplicated already-dispatched client callback must not authorize twice.
                model.clientExecutor.execute { callback.onAuthenticationSucceeded(strongResult()) }
            }
            drainMainQueue()
            scenario.onActivity {
                assertEquals(1, events.successes)
                assertEquals(1, events.total)
                assertNull(trace.hosts.single().context)
            }
        }
    }

    @Test
    fun deviceCredentialResultCannotAuthorizeStrongBiometricRequest() {
        withControlledPrompt { scenario, _, events, _ ->
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager.fragments.single { it.javaClass.name == HOST_CLASS }
                setResult(ViewModelProvider(host)[BiometricViewModel::class.java],
                    BiometricPrompt.AuthenticationResult(null, BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL))
            }
            drainMainQueue()
            scenario.onActivity {
                assertEquals(0, events.successes)
                assertEquals(1, events.errors.size)
                assertEquals(BiometricPrompt.ERROR_UNABLE_TO_PROCESS, events.errors.single().first)
                assertEquals(1, events.total)
            }
        }
    }

    @Test
    fun currentPasswordButtonUsesNegativeCallbackAndDoesNotAuthorize() {
        withControlledPrompt { scenario, _, events, trace ->
            scenario.onActivity { setError(trace.models.single(), BiometricPrompt.ERROR_NEGATIVE_BUTTON, "Use password") }
            drainMainQueue()
            scenario.onActivity {
                assertEquals(1, events.negativeButtons)
                assertEquals(1, events.total)
                assertNull(trace.hosts.single().context)
            }
        }
    }

    @Test
    fun currentUserCancellationUsesCancelCallbackAndDoesNotAuthorize() {
        withControlledPrompt { scenario, _, events, trace ->
            scenario.onActivity { setError(trace.models.single(), BiometricPrompt.ERROR_USER_CANCELED, "Canceled") }
            drainMainQueue()
            scenario.onActivity {
                assertEquals(1, events.cancellations)
                assertEquals(1, events.total)
                assertNull(trace.hosts.single().context)
            }
        }
    }

    @Test
    fun explicitCancelDropsAlreadyQueuedSuccess() {
        withControlledPrompt { scenario, helper, events, trace ->
            scenario.onActivity {
                setSuccess(trace.models.single())
                helper.cancelAuthentication()
            }
            drainMainQueue()
            scenario.onActivity {
                assertEquals(0, events.total)
                assertNull(trace.hosts.single().context)
            }
        }
    }

    @Test
    fun replacedRequestCannotReceiveOldHostSuccess() = checkReplacedRequest(newHelper = false)

    @Test
    fun newHelperInSameActivityCannotReceiveCanceledHostsSuccess() = checkReplacedRequest(newHelper = true)

    @Test
    fun backgroundRevokesCurrentHostAndDropsLateSuccessAfterResume() {
        withControlledPrompt { scenario, _, events, trace ->
            lateinit var oldModel: BiometricViewModel
            lateinit var oldCallback: BiometricPrompt.AuthenticationCallback
            scenario.onActivity {
                oldModel = trace.models.single()
                oldCallback = oldModel.clientCallback
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            drainMainQueue()
            instrumentation.runOnMainSync {
                setSuccess(oldModel)
                oldCallback.onAuthenticationSucceeded(strongResult())
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            drainMainQueue()
            scenario.onActivity {
                assertEquals(0, events.successes)
                assertEquals(1, trace.hosts.size)
                assertNull(trace.hosts.single().context)
            }
        }
    }

    private fun checkReplacedRequest(newHelper: Boolean) {
        withControlledPrompt { scenario, original, originalEvents, trace ->
            val replacementEvents = Events()
            lateinit var oldModel: BiometricViewModel
            lateinit var oldCallback: BiometricPrompt.AuthenticationCallback
            lateinit var replacement: BiometricAuthHelper
            scenario.onActivity { activity ->
                oldModel = trace.models.single()
                oldCallback = oldModel.clientCallback
                replacement = if (newHelper) {
                    original.cancelAuthentication()
                    BiometricAuthHelper(activity)
                } else original
                authenticate(replacement, activity, replacementEvents)
            }
            drainMainQueue()
            scenario.onActivity {
                assertEquals(2, trace.hosts.size)
                assertNull(trace.hosts.first().context)
                assertNotSame(oldModel, trace.models.last())
                // Deliver through the old real model after replacement, and also exercise an
                // already-dispatched callback captured before that host was retired.
                setSuccess(oldModel)
                oldCallback.onAuthenticationSucceeded(strongResult())
                assertEquals(0, originalEvents.total)
                assertEquals(0, replacementEvents.total)
                setSuccess(trace.models.last())
            }
            drainMainQueue()
            scenario.onActivity {
                assertEquals(0, originalEvents.total)
                assertEquals(1, replacementEvents.successes)
                assertEquals(1, replacementEvents.total)
            }
        }
    }

    private fun checkHelperRejectsPendingEvent(success: Boolean) {
        ActivityScenario.launch(BiometricPromptTestActivity::class.java).use { scenario ->
            val events = Events()
            lateinit var helper: BiometricAuthHelper
            lateinit var trace: FragmentTrace
            lateinit var activityModel: BiometricViewModel
            scenario.onActivity { activity ->
                trace = FragmentTrace(controlNativePrompt = true).also {
                    activity.supportFragmentManager.registerFragmentLifecycleCallbacks(it, true)
                }
                helper = BiometricAuthHelper(activity)
                activityModel = if (success) seedPendingSuccess(activity) else seedPendingError(activity)
                authenticate(helper, activity, events)
            }
            drainMainQueue()
            scenario.onActivity {
                assertEquals(1, trace.hosts.size)
                assertEquals(1, trace.attached.size)
                assertSame(trace.hosts.single(), trace.attached.single().parentFragment)
                assertNotSame(activityModel, trace.models.single())
                assertNotNull(readPendingEvent(activityModel,
                    if (success) "getAuthenticationResult" else "getAuthenticationError"))
                assertEquals(0, events.total)
                helper.cancelAuthentication()
            }
            drainMainQueue()
            scenario.onActivity {
                assertEquals(0, events.total)
                assertNull(trace.hosts.single().context)
            }
        }
    }

    private fun withControlledPrompt(block: (
        ActivityScenario<BiometricPromptTestActivity>, BiometricAuthHelper, Events, FragmentTrace
    ) -> Unit) {
        ActivityScenario.launch(BiometricPromptTestActivity::class.java).use { scenario ->
            val events = Events()
            lateinit var helper: BiometricAuthHelper
            lateinit var trace: FragmentTrace
            scenario.onActivity { activity ->
                helper = BiometricAuthHelper(activity)
                trace = FragmentTrace(controlNativePrompt = true).also {
                    activity.supportFragmentManager.registerFragmentLifecycleCallbacks(it, true)
                }
                authenticate(helper, activity, events)
            }
            drainMainQueue()
            scenario.onActivity {
                assertEquals(1, trace.hosts.size)
                assertEquals(1, trace.attached.size)
                assertSame(trace.hosts.single(), trace.attached.single().parentFragment)
                assertEquals(0, events.total)
            }
            try {
                block(scenario, helper, events, trace)
            } finally {
                scenario.onActivity { helper.cancelAuthentication() }
                drainMainQueue()
            }
        }
    }

    private fun authenticate(
        helper: BiometricAuthHelper,
        activity: BiometricPromptTestActivity,
        events: Events
    ) = helper.authenticate(
        activity = activity,
        title = "Synthetic biometric regression",
        subtitle = null,
        description = null,
        negativeButtonText = "Use password",
        onSuccess = { events.successes++ },
        onError = { code, message -> events.errors += code to message },
        onCancel = { events.cancellations++ },
        onNegativeButton = { events.negativeButtons++ }
    )

    private fun rawPrompt(activity: BiometricPromptTestActivity, events: Events) = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                events.successes++
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                events.errors += errorCode to errString.toString()
            }
        }
    )

    private fun promptInfo() = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Synthetic biometric regression")
        .setNegativeButtonText("Use password")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()

    private fun seedPendingError(activity: BiometricPromptTestActivity): BiometricViewModel {
        val model = retainedModelAwaitingResult(activity)
        setError(model, BiometricPrompt.ERROR_CANCELED, OLD_ERROR)
        assertNotNull(readPendingEvent(model, "getAuthenticationError"))
        return model
    }

    private fun seedPendingSuccess(activity: BiometricPromptTestActivity): BiometricViewModel {
        val model = retainedModelAwaitingResult(activity)
        setSuccess(model)
        assertNotNull(readPendingEvent(model, "getAuthenticationResult"))
        return model
    }

    private fun retainedModelAwaitingResult(activity: BiometricPromptTestActivity): BiometricViewModel {
        assertNull(activity.supportFragmentManager.findFragmentByTag(BIOMETRIC_TAG))
        val model = ViewModelProvider(activity)[BiometricViewModel::class.java]
        setBoolean(model, "setAwaitingResult", true)
        return model
    }

    private fun setError(model: BiometricViewModel, code: Int, message: String) {
        BiometricViewModel::class.java.getDeclaredMethod(
            "setAuthenticationError", BiometricErrorData::class.java
        ).apply { isAccessible = true }.invoke(model, BiometricErrorData(code, message))
    }

    private fun setSuccess(model: BiometricViewModel) = setResult(model, strongResult())

    private fun setResult(model: BiometricViewModel, result: BiometricPrompt.AuthenticationResult) {
        BiometricViewModel::class.java.getDeclaredMethod(
            "setAuthenticationResult", BiometricPrompt.AuthenticationResult::class.java
        ).apply { isAccessible = true }.invoke(model, result)
    }

    private fun strongResult() = BiometricPrompt.AuthenticationResult(
        null, BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC
    )

    private fun readPendingEvent(model: BiometricViewModel, getter: String): Any? =
        (BiometricViewModel::class.java.getDeclaredMethod(getter)
            .apply { isAccessible = true }.invoke(model) as androidx.lifecycle.LiveData<*>).value

    private fun drainMainQueue() {
        repeat(4) { instrumentation.waitForIdleSync() }
    }

    private class Events {
        var successes = 0
        var cancellations = 0
        var negativeButtons = 0
        val errors = mutableListOf<Pair<Int, String>>()
        val total get() = successes + cancellations + negativeButtons + errors.size
    }

    private class FragmentTrace(
        private val controlNativePrompt: Boolean = false,
        private val onHostCreated: () -> Unit = {}
    ) :
        FragmentManager.FragmentLifecycleCallbacks() {
        val attached = mutableListOf<Fragment>()
        val hosts = mutableListOf<Fragment>()
        val models = mutableListOf<BiometricViewModel>()
        var detached = 0

        override fun onFragmentAttached(fm: FragmentManager, f: Fragment, context: Context) {
            if (f.javaClass.name == BIOMETRIC_TAG) attached += f
        }

        override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
            if (f.javaClass.name != HOST_CLASS) return
            hosts += f
            val model = ViewModelProvider(f)[BiometricViewModel::class.java]
            models += model
            if (controlNativePrompt) {
                // Keep real AndroidX observers/callback dispatch while avoiding dependency on
                // fingerprint enrollment and asynchronous hardware availability on the emulator.
                setBoolean(model, "setPromptShowing", true)
                setBoolean(model, "setAwaitingResult", true)
            }
            onHostCreated()
        }

        override fun onFragmentDetached(fm: FragmentManager, f: Fragment) {
            if (f.javaClass.name == BIOMETRIC_TAG) {
                detached++
            }
        }
    }

    private companion object {
        const val BIOMETRIC_TAG = "androidx.biometric.internal.BiometricFragment"
        const val HOST_CLASS = "takagi.ru.monica.utils.BiometricPromptHostFragment"
        const val OLD_ERROR = "Previous canceled request fixture"

        fun setBoolean(model: BiometricViewModel, method: String, value: Boolean) {
            BiometricViewModel::class.java.getDeclaredMethod(method, java.lang.Boolean.TYPE)
                .apply { isAccessible = true }.invoke(model, value)
        }
    }
}
