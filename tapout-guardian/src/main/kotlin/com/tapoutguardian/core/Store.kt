package com.tapoutguardian.core

/** Persistence used by the engine. All mutations must happen inside [inTransaction]. */
interface WorkflowStore {
    fun <T> inTransaction(block: () -> T): T
    fun get(dateKey: String, kind: Kind): WorkflowRecord?
    fun put(record: WorkflowRecord)
    fun delete(dateKey: String, kind: Kind)
    fun active(): List<WorkflowRecord>
    fun recent(limit: Int): List<WorkflowRecord>
    fun addHistory(entry: HistoryEntry)
    fun history(limit: Int): List<HistoryEntry>
    fun settings(): Settings
    fun saveSettings(settings: Settings)
    fun pruneOlderThan(cutoff: Long)
}

class SqlWorkflowStore(private val db: SqlDb) : WorkflowStore {
    init {
        Schema.open(db)
        if (db.query("SELECT id FROM settings WHERE id = 1").isEmpty()) {
            db.execute("INSERT INTO settings (id) VALUES (1)")
        }
    }

    override fun <T> inTransaction(block: () -> T): T = db.transaction(block)

    override fun get(dateKey: String, kind: Kind): WorkflowRecord? =
        db.query("SELECT * FROM workflow WHERE dateKey = ? AND kind = ?", dateKey, kind.name).firstOrNull()?.toRecord()

