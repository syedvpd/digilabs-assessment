package com.tapoutguardian

import com.tapoutguardian.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = "2026-03-10"
    private var env: Env? = null

    /** Device armed at 08:00 on [day]; everything else defaults (09:00 / 18:00, 5 min second check). */
    private fun newEnv(zone: String = "UTC", startTime: String = "08:00"): Env {
        val e = Env(tmp.newFolder(), utc(day, startTime, zone), java.time.ZoneId.of(zone))
        e.coord.onTrigger("launch")           // first launch arms the schedule
        env = e
        return e
    }
    @After fun tearDown() { env?.rawDb?.close() }

    private fun Env.toEvening(time: String = "18:00") { runAlarmsUntil(utc(day, time)) }

    // 1 ------------------------------------------------------------------------------------------
    @Test fun eveningReminderFiresAtSixPm() {
        val e = newEnv()
        e.runAlarmsUntil(utc(day, "17:59"))
        assertNull(e.evening())
        assertEquals(utc(day, "18:00"), e.alarms.last!!.eveningStartAt)
        e.runAlarmsUntil(utc(day, "18:00"))
        val r = e.evening()!!
        assertEquals(State.FIRST_REMINDER_PENDING, r.state)
        assertEquals(utc(day, "18:00"), r.startedAt)
        assertEquals(utc(day, "18:00"), e.notifier.loudAlerts.last())
        assertNotNull(e.notifier.eveningShown)
    }

    // 2 ------------------------------------------------------------------------------------------
    @Test fun firstConfirmationSchedulesSecondCheckExactlyFiveMinutesLater() {
        val e = newEnv(); e.toEvening()
        e.clock.advanceTo(utc(day, "18:03"))
        assertEquals(Outcome.APPLIED, e.coord.confirmFirst(day, Kind.EVENING).outcome)
        val r = e.evening()!!
        assertEquals(State.WAITING_FOR_SECOND_CONFIRMATION, r.state)
        assertEquals(utc(day, "18:08"), r.secondDeadlineAt)
        assertEquals(utc(day, "18:08"), e.alarms.last!!.tickAt)
    }

    @Test fun deadlineIsCountedFromTheFirstConfirmationNotFromSixPm() {
        val e = newEnv(); e.toEvening()
        e.clock.advanceTo(utc(day, "18:17"))
        e.coord.confirmFirst(day, Kind.EVENING)
        assertEquals(utc(day, "18:22"), e.evening()!!.secondDeadlineAt)
    }

    // 3 ------------------------------------------------------------------------------------------
    @Test fun firstConfirmationCanNeverSatisfyTheSecond() {
        val e = newEnv(); e.toEvening()
        e.coord.confirmFirst(day, Kind.EVENING)
        assertEquals(Outcome.TOO_EARLY, e.coord.confirmSecond(day, Kind.EVENING).outcome)
        e.clock.advance(5 * MIN - 1)
        assertEquals(Outcome.TOO_EARLY, e.coord.confirmSecond(day, Kind.EVENING).outcome)
        assertEquals(State.WAITING_FOR_SECOND_CONFIRMATION, e.evening()!!.state)
        assertNull(e.evening()!!.completedAt)
        e.clock.advance(1)
        assertEquals(Outcome.APPLIED, e.coord.confirmSecond(day, Kind.EVENING).outcome)
        assertEquals(State.COMPLETED, e.evening()!!.state)
    }

    @Test fun secondConfirmationOnTheFirstScreenIsRejected() {
        val e = newEnv(); e.toEvening()
        assertEquals(Outcome.INVALID_STATE, e.coord.confirmSecond(day, Kind.EVENING).outcome)
        assertEquals(State.FIRST_REMINDER_PENDING, e.evening()!!.state)
    }

    // 4 ------------------------------------------------------------------------------------------
    @Test fun duplicateFirstConfirmationsDoNotResetTheDeadline() {
        val e = newEnv(); e.toEvening()
        e.coord.confirmFirst(day, Kind.EVENING)
        val deadline = e.evening()!!.secondDeadlineAt
        e.clock.advance(2 * MIN)
        assertEquals(Outcome.DUPLICATE, e.coord.confirmFirst(day, Kind.EVENING).outcome)
        assertEquals(Outcome.DUPLICATE, e.coord.confirmFirst(day, Kind.EVENING).outcome)
        assertEquals(deadline, e.evening()!!.secondDeadlineAt)
        assertEquals(1, e.history(HistoryType.FIRST_CONFIRMED).size)
    }

    // 5 ------------------------------------------------------------------------------------------
    @Test fun duplicateSecondConfirmationsDoNotCorruptHistory() {
        val e = newEnv(); e.toEvening()
        e.coord.confirmFirst(day, Kind.EVENING)
        e.runAlarmsUntil(utc(day, "18:05"))
        assertEquals(State.SECOND_CHECK_DUE, e.evening()!!.state)
        assertEquals(Outcome.APPLIED, e.coord.confirmSecond(day, Kind.EVENING).outcome)
        val completedAt = e.evening()!!.completedAt
        e.clock.advance(MIN)
        assertEquals(Outcome.DUPLICATE, e.coord.confirmSecond(day, Kind.EVENING).outcome)
        assertEquals(completedAt, e.evening()!!.completedAt)
        assertEquals(1, e.history(HistoryType.SECOND_CONFIRMED).size)
        assertNull(e.notifier.eveningShown)
    }

    // 6 ------------------------------------------------------------------------------------------
    @Test fun withoutConfirmationReminderKeepsEscalatingOnTheConfiguredGrid() {
        val e = newEnv(); e.toEvening()
        e.runAlarmsUntil(utc(day, "18:31"))
        val expected = (0..10).map { utc(day, "18:00") + it * 2 * MIN } + listOf("18:25", "18:30").map { utc(day, it) }
        assertEquals(expected, e.notifier.loudAlerts)
        assertEquals(State.FIRST_REMINDER_PENDING, e.evening()!!.state)
    }

    @Test fun escalationIntervalsAreConfigurable() {
        val e = newEnv()
        e.settings { it.copy(initialIntervalMin = 1, initialPhaseMin = 3, laterIntervalMin = 10) }
        e.toEvening()
        e.runAlarmsUntil(utc(day, "18:25"))
        assertEquals(listOf("18:00", "18:01", "18:02", "18:03", "18:13", "18:23").map { utc(day, it) }, e.notifier.loudAlerts)
    }

    // 7 ------------------------------------------------------------------------------------------
    @Test fun notYetKeepsWorkflowPendingAndRequiresAFreshTwoStageConfirmation() {
        val e = newEnv(); e.toEvening()
        e.coord.confirmFirst(day, Kind.EVENING)
        e.runAlarmsUntil(utc(day, "18:05"))
        assertEquals(Outcome.APPLIED, e.coord.notYet(day, Kind.EVENING).outcome)
        assertEquals(State.RECOVERY, e.evening()!!.state)
        e.notifier.loudAlerts.clear()
        e.runAlarmsUntil(utc(day, "18:10"))
        assertTrue("reminders continue in recovery", e.notifier.loudAlerts.isNotEmpty())
        assertEquals(Outcome.INVALID_STATE, e.coord.confirmSecond(day, Kind.EVENING).outcome)
        e.coord.confirmFirst(day, Kind.EVENING)
        assertEquals(Outcome.TOO_EARLY, e.coord.confirmSecond(day, Kind.EVENING).outcome)
        e.runAlarmsUntil(e.clock.wall + 5 * MIN)
        assertEquals(Outcome.APPLIED, e.coord.confirmSecond(day, Kind.EVENING).outcome)
        assertEquals(State.COMPLETED, e.evening()!!.state)
        assertEquals(2, e.history(HistoryType.FIRST_CONFIRMED).size)
    }

    @Test fun snoozeIsBoundedAndNeverSilencesPermanently() {
        val e = newEnv(); e.toEvening()
        repeat(3) { assertEquals(Outcome.APPLIED, e.coord.snooze(day, Kind.EVENING).outcome) }
        assertEquals(Outcome.LIMIT_REACHED, e.coord.snooze(day, Kind.EVENING).outcome)
        assertEquals(utc(day, "18:05"), e.evening()!!.nextAlertAt)
        assertNotNull("notification stays while snoozed", e.notifier.eveningShown)
        e.notifier.loudAlerts.clear()
        e.runAlarmsUntil(utc(day, "18:05") + 30_000)
        assertEquals(listOf(utc(day, "18:05")), e.notifier.loudAlerts)
    }

    // 8 + 9 -------------------------------------------------------------------------------------
    @Test fun rebootRestoresPendingDeadlineAndAlarms() {
        val e = newEnv(); e.toEvening()
        e.clock.advanceTo(utc(day, "18:03"))
        e.coord.confirmFirst(day, Kind.EVENING)
        e.clock.reboot(downMs = 60_000)               // 18:04, boot id changes, alarms are gone
        e.boot(); e.alarms.clear()
        e.coord.onTrigger("boot")
        val r = e.evening()!!
        assertEquals(State.WAITING_FOR_SECOND_CONFIRMATION, r.state)
        assertEquals(utc(day, "18:08"), r.secondDeadlineAt)
        assertEquals(utc(day, "18:08"), e.alarms.last!!.tickAt)
        assertNotNull(e.notifier.eveningShown)
        e.runAlarmsUntil(utc(day, "18:08"))
        assertEquals(State.SECOND_CHECK_DUE, e.evening()!!.state)
    }

    @Test fun processDeathDoesNotLoseOrDuplicateTheWorkflow() {
        val e = newEnv(); e.toEvening()
        e.coord.confirmFirst(day, Kind.EVENING)
        val before = e.evening()!!
        e.clock.advance(MIN)
        e.boot()                                       // new process, same database
        assertEquals(before, e.evening())
        e.coord.onTrigger("launch")
        assertEquals(before.secondDeadlineAt, e.evening()!!.secondDeadlineAt)
        assertEquals(1, e.history(HistoryType.STARTED).filter { it.kind == Kind.EVENING }.size)
        // The confirmation from before the death still counts, and the second check is still required.
        assertEquals(Outcome.TOO_EARLY, e.coord.confirmSecond(day, Kind.EVENING).outcome)
    }

    // 10 -----------------------------------------------------------------------------------------
    @Test fun lateAlarmDeliveryReconcilesFromSavedDeadline() {
        val e = newEnv(); e.toEvening()
        e.coord.confirmFirst(day, Kind.EVENING)       // deadline 18:05
        e.notifier.loudAlerts.clear()
        e.clock.advanceTo(utc(day, "18:30"))          // alarm was delayed by 25 minutes (Doze, phone off, ...)
        e.coord.onTrigger("alarm")
        val r = e.evening()!!
        assertEquals(State.SECOND_CHECK_DUE, r.state)
        assertEquals(utc(day, "18:05"), r.secondDeadlineAt)       // deadline not restarted
        assertEquals(1, e.notifier.loudAlerts.size)               // exactly one catch-up alert, no burst
        assertEquals(utc(day, "18:35"), r.nextAlertAt)            // initial phase (anchor 18:05 + 20 min) is over: 5-min grid
    }

    @Test fun missedEveningStartIsHonouredWhenDeliveredLate() {
        val e = newEnv()
        e.clock.advanceTo(utc(day, "18:40"))          // phone was off through 18:00
        e.coord.onTrigger("boot")
        val r = e.evening()!!
        assertEquals(State.FIRST_REMINDER_PENDING, r.state)
        assertEquals(utc(day, "18:00"), r.scheduledAt)
        assertEquals(1, e.history(HistoryType.STARTED_LATE).size)
        assertEquals(1, e.notifier.loudAlerts.size)
    }

    @Test fun veryStaleTriggerIsNotStartedAndFreshInstallAfterSixPmDoesNotNag() {
        val e = newEnv(startTime = "20:00")           // installed at 20:00: armedAt > 18:00
        assertNull(e.evening())
        assertEquals(utc("2026-03-11", "18:00"), e.alarms.last!!.eveningStartAt)
        val f = Env(tmp.newFolder(), utc(day, "08:00"))
        f.coord.onTrigger("launch"); f.clock.advanceTo(utc(day, "21:30")); f.coord.onTrigger("alarm")   // > 3h late
        assertNull(f.evening()); f.rawDb.close()
    }

    @Test fun changingSettingsAfterSixPmDoesNotStartAWorkflowRetroactively() {
        val e = newEnv(); e.clock.advanceTo(utc(day, "19:00"))
        e.runAlarmsUntil(utc(day, "19:00"))
        assertNotNull("armed at 08:00 and delivered late -> honoured", e.evening())
        val f = Env(tmp.newFolder(), utc(day, "19:30")); f.coord.onTrigger("launch")
        f.settings { it.copy(eveningMinute = 18 * 60 + 30) }
        assertNull(f.evening()); f.rawDb.close()
    }

    // 11 -----------------------------------------------------------------------------------------
    @Test fun timeZoneChangeKeepsActiveWorkflowAndReplansFutureTriggers() {
        val e = newEnv(); e.toEvening()
        e.coord.confirmFirst(day, Kind.EVENING)
        val deadline = e.evening()!!.secondDeadlineAt
        e.clock.zone = java.time.ZoneId.of("Asia/Tokyo")          // 18:00 UTC is 03:00 next day in Tokyo
        e.coord.onTrigger("timezone")
        val r = e.evening(day)!!
        assertEquals(deadline, r.secondDeadlineAt)                  // absolute instants are untouched
        assertEquals(day, r.dateKey)                                // still belongs to the original attendance date
        assertEquals(State.WAITING_FOR_SECOND_CONFIRMATION, r.state)
        assertEquals(utc("2026-03-11", "18:00", "Asia/Tokyo"), e.alarms.last!!.eveningStartAt)
        assertEquals(1, e.store.recent(10).count { it.kind == Kind.EVENING })
    }

    @Test fun timeZoneChangeBeforeSixPmMovesTheTriggerWithoutDuplicates() {
        val e = newEnv()
        e.clock.zone = java.time.ZoneId.of("America/New_York")
        e.coord.onTrigger("timezone")
        assertEquals(utc(day, "18:00", "America/New_York"), e.alarms.last!!.eveningStartAt)
    }

    @Test fun daylightSavingDayUsesLocalWallClockTime() {
        val ny = "America/New_York"
        val d = "2026-03-08"                                          // US spring-forward day
        val e = Env(tmp.newFolder(), utc(d, "08:00", ny), java.time.ZoneId.of(ny)); env = e
        e.coord.onTrigger("launch")
        assertEquals(utc(d, "18:00", ny), e.alarms.last!!.eveningStartAt)
        assertEquals(utc(d, "09:00", ny), e.alarms.last!!.morningStartAt)
        e.runAlarmsUntil(utc(d, "18:00", ny))
        assertEquals(utc(d, "18:00", ny), e.evening(d)!!.startedAt)
    }

    // 12 -----------------------------------------------------------------------------------------
    @Test fun clockMovedForwardDuringCountdownDoesNotSkipTheWait() {
        val e = newEnv(); e.toEvening()
        e.clock.advance(MIN); e.coord.confirmFirst(day, Kind.EVENING)         // 18:01 -> deadline 18:06
        e.clock.advance(MIN)                                                   // 1 real minute passes
        e.clock.setWallOnly(e.clock.wall + 3 * 3600_000L)                     // user sets clock +3h
        e.coord.onTrigger("time_set")
        val r = e.evening()!!
        assertEquals(State.WAITING_FOR_SECOND_CONFIRMATION, r.state)
        assertEquals(e.clock.wall + 4 * MIN, r.secondDeadlineAt)              // 4 real minutes remain
        assertEquals(1, e.history(HistoryType.CLOCK_ADJUSTED).size)
        assertEquals(Outcome.TOO_EARLY, e.coord.confirmSecond(day, Kind.EVENING).outcome)
    }

    @Test fun clockMovedBackwardDuringCountdownDoesNotExtendTheWait() {
        val e = newEnv(); e.toEvening()
        e.coord.confirmFirst(day, Kind.EVENING)                                // deadline 18:05
        e.clock.advance(2 * MIN)
        e.clock.setWallOnly(e.clock.wall - 90 * MIN)
        e.coord.onTrigger("time_set")
        assertEquals(e.clock.wall + 3 * MIN, e.evening()!!.secondDeadlineAt)
        assertEquals(e.clock.wall + 3 * MIN, e.alarms.last!!.tickAt)
    }

    @Test fun smallClockDriftIsIgnored() {
        val e = newEnv(); e.toEvening(); e.coord.confirmFirst(day, Kind.EVENING)
        val d = e.evening()!!.secondDeadlineAt
        e.clock.advance(MIN); e.clock.setWallOnly(e.clock.wall + 2_000)
        e.coord.onTrigger("time_set")
        assertEquals(d, e.evening()!!.secondDeadlineAt)
        assertTrue(e.history(HistoryType.CLOCK_ADJUSTED).isEmpty())
    }

    @Test fun backwardClockJumpAfterCompletionNeverCreatesASecondWorkflowForTheDate() {
        val e = newEnv(); e.toEvening()
        e.coord.confirmFirst(day, Kind.EVENING); e.runAlarmsUntil(utc(day, "18:05")); e.coord.confirmSecond(day, Kind.EVENING)
        e.clock.setWallOnly(utc(day, "17:00")); e.coord.onTrigger("time_set")
        assertEquals(utc("2026-03-11", "18:00"), e.alarms.last!!.eveningStartAt)
        e.clock.setWallOnly(utc(day, "18:30")); e.coord.onTrigger("time_set")
        assertEquals(State.COMPLETED, e.evening()!!.state)
        assertEquals(1, e.history(HistoryType.STARTED).count { it.kind == Kind.EVENING })
    }

    // 13 + 14 ------------------------------------------------------------------------------------
    @Test fun notificationPermissionDeniedNeverBreaksStateAndRestorationShowsPendingReminder() {
        val e = newEnv()
        e.notifier.notificationsAllowed = false
        e.runAlarmsUntil(utc(day, "18:00"))
        assertEquals(State.FIRST_REMINDER_PENDING, e.evening()!!.state)       // state machine is independent of permissions
        assertNull(e.notifier.eveningShown)
        val denied = Reliability.assess(caps(notifications = false)).first { it.id == "notifications" }
        assertEquals(Severity.ERROR, denied.severity)
        assertFalse(Reliability.canAlertAtAll(caps(notifications = false)))
        e.notifier.notificationsAllowed = true                                 // user grants permission in settings
        e.coord.onTrigger("launch")
        assertNotNull(e.notifier.eveningShown)
        assertEquals(Severity.OK, Reliability.assess(caps()).first { it.id == "notifications" }.severity)
        assertTrue(Reliability.canAlertAtAll(caps()))
    }

    @Test fun exactAlarmAndFullScreenDenialDegradeGracefully() {
        assertEquals(AlarmTier.ALARM_CLOCK, Reliability.tickTier(caps(exact = false)))
        assertEquals(AlarmTier.EXACT, Reliability.tickTier(caps(exact = true)))
        assertFalse(Reliability.attachFullScreenIntent(caps(fullScreen = false)))
        assertTrue(Reliability.attachFullScreenIntent(caps(fullScreen = true)))
        val items = Reliability.assess(caps(exact = false, fullScreen = false, battery = false))
        assertEquals(Severity.WARN, items.first { it.id == "exact" }.severity)
        assertEquals(Severity.WARN, items.first { it.id == "fullscreen" }.severity)
        assertEquals(Severity.WARN, items.first { it.id == "battery" }.severity)
    }

    @Test fun dismissedNotificationIsRestoredWhileOpenButNotAfterCompletion() {
        val e = newEnv(); e.toEvening()
        e.notifier.eveningShown = null                                          // user swiped it away
        e.coord.onNotificationDismissed()
        assertNotNull(e.notifier.eveningShown)
        e.coord.confirmFirst(day, Kind.EVENING); e.runAlarmsUntil(utc(day, "18:05")); e.coord.confirmSecond(day, Kind.EVENING)
        e.coord.onNotificationDismissed()
        assertNull(e.notifier.eveningShown)
    }

    @Test fun morningNotificationDismissedByUserIsNotNagged() {
        val e = newEnv(); e.runAlarmsUntil(utc(day, "09:00"))
        assertNotNull(e.notifier.morningShown)
        e.notifier.morningShown = null
        e.coord.onNotificationDismissed()
        assertNull(e.notifier.morningShown)
    }

    // 15 -----------------------------------------------------------------------------------------
    @Test fun concurrentEventsApplyExactlyOnce() {
        val e = newEnv(); e.toEvening()
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<Outcome>())
        repeat(16) { i ->
            pool.submit {
                start.await()
                outcomes += when (i % 4) {
                    0, 1 -> e.coord.confirmFirst(day, Kind.EVENING).outcome
                    2 -> e.coord.snooze(day, Kind.EVENING).outcome
                    else -> { e.coord.onTrigger("alarm"); Outcome.DUPLICATE }
                }
            }
        }
        start.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(1, outcomes.count { it == Outcome.APPLIED && e.evening()!!.state == State.WAITING_FOR_SECOND_CONFIRMATION }.coerceAtMost(1))
        assertTrue(outcomes.count { it == Outcome.DUPLICATE } >= 7)
        val r = e.evening()!!
        assertEquals(1, r.confirmCount)
        assertEquals(1, e.history(HistoryType.FIRST_CONFIRMED).size)
        assertEquals(r.firstConfirmedAt!! + 5 * MIN, r.secondDeadlineAt)
        assertTrue(r.state == State.WAITING_FOR_SECOND_CONFIRMATION)
    }

    // 16 -----------------------------------------------------------------------------------------
    @Test fun databaseFailureRollsBackAtomicallyAndRetrySucceeds() {
        val e = Env(tmp.newFolder(), utc(day, "08:00"), faulty = true); env = e
        e.coord.onTrigger("launch"); e.toEvening()
        val before = e.evening()!!
        val histBefore = e.store.history(1000).size
        (e.db as FaultyDb).failOnWrite(1)                                       // first write (record) succeeds, history write fails
        val res = e.coord.confirmFirst(day, Kind.EVENING)
        assertNotEquals(Outcome.APPLIED, res.outcome)
        assertEquals(before, e.evening())                                       // record rolled back
        assertEquals(histBefore, e.store.history(1000).size)
        assertTrue(e.logs.any { it.contains("injected disk failure") })
        assertNotNull(e.notifier.eveningShown)                                  // user still sees the pending reminder
        assertEquals(State.FIRST_REMINDER_PENDING, e.notifier.eveningShown!!.state)   // never shows an uncommitted state
        assertEquals(Outcome.APPLIED, e.coord.confirmFirst(day, Kind.EVENING).outcome)
        assertEquals(State.WAITING_FOR_SECOND_CONFIRMATION, e.evening()!!.state)
    }

    // 17 -----------------------------------------------------------------------------------------
    @Test fun midnightBoundaryKeepsOneWorkflowPerAttendanceDate() {
        val e = newEnv()
        e.settings { it.copy(eveningMinute = 23 * 60 + 58) }
        e.runAlarmsUntil(utc(day, "23:58"))
        e.clock.advanceTo(utc(day, "23:59")); e.coord.confirmFirst(day, Kind.EVENING)   // deadline 00:04 next day
        e.runAlarmsUntil(utc("2026-03-11", "00:01"))
        var r = e.evening(day)!!
        assertEquals(State.WAITING_FOR_SECOND_CONFIRMATION, r.state)             // not expired at midnight
        assertNull(e.store.get("2026-03-11", Kind.EVENING))
        e.runAlarmsUntil(utc("2026-03-11", "00:04"))
        assertEquals(State.SECOND_CHECK_DUE, e.evening(day)!!.state)
        assertEquals(Outcome.APPLIED, e.coord.confirmSecond(day, Kind.EVENING).outcome)
        r = e.evening(day)!!
        assertEquals(State.COMPLETED, r.state)
        assertEquals(utc("2026-03-11", "23:58"), e.alarms.last!!.eveningStartAt)  // next day's workflow is a separate record
    }

    @Test fun unconfirmedWorkflowExpiresAtEndOfDayAndIsRecordedAsMissed() {
        val e = newEnv(); e.toEvening()
        e.runAlarmsUntil(utc("2026-03-11", "00:01"))
        val r = e.evening(day)!!
        assertEquals(State.EXPIRED, r.state)
        assertTrue(r.endReason!!.contains("without completion"))
        assertEquals(1, e.history(HistoryType.EXPIRED).count { it.kind == Kind.EVENING })
        assertNull(e.notifier.eveningShown)
        e.runAlarmsUntil(utc("2026-03-11", "18:00"))
        assertEquals(State.FIRST_REMINDER_PENDING, e.evening("2026-03-11")!!.state)
    }

    // 18 -----------------------------------------------------------------------------------------
    @Test fun morningAndEveningWorkflowsAreIndependent() {
        val e = newEnv(); e.runAlarmsUntil(utc(day, "09:00"))
        assertEquals(State.MORNING_NOTIFIED, e.morning()!!.state)
        assertNull(e.evening())
        assertEquals(Outcome.INVALID_STATE, e.coord.confirmFirst(day, Kind.MORNING).outcome)
        assertEquals(Outcome.INVALID_STATE, e.coord.snooze(day, Kind.MORNING).outcome)
        assertEquals(Outcome.INVALID_STATE, e.coord.confirmSecond(day, Kind.MORNING).outcome)
        e.coord.acknowledgeMorning(day)
        assertEquals(State.MORNING_ACKNOWLEDGED, e.morning()!!.state)
        assertNull("morning ack does not create or complete an evening workflow", e.evening())
        e.toEvening()
        assertEquals(State.FIRST_REMINDER_PENDING, e.evening()!!.state)
        e.coord.confirmFirst(day, Kind.EVENING); e.runAlarmsUntil(utc(day, "18:05")); e.coord.confirmSecond(day, Kind.EVENING)
        assertEquals(State.MORNING_ACKNOWLEDGED, e.morning()!!.state)
        assertEquals(Outcome.NOT_FOUND, e.coord.acknowledgeMorning("2000-01-01").outcome)
    }

    @Test fun unacknowledgedMorningDoesNotBlockOrAffectEvening() {
        val e = newEnv(); e.runAlarmsUntil(utc(day, "18:00"))
        assertEquals(State.FIRST_REMINDER_PENDING, e.evening()!!.state)
        assertEquals(State.MORNING_NOTIFIED, e.morning()!!.state)
        e.coord.confirmFirst(day, Kind.EVENING)
        assertEquals(State.MORNING_NOTIFIED, e.morning()!!.state)
    }

    // 19 + 20 ------------------------------------------------------------------------------------
    @Test fun duplicateBootBroadcastsAndRepeatedLaunchesAreIdempotent() {
        val e = newEnv(); e.toEvening()
        e.coord.confirmFirst(day, Kind.EVENING)
        val rec = e.evening()!!
        val plan = e.alarms.last
        val histSize = e.store.history(1000).size
        val loud = e.notifier.loudAlerts.size
        repeat(6) { e.coord.onTrigger("boot"); e.coord.onTrigger("launch"); e.coord.onTrigger("service_restart") }
        assertEquals(rec, e.evening())
        assertEquals(plan, e.alarms.last)
        assertEquals(histSize, e.store.history(1000).size)
        assertEquals(loud, e.notifier.loudAlerts.size)
    }

    @Test fun repeatedTriggersInsideAnIntervalDoNotReAlert() {
        val e = newEnv(); e.toEvening()
        e.clock.advance(30_000)
        repeat(5) { e.coord.onTrigger("launch") }
        assertEquals(1, e.notifier.loudAlerts.size)
        assertEquals(1, e.history(HistoryType.ALERT).size)
    }

    // 21 -----------------------------------------------------------------------------------------
    @Test fun upgradingAnExistingVersion1DatabasePreservesData() {
        val dir = tmp.newFolder(); val file = java.io.File(dir, "tapout.db")
        val raw = JdbcDb(file)
        raw.execute("""CREATE TABLE workflow (dateKey TEXT NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL, rev INTEGER NOT NULL DEFAULT 0,
            zoneId TEXT NOT NULL, scheduledAt INTEGER NOT NULL, startedAt INTEGER NOT NULL, firstConfirmedAt INTEGER, confirmElapsed INTEGER,
            confirmBootId INTEGER, secondDeadlineAt INTEGER, secondConfirmedAt INTEGER, completedAt INTEGER, lastAlertAt INTEGER,
            nextAlertAt INTEGER, alertCount INTEGER NOT NULL DEFAULT 0, expiresAt INTEGER NOT NULL, endedAt INTEGER, PRIMARY KEY (dateKey, kind))""")
        raw.execute("""CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, dateKey TEXT NOT NULL, kind TEXT NOT NULL, at INTEGER NOT NULL, type TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '')""")
        raw.execute("""CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK (id = 1), morningEnabled INTEGER NOT NULL DEFAULT 1, morningMinute INTEGER NOT NULL DEFAULT 540,
            eveningEnabled INTEGER NOT NULL DEFAULT 1, eveningMinute INTEGER NOT NULL DEFAULT 1080, secondDelayMin INTEGER NOT NULL DEFAULT 5,
            initialIntervalMin INTEGER NOT NULL DEFAULT 2, initialPhaseMin INTEGER NOT NULL DEFAULT 20, laterIntervalMin INTEGER NOT NULL DEFAULT 5,
            soundEnabled INTEGER NOT NULL DEFAULT 1, vibrateEnabled INTEGER NOT NULL DEFAULT 1, attendancePackage TEXT NOT NULL DEFAULT '', armedAt INTEGER NOT NULL DEFAULT 0)""")
        val t = utc(day, "18:00")
        raw.execute("INSERT INTO settings (id, eveningMinute, attendancePackage, armedAt) VALUES (1, 1140, 'com.corp.attendance', 7)")
        raw.execute("INSERT INTO workflow (dateKey, kind, state, rev, zoneId, scheduledAt, startedAt, firstConfirmedAt, secondDeadlineAt, alertCount, expiresAt) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            day, "EVENING", "WAITING_FOR_SECOND_CONFIRMATION", 3L, "UTC", t, t, t + 3 * MIN, t + 8 * MIN, 4L, t + 6 * 3600_000L)
        raw.execute("INSERT INTO history (dateKey, kind, at, type) VALUES (?,?,?,?)", day, "EVENING", t, "STARTED")
        raw.userVersion = 1
        raw.close()

        val db = JdbcDb(file); val store = SqlWorkflowStore(db)
        assertEquals(Schema.VERSION, db.userVersion)
        val r = store.get(day, Kind.EVENING)!!
        assertEquals(State.WAITING_FOR_SECOND_CONFIRMATION, r.state)
        assertEquals(t + 8 * MIN, r.secondDeadlineAt)
        assertEquals(t, r.alertAnchorAt)                    // backfilled
        assertEquals(1, r.confirmCount)                     // backfilled
        assertEquals(0, r.snoozeCount)
        assertEquals(1, store.history(10).size)
        val s = store.settings()
        assertEquals(1140, s.eveningMinute); assertEquals("com.corp.attendance", s.attendancePackage); assertEquals(7L, s.armedAt)
        assertEquals(3, s.maxSnoozes); assertFalse(s.kioskOptIn)
        // The migrated database is fully usable by the engine.
        val clock = FakeClock(t + 9 * MIN)
        val engine = WorkflowEngine(store, clock)
        engine.advance("launch")
        assertEquals(State.SECOND_CHECK_DUE, store.get(day, Kind.EVENING)!!.state)
        db.close()
    }

    @Test fun failedMigrationRollsBackCompletely() {
        val file = java.io.File(tmp.newFolder(), "v1.db")
        val raw = JdbcDb(file)
        raw.execute("CREATE TABLE workflow (dateKey TEXT, scheduledAt INTEGER)")   // incomplete v1: later ALTERs / UPDATE fail
        raw.execute("CREATE TABLE settings (id INTEGER PRIMARY KEY)")
        raw.userVersion = 1
        try { Schema.migrate(raw, 1); fail("expected failure") } catch (_: Exception) {}
        assertEquals(1, raw.userVersion)
        assertTrue(raw.query("PRAGMA table_info(workflow)").none { it["name"] == "stateVersion" })
        raw.close()
    }

    // 22 -----------------------------------------------------------------------------------------
    @Test fun forceStopLosesAlarmsButNextLaunchRecoversTheMissedWorkflow() {
        val e = newEnv()
        // The user force-stops the app at 17:00: Android removes every alarm and delivers nothing until it is launched again.
        e.alarms.clear(); e.boot(); e.alarms.clear()
        e.clock.advanceTo(utc(day, "18:25"))
        assertNull(e.alarms.last); assertNull(e.evening())           // nothing could run in the meantime (documented limitation)
        e.coord.onTrigger("launch")                                   // user opens the app / any receiver wakes the stopped app
        val r = e.evening()!!
        assertEquals(State.FIRST_REMINDER_PENDING, r.state)
        assertEquals(1, e.history(HistoryType.STARTED_LATE).size)
        assertNotNull(e.alarms.last!!.tickAt)                          // alarms re-armed
    }

    // 23 + 24 ------------------------------------------------------------------------------------
    @Test fun kioskModeUnavailableFallsBackToUserConfirmedScreenPinning() {
        assertEquals(KioskMode.OFF, KioskPolicy.decide(optIn = false, lockTaskPermitted = false))
        assertEquals(KioskMode.OFF, KioskPolicy.decide(optIn = false, lockTaskPermitted = true))
        assertEquals(KioskMode.SCREEN_PINNING, KioskPolicy.decide(optIn = true, lockTaskPermitted = false))
        val d = Reliability.assess(caps(owner = false, lockTask = false)).first { it.id == "kiosk" }
        assertEquals(Severity.WARN, d.severity)
        assertTrue(d.detail.contains("NOT possible"))
    }

    @Test fun kioskModeDecisionOnProvisionedDevice() {
        // Decision logic only: Lock Task itself cannot be executed without a device-owner device (see README).
        assertEquals(KioskMode.LOCK_TASK, KioskPolicy.decide(optIn = true, lockTaskPermitted = true))
        assertEquals(Severity.OK, Reliability.assess(caps(owner = true, lockTask = true)).first { it.id == "kiosk" }.severity)
    }

    // extras -------------------------------------------------------------------------------------
    @Test fun testWorkflowIsSeparateFromRealDaysAndUsesShortDelay() {
        val e = newEnv()
        assertEquals(Outcome.APPLIED, e.coord.startTest().outcome)
        assertNull(e.evening())
        assertEquals(Outcome.DUPLICATE, e.coord.startTest().outcome)
        e.coord.confirmFirst(Rules.TEST_KEY, Kind.TEST)
        val t = e.store.get(Rules.TEST_KEY, Kind.TEST)!!
        assertEquals(t.firstConfirmedAt!! + 60_000, t.secondDeadlineAt)
        e.runAlarmsUntil(e.clock.wall + 61_000)
        assertEquals(Outcome.APPLIED, e.coord.confirmSecond(Rules.TEST_KEY, Kind.TEST).outcome)
    }

    @Test fun realEveningSupersedesAnActiveTestAndBlocksNewOnes() {
        val e = newEnv(); e.coord.startTest()
        e.runAlarmsUntil(utc(day, "18:00"))
        assertEquals(State.EXPIRED, e.store.get(Rules.TEST_KEY, Kind.TEST)!!.state)
        assertEquals(Outcome.BLOCKED, e.coord.startTest().outcome)
    }

    @Test fun resetDeletesTodayAndRestartsADueWorkflowFromScratch() {
        val e = newEnv(); e.toEvening()
        e.coord.confirmFirst(day, Kind.EVENING); e.clock.advance(MIN)
        e.coord.reset()
        val r = e.evening()!!
        assertEquals(State.FIRST_REMINDER_PENDING, r.state)
        assertEquals(0, r.confirmCount)
        assertEquals(1, e.history(HistoryType.RESET).size)
    }

    @Test fun disabledEveningNeverSchedulesOrStarts() {
        val e = newEnv(); e.settings { it.copy(eveningEnabled = false) }
        assertNull(e.alarms.last!!.eveningStartAt)
        e.clock.advanceTo(utc(day, "18:01")); e.coord.onTrigger("alarm")
        assertNull(e.evening())
    }

    @Test fun planNeverSchedulesInThePastAndTickHasAFloor() {
        val e = newEnv(); e.toEvening()
        val now = e.clock.wall
        val p = e.alarms.last!!
        assertTrue(p.tickAt!! > now); assertTrue(p.eveningStartAt!! > now); assertTrue(p.morningStartAt!! > now)
    }

    @Test fun scheduleGridIsStrictlyAfterNowAndMonotonic() {
        val s = Settings()
        val a = utc(day, "18:00")
        var t = a; var prev = a
        repeat(60) { t = Schedule.nextAlertAfter(a, t, s); assertTrue(t > prev); prev = t }
        assertEquals(a + 2 * MIN, Schedule.nextAlertAfter(a, a, s))
        assertEquals(a + 25 * MIN, Schedule.nextAlertAfter(a, a + 20 * MIN, s))
        assertEquals(a + 30 * MIN, Schedule.nextAlertAfter(a, a + 25 * MIN, s))
    }

    @Test fun settingsAreSanitisedToSafeBounds() {
        val s = Settings(secondDelayMin = 0, initialIntervalMin = 0, maxSnoozes = 999, eveningMinute = 5000).sanitized()
        assertEquals(1, s.secondDelayMin); assertEquals(1, s.initialIntervalMin); assertEquals(10, s.maxSnoozes); assertEquals(1439, s.eveningMinute)
    }

    @Test fun settingsEditsApplyToStoredValuesSoQuickEditsDoNotOverwriteEachOther() {
        val e = newEnv()
        e.coord.updateSettings { it.copy(soundEnabled = false) }
        e.coord.updateSettings { it.copy(vibrateEnabled = false) }     // closure never saw the first edit
        val s = e.store.settings()
        assertFalse(s.soundEnabled); assertFalse(s.vibrateEnabled)
    }

    @Test fun secondDelayIsConfigurable() {
        val e = newEnv(); e.settings { it.copy(secondDelayMin = 10) }; e.toEvening()
        e.coord.confirmFirst(day, Kind.EVENING)
        assertEquals(utc(day, "18:10"), e.evening()!!.secondDeadlineAt)
    }

    private fun caps(notifications: Boolean = true, exact: Boolean = true, fullScreen: Boolean = true, battery: Boolean = true,
                     owner: Boolean = false, lockTask: Boolean = false) =
        DeviceCapabilities(34, notifications, false, exact, fullScreen, battery, owner, lockTask)
}
