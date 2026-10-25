package com.tapoutguardian.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Which attendance workflow a record belongs to. Morning and evening never share state. */
enum class Kind { EVENING, MORNING, TEST }

/**
 * Workflow states. "IDLE" is represented by the absence of a record for (date, kind).
 *
 * EVENING / TEST:
 *   FIRST_REMINDER_PENDING -> WAITING_FOR_SECOND_CONFIRMATION -> SECOND_CHECK_DUE -> COMPLETED
 *   RECOVERY is entered when the user says "not yet / I forgot"; from there the user must confirm
 *   a new first tap-out and wait out a new second-check delay.
 * MORNING:
 *   MORNING_NOTIFIED -> MORNING_ACKNOWLEDGED
 * Any non-terminal state may end in EXPIRED (the attendance day ended without completion).
 */
enum class State {
    FIRST_REMINDER_PENDING,
    WAITING_FOR_SECOND_CONFIRMATION,
    SECOND_CHECK_DUE,
    RECOVERY,
    COMPLETED,
    MORNING_NOTIFIED,
    MORNING_ACKNOWLEDGED,
    EXPIRED;

    val isTerminal: Boolean get() = this == COMPLETED || this == EXPIRED || this == MORNING_ACKNOWLEDGED

    /** States in which the escalation schedule keeps alerting the user. */
    val isAlerting: Boolean get() = this == FIRST_REMINDER_PENDING || this == SECOND_CHECK_DUE || this == RECOVERY
}

enum class HistoryType {
    STARTED, STARTED_LATE, ALERT, FIRST_CONFIRMED, SECOND_DUE, SECOND_CONFIRMED, NOT_YET, SNOOZED,
    EXPIRED, RESET, MORNING_NOTIFIED, MORNING_ACK, CLOCK_ADJUSTED, KIOSK_EXIT
}

object Rules {
    /** A trigger that was missed (phone off, process frozen) is still honoured for this long. */
    const val LATE_START_WINDOW_MS = 3L * 60 * 60 * 1000
    /** Wall-clock vs. elapsed-realtime disagreement above this means the user/network changed the clock. */
    const val CLOCK_TOLERANCE_MS = 5_000L
    /** A workflow whose second deadline lies past midnight is kept alive this long after the deadline. */
    const val POST_DEADLINE_GRACE_MS = 30L * 60 * 1000
    const val TEST_TIMEOUT_MS = 30L * 60 * 1000
    const val TEST_KEY = "TEST"
    const val STATE_VERSION = 1
}

data class Settings(
    val morningEnabled: Boolean = true,
    val morningMinute: Int = 9 * 60,
    val eveningEnabled: Boolean = true,
    val eveningMinute: Int = 18 * 60,
    val secondDelayMin: Int = 5,
    val initialIntervalMin: Int = 2,
    val initialPhaseMin: Int = 20,
    val laterIntervalMin: Int = 5,
    val snoozeMin: Int = 5,
    val maxSnoozes: Int = 3,
    val soundEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    val attendancePackage: String = "",
    val kioskOptIn: Boolean = false,
    val testDelaySec: Int = 60,
    /** Instant from which scheduled triggers count as "missed" if they were not delivered. */
    val armedAt: Long = 0,
) {
    fun sanitized(): Settings = copy(
        morningMinute = morningMinute.coerceIn(0, 1439),
        eveningMinute = eveningMinute.coerceIn(0, 1439),
        secondDelayMin = secondDelayMin.coerceIn(1, 30),
        initialIntervalMin = initialIntervalMin.coerceIn(1, 15),
        initialPhaseMin = initialPhaseMin.coerceIn(1, 120),
        laterIntervalMin = laterIntervalMin.coerceIn(1, 30),
        snoozeMin = snoozeMin.coerceIn(1, 15),
        maxSnoozes = maxSnoozes.coerceIn(0, 10),
        testDelaySec = testDelaySec.coerceIn(10, 300),
        attendancePackage = attendancePackage.trim(),
    )
}

/** The single authoritative persisted record for one (attendance date, kind). */
data class WorkflowRecord(
    val dateKey: String,
    val kind: Kind,
    val state: State,
    /** Incremented on every committed transition. */
    val rev: Long = 0,
    val stateVersion: Int = Rules.STATE_VERSION,
    val zoneId: String,
    val scheduledAt: Long,
    val startedAt: Long,
    val firstConfirmedAt: Long? = null,
    /** elapsedRealtime + boot id captured at the first confirmation (clock-change detection). */
    val confirmElapsed: Long? = null,
    val confirmBootId: Long? = null,
    val secondDelayMs: Long = 5 * 60_000L,
    val secondDeadlineAt: Long? = null,
    val secondDueAt: Long? = null,
    val secondConfirmedAt: Long? = null,
    val completedAt: Long? = null,
    val lastAlertAt: Long? = null,
    val nextAlertAt: Long? = null,
    val alertCount: Int = 0,
    /** Start of the current escalation grid. */
    val alertAnchorAt: Long = startedAt,
    val snoozeCount: Int = 0,
    val notYetCount: Int = 0,
    val confirmCount: Int = 0,
    val expiresAt: Long,
    val endedAt: Long? = null,
    val endReason: String? = null,
) {
    val isTest: Boolean get() = kind == Kind.TEST
}

data class HistoryEntry(
    val id: Long = 0,
    val dateKey: String,
    val kind: Kind,
    val at: Long,
    val type: HistoryType,
    val detail: String = "",
)

/** Time source. `elapsedMillis` includes deep sleep; `bootId` changes on every reboot. */
interface Clock {
    fun wallMillis(): Long
    fun elapsedMillis(): Long
    fun bootId(): Long
    fun zone(): ZoneId
}

fun dateKeyOf(epochMillis: Long, zone: ZoneId): String = localDateOf(epochMillis, zone).toString()
fun localDateOf(epochMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
