package com.tapoutguardian.android

import android.content.BroadcastReceiver
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tapoutguardian.core.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Everything the UI needs in one consistent read. */
data class HomeData(
    val settings: Settings,
    val evening: WorkflowRecord?,      // the active evening/test workflow, else today's record if any
    val morning: WorkflowRecord?,
    val plan: Plan,
    val caps: DeviceCapabilities,
    val recent: List<WorkflowRecord>,
    val events: List<HistoryEntry>,
    val now: Long,
    val zoneId: String,
)

/**
 * Process-wide wiring. Every mutation goes through [executor] (a single thread), so receivers, notification actions
 * and UI taps are serialised on top of the engine's own lock + SQLite transactions.
 */
class Graph private constructor(val appContext: Context) {
    val probe = DeviceProbe(appContext)
    val clock = AndroidClock(appContext)
    val store = openStore(appContext)
    val engine = WorkflowEngine(store, clock)
    val notifier = AndroidNotifier(appContext, probe)
    val alarms = AndroidAlarms(appContext, probe)
    val coordinator = Coordinator(engine, clock, alarms, notifier) { Log.w(TAG, it) }
    val kiosk = KioskController(appContext, probe)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tapout-worker").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun fireChanged() = main.post { listeners.forEach { it() } }

    /** Runs [work] serially off the main thread, optionally finishing a goAsync() broadcast, then tells the UI. */
    fun run(pending: BroadcastReceiver.PendingResult? = null, notify: Boolean = true, work: () -> Unit) {
        executor.execute {
            try { work() } catch (e: Throwable) { Log.e(TAG, "work failed", e) }
            finally { pending?.finish() }
            if (notify) fireChanged()
        }
    }

    /** Runs [work] serially and delivers its result on the main thread. */
    fun <T> call(work: () -> T, notify: Boolean = true, done: (T) -> Unit) {
        executor.execute {
            val r = try { work() } catch (e: Throwable) { Log.e(TAG, "call failed", e); return@execute }
            main.post { done(r); if (notify) listeners.forEach { it() } }
        }
    }

    fun loadHome(): HomeData {
        val now = clock.wallMillis()
        return engine.read { st ->
            val s = st.settings()
            val active = st.active()
            val key = dateKeyOf(now, clock.zone())
            val evening = active.firstOrNull { it.kind == Kind.EVENING } ?: active.firstOrNull { it.kind == Kind.TEST }
                ?: st.get(key, Kind.EVENING)
            HomeData(
                settings = s, evening = evening, morning = st.get(key, Kind.MORNING),
                plan = Planner.plan(now, s, clock.zone(), active) { k, kind -> st.get(k, kind) != null },
                caps = probe.capabilities(Ids.alertChannel(s.soundEnabled, s.vibrateEnabled)),
                recent = st.recent(60), events = st.history(150), now = now, zoneId = clock.zone().id,
            )
        }
    }

    companion object {
        private const val TAG = "TapOut"

        /**
         * If the database cannot be opened or migrated (corruption, or a downgrade to an older app build), keep the
         * unreadable file as a backup and start clean rather than crash-looping and silently disabling the reminders.
         */
        private fun openStore(context: Context): SqlWorkflowStore = try {
            SqlWorkflowStore(AndroidSqlDb.open(context))
        } catch (e: Exception) {
            Log.e(TAG, "database unusable, moving it aside", e)
            val f = context.getDatabasePath("tapout.db")
            f.renameTo(java.io.File(f.parentFile, "tapout.db.unreadable-${System.currentTimeMillis()}"))
            java.io.File(f.path + "-wal").delete(); java.io.File(f.path + "-shm").delete()
            SqlWorkflowStore(AndroidSqlDb.open(context))
        }

        @Volatile private var instance: Graph? = null
        fun get(context: Context): Graph = instance ?: synchronized(this) {
            instance ?: Graph(context.applicationContext).also { instance = it }
        }
    }
}
