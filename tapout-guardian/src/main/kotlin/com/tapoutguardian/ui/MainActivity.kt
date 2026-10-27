package com.tapoutguardian.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.tapoutguardian.R
import com.tapoutguardian.android.Graph
import com.tapoutguardian.android.HomeData
import com.tapoutguardian.android.Ids
import com.tapoutguardian.core.*

class MainActivity : Activity() {
    private enum class Tab(val title: String, val icon: Int) {
        DASHBOARD("Dashboard", R.drawable.ic_nav_home), CONFIRM("Confirm tap-out", R.drawable.ic_nav_confirm),
        VERIFY("Verify", R.drawable.ic_nav_verify), CHECKS("Checks and history", R.drawable.ic_nav_checks)
    }

    private lateinit var g: Graph
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private val navViews = mutableMapOf<Tab, ImageView>()
    private var greetingView: TextView? = null
    private var subtitleView: TextView? = null
    private var timeView: TextView? = null
    private var ring: RingButton? = null
    private lateinit var frame: FrameLayout
    private var tab = Tab.DASHBOARD
    private var inSettings = false
    private lateinit var panel: WorkflowPanel
    private var data: HomeData? = null
    private var clockView: TextView? = null
    private var liveStatus: TextView? = null
    private var autoOpenPending = true
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { reload() }
    private val ticker = object : Runnable {
        override fun run() { tickUi(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        g = Graph.get(this)
        panel = WorkflowPanel(this, g, Scheme.LIGHT) { reload() }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.PAGE) }
        // header: greeting + settings
        greetingView = Ui.label(this, "", 26f, Ui.INK, true)
        subtitleView = Ui.label(this, "", 14f, Ui.MUTED)
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(greetingView); addView(subtitleView) }
        val gear = ImageView(this).apply {
            setImageResource(R.drawable.ic_tune); setColorFilter(Ui.INK)
            val pad = Ui.dp(this@MainActivity, 12); setPadding(pad, pad, pad, pad)
            background = Ui.circle(this@MainActivity, Color.WHITE, 48); elevation = Ui.dp(this@MainActivity, 3).toFloat()
            contentDescription = "Settings"; isClickable = true
            setOnClickListener { inSettings = true; render() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val p = Ui.dp(this@MainActivity, 20); setPadding(p, Ui.dp(this@MainActivity, 18), p, Ui.dp(this@MainActivity, 6))
            addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
            addView(gear, LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 48), Ui.dp(this@MainActivity, 48)))
        }
        root.addView(header)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            val p = Ui.dp(this@MainActivity, 16); setPadding(p, p, p, p)
        }
        scroll = ScrollView(this).apply { clipChildren = false; clipToPadding = false; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        // floating pill navigation (icons only, like the reference), with accessible labels
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            val v = Ui.dp(this@MainActivity, 8); setPadding(v, v, v, v)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = Ui.dp(this@MainActivity, 40).toFloat(); setStroke(Ui.dp(this@MainActivity, 1), Ui.BORDER) }
            elevation = Ui.dp(this@MainActivity, 8).toFloat()
        }
        Tab.values().forEach { t ->
            val v = ImageView(this).apply {
                setImageResource(t.icon); contentDescription = t.title; isClickable = true
                val pad = Ui.dp(this@MainActivity, 14); setPadding(pad, pad, pad, pad)
                setOnClickListener { tab = t; inSettings = false; render() }
            }
            navViews[t] = v
            pill.addView(v, LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)).apply { marginStart = Ui.dp(this@MainActivity, 4); marginEnd = Ui.dp(this@MainActivity, 4) })
        }
        root.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.dp(this@MainActivity, 4); bottomMargin = Ui.dp(this@MainActivity, 14) })
        frame = FrameLayout(this).apply { addView(root, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(frame)
        maybeIntro()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); autoOpenPending = true }

    override fun onResume() {
        super.onResume()
        g.addListener(listener)
        g.run(notify = false) { g.coordinator.onTrigger("activity_resume") }
        reload(); handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        g.removeListener(listener); handler.removeCallbacks(ticker)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        g.run { g.coordinator.onTrigger("permission_result") }
    }

    private fun reload() { g.call({ g.loadHome() }, notify = false) { data = it; render(); maybeAutoOpen(it) } }

    /** Launching the app while a tap-out is pending drops you straight on the reminder (Back returns here). */
    private fun maybeAutoOpen(d: HomeData) {
        if (!autoOpenPending) return
        autoOpenPending = false
        val r = d.evening ?: return
        if (r.kind != Kind.MORNING && !r.state.isTerminal) {
            startActivity(Intent(this, ReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun tickUi() {
        val d = data ?: return
        val now = System.currentTimeMillis()
        timeView?.text = Format.time(now)
        liveStatus?.text = statusLine(d, now)
        // ring caption/title follow the persisted deadline once a second (cosmetic only)
        ring?.let { rb -> ringModel(d, now).let { m -> rb.setTitleOnly(m.title) } }
        // The label is cosmetic; the engine decides from the persisted deadline.
        if (panel.tick(now, d.evening?.rev)) g.run { g.coordinator.onTrigger("ui_deadline") }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inSettings) { inSettings = false; render() } else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** Confirm / Verify areas: a status header plus the shared panel, always rendered from the persisted record. */
    private fun stagePage(d: HomeData, title: String, build: (LinearLayout) -> Unit) {
        add(Ui.label(this, title, 14f, Ui.MUTED, true), 0, 8)
        val status = Ui.card(this)
        liveStatus = Ui.label(this, statusLine(d, System.currentTimeMillis()), 16f, statusColor(d.evening), true)
        status.addView(liveStatus)
        add(status)
        val box = Ui.card(this)
        build(box)
        add(box)
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        val d = data ?: return
        navViews.forEach { (t, v) ->
            val on = t == tab && !inSettings
            v.background = if (on) Ui.circle(this, Ui.CORAL, 56) else null
            v.setColorFilter(if (on) Color.WHITE else Ui.MUTED)
            v.isSelected = on
        }
        greetingView?.text = if (inSettings) "Settings" else when (tab) {
            Tab.DASHBOARD -> greeting(System.currentTimeMillis())
            Tab.CONFIRM -> "Confirm tap-out"
            Tab.VERIFY -> "Verify"
            Tab.CHECKS -> "Checks & history"
        }
        subtitleView?.text = if (inSettings) "Schedule, alerts and reliability" else headline(d)
        val keepScroll = scroll.scrollY
        content.removeAllViews(); clockView = null; liveStatus = null; timeView = null; ring = null; panel.reset()
        if (inSettings) {
            add(Ui.button(this, "Back", Ui.CORAL_SOFT, Ui.CORAL, heightDp = 44, sp = 15f) { inSettings = false; render() })
            settings(d)
        } else when (tab) {
            Tab.DASHBOARD -> home(d)
            Tab.CONFIRM -> stagePage(d, "Stage 1: first confirmation") { box -> panel.stage1(box, d, d.evening) }
            Tab.VERIFY -> stagePage(d, "Stage 2: second verification") { box -> panel.stage2(box, d, d.evening) }
            Tab.CHECKS -> { checks(d); history(d) }
        }
        scroll.post { scroll.scrollTo(0, keepScroll) }
    }

    private fun add(v: View, top: Int = 0, bottom: Int = 12) = content.addView(v, Ui.lp(this, top, bottom))

    private fun statusLine(d: HomeData, now: Long): String {
        val r = d.evening
        return when {
            r == null -> idleLine(d, now)
            r.state == State.WAITING_FOR_SECOND_CONFIRMATION -> "${Format.stateLabel(r)}\nSecond check in ${Format.countdown((r.secondDeadlineAt ?: now) - now)}"
            r.state == State.COMPLETED -> "${Format.stateLabel(r)}\nSecond confirmation at ${Format.time(r.secondConfirmedAt ?: 0)}"
            r.state == State.EXPIRED -> "${Format.stateLabel(r)}\n${r.endReason ?: ""}\n" + idleLine(d, now)
            else -> Format.stateLabel(r) + (r.nextAlertAt?.let { "\nNext reminder ${Format.relative(it - now)} (${Format.timeSeconds(it)})" } ?: "")
        }
    }

    private fun idleLine(d: HomeData, now: Long): String {
        val t = d.plan.eveningStartAt
        return if (t == null) "IDLE - evening reminder is disabled" else "IDLE - next evening reminder ${Format.dateTime(t)} (${Format.relative(t - now)})"
    }

    private fun greeting(now: Long): String = when (Format.hourOfDay(now)) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

    private fun headline(d: HomeData): String {
        val r = d.evening?.takeIf { it.kind != Kind.MORNING }
        return when (r?.state) {
            State.FIRST_REMINDER_PENDING, State.RECOVERY -> "Your tap-out needs confirmation"
            State.WAITING_FOR_SECOND_CONFIRMATION -> "Verification opens soon"
            State.SECOND_CHECK_DUE -> "Did you tap out? Verify now"
            State.COMPLETED -> "Today is confirmed. Nice work"
            State.EXPIRED -> "Last workflow was missed"
            else -> "Your attendance guardian"
        }
    }

    private data class RingModel(val title: String, val caption: String, val color: Int, val pulse: Boolean, val target: Tab?, val desc: String)

    /** Derived only from the persisted record: the ring never decides anything, it only points at the screen that does. */
    private fun ringModel(d: HomeData, now: Long): RingModel {
        val r = d.evening?.takeIf { it.kind != Kind.MORNING }
        return when (Presentation.ring(r, now)) {
            RingKind.TAP_OUT -> RingModel("Tap out", "then confirm", Ui.CORAL, true, Tab.CONFIRM, "Tap out needs confirmation. Double tap to open the confirmation screen.")
            RingKind.COUNTDOWN -> {
                val left = (r?.secondDeadlineAt ?: now) - now
                RingModel(Format.countdown(left), "until verification", Ui.AMBER, false, Tab.VERIFY, "Verification opens in ${Format.countdown(left)}. Double tap to open.")
            }
            RingKind.VERIFY_READY -> RingModel("Verify", "ready now", Ui.CORAL, true, Tab.VERIFY, "Verification is ready. Double tap to open.")
            RingKind.VERIFY_DUE -> RingModel("Verify", "did you tap out?", Ui.CORAL, true, Tab.VERIFY, "Second check is due. Double tap to answer.")
            RingKind.DONE -> RingModel("Done", "confirmed twice", Ui.GREEN, false, null, "Today's tap-out is confirmed twice.")
            RingKind.MISSED -> RingModel("Missed", "day closed", Ui.DARK_RED, false, Tab.CHECKS, "Today's workflow was missed. Double tap to see history.")
            RingKind.IDLE -> RingModel("All set", d.plan.eveningStartAt?.let { "next ${Format.time(it)}" } ?: "reminder off", Color.parseColor("#8C8791"), false, null, "No tap-out is pending.")
        }
    }

    private fun home(d: HomeData) {
        val now = System.currentTimeMillis()
        // big clock, as in the reference
        timeView = Ui.label(this, Format.time(now), 52f, Ui.INK).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); gravity = Gravity.CENTER_HORIZONTAL
        }
        add(timeView!!, 4, 0)
        add(Ui.label(this, Format.dayLine(now), 15f, Ui.MUTED, center = true), 0, 8)

        val m = ringModel(d, now)
        val rb = RingButton(this).also { ring = it }
        rb.bind(m.title, m.caption, m.color, m.pulse, m.desc)
        rb.setOnClickListener {
            if (m.target != null) { tab = m.target; render() }
            else Toast.makeText(this, d.plan.eveningStartAt?.let { "Next evening reminder ${Format.dateTime(it)}" } ?: "Nothing pending.", Toast.LENGTH_SHORT).show()
        }
        content.addView(rb, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.dp(this@MainActivity, 4); bottomMargin = Ui.dp(this@MainActivity, 8) })

        // three stats like Check in / Check out / Total
        val ev = d.evening?.takeIf { it.kind != Kind.MORNING && !it.isTest }
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(icon: Int, value: String?, label: String) {
            val done = value != null
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(ImageView(this).apply {
                setImageResource(icon); setColorFilter(if (done) Ui.GREEN else Ui.CORAL)
                val pad = Ui.dp(this@MainActivity, 10); setPadding(pad, pad, pad, pad)
                background = Ui.circle(this@MainActivity, if (done) Ui.GREEN_SOFT else Ui.CORAL_SOFT, 48)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
            col.addView(Ui.label(this, value ?: "--:--", 17f, Ui.INK, true, true), Ui.lp(this, 6, 0))
            col.addView(Ui.label(this, label, 13f, Ui.MUTED, center = true))
            col.contentDescription = "$label: ${value ?: "not yet"}"
            stats.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        stat(R.drawable.ic_schedule, ev?.startedAt?.let { Format.time(it) }, "Reminder")
        stat(R.drawable.ic_nav_confirm, ev?.firstConfirmedAt?.let { Format.time(it) }, "Confirmed")
        stat(R.drawable.ic_nav_verify, ev?.secondConfirmedAt?.let { Format.time(it) }, "Verified")
        add(stats, 4, 16)

        weekStrip(d, now)

        // today's status
        val st = Ui.card(this)
        st.addView(Ui.label(this, "Today's tap-out  ·  ${Format.minuteOfDay(d.settings.eveningMinute)}", 13f, Ui.MUTED, true))
        liveStatus = Ui.label(this, statusLine(d, now), 18f, statusColor(d.evening), true)
        st.addView(liveStatus, Ui.lp(this, 4, 4))
        val active = d.evening?.takeIf { !it.state.isTerminal && it.kind != Kind.MORNING }
        if (active != null) st.addView(Ui.button(this, "Open reminder screen", Ui.CORAL) {
            startActivity(Intent(this, ReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }, Ui.lp(this, 8, 0))
        add(st)

        // next reminder
        val nx = Ui.card(this)
        nx.addView(Ui.label(this, "Next reminder", 13f, Ui.MUTED, true))
        val lines = mutableListOf<String>()
        d.plan.eveningStartAt?.let { lines += "Evening tap-out  ·  ${Format.dateTime(it)}  (${Format.relative(it - now)})" }
        d.plan.morningStartAt?.let { lines += "Morning check-in  ·  ${Format.dateTime(it)}" }
        active?.nextAlertAt?.let { lines.add(0, "Follow-up alert  ·  ${Format.timeSeconds(it)}  (${Format.relative(it - now)})") }
        if (active?.state == State.WAITING_FOR_SECOND_CONFIRMATION) active.secondDeadlineAt?.let { lines.add(0, "Verification opens  ·  ${Format.timeSeconds(it)}") }
        nx.addView(Ui.label(this, if (lines.isEmpty()) "Reminders are turned off in Settings." else lines.joinToString("\n"), 16f, Ui.INK), Ui.lp(this, 4, 0))
        add(nx)

        val mo = Ui.card(this)
        mo.addView(Ui.label(this, "Morning check-in  ·  ${Format.minuteOfDay(d.settings.morningMinute)}", 13f, Ui.MUTED, true))
        val mr = d.morning
        val mText = when {
            mr == null -> d.plan.morningStartAt?.let { "Next reminder ${Format.dateTime(it)}" } ?: "Disabled"
            mr.state == State.MORNING_ACKNOWLEDGED -> "You said you tapped in (${Format.time(mr.endedAt ?: 0)}). Not verified with the attendance system."
            mr.state == State.MORNING_NOTIFIED -> "Reminder sent at ${Format.time(mr.startedAt)}: have you completed your tap-in?"
            else -> Format.stateLabel(mr)
        }
        mo.addView(Ui.label(this, mText, 16f, Ui.INK), Ui.lp(this, 4, 0))
        if (mr?.state == State.MORNING_NOTIFIED) mo.addView(Ui.button(this, "I've tapped in", Ui.GREEN) { g.call({ g.coordinator.acknowledgeMorning(mr.dateKey) }) { } }, Ui.lp(this, 8, 0))
        add(mo)

        val rel = Ui.card(this)
        val diags = Reliability.assess(d.caps)
        val bad = diags.count { it.severity != Severity.OK }
        rel.addView(Ui.label(this, "System readiness", 13f, Ui.MUTED, true))
        rel.addView(Ui.label(this, if (bad == 0) "All checks passed" else "$bad item(s) need attention", 17f, if (bad == 0) Ui.GREEN else Ui.AMBER, true), Ui.lp(this, 4, 4))
        diags.forEach { rel.addView(Ui.label(this, "${icon(it.severity)}  ${it.title}", 15f, when (it.severity) { Severity.OK -> Ui.INK; Severity.WARN -> Ui.AMBER; Severity.ERROR -> Ui.CORAL }), Ui.lp(this, 2, 2)) }
        rel.addView(Ui.button(this, if (bad == 0) "View details" else "Fix now", if (bad == 0) Ui.CORAL_SOFT else Ui.CORAL, if (bad == 0) Ui.CORAL else Color.WHITE) { tab = Tab.CHECKS; render() }, Ui.lp(this, 10, 0))
        add(rel)

        add(Ui.button(this, "Test the reminder now", Color.TRANSPARENT, Ui.CORAL, stroke = Ui.CORAL) { startTest() })
        add(Ui.label(this, "TapOut Guardian only records what you tell it. It cannot read your attendance system, and Android does not let an ordinary app lock the phone. See Checks.", 13f, Ui.MUTED), 4, 16)
    }

    /** Mon-Sun strip. Dots come from persisted evening records: green confirmed twice, coral missed, amber in progress. */
    private fun weekStrip(d: HomeData, now: Long) {
        val zone = java.util.TimeZone.getDefault().toZoneId()
        val today = java.time.LocalDate.now(zone)
        val monday = today.with(java.time.DayOfWeek.MONDAY)
        val byDate = d.recent.filter { it.kind == Kind.EVENING }.associateBy { it.dateKey }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        for (i in 0..6) {
            val day = monday.plusDays(i.toLong())
            val isToday = day == today
            val rec = byDate[day.toString()]
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                val v = Ui.dp(this@MainActivity, 8); setPadding(0, v, 0, v)
                background = GradientDrawable().apply {
                    setColor(if (isToday) Ui.CORAL else Color.WHITE); cornerRadius = Ui.dp(this@MainActivity, 22).toFloat()
                    if (!isToday) setStroke(Ui.dp(this@MainActivity, 1), Ui.BORDER)
                }
                if (isToday) elevation = Ui.dp(this@MainActivity, 4).toFloat()
            }
            val fg = if (isToday) Color.WHITE else if (day.isAfter(today)) Ui.MUTED else Ui.INK
            chip.addView(Ui.label(this, day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()), 12f, fg, center = true))
            chip.addView(Ui.label(this, day.dayOfMonth.toString(), 17f, fg, true, true), Ui.lp(this, 2, 4))
            val dot = Presentation.dot(rec)
            val dotColor = when (dot) {
                DayDot.NONE -> Color.TRANSPARENT
                else -> if (isToday) Color.WHITE else when (dot) { DayDot.DONE -> Ui.GREEN; DayDot.MISSED -> Ui.CORAL; else -> Ui.AMBER }
            }
            chip.addView(View(this).apply { background = Ui.circle(this@MainActivity, dotColor, 6) }, LinearLayout.LayoutParams(Ui.dp(this, 6), Ui.dp(this, 6)))
            val status = when (dot) { DayDot.DONE -> "confirmed"; DayDot.MISSED -> "missed"; DayDot.NONE -> "no workflow"; DayDot.IN_PROGRESS -> "in progress" }
            chip.contentDescription = "${day.dayOfWeek} ${day.dayOfMonth}: $status"
            row.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(this@MainActivity, 3); marginEnd = Ui.dp(this@MainActivity, 3) })
        }
        add(row, 0, 16)
    }

    private fun statusColor(r: WorkflowRecord?) = when (r?.state) {
        State.COMPLETED -> Ui.GREEN
        null -> Ui.INK
        State.EXPIRED -> Ui.RED
        State.WAITING_FOR_SECOND_CONFIRMATION -> Ui.AMBER
        else -> Ui.RED
    }

    private fun icon(s: Severity) = when (s) { Severity.OK -> "\u2713"; Severity.WARN -> "!"; Severity.ERROR -> "\u2715" }

    private fun startTest() {
        g.call({ g.coordinator.startTest() }) { res ->
            when (res.outcome) {
                Outcome.APPLIED, Outcome.DUPLICATE -> startActivity(Intent(this, ReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                Outcome.BLOCKED -> Toast.makeText(this, "A real evening workflow is active; finish that first.", Toast.LENGTH_LONG).show()
                else -> Toast.makeText(this, "Could not start the test (${res.outcome}).", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ history

    private fun history(d: HomeData) {
        add(Ui.label(this, "History", 22f, Ui.INK, true), 16, 12)
        if (d.recent.isEmpty()) add(Ui.label(this, "Nothing recorded yet. Workflows appear here after the first reminder.", 16f, Ui.MUTED))
        d.recent.forEach { r ->
            val card = Ui.card(this)
            val date = if (r.isTest) "test" else r.dateKey
            card.addView(Ui.label(this, "${Format.kindLabel(r)}  -  $date", 16f, Ui.INK, true))
            val (badge, color) = when (r.state) {
                State.COMPLETED -> "COMPLETED" to Ui.GREEN
                State.MORNING_ACKNOWLEDGED -> "ACKNOWLEDGED" to Ui.GREEN
                State.EXPIRED -> (if (r.kind == Kind.MORNING) "NOT ACKNOWLEDGED" else "MISSED / INTERRUPTED") to Ui.RED
                else -> "IN PROGRESS: ${Format.stateLabel(r)}" to Ui.AMBER
            }
            card.addView(Ui.chip(this, badge, when (color) { Ui.GREEN -> Ui.GREEN_SOFT; Ui.AMBER -> Ui.AMBER_SOFT; else -> Ui.CORAL_SOFT }, color), LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(this@MainActivity, 6); bottomMargin = Ui.dp(this@MainActivity, 6) })
            val lines = mutableListOf("Reminder started: ${Format.dateTime(r.startedAt)}", "Reminders sent: ${r.alertCount}")
            if (r.kind != Kind.MORNING) {
                r.firstConfirmedAt?.let { lines += "First confirmation: ${Format.time(it)}" + if (r.confirmCount > 1) " (${r.confirmCount} attempts)" else "" }
                r.secondConfirmedAt?.let { lines += "Second confirmation: ${Format.time(it)}" }
                if (r.snoozeCount + r.notYetCount > 0) lines += "Snoozes: ${r.snoozeCount}   'Not yet': ${r.notYetCount}"
            }
            r.endReason?.let { lines += "Outcome: $it" }
            card.addView(Ui.label(this, lines.joinToString("\n"), 14f, Ui.MUTED))
            add(card)
        }
        add(Ui.label(this, "Event log", 18f, Ui.INK, true), 8, 8)
        d.events.forEach { e ->
            add(Ui.label(this, "${Format.dateTime(e.at)}  ${e.kind.name.lowercase()}  ${e.type.name}${if (e.detail.isNotBlank()) "  ${e.detail}" else ""}", 12f, Ui.MUTED), 0, 4)
        }
    }

    // ------------------------------------------------------------------ settings

    private fun save(change: (Settings) -> Settings) { g.run { g.coordinator.updateSettings(change) } }

    private fun settings(d: HomeData) {
        val s = d.settings
        add(Ui.label(this, "Settings", 22f, Ui.INK, true))

        section("Schedule") { c ->
            c.addView(switchRow("Morning tap-in reminder", s.morningEnabled) { save { st -> st.copy(morningEnabled = it) } })
            c.addView(timeRow("Morning time", s.morningMinute) { save { st -> st.copy(morningMinute = it) } })
            c.addView(switchRow("Evening tap-out enforcement", s.eveningEnabled) { save { st -> st.copy(eveningEnabled = it) } })
            c.addView(timeRow("Evening time", s.eveningMinute) { save { st -> st.copy(eveningMinute = it) } })
            c.addView(stepper("Second confirmation after", s.secondDelayMin, "min", 1, 30) { save { st -> st.copy(secondDelayMin = it) } })
        }
        section("Escalation while tap-out is pending") { c ->
            c.addView(stepper("Initial interval", s.initialIntervalMin, "min", 1, 15) { save { st -> st.copy(initialIntervalMin = it) } })
            c.addView(stepper("Initial phase lasts", s.initialPhaseMin, "min", 1, 120) { save { st -> st.copy(initialPhaseMin = it) } })
            c.addView(stepper("Then every", s.laterIntervalMin, "min", 1, 30) { save { st -> st.copy(laterIntervalMin = it) } })
            c.addView(stepper("Snooze length", s.snoozeMin, "min", 1, 15) { save { st -> st.copy(snoozeMin = it) } })
            c.addView(stepper("Max snoozes per day", s.maxSnoozes, "", 0, 10) { save { st -> st.copy(maxSnoozes = it) } })
            c.addView(Ui.label(this, "Reminders repeat until the second confirmation, or until the attendance day ends at midnight (then the day is recorded as missed).", 13f, Ui.MUTED))
        }
        section("Alert style") { c ->
            c.addView(switchRow("Alarm sound", s.soundEnabled) { save { st -> st.copy(soundEnabled = it) } })
            c.addView(switchRow("Vibration", s.vibrateEnabled) { save { st -> st.copy(vibrateEnabled = it) } })
            c.addView(Ui.label(this, "Sound and vibration are played by Android's notification channel (alarm audio stream), so Do Not Disturb 'alarms' rules and your system volume apply.", 13f, Ui.MUTED))
        }
        section("Reliability") { c ->
            val items = Reliability.assess(d.caps)
            val bad = items.count { it.severity != Severity.OK }
            c.addView(Ui.label(this, if (bad == 0) "Everything Android needs for on-time reminders is enabled." else "$bad item(s) can make reminders late or silent.", 15f, if (bad == 0) Ui.GREEN else Ui.AMBER, true))
            items.filter { it.severity != Severity.OK && it.actionId != null && it.actionId != "KIOSK_INFO" }.forEach { it ->
                c.addView(Ui.button(this, actionLabel(it.actionId!!), Ui.CORAL_SOFT, Ui.CORAL, heightDp = 48, sp = 15f) { fix(it.actionId, d) }, Ui.lp(this, 8, 0))
            }
            c.addView(Ui.button(this, "Open all checks", Ui.CORAL, heightDp = 48, sp = 15f) { inSettings = false; tab = Tab.CHECKS; render() }, Ui.lp(this, 8, 0))
        }
        section("Attendance app") { c ->
            val field = EditText(this).apply {
                setText(s.attendancePackage); hint = "package name, e.g. com.company.attendance"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                contentDescription = "Attendance app package name"
            }
            c.addView(field)
            c.addView(Ui.row(this,
                Ui.weight(Ui.button(this, "Save", Ui.BLUE, heightDp = 48, sp = 15f) { save { st -> st.copy(attendancePackage = field.text.toString()) }; toast("Saved") }),
                Ui.weight(Ui.button(this, "Pick app", Ui.BLUE, heightDp = 48, sp = 15f) { pickApp(s) }),
                Ui.weight(Ui.button(this, "Test launch", Ui.GREEN, heightDp = 48, sp = 15f) { launch(field.text.toString().trim()) }),
            ), Ui.lp(this, 8, 0))
        }
        section("Kiosk / task lock (optional, opt-in)") { c ->
            c.addView(switchRow("Lock the screen to the reminder while pending", s.kioskOptIn) { save { st -> st.copy(kioskOptIn = it) } })
            val mode = g.kiosk.mode(true)
            c.addView(Ui.label(this, when (mode) {
                KioskMode.LOCK_TASK -> "Available: this app is allowlisted for Lock Task Mode."
                else -> "True task lock is NOT available on this device (not a device owner). If enabled, the app can only ask Android for screen pinning, which you confirm and can undo."
            }, 14f, Ui.MUTED), Ui.lp(this, 4, 8))
            c.addView(Ui.button(this, "Kiosk setup instructions", Ui.BLUE, heightDp = 48, sp = 15f) { kioskInfo() })
        }
        section("Test mode & reset") { c ->
            c.addView(stepper("Test second-check delay", s.testDelaySec, "sec", 10, 300) { save { st -> st.copy(testDelaySec = it) } })
            c.addView(Ui.button(this, "Start test reminder", Ui.RED, heightDp = 52) { startTest() }, Ui.lp(this, 4, 8))
            c.addView(Ui.button(this, "Send test morning notification", Ui.GREEN, heightDp = 52) {
                g.run(notify = false) {
                    val now = System.currentTimeMillis()
                    g.notifier.showMorning(WorkflowRecord("TEST", Kind.MORNING, State.MORNING_NOTIFIED, zoneId = g.clock.zone().id, scheduledAt = now, startedAt = now, expiresAt = now + 3600_000), s)
                }
            }, Ui.lp(this, 0, 8))
            c.addView(Ui.button(this, "Reset today's evening workflow", Ui.AMBER, heightDp = 52) {
                AlertDialog.Builder(this).setTitle("Reset today's workflow?")
                    .setMessage("Deletes today's evening record and any test record. If it is already past the evening time (within the late-start window) the reminder starts again from the beginning.")
                    .setPositiveButton("Reset") { _, _ -> g.run { g.coordinator.reset() } }
                    .setNegativeButton("Cancel", null).show()
            })
        }
    }

    private fun section(title: String, build: (LinearLayout) -> Unit) {
        val card = Ui.card(this)
        card.addView(Ui.label(this, title, 14f, Ui.MUTED, true), Ui.lp(this, 0, 8))
        build(card)
        add(card)
    }

    private fun switchRow(title: String, on: Boolean, change: (Boolean) -> Unit): View {
        val sw = Switch(this).apply { isChecked = on; contentDescription = title; setOnCheckedChangeListener { _, v -> change(v) } }
        val t = Ui.label(this, title, 16f, Ui.INK)
        return Ui.row(this, Ui.weight(t), sw).apply { minimumHeight = Ui.dp(this@MainActivity, 52) }
    }

    private fun timeRow(title: String, minute: Int, change: (Int) -> Unit): View {
        val b = Ui.button(this, Format.minuteOfDay(minute), Ui.CORAL_SOFT, Ui.CORAL, heightDp = 48, sp = 16f) {
            TimePickerDialog(this, { _, h, m -> change(h * 60 + m) }, minute / 60, minute % 60, false).show()
        }
        b.contentDescription = "$title: ${Format.minuteOfDay(minute)}. Tap to change."
        return Ui.row(this, Ui.weight(Ui.label(this, title, 16f, Ui.INK)), b).apply { minimumHeight = Ui.dp(this@MainActivity, 52) }
    }

    private fun stepper(title: String, value: Int, unit: String, min: Int, max: Int, change: (Int) -> Unit): View {
        val v = Ui.label(this, "$value $unit".trim(), 16f, Ui.INK, true).apply { gravity = Gravity.CENTER; minWidth = Ui.dp(this@MainActivity, 64) }
        val minus = Ui.button(this, "-", Ui.CORAL_SOFT, Ui.CORAL, heightDp = 48, sp = 20f) { if (value > min) change(value - 1) }
        val plus = Ui.button(this, "+", Ui.CORAL_SOFT, Ui.CORAL, heightDp = 48, sp = 20f) { if (value < max) change(value + 1) }
        minus.contentDescription = "Decrease $title"; plus.contentDescription = "Increase $title"
        listOf(minus, plus).forEach { it.minWidth = Ui.dp(this, 52); it.minimumWidth = Ui.dp(this, 52) }
        return Ui.row(this, Ui.weight(Ui.label(this, title, 16f, Ui.INK)), minus, v, plus).apply { minimumHeight = Ui.dp(this@MainActivity, 56) }
    }

    private fun pickApp(s: Settings) {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
        AlertDialog.Builder(this).setTitle("Choose your attendance app")
            .setItems(apps.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, i -> save { st -> st.copy(attendancePackage = apps[i].second) }; toast("Saved ${apps[i].first}") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun launch(pkg: String) {
        val i = if (pkg.isBlank()) null else packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) toast("No launchable app with package \"$pkg\"") else startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ checks (diagnostics)

    private fun checks(d: HomeData) {
        add(Ui.label(this, "Permission & reliability checks", 22f, Ui.INK, true))
        Reliability.assess(d.caps).forEach { item ->
            val card = Ui.card(this)
            card.addView(Ui.label(this, "${icon(item.severity)}  ${item.title}", 17f, when (item.severity) { Severity.OK -> Ui.GREEN; Severity.WARN -> Ui.AMBER; Severity.ERROR -> Ui.RED }, true))
            card.addView(Ui.label(this, item.detail, 15f, Ui.INK), Ui.lp(this, 4, 4))
            if (item.actionId != null && (item.severity != Severity.OK || item.actionId == "KIOSK_INFO")) {
                card.addView(Ui.button(this, actionLabel(item.actionId), Ui.BLUE, heightDp = 48, sp = 15f) { fix(item.actionId, d) }, Ui.lp(this, 6, 0))
            }
            add(card)
        }
        val info = Ui.card(this)
        info.addView(Ui.label(this, "What this build can and cannot do", 14f, Ui.MUTED, true), Ui.lp(this, 0, 6))
        val tier = Reliability.tickTier(d.caps)
        info.addView(Ui.label(this,
            "- Android ${Build.VERSION.RELEASE} (API ${d.caps.sdk}). Follow-up alarms: ${if (tier == AlarmTier.EXACT) "exact" else "alarm-clock"}; start alarms: alarm-clock.\n" +
            "- Normal mode: persistent notification, alarm sound/vibration, full-screen reminder where Android allows, repeated reminders, recovery after reboot.\n" +
            "- Phone-wide blocking: ${if (d.caps.lockTaskPermitted) "AVAILABLE (Lock Task Mode, opt-in)" else "NOT AVAILABLE. An ordinary app cannot stop you from pressing Home, opening Settings or using the power button."}\n" +
            "- After 'Force stop', Android cancels all alarms and blocks every receiver until you open the app once more.\n" +
            "- The app records your own confirmations; it cannot verify them with the attendance system.", 14f, Ui.INK))
        add(info)
        add(Ui.button(this, "Settings", Ui.BLUE) { inSettings = true; render() })
    }

    private fun actionLabel(id: String) = when (id) {
        "NOTIFICATIONS" -> "Enable notifications"; "CHANNEL" -> "Open channel settings"; "EXACT_ALARM" -> "Allow exact alarms"
        "FULL_SCREEN" -> "Allow full-screen reminders"; "BATTERY" -> "Open battery settings"; else -> "Kiosk setup instructions"
    }

    private fun fix(id: String, d: HomeData) {
        val pkgUri = Uri.parse("package:$packageName")
        val intent: Intent? = when (id) {
            "NOTIFICATIONS" -> {
                val prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    !prefs.getBoolean("asked_notif", false)) {
                    prefs.edit().putBoolean("asked_notif", true).apply()
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100); null
                } else Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName)
            }
            "CHANNEL" -> Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(AndroidSettings.EXTRA_CHANNEL_ID, Ids.alertChannel(d.settings.soundEnabled, d.settings.vibrateEnabled))
            "EXACT_ALARM" -> if (Build.VERSION.SDK_INT >= 31) Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri) else null
            "FULL_SCREEN" -> if (Build.VERSION.SDK_INT >= 34) Intent(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri)
                else Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName)
            "BATTERY" -> Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "KIOSK_INFO" -> { kioskInfo(); null }
            else -> null
        }
        if (intent != null) try { startActivity(intent) } catch (_: Exception) {
            try { startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)) } catch (_: Exception) { toast("Open Android Settings > Apps > TapOut Guardian") }
        }
    }

    private fun kioskInfo() {
        val tv = TextView(this).apply {
            setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 20), 0)
            textSize = 14f; setTextColor(Ui.INK); setTextIsSelectable(true)
            text = """True phone-wide blocking uses Android Lock Task Mode, which only a DEVICE OWNER can grant. Normal installs cannot do this, by design.

Only do this on a phone you own and are willing to factory-reset. Device-owner provisioning is a device-management change: it requires a device with NO accounts (typically right after a factory reset), it cannot be undone from the UI, and it affects what you can install and change.

1. Factory-reset the test device and skip adding any Google/other account.
2. Enable USB debugging and install the APK.
3. Run:
   adb shell dpm set-device-owner com.tapoutguardian/.android.AdminReceiver
4. Open Settings > Kiosk and switch the opt-in on. At the next pending reminder the app locks to the reminder screen. The power menu, status info and emergency calls stay available, and your attendance app is allowlisted so you can tap out.
5. Emergency exit: hold the "Emergency exit" button on the reminder screen.
6. Remove device ownership on a debuggable build:
   adb shell dpm remove-active-admin com.tapoutguardian/.android.AdminReceiver
   (otherwise a factory reset removes it).

Without a device owner, enabling the switch only asks Android for screen pinning, which you confirm and can undo with Back + Overview."""
        }
        AlertDialog.Builder(this).setTitle("Kiosk (Lock Task) setup").setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null).show()
    }

    // ------------------------------------------------------------------ first-run explanation

    /** First launch only: a short branded welcome (the "splash") that also explains the permissions before asking for them. */
    private fun maybeIntro() {
        val prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("intro_done", false)) return
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(Ui.PAGE)
            val p = Ui.dp(this@MainActivity, 28); setPadding(p, p * 2, p, p)
            isClickable = true
        }
        overlay.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        overlay.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo); setColorFilter(Color.WHITE)
            val pad = Ui.dp(this@MainActivity, 28); setPadding(pad, pad, pad, pad)
            background = Ui.circle(this@MainActivity, Ui.CORAL, 120); elevation = Ui.dp(this@MainActivity, 10).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120)))
        overlay.addView(Ui.label(this, "Attendance made effortless", 30f, Ui.INK, true, true), Ui.lp(this, 28, 12))
        overlay.addView(Ui.label(this, "TapOut Guardian keeps reminding you each evening until you have confirmed your tap-out twice. Everything stays on this phone.", 16f, Ui.MUTED, center = true), Ui.lp(this, 0, 16))
        overlay.addView(Ui.tint(this, Ui.CORAL_SOFT).apply {
            addView(Ui.label(this@MainActivity, "Notifications show the reminder. Alarms & reminders make follow-ups exact. Full-screen alerts open it over the lock screen. No accessibility, overlay or device-admin access is used.", 14f, Ui.INK))
        }, Ui.lp(this, 0, 12))
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (i in 0 until 5) dots.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(if (i == 2) Ui.CORAL else Color.parseColor("#D9D4D0")); cornerRadius = Ui.dp(this@MainActivity, 4).toFloat() }
        }, LinearLayout.LayoutParams(Ui.dp(this, if (i == 2) 22 else 8), Ui.dp(this, 8)).apply { marginStart = Ui.dp(this@MainActivity, 4); marginEnd = Ui.dp(this@MainActivity, 4) })
        overlay.addView(dots, Ui.lp(this, 8, 16))
        overlay.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        overlay.addView(Ui.button(this, "Get started", Ui.CORAL, heightDp = 60, sp = 18f) {
            prefs.edit().putBoolean("intro_done", true).putBoolean("asked_notif", true).apply()
            frame.removeView(overlay)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }, Ui.lp(this, 0, 8))
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }
}
