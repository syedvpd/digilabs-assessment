package com.tapoutguardian.core

import java.time.ZoneId

/** Absolute instants (epoch millis) at which the platform must wake the app. null = nothing to schedule. */
data class Plan(val eveningStartAt: Long?, val morningStartAt: Long?, val tickAt: Long?)

/**
 * Derives the desired alarm set purely from persisted state. Because it is a function of the database,
 * re-applying it after a reboot, time-zone change or process death always yields the same alarms.
 */
object Planner {
    fun plan(now: Long, s: Settings, zone: ZoneId, active: List<WorkflowRecord>, exists: (String, Kind) -> Boolean): Plan =
        Plan(
            eveningStartAt = nextStart(Kind.EVENING, s.eveningEnabled, s.eveningMinute, now, zone, exists),
            morningStartAt = nextStart(Kind.MORNING, s.morningEnabled, s.morningMinute, now, zone, exists),
            tickAt = tick(now, active),
        )

    private fun nextStart(kind: Kind, enabled: Boolean, minute: Int, now: Long, zone: ZoneId, exists: (String, Kind) -> Boolean): Long? {
        if (!enabled) return null
        val today = localDateOf(now, zone)
        val candidate = Schedule.instantFor(today, minute, zone)
        // A date that already has a workflow (or whose time has passed) never gets a second one.
        return if (candidate > now && !exists(today.toString(), kind)) candidate
        else Schedule.instantFor(today.plusDays(1), minute, zone)
    }

    private fun tick(now: Long, active: List<WorkflowRecord>): Long? {
        val times = active.mapNotNull { r ->
            when (r.state) {
                State.WAITING_FOR_SECOND_CONFIRMATION -> minOf(r.secondDeadlineAt ?: r.expiresAt, r.expiresAt)
                State.FIRST_REMINDER_PENDING, State.SECOND_CHECK_DUE, State.RECOVERY -> minOf(r.nextAlertAt ?: r.expiresAt, r.expiresAt)
                State.MORNING_NOTIFIED -> r.expiresAt
                else -> null
            }
        }
        // Floor protects against a tight alarm loop if a bug ever left a due time in the past.
        return times.minOrNull()?.let { maxOf(it, now + 1_000) }
    }
}
