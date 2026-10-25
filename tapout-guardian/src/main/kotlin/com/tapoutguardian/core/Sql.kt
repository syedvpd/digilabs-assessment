package com.tapoutguardian.core

typealias Row = Map<String, Any?>

/**
 * Minimal synchronous SQL surface. The Android build wraps SQLiteDatabase, the unit tests wrap a real
 * SQLite engine through JDBC, so the DDL, migrations and queries below are exercised for real.
 */
interface SqlDb {
    fun execute(sql: String, vararg args: Any?)
    fun query(sql: String, vararg args: Any?): List<Row>
    /** Runs [block] atomically. Nested calls join the outer transaction. An exception rolls everything back. */
    fun <T> transaction(block: () -> T): T
    var userVersion: Int
}

object Schema {
    const val VERSION = 2

    private val WORKFLOW_V2 = """
        CREATE TABLE workflow (
            dateKey TEXT NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL,
            rev INTEGER NOT NULL DEFAULT 0, stateVersion INTEGER NOT NULL DEFAULT 1,
            zoneId TEXT NOT NULL, scheduledAt INTEGER NOT NULL, startedAt INTEGER NOT NULL,
            firstConfirmedAt INTEGER, confirmElapsed INTEGER, confirmBootId INTEGER,
            secondDelayMs INTEGER NOT NULL DEFAULT 300000,
            secondDeadlineAt INTEGER, secondDueAt INTEGER, secondConfirmedAt INTEGER, completedAt INTEGER,
            lastAlertAt INTEGER, nextAlertAt INTEGER, alertCount INTEGER NOT NULL DEFAULT 0,
            alertAnchorAt INTEGER NOT NULL DEFAULT 0,
            snoozeCount INTEGER NOT NULL DEFAULT 0, notYetCount INTEGER NOT NULL DEFAULT 0,
            confirmCount INTEGER NOT NULL DEFAULT 0,
            expiresAt INTEGER NOT NULL, endedAt INTEGER, endReason TEXT,
            PRIMARY KEY (dateKey, kind)
        )""".trimIndent()

    private val HISTORY = """
        CREATE TABLE history (
            id INTEGER PRIMARY KEY AUTOINCREMENT, dateKey TEXT NOT NULL, kind TEXT NOT NULL,
            at INTEGER NOT NULL, type TEXT NOT NULL, detail TEXT NOT NULL DEFAULT ''
        )""".trimIndent()

    private val SETTINGS_V2 = """
        CREATE TABLE settings (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            morningEnabled INTEGER NOT NULL DEFAULT 1, morningMinute INTEGER NOT NULL DEFAULT 540,
            eveningEnabled INTEGER NOT NULL DEFAULT 1, eveningMinute INTEGER NOT NULL DEFAULT 1080,
            secondDelayMin INTEGER NOT NULL DEFAULT 5, initialIntervalMin INTEGER NOT NULL DEFAULT 2,
            initialPhaseMin INTEGER NOT NULL DEFAULT 20, laterIntervalMin INTEGER NOT NULL DEFAULT 5,
            soundEnabled INTEGER NOT NULL DEFAULT 1, vibrateEnabled INTEGER NOT NULL DEFAULT 1,
            attendancePackage TEXT NOT NULL DEFAULT '', armedAt INTEGER NOT NULL DEFAULT 0,
            snoozeMin INTEGER NOT NULL DEFAULT 5, maxSnoozes INTEGER NOT NULL DEFAULT 3,
            kioskOptIn INTEGER NOT NULL DEFAULT 0, testDelaySec INTEGER NOT NULL DEFAULT 60
        )""".trimIndent()

    fun create(db: SqlDb) = db.transaction {
        db.execute(WORKFLOW_V2)
        db.execute(HISTORY)
        db.execute("CREATE INDEX history_at ON history(at)")
        db.execute(SETTINGS_V2)
        db.userVersion = VERSION
    }

    /** Brings any older database up to [VERSION] inside one transaction (all-or-nothing). */
    fun migrate(db: SqlDb, from: Int) = db.transaction {
        if (from < 2) {
            // v1 -> v2: snooze/not-yet counters, per-workflow delay, escalation anchor, kiosk opt-in.
            db.execute("ALTER TABLE workflow ADD COLUMN stateVersion INTEGER NOT NULL DEFAULT 1")
            db.execute("ALTER TABLE workflow ADD COLUMN secondDelayMs INTEGER NOT NULL DEFAULT 300000")
            db.execute("ALTER TABLE workflow ADD COLUMN secondDueAt INTEGER")
            db.execute("ALTER TABLE workflow ADD COLUMN alertAnchorAt INTEGER NOT NULL DEFAULT 0")
            db.execute("ALTER TABLE workflow ADD COLUMN snoozeCount INTEGER NOT NULL DEFAULT 0")
            db.execute("ALTER TABLE workflow ADD COLUMN notYetCount INTEGER NOT NULL DEFAULT 0")
            db.execute("ALTER TABLE workflow ADD COLUMN confirmCount INTEGER NOT NULL DEFAULT 0")
            db.execute("ALTER TABLE workflow ADD COLUMN endReason TEXT")
            db.execute("UPDATE workflow SET alertAnchorAt = scheduledAt")
            db.execute("UPDATE workflow SET confirmCount = 1 WHERE firstConfirmedAt IS NOT NULL")
            db.execute("ALTER TABLE settings ADD COLUMN snoozeMin INTEGER NOT NULL DEFAULT 5")
            db.execute("ALTER TABLE settings ADD COLUMN maxSnoozes INTEGER NOT NULL DEFAULT 3")
            db.execute("ALTER TABLE settings ADD COLUMN kioskOptIn INTEGER NOT NULL DEFAULT 0")
            db.execute("ALTER TABLE settings ADD COLUMN testDelaySec INTEGER NOT NULL DEFAULT 60")
        }
        db.userVersion = VERSION
    }

    fun open(db: SqlDb) {
        val v = db.userVersion
        when {
            v == 0 -> create(db)
            v < VERSION -> migrate(db, v)
            v > VERSION -> error("Database version $v is newer than this app supports ($VERSION)")
        }
    }
}
