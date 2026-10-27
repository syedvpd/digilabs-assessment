package com.tapoutguardian.core

/** What the dashboard's ring button shows. Derived only from the persisted record, never from UI state or timers. */
enum class RingKind { IDLE, TAP_OUT, COUNTDOWN, VERIFY_READY, VERIFY_DUE, DONE, MISSED }

/** Dot under a day in the week strip. */
enum class DayDot { NONE, DONE, MISSED, IN_PROGRESS }

object Presentation {
    fun ring(r: WorkflowRecord?, now: Long): RingKind {
        if (r == null || r.kind == Kind.MORNING) return RingKind.IDLE
        return when (r.state) {
            State.FIRST_REMINDER_PENDING, State.RECOVERY -> RingKind.TAP_OUT
            State.WAITING_FOR_SECOND_CONFIRMATION -> if (r.secondConfirmEnabled(now)) RingKind.VERIFY_READY else RingKind.COUNTDOWN
            State.SECOND_CHECK_DUE -> RingKind.VERIFY_DUE
            State.COMPLETED -> RingKind.DONE          // the ONLY path to a success state
            State.EXPIRED -> RingKind.MISSED
            else -> RingKind.IDLE
        }
    }

    fun dot(r: WorkflowRecord?): DayDot = when (r?.state) {
        null -> DayDot.NONE
        State.COMPLETED -> DayDot.DONE
        State.EXPIRED -> DayDot.MISSED
        else -> DayDot.IN_PROGRESS
    }
}
