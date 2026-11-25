package takagi.ru.monica.ui.components

import org.junit.Assert.*
import org.junit.Test

class SliderTickGateTest {
    @Test fun skipsRepeatedValuesAndNeverQueuesRapidMovement() {
        val gate = SliderTickGate(0f, 0f..10f, 9)
        assertFalse(gate.onUserValue(0f, 0))
        assertTrue(gate.onUserValue(1f, 0))
        assertFalse(gate.onUserValue(2f, 10))
        assertFalse(gate.onUserValue(3f, 20))
        assertFalse(gate.onUserValue(3f, 100))
        assertTrue(gate.onUserValue(2f, 101))
    }

    @Test fun restoringAnExternalValueDoesNotProduceAnExtraTick() {
        val gate = SliderTickGate(40f, 0f..100f, 99)
        gate.synchronize(80f)
        assertFalse(gate.onUserValue(80f, 100))
        assertTrue(gate.onUserValue(79f, 101))
        assertFalse(gate.onUserValue(79f, 200))
    }

    @Test fun continuousRangesUseDetentsInBothDirectionsAndClampEndpoints() {
        val gate = SliderTickGate(0f, 0f..1f, 0)
        assertFalse(gate.onUserValue(0.001f, 0))
        assertTrue(gate.onUserValue(0.02f, 10))
        assertTrue(gate.onUserValue(0.01f, 50))
        assertTrue(gate.onUserValue(-1f, 100))
        assertFalse(gate.onUserValue(0f, 140))
        assertTrue(gate.onUserValue(2f, 180))
        assertFalse(gate.onUserValue(1f, 220))
    }
}
