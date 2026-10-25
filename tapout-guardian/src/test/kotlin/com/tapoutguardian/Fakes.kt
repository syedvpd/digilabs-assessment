package com.tapoutguardian

import com.tapoutguardian.core.*
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId

class FakeClock(var wall: Long, var elapsed: Long = 1_000_000L, var boot: Long = 1, var zone: ZoneId = ZoneId.of("UTC")) : Clock {
    override fun wallMillis() = wall
    override fun elapsedMillis() = elapsed
    override fun bootId() = boot
    override fun zone() = zone
    /** Real time passing: both clocks advance. */
    fun advance(ms: Long) { wall += ms; elapsed += ms }
    fun advanceTo(t: Long) = advance(t - wall)
    /** The user (or network) changes the wall clock; elapsedRealtime is unaffected. */
    fun setWallOnly(t: Long) { wall = t }
    /** Device restarts after [downMs]: elapsedRealtime restarts, boot id changes, wall clock keeps running. */
    fun reboot(downMs: Long = 60_000) { wall += downMs; elapsed = 5_000; boot += 1 }
}

/** SqlDb over a real SQLite engine (JDBC) so DDL, migrations and transactions are genuinely exercised. */
class JdbcDb(private val file: File) : SqlDb {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")
    private var depth = 0
    override fun execute(sql: String, vararg args: Any?) {
        conn.prepareStatement(sql).use { st -> args.forEachIndexed { i, a -> st.setObject(i + 1, a) }; st.executeUpdate() }
    }
    override fun query(sql: String, vararg args: Any?): List<Row> =
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs ->
                val md = rs.metaData
                val out = ArrayList<Row>()
                while (rs.next()) out += (1..md.columnCount).associate { md.getColumnLabel(it) to rs.getObject(it) }
                out
            }
        }
    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        depth++
        conn.autoCommit = false
        try {
            val r = block(); conn.commit(); return r
        } catch (t: Throwable) {
            conn.rollback(); throw t
        } finally { depth--; conn.autoCommit = true }
    }
    override var userVersion: Int
        get() = (query("PRAGMA user_version").first().values.first() as Number).toInt()
        set(v) { conn.createStatement().use { it.execute("PRAGMA user_version = $v") } }
    fun close() = conn.close()
}

/** Throws on the Nth write statement after [arm]. */
class FaultyDb(private val d: SqlDb) : SqlDb by d {
    private var countdown = -1
    fun failOnWrite(n: Int) { countdown = n }
    override fun execute(sql: String, vararg args: Any?) {
        if (countdown >= 0 && --countdown < 0) { countdown = -1; throw IllegalStateException("injected disk failure") }
        d.execute(sql, *args)
    }
}

class FakeAlarms : AlarmPort {
    var last: Plan? = null
    var applyCount = 0
    override fun apply(plan: Plan) { last = plan; applyCount++ }
    fun clear() { last = null }       // what a reboot / force-stop does to AlarmManager
}

class FakeNotifier : NotifyPort {
    var notificationsAllowed = true
    var eveningShown: WorkflowRecord? = null
    var morningShown: WorkflowRecord? = null
    val loudAlerts = mutableListOf<Long>()
    var clockRef: Clock? = null
    override fun showEvening(record: WorkflowRecord, alert: Boolean, settings: Settings) {
        if (!notificationsAllowed) return
        eveningShown = record
        if (alert) loudAlerts += clockRef!!.wallMillis()
    }
    override fun cancelEvening() { eveningShown = null }
    override fun showMorning(record: WorkflowRecord, settings: Settings) { if (notificationsAllowed) morningShown = record }
    override fun cancelMorning() { morningShown = null }
}

/** One simulated device: a database file survives "process death"; engine/coordinator/ports are rebuilt. */
class Env(val dir: File, startWall: Long, zone: ZoneId = ZoneId.of("UTC"), var faulty: Boolean = false) {
    val file = File(dir, "tapout.db")
    val clock = FakeClock(startWall, zone = zone)
    lateinit var rawDb: JdbcDb
    lateinit var db: SqlDb
    lateinit var store: WorkflowStore
    lateinit var engine: WorkflowEngine
    lateinit var alarms: FakeAlarms
    lateinit var notifier: FakeNotifier
    lateinit var coord: Coordinator
    val logs = mutableListOf<String>()

    init { boot() }

    /** (Re)creates everything except the database file: this is what process death looks like. */
    fun boot() {
        if (this::rawDb.isInitialized) rawDb.close()
        rawDb = JdbcDb(file)
        db = if (faulty) FaultyDb(rawDb) else rawDb
        store = SqlWorkflowStore(db)
        engine = WorkflowEngine(store, clock)
        alarms = FakeAlarms()
        notifier = FakeNotifier().also { it.clockRef = clock }
        coord = Coordinator(engine, clock, alarms, notifier) { logs += it }
    }

    fun settings(f: (Settings) -> Settings) { coord.updateSettings(f) }
    fun evening(date: String = dateKeyOf(clock.wall, clock.zone)) = store.get(date, Kind.EVENING)
    fun morning(date: String = dateKeyOf(clock.wall, clock.zone)) = store.get(date, Kind.MORNING)
    fun key() = dateKeyOf(clock.wall, clock.zone)
    fun history(type: HistoryType) = store.history(1000).filter { it.type == type }
    /** Simulates AlarmManager delivering the planned tick/start alarms in order until [until]. */
    fun runAlarmsUntil(until: Long) {
        var guard = 0
        while (guard++ < 2000) {
            val p = alarms.last ?: return
            val next = listOfNotNull(p.eveningStartAt, p.morningStartAt, p.tickAt).minOrNull() ?: return
            if (next > until) break
            clock.advanceTo(maxOf(next, clock.wall))
            coord.onTrigger("alarm")
        }
        clock.advanceTo(maxOf(until, clock.wall))
    }
}

fun utc(date: String, time: String, zone: String = "UTC"): Long =
    LocalDate.parse(date).atTime(java.time.LocalTime.parse(time)).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

const val MIN = 60_000L
