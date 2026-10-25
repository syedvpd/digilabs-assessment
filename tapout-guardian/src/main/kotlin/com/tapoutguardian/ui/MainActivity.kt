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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.tapoutguardian.android.Graph
import com.tapoutguardian.android.HomeData
import com.tapoutguardian.android.Ids
import com.tapoutguardian.core.*

class MainActivity : Activity() {
    private enum class Tab(val title: String) { HOME("Home"), HISTORY("History"), SETTINGS("Settings"), CHECKS("Checks") }

    private lateinit var g: Graph
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private val nav = mutableMapOf<Tab, android.widget.Button>()
    private var tab = Tab.HOME
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.PAGE) }
        root.addView(Ui.label(this, "TapOut Guardian", 22f, Ui.INK, true).apply {
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 10))
            setBackgroundColor(Color.WHITE)
            contentDescription = "TapOut Guardian, attendance tap-out enforcement"
        })
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@MainActivity, 16); setPadding(p, p, p, p)
        }
        scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE) }
        Tab.values().forEach { t ->
            val b = Ui.button(this, t.title, Color.TRANSPARENT, Ui.INK, heightDp = 56, sp = 15f) { tab = t; render() }
            nav[t] = b; bar.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(bar)
        setContentView(root)
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
        clockView?.text = Format.date(now) + "\n" + Format.timeSeconds(now)
        liveStatus?.text = statusLine(d, now)
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        val d = data ?: return
        nav.forEach { (t, b) -> b.setTextColor(if (t == tab) Ui.BLUE else Ui.MUTED); b.alpha = if (t == tab) 1f else 0.8f }
        val keepScroll = scroll.scrollY
        content.removeAllViews(); clockView = null; liveStatus = null
        when (tab) { Tab.HOME -> home(d); Tab.HISTORY -> history(d); Tab.SETTINGS -> settings(d); Tab.CHECKS -> checks(d) }
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

    private fun home(d: HomeData) {
        val now = System.currentTimeMillis()
        val clockCard = Ui.card(this)
        clockView = Ui.label(this, Format.date(now) + "\n" + Format.timeSeconds(now), 26f, Ui.INK, true)
        clockCard.addView(clockView)
        clockCard.addView(Ui.label(this, "Device time zone: ${d.zoneId}", 13f, Ui.MUTED))
        add(clockCard)

        val ev = Ui.card(this)
        ev.addView(Ui.label(this, "Evening tap-out (${Format.minuteOfDay(d.settings.eveningMinute)})", 14f, Ui.MUTED, true))
        liveStatus = Ui.label(this, statusLine(d, now), 19f, statusColor(d.evening), true)
        ev.addView(liveStatus, Ui.lp(this, 4, 8))
        val active = d.evening?.takeIf { !it.state.isTerminal && it.kind != Kind.MORNING }
        if (active != null) ev.addView(Ui.button(this, "OPEN REMINDER SCREEN", Ui.RED) {
            startActivity(Intent(this, ReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        })
        add(ev)

        val mo = Ui.card(this)
        mo.addView(Ui.label(this, "Morning tap-in (${Format.minuteOfDay(d.settings.morningMinute)})", 14f, Ui.MUTED, true))
        val m = d.morning
        val mText = when {
            m == null -> d.plan.morningStartAt?.let { "Next reminder ${Format.dateTime(it)}" } ?: "Disabled"
            m.state == State.MORNING_ACKNOWLEDGED -> "You said you tapped in (${Format.time(m.endedAt ?: 0)}). Not verified with the attendance system."
            m.state == State.MORNING_NOTIFIED -> "Reminder sent at ${Format.time(m.startedAt)}: have you completed your tap-in?"
            else -> Format.stateLabel(m)
        }
        mo.addView(Ui.label(this, mText, 17f, Ui.INK), Ui.lp(this, 4, 8))
        if (m?.state == State.MORNING_NOTIFIED) mo.addView(Ui.button(this, "I've tapped in", Ui.GREEN) { g.call({ g.coordinator.acknowledgeMorning(m.dateKey) }) { } }, Ui.lp(this, 0, 8))
        add(mo)

        val rel = Ui.card(this)
        val diags = Reliability.assess(d.caps)
        val bad = diags.count { it.severity != Severity.OK }
        rel.addView(Ui.label(this, "Permissions & reliability", 14f, Ui.MUTED, true))
        rel.addView(Ui.label(this, if (bad == 0) "All checks passed." else "$bad item(s) need attention.", 17f, if (bad == 0) Ui.GREEN else Ui.AMBER, true), Ui.lp(this, 4, 4))
        diags.forEach { rel.addView(Ui.label(this, "${icon(it.severity)}  ${it.title}", 15f, Ui.INK), Ui.lp(this, 2, 2)) }
        rel.addView(Ui.button(this, if (bad == 0) "View details" else "FIX NOW", if (bad == 0) Ui.BLUE else Ui.AMBER) { tab = Tab.CHECKS; render() }, Ui.lp(this, 10, 0))
        add(rel)

        add(Ui.button(this, "Test the reminder now", Ui.RED) { startTest() })
        add(Ui.button(this, "Settings", Ui.BLUE) { tab = Tab.SETTINGS; render() })
        add(Ui.button(this, "History", Ui.BLUE) { tab = Tab.HISTORY; render() })
        add(Ui.label(this, "TapOut Guardian only records what you tell it. It cannot read your attendance system, and Android does not let an ordinary app lock the phone. See Checks.", 13f, Ui.MUTED))
    }

    private fun statusColor(r: WorkflowRecord?) = when (r?.state) {
        State.COMPLETED -> Ui.GREEN
        null -> Ui.INK
        State.EXPIRED -> Ui.RED
        State.WAITING_FOR_SECOND_CONFIRMATION -> Ui.AMBER
        else -> Ui.RED
    }

    private fun icon(s: Severity) = when (s) { Severity.OK -> "[OK]"; Severity.WARN -> "[!]"; Severity.ERROR -> "[X]" }

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
        add(Ui.label(this, "History", 22f, Ui.INK, true))
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
            card.addView(Ui.label(this, badge, 14f, color, true), Ui.lp(this, 2, 6))
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
        val b = Ui.button(this, Format.minuteOfDay(minute), Ui.BLUE, heightDp = 48, sp = 16f) {
            TimePickerDialog(this, { _, h, m -> change(h * 60 + m) }, minute / 60, minute % 60, false).show()
        }
        b.contentDescription = "$title: ${Format.minuteOfDay(minute)}. Tap to change."
        return Ui.row(this, Ui.weight(Ui.label(this, title, 16f, Ui.INK)), b).apply { minimumHeight = Ui.dp(this@MainActivity, 52) }
    }

    private fun stepper(title: String, value: Int, unit: String, min: Int, max: Int, change: (Int) -> Unit): View {
        val v = Ui.label(this, "$value $unit".trim(), 16f, Ui.INK, true).apply { gravity = Gravity.CENTER; minWidth = Ui.dp(this@MainActivity, 64) }
        val minus = Ui.button(this, "-", Ui.BLUE, heightDp = 48, sp = 20f) { if (value > min) change(value - 1) }
        val plus = Ui.button(this, "+", Ui.BLUE, heightDp = 48, sp = 20f) { if (value < max) change(value + 1) }
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

    private fun maybeIntro() {
        val prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("intro_done", false)) return
        AlertDialog.Builder(this).setTitle("Before we start")
            .setMessage("TapOut Guardian keeps reminding you each evening until you have confirmed your tap-out twice.\n\n" +
                "- Notifications: required to show the reminder.\n" +
                "- Alarms & reminders: lets follow-up reminders fire exactly on time.\n" +
                "- Full-screen alerts: lets the reminder open over the lock screen.\n\n" +
                "No accessibility, overlay or device-admin permission is used. Android still lets you leave the app; see the Checks tab for what this device allows.")
            .setCancelable(false)
            .setPositiveButton("Continue") { _, _ ->
                prefs.edit().putBoolean("intro_done", true).putBoolean("asked_notif", true).apply()
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                }
            }.show()
    }
}
