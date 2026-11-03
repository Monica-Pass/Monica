package takagi.ru.monica.service

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class BrowserAutofillContextStoreTest {
    @Test fun accessibilityAddressCacheUsesCanonicalIpHosts() {
        BrowserAutofillContextStore.update("com.android.chrome", "http://192.168.1.1:8080/login")
        assertEquals("192.168.1.1", BrowserAutofillContextStore.getRecentDomain("com.android.chrome"))
        BrowserAutofillContextStore.update("com.android.chrome", "http://[fd00:0:0:0:0:0:0:1]:8080/login")
        assertEquals("fd00::1", BrowserAutofillContextStore.getRecentDomain("com.android.chrome"))
        assertNull(BrowserAutofillContextStore.getRecentDomain("org.mozilla.firefox"))
    }

    @Test fun rejectedNumericAddressRevokesTheSameBrowsersPreviousSnapshot() {
        BrowserAutofillContextStore.update("com.android.chrome", "http://192.168.1.1")
        BrowserAutofillContextStore.update("org.mozilla.firefox", "http://999.168.1.1")
        assertEquals("192.168.1.1", BrowserAutofillContextStore.getRecentDomain("com.android.chrome"))
        BrowserAutofillContextStore.update("com.android.chrome", "http://999.168.1.1")
        assertNull(BrowserAutofillContextStore.getRecentDomain("com.android.chrome"))
    }
}
