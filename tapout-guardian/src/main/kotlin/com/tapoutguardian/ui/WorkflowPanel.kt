package com.tapoutguardian.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.tapoutguardian.android.Graph
import com.tapoutguardian.android.HomeData
import com.tapoutguardian.core.*

/** Colours for the two hosts: the dark-red full-screen reminder and the light in-app pages. */
class Scheme(val fg: Int, val muted: Int, val primaryBg: Int, val primaryFg: Int, val okBg: Int, val outline: Int) {
    companion object {
        val DARK = Scheme(Color.WHITE, Color.argb(220, 255, 255, 255), Color.WHITE, Ui.DARK_RED, Ui.GREEN, Color.WHITE)
        val LIGHT = Scheme(Ui.INK, Ui.MUTED, Ui.RED, Color.WHITE, Ui.GREEN, Ui.INK)
    }
}

/**
 * Builds the Stage-1 and Stage-2 views from the persisted record. It holds no workflow state of its own: every
 * label, countdown and enabled/disabled decision comes from the record (and `secondConfirmEnabled`), and every button just
 * sends a command to the engine. Re-creating it (screen rotation, process death, returning to the tab) therefore cannot
 * reset or skip anything.
 */
class WorkflowPanel(private val a: Activity, private val g: Graph, private val sc: Scheme, private val after: () -> Unit) {
    private var countdown: TextView? = null
    private var deadline: Long = 0
    /** Persisted deadline of the WAITING record on screen, so the engine is nudged even if no countdown label is shown. */
    private var watch: Long? = null
    private var nudgedRev = -1L

    /** Call once a second. Returns true when the host should ask the engine to advance (deadline/expiry reached). */
    fun tick(now: Long, rev: Long?): Boolean {
        countdown?.text = Format.countdown(deadline - now)
        val w = watch ?: return false
        return now >= w && rev != null && nudgedRev != rev && run { nudgedRev = rev; true }
    }

    fun reset() { countdown = null; watch = null }

    // ------------------------------------------------------------------ Stage 1

