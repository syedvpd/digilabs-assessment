package com.tapoutguardian.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.tapoutguardian.android.Graph
import com.tapoutguardian.android.HomeData
import com.tapoutguardian.core.*

/**
 * Full-screen reminder. It is a pure view of the persisted record and NEVER finishes itself on a timer: the only ways out are
 * the user's own Back/Close (unless task-locked) or a terminal state the user is shown. The 5-minute interval is a verification
 * deadline that enables Stage 2; it does not dismiss this screen and does not complete anything.
 */
class ReminderActivity : Activity() {
    private lateinit var g: Graph
    private lateinit var container: LinearLayout
    private lateinit var panel: WorkflowPanel
    private var data: HomeData? = null
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { refresh() }
    private var kioskSuppressed = false
    private var kioskRequestedFor: String? = null
    private var expiryNudgedRev = -1L

    private val ticker = object : Runnable {
        override fun run() { tick(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        g = Graph.get(this)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // plain window flag, no wakelock
        panel = WorkflowPanel(this, g, Scheme.DARK) { refresh() }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            val p = Ui.dp(this@ReminderActivity, 20); setPadding(p, p * 2, p, p * 2)
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(container) })
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); refresh() }

    override fun onResume() { super.onResume(); g.addListener(listener); refresh(); handler.post(ticker) }

    override fun onPause() { super.onPause(); g.removeListener(listener); handler.removeCallbacks(ticker) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // While task-locked Back must not drop the user out of the confirmation. Otherwise Back simply leaves (we cannot, and do not try to, trap you).
        if (g.kiosk.isLocked(this)) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun refresh() { g.call({ g.loadHome() }, notify = false) { data = it; render(it) } }

    /** The countdown is cosmetic. It only nudges the engine, which decides from the persisted deadline. */
    private fun tick() {
        val d = data ?: return
        val r = d.evening
        val now = System.currentTimeMillis()
        if (panel.tick(now, r?.rev)) g.run { g.coordinator.onTrigger("ui_deadline") }
        else if (r != null && !r.state.isTerminal && now >= r.expiresAt && expiryNudgedRev != r.rev) {
            expiryNudgedRev = r.rev; g.run { g.coordinator.onTrigger("ui_expiry") }
        }
    }

    private fun render(d: HomeData) {
        val r = d.evening?.takeIf { it.kind != Kind.MORNING }
        container.removeAllViews(); panel.reset()
        fun add(v: View, top: Int = 0, bottom: Int = 0) = container.addView(v, Ui.lp(this, top, bottom))
        val terminal = r == null || r.state.isTerminal
        window.decorView.setBackgroundColor(if (r?.state == State.COMPLETED) Ui.GREEN else Ui.DARK_RED)

        if (terminal) {
            releaseKiosk()
            if (r?.state == State.COMPLETED) {
                add(Ui.chip(this, "CONFIRMED TWICE", Color.WHITE, Ui.GREEN), 24, 16)
            } else add(Ui.label(this, "No tap-out is waiting for confirmation.", 24f, Color.WHITE, true, true), 40, 12)
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            panel.stage2(box, d, r); add(box, 0, 24)
            add(Ui.button(this, "Close", Color.WHITE, Ui.INK, heightDp = 64) { finish() })
            return
        }
        r!!
        applyKiosk(d, r)
        val test = if (r.isTest) "[TEST]  " else ""
        add(Ui.chip(this, test + when (r.state) {
            State.WAITING_FOR_SECOND_CONFIRMATION -> "FIRST CONFIRMATION RECORDED - NOT COMPLETE"
            State.SECOND_CHECK_DUE -> "SECOND CHECK"
            else -> "TAP-OUT NEEDS CONFIRMATION"
        }, Color.WHITE, Ui.DARK_RED), 8, 16)
        add(Ui.label(this, "DON'T LOSE TODAY'S ATTENDANCE", 30f, Color.WHITE, true, true), 0, 12)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Stage 1 content until the first confirmation is recorded; from then on the verification (Stage 2) content.
        if (r.state == State.FIRST_REMINDER_PENDING || r.state == State.RECOVERY) panel.stage1(body, d, r) else panel.stage2(body, d, r)
        add(body)
        add(Ui.label(this, honesty(d), 13f, Color.argb(220, 255, 255, 255), center = true), 24, 8)
        if (g.kiosk.isLocked(this)) {
            add(Ui.button(this, "Emergency exit (hold 3 s)", Color.TRANSPARENT, Color.WHITE, stroke = Color.WHITE, heightDp = 48, sp = 14f) {
                Toast.makeText(this, "Press and HOLD to leave the locked screen", Toast.LENGTH_SHORT).show()
            }.also { b ->
                b.setOnLongClickListener {
                    kioskSuppressed = true
                    g.run { g.engine.recordKioskExit(r.dateKey, r.kind) }
                    g.kiosk.exit(this); true
                }
            })
        }
    }

    private fun honesty(d: HomeData): String = when (g.kiosk.mode(d.settings.kioskOptIn)) {
        KioskMode.LOCK_TASK -> "Task lock is active. You can still use the power menu and emergency calls."
        KioskMode.SCREEN_PINNING -> "Screen pinning requested. Android lets you unpin at any time (Back + Overview)."
        KioskMode.OFF -> "Android lets you leave this screen. The notification stays and reminders keep coming until the second check is confirmed."
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
}
