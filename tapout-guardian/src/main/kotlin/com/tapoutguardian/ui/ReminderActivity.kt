package com.tapoutguardian.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tapoutguardian.android.Graph
import com.tapoutguardian.android.HomeData
import com.tapoutguardian.core.*

/**
 * Full-screen reminder and the two-stage confirmation. The screen is a pure function of the persisted
 * WorkflowRecord: it can be killed and recreated at any time without losing or duplicating a stage.
 */
class ReminderActivity : Activity() {
    private lateinit var g: Graph
    private lateinit var container: LinearLayout
    private var data: HomeData? = null
    private var countdown: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { refresh() }
    private var kioskSuppressed = false
    private var kioskRequestedFor: String? = null
    private var lastTriggerRev = -1L

    private val ticker = object : Runnable {
        override fun run() { tick(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        g = Graph.get(this)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // plain window flag, no wakelock
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            val p = Ui.dp(this@ReminderActivity, 20); setPadding(p, p * 2, p, p * 2)
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(container) })
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); refresh() }

    override fun onResume() {
        super.onResume()
        g.addListener(listener); refresh(); handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        g.removeListener(listener); handler.removeCallbacks(ticker)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // While task-locked Back must not drop the user out of the confirmation. Otherwise Back simply leaves (we cannot, and do not try to, trap you).
        if (g.kiosk.isLocked(this)) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun refresh() { g.call({ g.loadHome() }, notify = false) { data = it; render(it) } }

    private fun tick() {
        val d = data ?: return
        val r = d.evening ?: return
        val now = System.currentTimeMillis()
        val deadline = r.secondDeadlineAt
        if (r.state == State.WAITING_FOR_SECOND_CONFIRMATION && deadline != null) {
            countdown?.text = Format.countdown(deadline - now)
            // Don't rely only on the alarm: if the deadline passed, ask the engine to advance (idempotent).
            if (now >= deadline && lastTriggerRev != r.rev) { lastTriggerRev = r.rev; g.run { g.coordinator.onTrigger("ui_deadline") } }
        } else if (!r.state.isTerminal && now >= r.expiresAt && lastTriggerRev != r.rev) {
            lastTriggerRev = r.rev; g.run { g.coordinator.onTrigger("ui_expiry") }
        }
    }

    private fun render(d: HomeData) {
        val r = d.evening?.takeIf { it.kind != Kind.MORNING }
        container.removeAllViews(); countdown = null
        val c = this
        fun add(v: android.view.View, top: Int = 0, bottom: Int = 0) = container.addView(v, Ui.lp(c, top, bottom))

        if (r == null || r.state == State.EXPIRED) {
            releaseKiosk()
            window.decorView.setBackgroundColor(Ui.DARK_RED)
            add(Ui.label(c, "No tap-out is waiting for confirmation.", 24f, Color.WHITE, true, true), 40, 12)
            if (r?.state == State.EXPIRED) add(Ui.label(c, "The last workflow ended without a completed confirmation (${r.endReason}). It is recorded as missed in History.", 16f, Color.WHITE, center = true), 0, 24)
            add(closeButton())
            return
        }
        if (r.state == State.COMPLETED) {
            releaseKiosk()
            window.decorView.setBackgroundColor(Ui.GREEN)
            add(Ui.chip(c, "CONFIRMED TWICE", Color.WHITE, Ui.GREEN), 24, 16)
            add(Ui.label(c, "Recorded: you confirmed your tap-out at ${Format.time(r.secondConfirmedAt ?: 0)}.", 24f, Color.WHITE, true, true), 0, 12)
            add(Ui.label(c, "This app cannot see your attendance system. It only records what you told it, so check your attendance app if unsure.", 16f, Color.WHITE, center = true), 0, 24)
            add(closeButton())
            return
        }

        window.decorView.setBackgroundColor(Ui.DARK_RED)
        applyKiosk(d, r)
        val key = r.dateKey; val kind = r.kind
        val test = if (r.isTest) "[TEST]  " else ""

        add(Ui.chip(c, test + when (r.state) {
            State.WAITING_FOR_SECOND_CONFIRMATION -> "FIRST CONFIRMATION RECORDED - NOT COMPLETE"
            State.SECOND_CHECK_DUE -> "SECOND CHECK"
            else -> "TAP-OUT NEEDS CONFIRMATION"
        }, Color.WHITE, Ui.DARK_RED), 8, 16)
        add(Ui.label(c, "DON'T LOSE TODAY'S ATTENDANCE", 30f, Color.WHITE, true, true), 0, 12)

        when (r.state) {
            State.FIRST_REMINDER_PENDING -> {
                add(Ui.label(c, "Your attendance tap-out is not yet confirmed.", 20f, Color.WHITE, center = true), 0, 16)
                add(steps("1. Open your attendance app and tap OUT.", "2. Come back here and press the big button.",
                    "3. I will ask you once more ${d.settings.secondDelayMin} min later: nothing is complete until you answer that too."), 0, 20)
                add(Ui.button(c, "I HAVE TAPPED OUT", Color.WHITE, Ui.DARK_RED, heightDp = 88, sp = 24f) { act { g.coordinator.confirmFirst(key, kind) } }, 0, 12)
                attendanceButton(d, Color.TRANSPARENT)
                notYetButton(key, kind, "NOT YET / I FORGOT")
                snoozeButton(d, r)
            }
            State.WAITING_FOR_SECOND_CONFIRMATION -> {
                val left = (r.secondDeadlineAt ?: 0) - System.currentTimeMillis()
                add(Ui.label(c, "Second check in", 18f, Color.WHITE, center = true), 8, 0)
                countdown = Ui.label(c, Format.countdown(left), 64f, Color.WHITE, true, true).also {
                    it.contentDescription = "Time until the second check"; add(it, 0, 8)
                }
                add(Ui.label(c, "Your first confirmation was recorded at ${Format.time(r.firstConfirmedAt ?: 0)}. Your tap-out is NOT verified yet. " +
                    "At ${Format.time(r.secondDeadlineAt ?: 0)} I will ask you to confirm again, and the workflow only completes after that answer.",
                    18f, Color.WHITE, center = true), 0, 20)
                attendanceButton(d, Color.TRANSPARENT)
                notYetButton(key, kind, "WAIT - I HAVEN'T ACTUALLY TAPPED OUT")
            }
            State.SECOND_CHECK_DUE -> {
                add(Ui.label(c, "SECOND CHECK: Did you successfully tap out in your attendance app?", 24f, Color.WHITE, true, true), 0, 20)
                add(Ui.button(c, "YES, TAP-OUT IS DONE", Ui.GREEN, Color.WHITE, stroke = Color.WHITE, heightDp = 88, sp = 22f) { act { g.coordinator.confirmSecond(key, kind) } }, 0, 12)
                notYetButton(key, kind, "NOT YET / I FORGOT")
                attendanceButton(d, Color.TRANSPARENT)
                snoozeButton(d, r)
            }
            State.RECOVERY -> {
                add(Ui.label(c, "Not confirmed. Do this now:", 22f, Color.WHITE, true, true), 0, 12)
                add(steps("1. Open your attendance app" + (if (d.settings.attendancePackage.isBlank()) " (set it in Settings to launch it from here)." else "."),
                    "2. Tap OUT and check that it succeeded.", "3. Come back and press \"I HAVE TAPPED OUT\". A new ${d.settings.secondDelayMin}-minute second check follows."), 0, 20)
                if (d.settings.attendancePackage.isNotBlank()) attendanceButton(d, Color.WHITE, primary = true)
                add(Ui.button(c, "I HAVE TAPPED OUT", Color.WHITE, Ui.DARK_RED, heightDp = 80, sp = 22f) { act { g.coordinator.confirmFirst(key, kind) } }, 0, 12)
                snoozeButton(d, r)
            }
            else -> {}
        }

        add(Ui.label(c, honesty(d), 13f, Color.argb(220, 255, 255, 255), center = true), 24, 8)
        if (g.kiosk.isLocked(this)) {
            add(Ui.button(c, "Emergency exit (hold 3 s)", Color.TRANSPARENT, Color.WHITE, stroke = Color.WHITE, heightDp = 48, sp = 14f) {
                Toast.makeText(c, "Press and HOLD to leave the locked screen", Toast.LENGTH_SHORT).show()
            }.also { b ->
                b.setOnLongClickListener {
                    kioskSuppressed = true
                    g.run { g.engine.recordKioskExit(key, kind) }
                    g.kiosk.exit(this); true
                }
            })
        }
    }

    private fun honesty(d: HomeData): String {
        val m = g.kiosk.mode(d.settings.kioskOptIn)
        return when (m) {
            KioskMode.LOCK_TASK -> "Task lock is active. You can still use the power menu and emergency calls."
            KioskMode.SCREEN_PINNING -> "Screen pinning requested. Android lets you unpin at any time (Back + Overview)."
            KioskMode.OFF -> "Android lets you leave this screen. The notification stays and reminders keep coming until the second check is confirmed."
        }
    }

    private fun applyKiosk(d: HomeData, r: WorkflowRecord) {
        if (kioskSuppressed) return
        if (g.kiosk.mode(d.settings.kioskOptIn) == KioskMode.OFF) return
        val token = r.dateKey + r.kind
        if (kioskRequestedFor == token) return
        kioskRequestedFor = token
        g.kiosk.configureAsDeviceOwner(d.settings.attendancePackage)
        g.kiosk.enter(this)
    }

    private fun releaseKiosk() { kioskRequestedFor = null; g.kiosk.exit(this) }

    private fun steps(vararg lines: String): LinearLayout {
        val box = Ui.card(this, Color.argb(40, 255, 255, 255))
        lines.forEach { box.addView(Ui.label(this, it, 17f, Color.WHITE), Ui.lp(this, 2, 2)) }
        return box
    }

    private fun closeButton() = Ui.button(this, "Close", Color.WHITE, Ui.INK, heightDp = 64) { finish() }

    private fun attendanceButton(d: HomeData, bg: Int, primary: Boolean = false) {
        val pkg = d.settings.attendancePackage
        if (pkg.isBlank()) return
        container.addView(Ui.button(this, "OPEN ATTENDANCE APP", if (primary) Color.WHITE else bg, if (primary) Ui.DARK_RED else Color.WHITE,
            stroke = if (primary) null else Color.WHITE, heightDp = 60) { launchAttendance(pkg) }, Ui.lp(this, 0, 12))
    }

    private fun notYetButton(key: String, kind: Kind, label: String) {
        container.addView(Ui.button(this, label, Color.TRANSPARENT, Color.WHITE, stroke = Color.WHITE, heightDp = 60) {
            act { g.coordinator.notYet(key, kind) }
        }, Ui.lp(this, 0, 12))
    }

    private fun snoozeButton(d: HomeData, r: WorkflowRecord) {
        val left = d.settings.maxSnoozes - r.snoozeCount
        if (left <= 0) {
            container.addView(Ui.label(this, "No snoozes left for this workflow.", 14f, Color.WHITE, center = true), Ui.lp(this, 4, 4))
            return
        }
        container.addView(Ui.button(this, "Snooze ${d.settings.snoozeMin} min  ($left left)", Color.TRANSPARENT, Color.WHITE, stroke = Color.argb(150, 255, 255, 255), heightDp = 52, sp = 15f) {
            act { g.coordinator.snooze(r.dateKey, r.kind) }
        }, Ui.lp(this, 0, 8))
    }

    private fun launchAttendance(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { Toast.makeText(this, "Attendance app \"$pkg\" not found. Check Settings.", Toast.LENGTH_LONG).show(); return }
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
            Toast.makeText(this, "Could not open attendance app (${e.javaClass.simpleName}). In task-lock mode it must be allowlisted.", Toast.LENGTH_LONG).show()
        }
    }

    private fun act(work: () -> CommandResult) {
        g.call(work) { res ->
            val msg = when (res.outcome) {
                Outcome.APPLIED -> null
                Outcome.DUPLICATE -> "Already recorded."
                Outcome.TOO_EARLY -> "Too early: the second check is not due yet."
                Outcome.LIMIT_REACHED -> "No snoozes left."
                Outcome.NOT_FOUND, Outcome.INVALID_STATE, Outcome.BLOCKED -> "That step is no longer valid; showing the current state."
            }
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            refresh()
        }
    }
}
