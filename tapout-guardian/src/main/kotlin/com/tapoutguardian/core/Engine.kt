package com.tapoutguardian.core

import kotlin.math.abs

enum class Outcome { APPLIED, DUPLICATE, TOO_EARLY, INVALID_STATE, NOT_FOUND, LIMIT_REACHED, BLOCKED,
    /** The transaction failed and was rolled back; nothing changed and the user must retry. */
    ERROR }

data class CommandResult(val outcome: Outcome, val record: WorkflowRecord?)

/**
 * Whether the Stage-2 "YES, TAP-OUT IS DONE" action may be offered as enabled at [now]. The engine re-checks this against the
 * persisted deadline inside the transaction, so a stale or manipulated UI can never complete the workflow early.
 */
fun WorkflowRecord.secondConfirmEnabled(now: Long): Boolean = when (state) {
    State.SECOND_CHECK_DUE -> true
    State.WAITING_FOR_SECOND_CONFIRMATION -> secondDeadlineAt?.let { now >= it } == true
    else -> false
}

/** What the platform layer must do loudly right now. Quiet state refreshes are derived from the database. */
data class AdvanceResult(val alertEvening: Boolean = false, val alertMorning: Boolean = false)

/**
 * The single authoritative state machine. Every entry point (alarm, boot, time change, app launch,
 * notification action, button tap) funnels into [advance] or one of the commands below. Each one:
 *  - runs under one process-wide lock and one database transaction (all-or-nothing),
 *  - first brings the record up to date for "now" (late alarms, clock changes, expiry), so the outcome
 *    depends on persisted deadlines and never on which event happened to arrive,
 *  - is idempotent: replaying an event cannot move a deadline or duplicate history.
 */
class WorkflowEngine(private val store: WorkflowStore, private val clock: Clock) {
    private val lock = Any()

    /** Read access serialised with writers. */
    fun <T> read(block: (WorkflowStore) -> T): T = synchronized(lock) { block(store) }

    fun advance(@Suppress("UNUSED_PARAMETER") reason: String): AdvanceResult = synchronized(lock) {
        store.inTransaction { advanceLocked() }
    }

    // ---------------------------------------------------------------- user commands

    /** "I HAVE TAPPED OUT" (stage 1). Valid from FIRST_REMINDER_PENDING and RECOVERY only. */
    fun confirmFirst(dateKey: String, kind: Kind): CommandResult = mutate(dateKey, kind) { cur, now, s ->
        if (cur.kind == Kind.MORNING) return@mutate CommandResult(Outcome.INVALID_STATE, cur)
        when (cur.state) {
            State.FIRST_REMINDER_PENDING, State.RECOVERY -> {
                val delay = if (cur.isTest) s.testDelaySec * 1000L else s.secondDelayMin * 60_000L
                val deadline = now + delay
                val n = save(cur.copy(
                    state = State.WAITING_FOR_SECOND_CONFIRMATION,
                    firstConfirmedAt = now, confirmElapsed = clock.elapsedMillis(), confirmBootId = clock.bootId(),
                    secondDelayMs = delay, secondDeadlineAt = deadline, secondDueAt = null, nextAlertAt = null,
                    confirmCount = cur.confirmCount + 1,
                    expiresAt = maxOf(cur.expiresAt, deadline + Rules.POST_DEADLINE_GRACE_MS),
                ))
                hist(n, HistoryType.FIRST_CONFIRMED, "second check due at $deadline", now)
                CommandResult(Outcome.APPLIED, n)
            }
            State.WAITING_FOR_SECOND_CONFIRMATION -> CommandResult(Outcome.DUPLICATE, cur)
            else -> CommandResult(Outcome.INVALID_STATE, cur)
        }
    }

    /** "YES, TAP-OUT IS DONE" (stage 2). Only valid once the persisted deadline has passed. */
    fun confirmSecond(dateKey: String, kind: Kind): CommandResult = mutate(dateKey, kind) { cur, now, _ ->
        when (cur.state) {
            State.SECOND_CHECK_DUE -> {
                val n = save(cur.copy(
                    state = State.COMPLETED, secondConfirmedAt = now, completedAt = now, endedAt = now,
                    endReason = "confirmed twice by user", nextAlertAt = null,
                ))
                hist(n, HistoryType.SECOND_CONFIRMED, "", now)
                CommandResult(Outcome.APPLIED, n)
            }
            State.WAITING_FOR_SECOND_CONFIRMATION -> CommandResult(Outcome.TOO_EARLY, cur)
            State.COMPLETED -> CommandResult(Outcome.DUPLICATE, cur)
            else -> CommandResult(Outcome.INVALID_STATE, cur)
        }
    }

