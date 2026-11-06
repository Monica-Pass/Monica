package takagi.ru.monica.ui.screens

import org.junit.Assert.*
import org.junit.Test

class DedupEntrySessionTest {
    @Test fun cancellationRejectsLateConfirmation() {
        val session = DedupEntrySession()
        assertFalse(session.accepted)
        assertTrue(session.cancel())
        assertFalse(session.confirm())
        assertFalse(session.accepted)
        assertFalse(session.cancel())
    }

    @Test fun oneVisitAcknowledgesOnceWithoutPersistentExemption() {
        val visit = DedupEntrySession()
        assertTrue(visit.confirm())
        assertTrue(visit.accepted)
        assertFalse(visit.confirm())
        assertFalse(visit.cancel())
        visit.dispose()
        assertFalse(DedupEntrySession().accepted)
    }

    @Test fun removedEntryRejectsQueuedConfirmation() {
        val session = DedupEntrySession()
        session.dispose()
        assertFalse(session.confirm())
        assertFalse(session.cancel())
        assertFalse(session.accepted)
    }
}
