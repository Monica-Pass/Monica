package takagi.ru.monica.lansync

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ProcessLifecycleOwner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import takagi.ru.monica.security.SessionManager
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [NearbyHotspotSessionTest.WifiDriver::class, NearbyHotspotSessionTest.OwnedReservation::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NearbyHotspotSessionTest {
    private lateinit var session: NearbyHotspotSession
    @Before fun prepare() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        DeviceSyncService::class.java.getDeclaredField("visible").apply { isAccessible = true }.setInt(null, 1)
        SessionManager.markUnlocked()
        session = NearbyHotspotSession.get(RuntimeEnvironment.getApplication())
        session.close(); WifiDriver.callback = null; WifiDriver.requests = 0; OwnedReservation.closed = 0
        ConnectivityDriver.callback = null; ConnectivityDriver.requests = 0; ConnectivityDriver.cancelled = 0
        shadowOf(Looper.getMainLooper()).idle()
    }
    @After fun cleanup() {
        session.close(); SessionManager.markLocked()
        DeviceSyncService::class.java.getDeclaredField("visible").apply { isAccessible = true }.setInt(null, 0)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun owned() = Shadow.newInstanceOf(WifiManager.LocalOnlyHotspotReservation::class.java)
    private fun network() = Shadow.newInstanceOf(Network::class.java)
    private fun invite() = requireNotNull(NearbyHotspotInvitation.create("SyntheticHotspot", "SyntheticPassword",
        "monica-lan:2:192.168.43.1:54321:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })))

    @Test fun cancellationRejectsLateHostCallbacksAndClosesOnlyTheirOwnedReservation() {
        var accepted = 0
        session.host { accepted++ }
        assertEquals(1, WifiDriver.requests)
        val callback = requireNotNull(WifiDriver.callback)
        session.close()
        callback.onStarted(owned())
        assertEquals(1, OwnedReservation.closed)
        assertEquals(0, accepted)
        assertEquals("idle", session.state.value.phase)
    }

    @Test fun replacingRequestCannotPublishPreviousHotspotOrInvitation() {
        var accepted = 0
        session.host { accepted++ }
        val stale = requireNotNull(WifiDriver.callback)
        session.host { accepted += 10 }
        stale.onStarted(owned())
        assertEquals(0, accepted)
        requireNotNull(WifiDriver.callback).onStarted(owned())
        assertEquals(10, accepted)
        assertEquals("hosting", session.state.value.phase)
        assertEquals("SyntheticHotspot", session.state.value.networkName)
        session.close()
        assertEquals(2, OwnedReservation.closed)
    }

    @Test fun lockedOrBackgroundApplicationCannotCreateHotspots() {
        SessionManager.markLocked()
        session.host { fail("Locked session must not request a hotspot") }
        assertEquals(0, WifiDriver.requests)
        SessionManager.markUnlocked()
        DeviceSyncService::class.java.getDeclaredField("visible").apply { isAccessible = true }.setInt(null, 0)
        session.host { fail("Background session must not request a hotspot") }
        assertEquals(0, WifiDriver.requests)
    }

    @Test @Config(sdk = [29], shadows = [ConnectivityDriver::class])
    fun floatingSystemDialogPauseKeepsThePendingJoinAndAcceptsItsNetwork() {
        var accepted = 0
        session.join(invite()) { accepted++ }
        assertEquals(1, ConnectivityDriver.requests)
        session.onPause(ProcessLifecycleOwner.get())
        assertEquals("connecting", session.state.value.phase)
        assertEquals(0, ConnectivityDriver.cancelled)
        requireNotNull(ConnectivityDriver.callback).onAvailable(network())
        assertEquals(1, accepted)
        assertEquals("joined", session.state.value.phase)
    }

    @Test @Config(sdk = [29], shadows = [ConnectivityDriver::class])
    fun stoppedApplicationCancelsPendingJoinAndRejectsTheLateNetwork() {
        var accepted = 0
        session.join(invite()) { accepted++ }
        val late = requireNotNull(ConnectivityDriver.callback)
        DeviceSyncService::class.java.getDeclaredField("visible").apply { isAccessible = true }.setInt(null, 0)
        session.onStop(ProcessLifecycleOwner.get())
        assertEquals(1, ConnectivityDriver.cancelled)
        late.onAvailable(network())
        assertEquals(0, accepted)
        assertEquals("idle", session.state.value.phase)
    }

    @Test @Config(sdk = [29], shadows = [ConnectivityDriver::class])
    fun lockingRevokesPendingJoinEvenIfTheApplicationRemainsVisible() {
        var accepted = 0
        session.join(invite()) { accepted++ }
        val late = requireNotNull(ConnectivityDriver.callback)
        SessionManager.markLocked()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ConnectivityDriver.cancelled)
        late.onAvailable(network())
        assertEquals(0, accepted)
        assertEquals("idle", session.state.value.phase)
    }

    @Implements(WifiManager::class)
    class WifiDriver {
        companion object { var callback: WifiManager.LocalOnlyHotspotCallback? = null; var requests = 0 }
        @Implementation fun startLocalOnlyHotspot(received: WifiManager.LocalOnlyHotspotCallback, handler: Handler?) {
            requests++; callback = received
        }
    }

    @Implements(ConnectivityManager::class)
    class ConnectivityDriver {
        companion object { var callback: ConnectivityManager.NetworkCallback? = null; var requests = 0; var cancelled = 0 }
        @Implementation fun requestNetwork(request: NetworkRequest, received: ConnectivityManager.NetworkCallback, handler: Handler, timeoutMs: Int) {
            requests++; callback = received
        }
        @Implementation fun unregisterNetworkCallback(received: ConnectivityManager.NetworkCallback) { cancelled++ }
        @Implementation fun getNetworkCapabilities(network: Network): NetworkCapabilities =
            ReflectionHelpers.callInstanceMethod<NetworkCapabilities>(NetworkCapabilities(), "addTransportType",
                ClassParameter.from(Int::class.javaPrimitiveType!!, NetworkCapabilities.TRANSPORT_WIFI))
        @Implementation fun getLinkProperties(network: Network): LinkProperties = LinkProperties().apply {
            val address = ReflectionHelpers.callConstructor<LinkAddress>(LinkAddress::class.java,
                ClassParameter.from(InetAddress::class.java, InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 43, 2))),
                ClassParameter.from(Int::class.javaPrimitiveType!!, 24))
            ReflectionHelpers.callInstanceMethod<Boolean>(this, "addLinkAddress",
                ClassParameter.from(LinkAddress::class.java, address))
        }
    }

    @Implements(WifiManager.LocalOnlyHotspotReservation::class)
    class OwnedReservation {
        companion object { var closed = 0 }
        @Implementation fun close() { closed++ }
        @Implementation fun getWifiConfiguration(): WifiConfiguration = WifiConfiguration().apply {
            SSID = "SyntheticHotspot"; preSharedKey = "SyntheticPassword"
        }
    }
}
