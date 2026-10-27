package com.tapoutguardian.ui

import com.tapoutguardian.core.Kind
import com.tapoutguardian.core.State
import com.tapoutguardian.core.WorkflowRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

object Format {
    private fun zone(): ZoneId = TimeZone.getDefault().toZoneId()
    private fun fmt(p: String) = DateTimeFormatter.ofPattern(p, Locale.getDefault()).withZone(zone())

    fun time(ms: Long): String = fmt("h:mm a").format(Instant.ofEpochMilli(ms))
    fun timeSeconds(ms: Long): String = fmt("h:mm:ss a").format(Instant.ofEpochMilli(ms))
    fun dateTime(ms: Long): String = fmt("EEE d MMM, h:mm a").format(Instant.ofEpochMilli(ms))
    fun date(ms: Long): String = fmt("EEE d MMM yyyy").format(Instant.ofEpochMilli(ms))

    fun minuteOfDay(m: Int): String = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(java.time.LocalTime.of(m / 60, m % 60))

    fun countdown(ms: Long): String {
        val s = (maxOf(ms, 0) + 999) / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    fun relative(ms: Long): String {
        val m = maxOf(ms, 0) / 60_000
        return when {
            m < 1 -> "in under a minute"
            m < 60 -> "in $m min"
            else -> "in ${m / 60}h ${m % 60}m"
        }
    }

    fun kindLabel(r: WorkflowRecord) = when (r.kind) { Kind.EVENING -> "Evening tap-out"; Kind.MORNING -> "Morning tap-in"; Kind.TEST -> "TEST workflow" }

    fun stateLabel(r: WorkflowRecord): String = when (r.state) {
        State.FIRST_REMINDER_PENDING -> "TAP-OUT NEEDS CONFIRMATION"
        State.WAITING_FOR_SECOND_CONFIRMATION -> "FIRST CONFIRMATION RECORDED - WAITING FOR SECOND CHECK"
        State.SECOND_CHECK_DUE -> "SECOND CHECK DUE"
        State.RECOVERY -> "NOT CONFIRMED - TAP OUT NOW"
        State.COMPLETED -> "COMPLETED (confirmed twice)"
        State.MORNING_NOTIFIED -> "REMINDER SENT"
        State.MORNING_ACKNOWLEDGED -> "ACKNOWLEDGED"
        State.EXPIRED -> if (r.kind == Kind.MORNING) "NOT ACKNOWLEDGED" else "MISSED / INTERRUPTED"
    }
}
