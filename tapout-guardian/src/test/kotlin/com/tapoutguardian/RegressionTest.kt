package com.tapoutguardian

import com.tapoutguardian.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression tests for the "five minutes is a deadline, not a dismissal/completion" requirements. */
class RegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = "2026-03-10"
    private var env: Env? = null

    private fun newEnv(faulty: Boolean = false): Env {
        val e = Env(tmp.newFolder(), utc(day, "08:00"), faulty = faulty)
        e.coord.onTrigger("launch"); env = e
        return e
    }
    @After fun tearDown() { env?.rawDb?.close() }

    /** 18:00 start, first confirmation at 18:03 -> deadline 18:08. */
    private fun confirmedAt1803(): Env {
        val e = newEnv()
        e.runAlarmsUntil(utc(day, "18:03"))
        assertEquals(Outcome.APPLIED, e.coord.confirmFirst(day, Kind.EVENING).outcome)
        return e
    }

    @Test fun firstConfirmationPersistsDeadlineExactlyFiveMinutesLaterAndStaysPending() {
        val e = confirmedAt1803()
        val r = e.evening()!!
        assertEquals(State.WAITING_FOR_SECOND_CONFIRMATION, r.state)
        assertEquals(utc(day, "18:03"), r.firstConfirmedAt)
        assertEquals(utc(day, "18:08"), r.secondDeadlineAt)
        assertNull(r.completedAt); assertNull(r.secondConfirmedAt)
        assertNotNull("notification stays", e.notifier.eveningShown)
        assertEquals("second-check alarm is its own alarm, set to the deadline", utc(day, "18:08"), e.alarms.last!!.tickAt)
    }

    @Test fun countdownExpiryNeverCompletesOrDismissesTheWorkflow() {
        val e = confirmedAt1803()
        e.notifier.loudAlerts.clear()
        e.runAlarmsUntil(utc(day, "19:30"))                 // user does nothing for 1h27m
        val r = e.evening()!!
        assertEquals(State.SECOND_CHECK_DUE, r.state)
        assertNull(r.completedAt); assertNull(r.secondConfirmedAt)
        assertNotNull("notification still pending", e.notifier.eveningShown)
        assertEquals("stage-2 alert fired exactly at the deadline", utc(day, "18:08"), e.notifier.loudAlerts.first())
        assertTrue("reminders keep coming after the deadline", e.notifier.loudAlerts.size > 5)
    }

    @Test fun stageTwoActivatesAtTheSavedDeadlineNotBefore() {
        val e = confirmedAt1803()
        val rec = { e.evening()!! }
        e.clock.advanceTo(utc(day, "18:08") - 1); e.coord.onTrigger("alarm")
        assertEquals(State.WAITING_FOR_SECOND_CONFIRMATION, rec().state)
        assertFalse(rec().secondConfirmEnabled(e.clock.wall))
        assertEquals(Outcome.TOO_EARLY, e.coord.confirmSecond(day, Kind.EVENING).outcome)
        e.clock.advance(1); e.coord.onTrigger("alarm")
        assertEquals(State.SECOND_CHECK_DUE, rec().state)
        assertTrue(rec().secondConfirmEnabled(e.clock.wall))
        assertEquals(utc(day, "18:08"), e.notifier.loudAlerts.last())
    }

    @Test fun yesGateFollowsThePersistedDeadlineEvenBeforeTheAlarmIsProcessed() {
        val e = confirmedAt1803()
        e.clock.advanceTo(utc(day, "18:09"))                // alarm not delivered yet
        assertEquals(State.WAITING_FOR_SECOND_CONFIRMATION, e.evening()!!.state)
        assertTrue(e.evening()!!.secondConfirmEnabled(e.clock.wall))
        assertEquals(Outcome.APPLIED, e.coord.confirmSecond(day, Kind.EVENING).outcome)   // engine reconciles, then accepts
        assertEquals(State.COMPLETED, e.evening()!!.state)
    }

    @Test fun yesGateIsClosedInEveryOtherState() {
        val e = newEnv(); e.runAlarmsUntil(utc(day, "18:00"))
        assertFalse(e.evening()!!.secondConfirmEnabled(e.clock.wall))                       // FIRST_REMINDER_PENDING
        e.coord.confirmFirst(day, Kind.EVENING); e.runAlarmsUntil(utc(day, "18:05")); e.coord.notYet(day, Kind.EVENING)
        assertFalse(e.evening()!!.secondConfirmEnabled(e.clock.wall))                       // RECOVERY
        assertEquals(Outcome.INVALID_STATE, e.coord.confirmSecond(day, Kind.EVENING).outcome)
    }

    @Test fun ignoringAndDismissingAlertsNeverCompletesAnything() {
        val e = confirmedAt1803(); e.runAlarmsUntil(utc(day, "18:20"))
        repeat(5) { e.notifier.eveningShown = null; e.coord.onNotificationDismissed(); e.clock.advance(MIN) }
        val r = e.evening()!!
        assertEquals(State.SECOND_CHECK_DUE, r.state)
        assertNull(r.completedAt)
        assertNotNull("dismissed notification is restored", e.notifier.eveningShown)
        assertEquals(0, e.history(HistoryType.SECOND_CONFIRMED).size)
    }

    @Test fun notYetDefinesAnExplicitRestartTransitionAndNeverResetsSilently() {
        val e = confirmedAt1803(); e.runAlarmsUntil(utc(day, "18:09"))
        assertEquals(State.SECOND_CHECK_DUE, e.evening()!!.state)
        e.clock.advanceTo(utc(day, "18:10"))
        assertEquals(Outcome.APPLIED, e.coord.notYet(day, Kind.EVENING).outcome)
        var r = e.evening()!!
        assertEquals(State.RECOVERY, r.state)
        assertNull("old deadline is cancelled, not reused", r.secondDeadlineAt)
        assertNull(r.completedAt); assertEquals(1, r.notYetCount)
        assertNotNull(r.nextAlertAt)                                   // reminders continue
        assertEquals(Outcome.DUPLICATE, e.coord.notYet(day, Kind.EVENING).outcome)
        assertEquals(1, e.history(HistoryType.NOT_YET).size)
        e.clock.advanceTo(utc(day, "18:12"))
        assertEquals(Outcome.INVALID_STATE, e.coord.confirmSecond(day, Kind.EVENING).outcome)
        assertEquals(Outcome.APPLIED, e.coord.confirmFirst(day, Kind.EVENING).outcome)   // explicit restart
        r = e.evening()!!
        assertEquals(State.WAITING_FOR_SECOND_CONFIRMATION, r.state)
        assertEquals("new deadline = new confirmation + 5 min", utc(day, "18:17"), r.secondDeadlineAt)
        assertEquals(utc(day, "18:17"), e.alarms.last!!.tickAt)
        e.clock.advanceTo(utc(day, "18:13"))
        assertEquals(Outcome.TOO_EARLY, e.coord.confirmSecond(day, Kind.EVENING).outcome)
    }

    @Test fun notYetDuringCountdownCancelsTheCountdownButKeepsTheWorkflow() {
        val e = confirmedAt1803()
        e.clock.advanceTo(utc(day, "18:05")); e.coord.notYet(day, Kind.EVENING)
        assertEquals(State.RECOVERY, e.evening()!!.state)
        e.runAlarmsUntil(utc(day, "18:09"))
        assertEquals("old deadline passing does nothing", State.RECOVERY, e.evening()!!.state)
        assertEquals(0, e.history(HistoryType.SECOND_DUE).size)
    }

    @Test fun duplicateButtonPressesNeverResetTheCountdown() {
        val e = confirmedAt1803()
        repeat(4) { e.clock.advance(30_000); assertEquals(Outcome.DUPLICATE, e.coord.confirmFirst(day, Kind.EVENING).outcome) }
        assertEquals(utc(day, "18:08"), e.evening()!!.secondDeadlineAt)
        assertEquals(utc(day, "18:03"), e.evening()!!.firstConfirmedAt)
    }

    @Test fun rebootDuringCountdownKeepsDeadlineEvenWhenTheBootIdIsUnavailable() {
        val e = confirmedAt1803()
        val bootBefore = e.clock.boot
        e.clock.reboot(downMs = 90_000)                      // 18:04:30, elapsedRealtime restarted
        e.clock.boot = bootBefore                            // platform could not supply a boot id: constant
        e.boot(); e.alarms.clear()
        e.coord.onTrigger("boot")
        val r = e.evening()!!
        assertEquals("a reboot must not look like a clock change", utc(day, "18:08"), r.secondDeadlineAt)
        assertEquals(0, e.history(HistoryType.CLOCK_ADJUSTED).size)
        assertEquals(utc(day, "18:08"), e.alarms.last!!.tickAt)
        e.runAlarmsUntil(utc(day, "18:08"))
        assertEquals(State.SECOND_CHECK_DUE, e.evening()!!.state)
    }

    @Test fun rebootAfterDeadlineRecoversIntoSecondCheckDueNotCompletion() {
        val e = confirmedAt1803()
        e.clock.reboot(downMs = 20 * MIN)                    // phone off across the deadline
        e.boot(); e.alarms.clear(); e.notifier.loudAlerts.clear()
        e.coord.onTrigger("boot")
        assertEquals(State.SECOND_CHECK_DUE, e.evening()!!.state)
        assertEquals(1, e.notifier.loudAlerts.size)
        assertNull(e.evening()!!.completedAt)
    }

    @Test fun staleAlarmForAnOldWorkflowCannotTouchANewerOne() {
        val e = confirmedAt1803()                            // old tick would be due at 18:08
        e.coord.reset()                                      // today's workflow is deleted and restarted from scratch
        val fresh = e.evening()!!
        assertEquals(State.FIRST_REMINDER_PENDING, fresh.state)
        e.clock.advanceTo(utc(day, "18:08")); e.coord.onTrigger("stale-tick")
        val r = e.evening()!!
        assertEquals(State.FIRST_REMINDER_PENDING, r.state)
        assertEquals(0, r.confirmCount); assertNull(r.secondDeadlineAt); assertNull(r.completedAt)
    }

    @Test fun lateAlarmRescheduleContinuesTheEscalation() {
        val e = confirmedAt1803()
        e.clock.advanceTo(utc(day, "18:40")); e.coord.onTrigger("late")
        val r = e.evening()!!
        assertEquals(State.SECOND_CHECK_DUE, r.state)
        assertEquals("next alert and the tick alarm agree", r.nextAlertAt, e.alarms.last!!.tickAt)
        assertTrue(r.nextAlertAt!! > e.clock.wall)
    }

    @Test fun databaseFailureIsReportedAsErrorAndNothingChanges() {
        val e = newEnv(faulty = true); e.runAlarmsUntil(utc(day, "18:00"))
        val before = e.evening()!!
        (e.db as FaultyDb).failOnWrite(0)
        assertEquals(Outcome.ERROR, e.coord.confirmFirst(day, Kind.EVENING).outcome)
        assertEquals(before, e.evening())
        assertEquals(State.FIRST_REMINDER_PENDING, e.notifier.eveningShown!!.state)
        (e.db as FaultyDb).failOnWrite(0)
        e.runAlarmsUntil(utc(day, "18:10"))                  // a failing advance leaves state intact and is retried
        assertTrue(e.evening()!!.state == State.FIRST_REMINDER_PENDING)
    }

    @Test fun secondConfirmationDatabaseFailureNeverShowsCompletion() {
        val e = newEnv(faulty = true)
        e.runAlarmsUntil(utc(day, "18:03")); e.coord.confirmFirst(day, Kind.EVENING); e.runAlarmsUntil(utc(day, "18:09"))
        assertEquals(State.SECOND_CHECK_DUE, e.evening()!!.state)
        (e.db as FaultyDb).failOnWrite(1)                    // record write succeeds, history write fails -> rollback
        assertEquals(Outcome.ERROR, e.coord.confirmSecond(day, Kind.EVENING).outcome)
        assertEquals(State.SECOND_CHECK_DUE, e.evening()!!.state)
        assertNull(e.evening()!!.completedAt)
        assertEquals("UI/notification still show the pending second check", State.SECOND_CHECK_DUE, e.notifier.eveningShown!!.state)
        assertEquals(0, e.history(HistoryType.SECOND_CONFIRMED).size)
        assertEquals(Outcome.APPLIED, e.coord.confirmSecond(day, Kind.EVENING).outcome)   // retry works
        assertNull(e.notifier.eveningShown)
    }

    @Test fun permissionDeniedDuringSecondCheckStillAdvancesAndRestoresTheAlert() {
        val e = confirmedAt1803()
        e.notifier.notificationsAllowed = false
        e.runAlarmsUntil(utc(day, "18:10"))
        assertEquals(State.SECOND_CHECK_DUE, e.evening()!!.state)
        e.notifier.notificationsAllowed = true
        e.coord.onTrigger("launch")
        assertEquals(State.SECOND_CHECK_DUE, e.notifier.eveningShown!!.state)
    }

    @Test fun dueSecondCheckThatIsNeverAnsweredIsRecordedAsMissedAtMidnight() {
        val e = confirmedAt1803()
        e.runAlarmsUntil(utc("2026-03-11", "00:01"))
        val r = e.evening(day)!!
        assertEquals(State.EXPIRED, r.state)
        assertNull(r.completedAt)
        assertEquals(1, e.history(HistoryType.EXPIRED).count { it.kind == Kind.EVENING })
        assertNull(e.notifier.eveningShown)
    }

    @Test fun planHasOneSlotPerPurposeAndNeverDuplicates() {
        val e = confirmedAt1803()
        val before = e.alarms.last
        repeat(5) { e.coord.onTrigger("boot") }
        assertEquals(before, e.alarms.last)
        assertEquals(1, e.store.recent(10).count { it.kind == Kind.EVENING })
        assertEquals(utc("2026-03-11", "18:00"), e.alarms.last!!.eveningStartAt)       // today's date is taken: next start is tomorrow
        assertEquals(utc("2026-03-11", "09:00"), e.alarms.last!!.morningStartAt)
    }

    @Test fun morningTapInNeverInterferesWithEveningStageTwo() {
        val e = newEnv(); e.runAlarmsUntil(utc(day, "09:00")); e.coord.acknowledgeMorning(day)
        e.runAlarmsUntil(utc(day, "18:03")); e.coord.confirmFirst(day, Kind.EVENING)
        e.runAlarmsUntil(utc(day, "18:09"))
        assertEquals(Outcome.APPLIED, e.coord.confirmSecond(day, Kind.EVENING).outcome)
        assertEquals(State.MORNING_ACKNOWLEDGED, e.morning()!!.state)
    }
}