    /**
     * "Not yet / I forgot". Explicit restart-confirmation transition:
     *   FIRST_REMINDER_PENDING | WAITING_FOR_SECOND_CONFIRMATION | SECOND_CHECK_DUE  --notYet-->  RECOVERY
     * RECOVERY clears the second deadline (it can no longer be satisfied), keeps the workflow open, restarts the escalation
     * grid from "now" and never completes anything. The user must press "I HAVE TAPPED OUT" again, which records a NEW first
     * confirmation and a NEW deadline (= that confirmation + delay); only then can Stage 2 be answered.
     */
    fun notYet(dateKey: String, kind: Kind): CommandResult = mutate(dateKey, kind) { cur, now, s ->
        if (cur.kind == Kind.MORNING) return@mutate CommandResult(Outcome.INVALID_STATE, cur)
        when (cur.state) {
            State.FIRST_REMINDER_PENDING, State.WAITING_FOR_SECOND_CONFIRMATION, State.SECOND_CHECK_DUE -> {
                val n = save(cur.copy(
                    state = State.RECOVERY, notYetCount = cur.notYetCount + 1, secondDeadlineAt = null,
                    alertAnchorAt = now, nextAlertAt = Schedule.nextAlertAfter(now, now, s),
                ))
                hist(n, HistoryType.NOT_YET, "from ${cur.state}", now)
                CommandResult(Outcome.APPLIED, n)
            }
            State.RECOVERY -> CommandResult(Outcome.DUPLICATE, cur)
            else -> CommandResult(Outcome.INVALID_STATE, cur)
        }
    }

    /** Bounded snooze: delays the next alert only. The notification stays and the day's expiry still applies. */
    fun snooze(dateKey: String, kind: Kind): CommandResult = mutate(dateKey, kind) { cur, now, s ->
        if (!cur.state.isAlerting || cur.kind == Kind.MORNING) return@mutate CommandResult(Outcome.INVALID_STATE, cur)
        if (cur.snoozeCount >= s.maxSnoozes) return@mutate CommandResult(Outcome.LIMIT_REACHED, cur)
        val n = save(cur.copy(snoozeCount = cur.snoozeCount + 1, nextAlertAt = now + s.snoozeMin * 60_000L))
        hist(n, HistoryType.SNOOZED, "${n.snoozeCount}/${s.maxSnoozes}", now)
        CommandResult(Outcome.APPLIED, n)
    }

    fun acknowledgeMorning(dateKey: String): CommandResult = mutate(dateKey, Kind.MORNING) { cur, now, _ ->
        when (cur.state) {
            State.MORNING_NOTIFIED -> {
                val n = save(cur.copy(state = State.MORNING_ACKNOWLEDGED, endedAt = now, endReason = "acknowledged by user"))
                hist(n, HistoryType.MORNING_ACK, "", now)
                CommandResult(Outcome.APPLIED, n)
            }
            State.MORNING_ACKNOWLEDGED -> CommandResult(Outcome.DUPLICATE, cur)
            else -> CommandResult(Outcome.INVALID_STATE, cur)
        }
    }

    /** Starts a clearly separated TEST workflow (short second-check delay). Never touches a real day. */
    fun startTest(): CommandResult = synchronized(lock) {
        store.inTransaction {
            val now = clock.wallMillis(); val s = store.settings(); val zone = clock.zone()
            val existing = store.get(Rules.TEST_KEY, Kind.TEST)
            if (existing != null && !existing.state.isTerminal) return@inTransaction CommandResult(Outcome.DUPLICATE, existing)
            if (store.active().any { it.kind == Kind.EVENING }) return@inTransaction CommandResult(Outcome.BLOCKED, null)
            val r = WorkflowRecord(
                dateKey = Rules.TEST_KEY, kind = Kind.TEST, state = State.FIRST_REMINDER_PENDING, rev = 1,
                zoneId = zone.id, scheduledAt = now, startedAt = now, lastAlertAt = now, alertCount = 1,
                nextAlertAt = Schedule.nextAlertAfter(now, now, s), alertAnchorAt = now,
                expiresAt = now + Rules.TEST_TIMEOUT_MS,
            )
            store.put(r)
            hist(r, HistoryType.STARTED, "test workflow", now)
            hist(r, HistoryType.ALERT, "#1", now)
            CommandResult(Outcome.APPLIED, r)
        }
    }

