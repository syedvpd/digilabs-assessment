package com.tapoutguardian.android

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.tapoutguardian.core.Row
import com.tapoutguardian.core.Schema
import com.tapoutguardian.core.SqlDb

/** [SqlDb] over the framework SQLite. Schema creation / migration runs through the JVM-tested [Schema.open]. */
class AndroidSqlDb private constructor(private val db: SQLiteDatabase) : SqlDb {
    override fun execute(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, arrayOf(*args))
    }

    override fun query(sql: String, vararg args: Any?): List<Row> {
        val cursor = db.rawQuery(sql, args.map { it?.toString() }.toTypedArray())
        cursor.use { c ->
            val out = ArrayList<Row>()
            while (c.moveToNext()) {
                val row = HashMap<String, Any?>()
                for (i in 0 until c.columnCount) row[c.getColumnName(i)] = c.value(i)
                out += row
            }
            return out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    override var userVersion: Int
        get() = (query("PRAGMA user_version").firstOrNull()?.values?.firstOrNull() as Number?)?.toInt() ?: 0
        set(v) = db.execSQL("PRAGMA user_version = $v")

    private fun Cursor.value(i: Int): Any? = when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> getBlob(i)
        else -> getString(i)
    }

    companion object {
        fun open(context: Context): AndroidSqlDb {
            val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("tapout.db").also { it.parentFile?.mkdirs() }, null)
            raw.enableWriteAheadLogging()
            return AndroidSqlDb(raw).also { Schema.open(it) }
        }
    }
}
