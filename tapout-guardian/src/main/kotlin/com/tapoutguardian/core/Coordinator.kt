package com.tapoutguardian.core

/** Platform ports. The Android implementations live in `com.tapoutguardian.android`. */
interface AlarmPort { fun apply(plan: Plan) }

interface NotifyPort {
    fun showEvening(record: WorkflowRecord, alert: Boolean, settings: Settings)
    fun cancelEvening()
    fun showMorning(record: WorkflowRecord, settings: Settings)
    fun cancelMorning()
}

/**
 * Glue between the engine (truth) and the platform (alarms + notifications). Effects are always derived from
 * the committed database state *after* the transaction, so a notification never shows a state that failed to commit.
 */
class Coordinator(
    private val engine: WorkflowEngine,
    private val clock: Clock,
    private val alarms: AlarmPort,
    private val notifier: NotifyPort,
    private val log: (String) -> Unit = {},
) {
    fun onTrigger(reason: String): AdvanceResult {
        val r = try { engine.advance(reason) } catch (e: Exception) {
            log("advance($reason) failed, state unchanged: $e"); AdvanceResult()
        }
        sync(r)
        return r
    }

    fun confirmFirst(dateKey: String, kind: Kind) = command { engine.confirmFirst(dateKey, kind) }
    fun confirmSecond(dateKey: String, kind: Kind) = command { engine.confirmSecond(dateKey, kind) }
    fun notYet(dateKey: String, kind: Kind) = command { engine.notYet(dateKey, kind) }
    fun snooze(dateKey: String, kind: Kind) = command { engine.snooze(dateKey, kind) }
    fun acknowledgeMorning(dateKey: String) = command { engine.acknowledgeMorning(dateKey) }
    fun startTest(): CommandResult = command { engine.startTest() }.also { /* alert on creation */ }

    fun reset() { sync(try { engine.reset() } catch (e: Exception) { log("reset failed: $e"); AdvanceResult() }) }

    fun updateSettings(change: (Settings) -> Settings) {
        try { engine.updateSettings(change) } catch (e: Exception) { log("updateSettings failed: $e") }
        onTrigger("settings")
    }

    /** The user swiped away (or the system removed) the notification: put it back if the workflow is still open. */
    fun onNotificationDismissed() = sync(null)

    private fun command(block: () -> CommandResult): CommandResult {
        val res = try { block() } catch (e: Exception) { log("command failed, state unchanged: $e"); CommandResult(Outcome.ERROR, null) }
        val startedTest = res.outcome == Outcome.APPLIED && res.record?.isTest == true && res.record.state == State.FIRST_REMINDER_PENDING
        sync(if (startedTest) AdvanceResult(alertEvening = true) else null)
        return res
    }

    fun sync(adv: AdvanceResult?) {
        try {
            val (s, active, now, zone, exists) = engine.read { st ->
                val act = st.active()
                Snapshot(st.settings(), act, clock.wallMillis(), clock.zone()) { k, kind -> st.get(k, kind) != null }
            }
            val evening = active.filter { it.kind != Kind.MORNING }.let { l -> l.firstOrNull { it.kind == Kind.EVENING } ?: l.firstOrNull() }
            if (evening != null) notifier.showEvening(evening, adv?.alertEvening == true, s) else notifier.cancelEvening()
            val morning = active.firstOrNull { it.kind == Kind.MORNING }
            if (morning != null) { if (adv?.alertMorning == true) notifier.showMorning(morning, s) } else notifier.cancelMorning()
            alarms.apply(Planner.plan(now, s, zone, active, exists))
        } catch (e: Exception) {
            log("sync failed: $e")
        }
    }

    private data class Snapshot(
        val s: Settings, val active: List<WorkflowRecord>, val now: Long, val zone: java.time.ZoneId,
        val exists: (String, Kind) -> Boolean,
    )
}
