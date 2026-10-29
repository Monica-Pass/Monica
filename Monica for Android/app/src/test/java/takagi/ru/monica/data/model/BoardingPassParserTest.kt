package takagi.ru.monica.data.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardingPassParserTest {
    private val today = LocalDate.of(2026, 10, 29)

    @Test fun parsesTheMandatoryItemsOfASingleLeg() {
        val pass = BoardingPassParser.parse("M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 100", today)!!
        assertEquals("DESMARAIS/LUC", pass.passenger)
        assertEquals("ABC123", pass.bookingReference)
        assertEquals("YUL", pass.origin)
        assertEquals("FRA", pass.destination)
        assertEquals("AC", pass.carrier)
        assertEquals("AC834", pass.serviceNumber)
        assertEquals("2026-11-22", pass.departure)
        assertEquals("1A", pass.seat)
        assertEquals("J", pass.compartment)
    }

    @Test fun skipsConditionalDataAndSpansConnectingLegs() {
        val raw = "M2DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 10A>5180  W0D" +
            "DEF456 FRAGVALH 3664 327Y012C0012 100"
        val pass = BoardingPassParser.parse(raw, today)!!
        assertEquals("YUL", pass.origin)
        assertEquals("GVA", pass.destination)
        assertEquals("ABC123 / DEF456", pass.bookingReference)
        assertEquals("AC834 / LH3664", pass.serviceNumber)
        assertEquals("1A / 12C", pass.seat)
        assertEquals("J / Y", pass.compartment)
    }

    @Test fun flightDateUsesTheClosestYear() {
        assertEquals(LocalDate.of(2026, 12, 26), BoardingPassParser.flightDate(360, LocalDate.of(2027, 1, 2)))
        assertEquals(LocalDate.of(2027, 1, 3), BoardingPassParser.flightDate(3, LocalDate.of(2026, 12, 30)))
    }

    @Test fun rejectsOtherBarcodes() {
        assertNull(BoardingPassParser.parse("otpauth://totp/Example?secret=JBSWY3DPEHPK3PXP", today))
        assertNull(BoardingPassParser.parse("M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 1", today))
        assertNull(BoardingPassParser.parse("M1DESMARAIS/LUC       EABC123 yulFRAAC 0834 326J001A0025 100", today))
    }
}