    /** Deletes today's evening record and any test record, then re-evaluates (may restart a due workflow). */
    fun reset(): AdvanceResult = synchronized(lock) {
        store.inTransaction {
            advanceLocked()
            val now = clock.wallMillis()
            val key = dateKeyOf(now, clock.zone())
            store.delete(key, Kind.EVENING)
            store.delete(Rules.TEST_KEY, Kind.TEST)
            store.addHistory(HistoryEntry(dateKey = key, kind = Kind.EVENING, at = now, type = HistoryType.RESET, detail = "reset by user"))
            advanceLocked()
        }
    }

    fun recordKioskExit(dateKey: String, kind: Kind) = synchronized(lock) {
        store.inTransaction {
            store.addHistory(HistoryEntry(dateKey = dateKey, kind = kind, at = clock.wallMillis(), type = HistoryType.KIOSK_EXIT,
                detail = "emergency exit from kiosk / screen pinning"))
        }
    }

    /** Applies pending time-based work under the old settings, then saves the new ones and re-arms. */
    fun updateSettings(change: (Settings) -> Settings) = synchronized(lock) {
        store.inTransaction {
            advanceLocked()
            // The transform runs against the *stored* settings, so two quick edits of different fields cannot overwrite each other.
            store.saveSettings(change(store.settings()).sanitized().copy(armedAt = clock.wallMillis()))
        }
    }

    // ---------------------------------------------------------------- internals

    private fun mutate(
        dateKey: String, kind: Kind,
        block: (cur: WorkflowRecord, now: Long, s: Settings) -> CommandResult,
    ): CommandResult = synchronized(lock) {
        store.inTransaction {
            val rec = store.get(dateKey, kind) ?: return@inTransaction CommandResult(Outcome.NOT_FOUND, null)
            val now = clock.wallMillis(); val s = store.settings()
            val (cur, _) = advanceRecord(rec, now, s)
            block(cur, now, s)
        }
    }

    private fun advanceLocked(): AdvanceResult {
        val now = clock.wallMillis(); val zone = clock.zone()
        var s = store.settings()
        if (s.armedAt == 0L) { s = s.copy(armedAt = now); store.saveSettings(s) }
        var alertE = false; var alertM = false
        for (rec in store.active()) {
            val (_, alerted) = advanceRecord(rec, now, s)
            if (alerted) { if (rec.kind == Kind.MORNING) alertM = true else alertE = true }
        }
        if (startIfDue(Kind.EVENING, s.eveningEnabled, s.eveningMinute, now, s, zone)) alertE = true
        if (startIfDue(Kind.MORNING, s.morningEnabled, s.morningMinute, now, s, zone)) alertM = true
        return AdvanceResult(alertE, alertM)
    }

    /** Returns the updated record and whether a loud alert is due now. */
    private fun advanceRecord(rec: WorkflowRecord, now: Long, s: Settings): Pair<WorkflowRecord, Boolean> {
        var r = rec
        if (r.state.isTerminal) return r to false
        if (now >= r.expiresAt) return expire(r, now) to false
        if (r.state == State.WAITING_FOR_SECOND_CONFIRMATION) {
            r = reconcileClock(r, now)
            val deadline = r.secondDeadlineAt ?: return r to false
            if (now < deadline) return r to false
            val n = save(r.copy(
                state = State.SECOND_CHECK_DUE, secondDueAt = now, alertAnchorAt = deadline,
                lastAlertAt = now, alertCount = r.alertCount + 1, nextAlertAt = Schedule.nextAlertAfter(deadline, now, s),
            ))
            hist(n, HistoryType.SECOND_DUE, "deadline was $deadline", now)
            hist(n, HistoryType.ALERT, "#${n.alertCount}", now)
            return n to true
        }
        if (r.state.isAlerting) {
            val next = r.nextAlertAt
            if (next != null && now >= next) {
                val n = save(r.copy(
                    lastAlertAt = now, alertCount = r.alertCount + 1,
                    nextAlertAt = Schedule.nextAlertAfter(r.alertAnchorAt, now, s),
                ))
                hist(n, HistoryType.ALERT, "#${n.alertCount}", now)
                return n to true
            }
        }
        return r to false
    }

