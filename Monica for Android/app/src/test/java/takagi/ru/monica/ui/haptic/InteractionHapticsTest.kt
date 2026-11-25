package takagi.ru.monica.ui.haptic

import org.junit.Assert.*
import org.junit.Test

class InteractionHapticsTest {
    private class Device : InteractionHapticDevice {
        var system = true
        var accepted = true
        var fallbackAvailable = true
        val requests = mutableListOf<Pair<Boolean, Boolean>>()
        val fallbacks = mutableListOf<Pair<Boolean, Boolean>>()
        override fun systemEnabled() = system
        override fun viewFeedback(strong: Boolean, tick: Boolean): Boolean {
            requests += strong to tick; return accepted
        }
        override fun fallback(strong: Boolean, tick: Boolean): Boolean {
            fallbacks += strong to tick; return fallbackAvailable
        }
    }
    @Test fun acceptedRequestNeverVibratesTwice() {
        val d = Device()
        assertEquals(InteractionHapticResult.REQUESTED, dispatchInteractionHaptic(true, 1, false, d))
        assertEquals(listOf(false to false), d.requests)
        assertTrue(d.fallbacks.isEmpty())
    }
    @Test fun rejectedHostGetsOneMatchingFallback() {
        val d = Device().apply { accepted = false }
        assertEquals(InteractionHapticResult.REQUESTED, dispatchInteractionHaptic(true, 2, true, d))
        assertEquals(listOf(true to true), d.requests)
        assertEquals(listOf(true to true), d.fallbacks)
    }
    @Test fun appAndSystemOffNeverReachEitherOutput() {
        val d = Device()
        assertEquals(InteractionHapticResult.DISABLED, dispatchInteractionHaptic(false, 2, false, d))
        d.system = false
        assertEquals(InteractionHapticResult.SYSTEM_DISABLED, dispatchInteractionHaptic(true, 1, false, d))
        assertTrue(d.requests.isEmpty()); assertTrue(d.fallbacks.isEmpty())
    }
    @Test fun unavailableMotorIsReportedInsteadOfClaimingSuccess() {
        val d = Device().apply { accepted = false; fallbackAvailable = false }
        assertEquals(InteractionHapticResult.UNAVAILABLE, dispatchInteractionHaptic(true, 1, true, d))
        assertEquals(1, d.fallbacks.size)
    }
    @Test fun malformedStrengthIsBounded() {
        val d = Device()
        dispatchInteractionHaptic(true, -100, false, d)
        dispatchInteractionHaptic(true, 100, false, d)
        assertEquals(listOf(false to false, true to false), d.requests)
    }
}