    fun stage1(p: LinearLayout, d: HomeData, r: WorkflowRecord?) {
        watchDeadline(r)
        if (r == null || r.kind == Kind.MORNING || r.state.isTerminal) {
            p.add(label("No tap-out is waiting for its first confirmation.", 18f, bold = true))
            p.add(label(if (r?.state == State.COMPLETED) "Today's workflow is complete (confirmed twice)." else "It starts automatically at ${Format.minuteOfDay(d.settings.eveningMinute)}.", 15f, muted = true), 4)
            return
        }
        val key = r.dateKey; val kind = r.kind
        when (r.state) {
            State.FIRST_REMINDER_PENDING -> {
                p.add(label("Your attendance tap-out is not yet confirmed.", 20f, bold = true))
                p.add(steps("1. Open your attendance app and tap OUT.", "2. Come back and press the big button.",
                    "3. ${d.settings.secondDelayMin} minutes later I ask you to verify it. Nothing is complete until you answer that too."), 12)
                attendance(p, d, primary = true)
                p.add(primary("I HAVE TAPPED OUT", 88, 24f) { act { g.coordinator.confirmFirst(key, kind) } }, 8)
                p.add(outline("NOT YET / I FORGOT") { notYet(key, kind, d) }, 8)
                snooze(p, d, r)
            }
            State.RECOVERY -> {
                p.add(label("Not confirmed. Do this now:", 22f, bold = true))
                p.add(steps("1. Open your attendance app" + (if (d.settings.attendancePackage.isBlank()) " (choose it in Settings to launch it from here)." else "."),
                    "2. Tap OUT and check that it succeeded.",
                    "3. Come back and press \"I HAVE TAPPED OUT\". A NEW ${d.settings.secondDelayMin}-minute verification follows; the old one was cancelled."), 12)
                attendance(p, d, primary = true)
                p.add(primary("I HAVE TAPPED OUT", 80, 22f) { act { g.coordinator.confirmFirst(key, kind) } }, 8)
                snooze(p, d, r)
            }
            State.WAITING_FOR_SECOND_CONFIRMATION -> {
                p.add(label("First confirmation recorded at ${Format.time(r.firstConfirmedAt ?: 0)}.", 18f, bold = true))
                countdownBlock(p, r, "Second verification opens in")
                p.add(label("Your tap-out is NOT verified yet. Keep this notification; I will alert you when the countdown ends.", 15f, muted = true), 4)
                attendance(p, d, primary = false)
                p.add(outline("WAIT - I HAVEN'T ACTUALLY TAPPED OUT") { notYet(key, kind, d) }, 8)
            }
            State.SECOND_CHECK_DUE -> {
                p.add(label("Stage 1 was recorded at ${Format.time(r.firstConfirmedAt ?: 0)}.", 18f, bold = true))
                p.add(label("The verification is open now: use the Verify area.", 16f, muted = true), 4)
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ Stage 2

    fun stage2(p: LinearLayout, d: HomeData, r: WorkflowRecord?) {
        watchDeadline(r)
        if (r == null || r.kind == Kind.MORNING || r.state == State.EXPIRED) {
            p.add(label("No second verification is pending.", 18f, bold = true))
            if (r?.state == State.EXPIRED) p.add(label("The last workflow ended without completion (${r.endReason}). It is recorded as missed.", 15f, muted = true), 4)
            return
        }
        if (r.state == State.COMPLETED) {
            p.add(label("Recorded: you confirmed twice, the second time at ${Format.time(r.secondConfirmedAt ?: 0)}.", 20f, bold = true))
            p.add(label("This app cannot see your attendance system; it only records what you told it.", 15f, muted = true), 6)
            return
        }
        val key = r.dateKey; val kind = r.kind
        val now = System.currentTimeMillis()
        val enabled = r.secondConfirmEnabled(now)
        if (r.state == State.SECOND_CHECK_DUE || enabled) {
            p.add(label("SECOND CHECK: Did you successfully tap out in your attendance app?", 24f, bold = true))
        } else {
            p.add(label("Second verification", 20f, bold = true))
        }
        if (r.state == State.WAITING_FOR_SECOND_CONFIRMATION && !enabled) countdownBlock(p, r, "Opens in")
        if (r.state == State.FIRST_REMINDER_PENDING || r.state == State.RECOVERY) {
            p.add(label("Locked: confirm Stage 1 first. Stage 1 can never count as the second verification.", 15f, muted = true), 4)
        }
        // YES exists on this screen only, and is enabled only once the persisted deadline has passed.
        val yes = Ui.button(a, "YES, TAP-OUT IS DONE", sc.okBg, Color.WHITE, stroke = if (sc === Scheme.DARK) Color.WHITE else null, heightDp = 88, sp = 22f) {
            act { g.coordinator.confirmSecond(key, kind) }
        }
        yes.isEnabled = enabled; yes.alpha = if (enabled) 1f else 0.35f
        if (!enabled && r.secondDeadlineAt != null) yes.contentDescription = "YES, TAP-OUT IS DONE. Disabled until ${Format.time(r.secondDeadlineAt!!)}"
        p.add(yes, 12)
        if (!enabled && r.secondDeadlineAt != null) p.add(label("Enabled at ${Format.time(r.secondDeadlineAt!!)}", 14f, muted = true), 2)
        p.add(outline("NOT YET / I FORGOT") { notYet(key, kind, d) }, 8)
        attendance(p, d, primary = false)
        if (r.state == State.SECOND_CHECK_DUE) snooze(p, d, r)
    }

    // ------------------------------------------------------------------ pieces

    private fun watchDeadline(r: WorkflowRecord?) {
        watch = if (r?.state == State.WAITING_FOR_SECOND_CONFIRMATION) r.secondDeadlineAt else null
    }

    private fun countdownBlock(p: LinearLayout, r: WorkflowRecord, caption: String) {
        deadline = r.secondDeadlineAt ?: 0
        p.add(label(caption, 16f, muted = true, center = true), 8)
        val t = label(Format.countdown(deadline - System.currentTimeMillis()), 60f, bold = true, center = true)
        t.contentDescription = "Time left until the second verification"
        countdown = t; p.add(t, 0)
        p.add(label("at ${Format.time(deadline)}", 14f, muted = true, center = true), 0)
    }

    private fun attendance(p: LinearLayout, d: HomeData, primary: Boolean) {
        val pkg = d.settings.attendancePackage
        if (pkg.isBlank()) {
            p.add(label("No attendance app is set. Choose it in Settings to launch it from here.", 13f, muted = true), 8)
            return
        }
        p.add(if (primary) primary("OPEN ATTENDANCE APP", 60, 17f) { launchAttendance(pkg) } else outline("OPEN ATTENDANCE APP") { launchAttendance(pkg) }, 8)
    }

    private fun snooze(p: LinearLayout, d: HomeData, r: WorkflowRecord) {
        val left = d.settings.maxSnoozes - r.snoozeCount
        if (left <= 0) { p.add(label("No snoozes left for this workflow.", 14f, muted = true, center = true), 6); return }
        p.add(Ui.button(a, "Snooze ${d.settings.snoozeMin} min  ($left left)", Color.TRANSPARENT, sc.fg, stroke = sc.muted, heightDp = 52, sp = 15f) {
            act { g.coordinator.snooze(r.dateKey, r.kind) }
        }, 8)
    }

    private fun notYet(key: String, kind: Kind, d: HomeData) {
        g.call({ g.coordinator.notYet(key, kind) }) { res ->
            feedback(res)
            // Recovery instructions are on screen now; also open the attendance app, as required.
            if (res.outcome == Outcome.APPLIED || res.outcome == Outcome.DUPLICATE) {
                if (d.settings.attendancePackage.isNotBlank()) launchAttendance(d.settings.attendancePackage)
            }
            after()
        }
    }

    fun launchAttendance(pkg: String) {
        val i = a.packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { Toast.makeText(a, "Attendance app \"$pkg\" not found. Check Settings.", Toast.LENGTH_LONG).show(); return }
        try { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
            Toast.makeText(a, "Could not open the attendance app (${e.javaClass.simpleName}). In task-lock mode it must be allowlisted.", Toast.LENGTH_LONG).show()
        }
    }

    private fun act(work: () -> CommandResult) { g.call(work) { feedback(it); after() } }

    private fun feedback(res: CommandResult) {
        val msg = when (res.outcome) {
            Outcome.APPLIED -> null
            Outcome.DUPLICATE -> "Already recorded."
            Outcome.TOO_EARLY -> "Too early: the verification opens at the saved deadline."
            Outcome.LIMIT_REACHED -> "No snoozes left."
            Outcome.ERROR -> "Could not save (storage error). Nothing was changed - please try again."
            Outcome.NOT_FOUND, Outcome.INVALID_STATE, Outcome.BLOCKED -> "That step is no longer valid; showing the current state."
        }
        if (msg != null) Toast.makeText(a, msg, Toast.LENGTH_LONG).show()
    }

    // view helpers -------------------------------------------------------------------------------
    private fun LinearLayout.add(v: View, top: Int = 0) = addView(v, Ui.lp(a, top, 0))
    private fun label(t: String, sp: Float, bold: Boolean = false, muted: Boolean = false, center: Boolean = false) =
        Ui.label(a, t, sp, if (muted) sc.muted else sc.fg, bold, center)
    private fun primary(t: String, h: Int, sp: Float, f: () -> Unit) = Ui.button(a, t, sc.primaryBg, sc.primaryFg, heightDp = h, sp = sp) { f() }
    private fun outline(t: String, f: () -> Unit) = Ui.button(a, t, Color.TRANSPARENT, sc.fg, stroke = sc.outline, heightDp = 60) { f() }
    private fun steps(vararg lines: String): LinearLayout {
        val box = Ui.card(a, if (sc === Scheme.DARK) Color.argb(40, 255, 255, 255) else Color.parseColor("#F3F4F6"))
        lines.forEach { box.addView(Ui.label(a, it, 16f, sc.fg), Ui.lp(a, 2, 2)) }
        return box
    }
}