    private fun expire(r: WorkflowRecord, now: Long): WorkflowRecord {
        val reason = when {
            r.isTest -> "test timed out"
            r.state == State.MORNING_NOTIFIED -> "never acknowledged"
            else -> "day ended without completion (was ${r.state})"
        }
        val n = save(r.copy(state = State.EXPIRED, endedAt = now, endReason = reason, nextAlertAt = null))
        hist(n, HistoryType.EXPIRED, reason, now)
        return n
    }

    private fun reconcileClock(r: WorkflowRecord, now: Long): WorkflowRecord {
        val el = r.confirmElapsed; val boot = r.confirmBootId; val fc = r.firstConfirmedAt
        if (el == null || boot == null || fc == null) return r
        val nowEl = clock.elapsedMillis()
        // elapsedRealtime only ever grows within one boot, so a smaller value proves a reboot even when the platform
        // could not give us a boot id (it falls back to a constant). Without this a reboot would look like a clock change
        // and silently push the second-check deadline out.
        if (boot != clock.bootId() || nowEl < el) {
            // Rebooted: elapsedRealtime restarted, so the persisted wall-clock deadline is the truth.
            return save(r.copy(confirmBootId = clock.bootId(), confirmElapsed = nowEl - (now - fc)))
        }
        val elapsedSince = nowEl - el
        val wallSince = now - fc
        if (abs(wallSince - elapsedSince) <= Rules.CLOCK_TOLERANCE_MS) return r
        // The wall clock was changed while counting down: keep the *remaining elapsed time*, not the old instant.
        val newConfirm = now - elapsedSince
        val newDeadline = newConfirm + r.secondDelayMs
        val n = save(r.copy(
            firstConfirmedAt = newConfirm, secondDeadlineAt = newDeadline,
            expiresAt = maxOf(r.expiresAt, newDeadline + Rules.POST_DEADLINE_GRACE_MS),
        ))
        hist(n, HistoryType.CLOCK_ADJUSTED, "second deadline ${r.secondDeadlineAt} -> $newDeadline", now)
        return n
    }

    private fun startIfDue(kind: Kind, enabled: Boolean, minute: Int, now: Long, s: Settings, zone: java.time.ZoneId): Boolean {
        if (!enabled) return false
        val date = localDateOf(now, zone)
        val key = date.toString()
        val scheduled = Schedule.instantFor(date, minute, zone)
        if (scheduled > now) return false                               // not yet due
        if (scheduled < s.armedAt) return false                         // was never armed for it (fresh install / settings change)
        if (now - scheduled > Rules.LATE_START_WINDOW_MS) return false // too stale to be useful
        if (store.get(key, kind) != null) return false                  // one workflow per date per kind, ever
        val eod = Schedule.startOfNextDay(date, zone)
        val late = now - scheduled > 60_000
        val base = WorkflowRecord(
            dateKey = key, kind = kind, state = State.FIRST_REMINDER_PENDING, rev = 1, zoneId = zone.id,
            scheduledAt = scheduled, startedAt = now, lastAlertAt = now, alertCount = 1, alertAnchorAt = scheduled,
            expiresAt = eod, secondDelayMs = s.secondDelayMin * 60_000L,
        )
        val r = if (kind == Kind.EVENING) {
            store.get(Rules.TEST_KEY, Kind.TEST)?.takeIf { !it.state.isTerminal }?.let { expire(it, now) }
            base.copy(nextAlertAt = Schedule.nextAlertAfter(scheduled, now, s))
        } else {
            base.copy(state = State.MORNING_NOTIFIED, nextAlertAt = null)
        }
        store.put(r)
        hist(r, if (late) HistoryType.STARTED_LATE else HistoryType.STARTED, if (late) "late by ${(now - scheduled) / 1000}s" else "", now)
        hist(r, if (kind == Kind.MORNING) HistoryType.MORNING_NOTIFIED else HistoryType.ALERT, "#1", now)
        store.pruneOlderThan(now - 90L * 24 * 3600 * 1000)
        return true
    }

    private fun save(r: WorkflowRecord): WorkflowRecord = r.copy(rev = r.rev + 1).also { store.put(it) }

    private fun hist(r: WorkflowRecord, type: HistoryType, detail: String, at: Long) =
        store.addHistory(HistoryEntry(dateKey = r.dateKey, kind = r.kind, at = at, type = type, detail = detail))
}
