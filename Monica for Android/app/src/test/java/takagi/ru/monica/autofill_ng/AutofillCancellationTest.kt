package takagi.ru.monica.autofill_ng

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.CancellationSignal
import android.os.Looper
import android.os.Build
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import java.lang.reflect.Proxy
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
@OptIn(androidx.room.ExperimentalRoomApi::class)
class AutofillCancellationTest {
    @Test fun cancelledRequestDoesNotDeliverFailureOrSuccessToTheOldSession() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val invalidation = Robolectric.buildService(androidx.room.MultiInstanceInvalidationService::class.java).create()
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
            ComponentName(app.packageName, "androidx.room.MultiInstanceInvalidationService"), invalidation.get().onBind(Intent()))
        val controller = Robolectric.buildService(MonicaAutofillServiceNg::class.java).create()
        try {
            val request = ReflectionHelpers.newInstance(FillRequest::class.java)
            ReflectionHelpers.setField(request,if (Build.VERSION.SDK_INT <= 29) "mContexts" else "mFillContexts",arrayListOf<Any>())
            val calls = mutableListOf<String>()
            val callbackType = Class.forName("android.service.autofill.IFillCallback")
            val callbackProxy = Proxy.newProxyInstance(callbackType.classLoader,arrayOf(callbackType)) { _, method, _ ->
                if (method.name == "onSuccess" || method.name == "onFailure") calls += method.name
                null
            }
            val callback = FillCallback::class.java.getDeclaredConstructor(callbackType,Int::class.javaPrimitiveType)
                .newInstance(callbackProxy,1)
            val cancellation = CancellationSignal().apply { cancel() }
            controller.get().onFillRequest(request,cancellation,callback)
            repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
            assertTrue("Cancelled request delivered $calls",calls.isEmpty())
        } finally { controller.destroy(); invalidation.destroy() }
    }
}
