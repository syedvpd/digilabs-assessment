package com.tapoutguardian

import com.tapoutguardian.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The redesigned dashboard derives everything from the record; these pin the "no misleading success state" rule. */
class PresentationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = "2026-03-10"
    private var env: Env? = null
    @After fun tearDown() { env?.rawDb?.close() }

    private fun start(): Env { val e = Env(tmp.newFolder(), utc(day, "08:00")); e.coord.onTrigger("launch"); env = e; return e }

    @Test fun idleBeforeAnyWorkflow() {
        val e = start()
        assertEquals(RingKind.IDLE, Presentation.ring(null, e.clock.wall))
        assertEquals(DayDot.NONE, Presentation.dot(null))
    }

    @Test fun successIsShownOnlyAfterTheSecondConfirmationIsCommitted() {
        val e = start(); e.runAlarmsUntil(utc(day, "18:00"))
        val kinds = mutableListOf<RingKind>()
        fun snap() { kinds += Presentation.ring(e.evening(), e.clock.wall) }
        snap()                                                         // TAP_OUT
        e.coord.confirmFirst(day, Kind.EVENING); snap()                // COUNTDOWN
        e.clock.advance(5 * MIN - 1); snap()                           // still COUNTDOWN: one ms before the deadline
        e.clock.advance(1); snap()                                     // VERIFY_READY (persisted deadline reached, engine not yet advanced)
        e.coord.onTrigger("alarm"); snap()                             // VERIFY_DUE
        e.coord.notYet(day, Kind.EVENING); snap()                      // TAP_OUT again (recovery)
        e.coord.confirmFirst(day, Kind.EVENING); e.clock.advance(5 * MIN); e.coord.onTrigger("alarm"); snap()   // VERIFY_DUE
        assertEquals(listOf(RingKind.TAP_OUT, RingKind.COUNTDOWN, RingKind.COUNTDOWN, RingKind.VERIFY_READY, RingKind.VERIFY_DUE,
            RingKind.TAP_OUT, RingKind.VERIFY_DUE), kinds)
        assertFalse("no success before the second confirmation", kinds.contains(RingKind.DONE))
        e.coord.confirmSecond(day, Kind.EVENING)
        assertEquals(RingKind.DONE, Presentation.ring(e.evening(), e.clock.wall))
        assertEquals(DayDot.DONE, Presentation.dot(e.evening()))
    }

    @Test fun ringReadyMatchesTheEngineGateExactly() {
        val e = start(); e.runAlarmsUntil(utc(day, "18:00")); e.coord.confirmFirst(day, Kind.EVENING)
        for (offset in listOf(-1L, 0L, 1L)) {
            val t = e.evening()!!.secondDeadlineAt!! + offset
            val ready = Presentation.ring(e.evening(), t) == RingKind.VERIFY_READY
            assertEquals(e.evening()!!.secondConfirmEnabled(t), ready)
        }
    }

    @Test fun missedDayShowsMissedNeverDone() {
        val e = start(); e.runAlarmsUntil(utc("2026-03-11", "00:01"))
        assertEquals(RingKind.MISSED, Presentation.ring(e.evening(day), e.clock.wall))
        assertEquals(DayDot.MISSED, Presentation.dot(e.evening(day)))
    }

    @Test fun morningRecordNeverDrivesTheEveningRing() {
        val e = start(); e.runAlarmsUntil(utc(day, "09:00"))
        assertEquals(RingKind.IDLE, Presentation.ring(e.morning(), e.clock.wall))
    }

    @Test fun inProgressDayHasAnAmberDot() {
        val e = start(); e.runAlarmsUntil(utc(day, "18:00"))
        assertEquals(DayDot.IN_PROGRESS, Presentation.dot(e.evening()))
    }
}
