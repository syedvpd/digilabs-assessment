package com.tapoutguardian.core

import java.time.LocalDate
import java.time.ZoneId

/** Pure time arithmetic. Everything is epoch millis; zone rules are applied only when mapping dates. */
object Schedule {
    private const val MIN = 60_000L

    /** Instant at which `minuteOfDay` occurs on `date` in `zone` (DST gaps resolve forward). */
    fun instantFor(date: LocalDate, minuteOfDay: Int, zone: ZoneId): Long =
        date.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone).toInstant().toEpochMilli()

    fun startOfNextDay(date: LocalDate, zone: ZoneId): Long =
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Escalation grid: alerts at anchor+k*initialInterval while inside the initial phase, then every
     * laterInterval. Returns the first grid point strictly after `now`, so a late delivery produces
     * exactly one catch-up alert instead of a burst.
     */
    fun nextAlertAfter(anchor: Long, now: Long, s: Settings): Long {
        val ini = s.initialIntervalMin * MIN
        val later = s.laterIntervalMin * MIN
        val steps = (s.initialPhaseMin * MIN) / ini
        val lastInitial = anchor + steps * ini
        return if (now < lastInitial) {
            val k = if (now < anchor) 1 else (now - anchor) / ini + 1
            anchor + k * ini
        } else {
            lastInitial + ((now - lastInitial) / later + 1) * later
        }
    }
}
