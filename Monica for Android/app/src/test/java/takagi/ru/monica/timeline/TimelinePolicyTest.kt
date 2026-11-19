package takagi.ru.monica.timeline

import org.junit.Test
import org.junit.Assert.*
import java.time.*

class TimelinePolicyTest {
    private fun event(id: String, scope: String, time: Long, empty: Boolean) = TimelineReader.Entry(id,
        TimelineRecord(id, time, "SYNC", "OBSERVED", scope, "encrypted", Long.MAX_VALUE, false),
        TimelineDetails(automaticEmptySync = empty), "READY", emptyList())
    @Test fun onlyExplicitEmptyChecksFromSameVaultAndDayCollapse() {
        val now = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
        val list = listOf(event("1", "bitwarden:1", now, true), event("2", "bitwarden:2", now, true),
            event("3", "bitwarden:1", now, false), event("4", "bitwarden:1", now, true))
        val collapsed = TimelineReader.collapse(list)
        assertEquals(3, collapsed.size)
        assertEquals(2, collapsed.first().checkCount)
    }
    @Test fun calendarDaysHandleDstAndYearBoundaries() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(LocalDate.of(2026, 3, 8), TimelineReader.day(Instant.parse("2026-03-09T03:30:00Z").toEpochMilli(), zone))
        assertEquals(LocalDate.of(2025, 12, 31), TimelineReader.day(Instant.parse("2026-01-01T02:00:00Z").toEpochMilli(), zone))
    }
    @Test fun searchUsesLabelsNotFactValues() {
        val e = event("1", "local", 0, false).copy(details = TimelineDetails(title = "Synthetic", facts = listOf(TimelineFact("Password", "secret-before", "secret-after"))))
        assertTrue(TimelineReader.matches(e, "Synthetic"))
        assertTrue(TimelineReader.matches(e, "Password"))
        assertFalse(TimelineReader.matches(e, "secret-before"))
    }
}
