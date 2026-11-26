package takagi.ru.monica.ui.haptic

import org.junit.Assert.*
import org.junit.Test

class InteractionHapticsTest {
    private class Device : InteractionHapticDevice {
        var accepted = true
        var motorAvailable = true
        val requests = mutableListOf<Boolean>()
        val motorRequests = mutableListOf<Boolean>()
        override fun viewFeedback(strong: Boolean): Boolean {
            requests += strong; return accepted
        }
        override fun vibrate(strong: Boolean): Boolean {
            motorRequests += strong; return motorAvailable
        }
    }
    @Test fun submittedViewRequestNeverSubmitsASecondVibration() {
        val d = Device()
        assertEquals(InteractionHapticResult.REQUESTED, dispatchInteractionHaptic(true, 1, d))
        assertEquals(listOf(false), d.requests)
        assertTrue(d.motorRequests.isEmpty())
    }
    @Test fun rejectedViewGetsOneMatchingMotorRequest() {
        val d = Device().apply { accepted = false }
        assertEquals(InteractionHapticResult.REQUESTED, dispatchInteractionHaptic(true, 2, d))
        assertEquals(listOf(true), d.requests)
        assertEquals(listOf(true), d.motorRequests)
    }
    @Test fun appOffNeverReachesEitherOutput() {
        val d = Device()
        assertEquals(InteractionHapticResult.DISABLED, dispatchInteractionHaptic(false, 2, d))
        assertTrue(d.requests.isEmpty()); assertTrue(d.motorRequests.isEmpty())
    }
    @Test fun unavailableMotorIsReportedInsteadOfClaimingSuccess() {
        val d = Device().apply { accepted = false; motorAvailable = false }
        assertEquals(InteractionHapticResult.UNAVAILABLE, dispatchInteractionHaptic(true, 1, d))
        assertEquals(1, d.motorRequests.size)
    }
    @Test fun malformedStrengthIsBounded() {
        val d = Device()
        dispatchInteractionHaptic(true, -100, d)
        dispatchInteractionHaptic(true, 100, d)
        assertEquals(listOf(false, true), d.requests)
    }
}
