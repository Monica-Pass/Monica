package takagi.ru.monica.autofill_ng

import android.app.Application
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import takagi.ru.monica.security.SecurityManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
@OptIn(androidx.room.ExperimentalRoomApi::class)
class AutofillServiceRevocationTest {
    @Test fun screenOffAndUnlockCannotReviveAReadEvenWithoutAnAutofillGrant() = withService { service ->
        val app = RuntimeEnvironment.getApplication()
        val epoch = epoch(service)
        val generation = SecurityManager.dataReadGeneration.value
        assertTrue(current(service, epoch, generation))
        // The service receiver must revoke the old request even when the device is already
        // interactive again by the time a slow native provider returns its result.
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(current(service, epoch, generation))
        assertTrue("A new request remains available", current(service, epoch(service), generation))
    }

    @Test fun observingKeyguardRevokesTheReadBeforeTheScreenOffReceiverArrives() = withService { service ->
        val keyguard = RuntimeEnvironment.getApplication().getSystemService(KeyguardManager::class.java)
        val epoch = epoch(service)
        val generation = SecurityManager.dataReadGeneration.value
        shadowOf(keyguard).setKeyguardLocked(true)
        assertFalse(current(service, epoch, generation))
        shadowOf(keyguard).setKeyguardLocked(false)
        assertFalse("Unlock must not revive an already rejected read", current(service, epoch, generation))
        assertTrue(current(service, epoch(service), generation))
    }

    @Test fun runtimeRevocationRejectsAResponsePreparedForThePreviousGeneration() = withService { service ->
        val epoch = epoch(service)
        val generation = SecurityManager.dataReadGeneration.value
        assertTrue(current(service, epoch, generation))
        SecurityManager.clearRuntimeUnlockCache()
        assertFalse(current(service, epoch, generation))
    }

    private fun epoch(service: MonicaAutofillServiceNg): Long =
        ReflectionHelpers.getField<AtomicLong>(service, "fillReadEpoch").get()

    private fun current(service: MonicaAutofillServiceNg, epoch: Long, generation: Long): Boolean =
        MonicaAutofillServiceNg::class.java.getDeclaredMethod(
            "isFillRequestCurrent", java.lang.Long.TYPE, java.lang.Long.TYPE,
        ).apply { isAccessible = true }.invoke(service, epoch, generation) as Boolean

    private fun withService(block: (MonicaAutofillServiceNg) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        val invalidation = Robolectric.buildService(androidx.room.MultiInstanceInvalidationService::class.java).create()
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app.packageName, "androidx.room.MultiInstanceInvalidationService"), invalidation.get().onBind(Intent()),
        )
        val controller = Robolectric.buildService(MonicaAutofillServiceNg::class.java).create()
        try {
            block(controller.get())
        } finally {
            controller.destroy()
            invalidation.destroy()
        }
    }
}
