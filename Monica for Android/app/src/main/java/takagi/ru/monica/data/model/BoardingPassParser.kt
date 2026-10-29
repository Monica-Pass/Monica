package takagi.ru.monica.data.model

import java.time.LocalDate

/** Fields read from an IATA Resolution 792 bar coded boarding pass (BCBP). */
data class BoardingPass(
    val passenger: String,
    val bookingReference: String,
    val origin: String,
    val destination: String,
    val carrier: String,
    val serviceNumber: String,
    val departure: String,
    val seat: String,
    val compartment: String,
)

/**
 * Parses only the mandatory BCBP items of each leg. Conditional and airline-private
 * sections are skipped by their declared sizes and never copied into the pass.
 */
object BoardingPassParser {
    private const val HEADER_LENGTH = 23
    private const val LEG_LENGTH = 35

    private data class Leg(
        val pnr: String, val from: String, val to: String, val carrier: String,
        val flight: String, val julianDay: Int, val compartment: String, val seat: String,
    )

    fun parse(raw: String, today: LocalDate = LocalDate.now()): BoardingPass? {
        val data = raw.trimEnd('\r', '\n')
        if (data.length < HEADER_LENGTH + LEG_LENGTH + 2 || data[0] != 'M') return null
        val legCount = data[1].digitToIntOrNull()?.takeIf { it in 1..4 } ?: return null
        val passenger = data.substring(2, 22).trim()
        val legs = ArrayList<Leg>(legCount)
        var offset = HEADER_LENGTH
        repeat(legCount) {
            if (data.length < offset + LEG_LENGTH + 2) return null
            val leg = data.substring(offset, offset + LEG_LENGTH)
            val conditionalSize = data.substring(offset + LEG_LENGTH, offset + LEG_LENGTH + 2)
                .toIntOrNull(16) ?: return null
            val from = leg.substring(7, 10).trim()
            val to = leg.substring(10, 13).trim()
            if (!from.isAirportCode() || !to.isAirportCode()) return null
            val julianDay = leg.substring(21, 24).trim().toIntOrNull()?.takeIf { it in 1..366 } ?: return null
            legs += Leg(
                pnr = leg.substring(0, 7).trim(),
                from = from,
                to = to,
                carrier = leg.substring(13, 16).trim(),
                flight = leg.substring(16, 21).trim().trimStart('0').ifEmpty { "0" },
                julianDay = julianDay,
                compartment = leg.substring(24, 25).trim(),
                seat = leg.substring(25, 29).trim().trimStart('0'),
            )
            offset += LEG_LENGTH + 2 + conditionalSize
            if (offset > data.length) return null
        }
        if (passenger.isEmpty() || legs.any { it.carrier.isEmpty() }) return null
        fun joined(value: (Leg) -> String) = legs.map(value).filter { it.isNotEmpty() }.distinct().joinToString(" / ")
        return BoardingPass(
            passenger = passenger,
            bookingReference = joined { it.pnr },
            origin = legs.first().from,
            destination = legs.last().to,
            carrier = joined { it.carrier },
            serviceNumber = joined { it.carrier + it.flight },
            departure = flightDate(legs.first().julianDay, today)?.toString().orEmpty(),
            seat = joined { it.seat },
            compartment = joined { it.compartment },
        )
    }

    /** BCBP stores no year, so choose the calendar date closest to the scan day. */
    internal fun flightDate(julianDay: Int, today: LocalDate): LocalDate? =
        (today.year - 1..today.year + 1)
            .filter { julianDay <= it.let(java.time.Year::of).length() }
            .map { LocalDate.ofYearDay(it, julianDay) }
            .minByOrNull { kotlin.math.abs(it.toEpochDay() - today.toEpochDay()) }

    private fun String.isAirportCode() = length == 3 && all { it in 'A'..'Z' }
}