    override fun put(record: WorkflowRecord) {
        val r = record
        db.execute(
            """INSERT OR REPLACE INTO workflow (dateKey, kind, state, rev, stateVersion, zoneId, scheduledAt, startedAt,
                firstConfirmedAt, confirmElapsed, confirmBootId, secondDelayMs, secondDeadlineAt, secondDueAt,
                secondConfirmedAt, completedAt, lastAlertAt, nextAlertAt, alertCount, alertAnchorAt, snoozeCount,
                notYetCount, confirmCount, expiresAt, endedAt, endReason)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            r.dateKey, r.kind.name, r.state.name, r.rev, r.stateVersion.toLong(), r.zoneId, r.scheduledAt, r.startedAt,
            r.firstConfirmedAt, r.confirmElapsed, r.confirmBootId, r.secondDelayMs, r.secondDeadlineAt, r.secondDueAt,
            r.secondConfirmedAt, r.completedAt, r.lastAlertAt, r.nextAlertAt, r.alertCount.toLong(), r.alertAnchorAt,
            r.snoozeCount.toLong(), r.notYetCount.toLong(), r.confirmCount.toLong(), r.expiresAt, r.endedAt, r.endReason,
        )
    }

    override fun delete(dateKey: String, kind: Kind) {
        db.execute("DELETE FROM workflow WHERE dateKey = ? AND kind = ?", dateKey, kind.name)
    }

    override fun active(): List<WorkflowRecord> =
        db.query("SELECT * FROM workflow WHERE state NOT IN ('COMPLETED','EXPIRED','MORNING_ACKNOWLEDGED') ORDER BY startedAt")
            .map { it.toRecord() }

    override fun recent(limit: Int): List<WorkflowRecord> =
        db.query("SELECT * FROM workflow ORDER BY startedAt DESC LIMIT ${limit.coerceIn(1, 1000)}").map { it.toRecord() }

    override fun addHistory(entry: HistoryEntry) {
        db.execute(
            "INSERT INTO history (dateKey, kind, at, type, detail) VALUES (?,?,?,?,?)",
            entry.dateKey, entry.kind.name, entry.at, entry.type.name, entry.detail,
        )
    }

    override fun history(limit: Int): List<HistoryEntry> =
        db.query("SELECT * FROM history ORDER BY at DESC, id DESC LIMIT ${limit.coerceIn(1, 5000)}").map {
            HistoryEntry(it.long("id"), it.str("dateKey"), Kind.valueOf(it.str("kind")), it.long("at"),
                HistoryType.valueOf(it.str("type")), it.str("detail"))
        }

    override fun settings(): Settings {
        val r = db.query("SELECT * FROM settings WHERE id = 1").first()
        return Settings(
            morningEnabled = r.long("morningEnabled") != 0L, morningMinute = r.long("morningMinute").toInt(),
            eveningEnabled = r.long("eveningEnabled") != 0L, eveningMinute = r.long("eveningMinute").toInt(),
            secondDelayMin = r.long("secondDelayMin").toInt(), initialIntervalMin = r.long("initialIntervalMin").toInt(),
            initialPhaseMin = r.long("initialPhaseMin").toInt(), laterIntervalMin = r.long("laterIntervalMin").toInt(),
            snoozeMin = r.long("snoozeMin").toInt(), maxSnoozes = r.long("maxSnoozes").toInt(),
            soundEnabled = r.long("soundEnabled") != 0L, vibrateEnabled = r.long("vibrateEnabled") != 0L,
            attendancePackage = r.str("attendancePackage"), kioskOptIn = r.long("kioskOptIn") != 0L,
            testDelaySec = r.long("testDelaySec").toInt(), armedAt = r.long("armedAt"),
        )
    }

    override fun saveSettings(settings: Settings) {
        val s = settings.sanitized()
        db.execute(
            """UPDATE settings SET morningEnabled=?, morningMinute=?, eveningEnabled=?, eveningMinute=?, secondDelayMin=?,
               initialIntervalMin=?, initialPhaseMin=?, laterIntervalMin=?, snoozeMin=?, maxSnoozes=?, soundEnabled=?,
               vibrateEnabled=?, attendancePackage=?, kioskOptIn=?, testDelaySec=?, armedAt=? WHERE id = 1""",
            s.morningEnabled.toLong(), s.morningMinute.toLong(), s.eveningEnabled.toLong(), s.eveningMinute.toLong(),
            s.secondDelayMin.toLong(), s.initialIntervalMin.toLong(), s.initialPhaseMin.toLong(), s.laterIntervalMin.toLong(),
            s.snoozeMin.toLong(), s.maxSnoozes.toLong(), s.soundEnabled.toLong(), s.vibrateEnabled.toLong(),
            s.attendancePackage, s.kioskOptIn.toLong(), s.testDelaySec.toLong(), s.armedAt,
        )
    }

    override fun pruneOlderThan(cutoff: Long) {
        db.execute("DELETE FROM history WHERE at < ?", cutoff)
        db.execute("DELETE FROM workflow WHERE startedAt < ? AND state IN ('COMPLETED','EXPIRED','MORNING_ACKNOWLEDGED')", cutoff)
    }

    private fun Boolean.toLong() = if (this) 1L else 0L
    private fun Row.long(k: String): Long = (this[k] as Number).toLong()
    private fun Row.longOrNull(k: String): Long? = (this[k] as Number?)?.toLong()
    private fun Row.str(k: String): String = this[k] as String

    private fun Row.toRecord() = WorkflowRecord(
        dateKey = str("dateKey"), kind = Kind.valueOf(str("kind")), state = State.valueOf(str("state")),
        rev = long("rev"), stateVersion = long("stateVersion").toInt(), zoneId = str("zoneId"),
        scheduledAt = long("scheduledAt"), startedAt = long("startedAt"),
        firstConfirmedAt = longOrNull("firstConfirmedAt"), confirmElapsed = longOrNull("confirmElapsed"),
        confirmBootId = longOrNull("confirmBootId"), secondDelayMs = long("secondDelayMs"),
        secondDeadlineAt = longOrNull("secondDeadlineAt"), secondDueAt = longOrNull("secondDueAt"),
        secondConfirmedAt = longOrNull("secondConfirmedAt"), completedAt = longOrNull("completedAt"),
        lastAlertAt = longOrNull("lastAlertAt"), nextAlertAt = longOrNull("nextAlertAt"),
        alertCount = long("alertCount").toInt(), alertAnchorAt = long("alertAnchorAt"),
        snoozeCount = long("snoozeCount").toInt(), notYetCount = long("notYetCount").toInt(),
        confirmCount = long("confirmCount").toInt(), expiresAt = long("expiresAt"),
        endedAt = longOrNull("endedAt"), endReason = this["endReason"] as String?,
    )
}
