package com.abhi.vozconversa

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.abhi.vozconversa.core.HistoryStore
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.TranslationRecord

/** Saved translations. Text only (no audio), so it stays small: about 200 bytes per sentence. */
class HistoryDb(context: Context, name: String? = "history.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1), HistoryStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE translations (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "session INTEGER NOT NULL, src TEXT NOT NULL, original TEXT NOT NULL, translated TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_ts ON translations(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    override fun save(record: TranslationRecord): Long {
        val v = ContentValues().apply {
            put("ts", record.timeMillis)
            put("session", record.sessionId)
            put("src", record.source.name)
            put("original", record.original)
            put("translated", record.translated)
        }
        return writableDatabase.insertOrThrow("translations", null, v)
    }

    /** Newest first. [query] filters on original or translated text. */
    fun recent(limit: Int = 200, query: String? = null): List<TranslationRecord> {
        val q = query?.trim().orEmpty()
        val (where, args) = if (q.isEmpty()) null to null
        else "original LIKE ? OR translated LIKE ?" to arrayOf("%$q%", "%$q%")
        return read(where, args, "ts DESC, id DESC", limit.toString())
    }

    fun all(): List<TranslationRecord> = read(null, null, "ts ASC, id ASC", null)

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM translations", null).use {
        it.moveToFirst(); it.getLong(0)
    }

    fun deleteOlderThan(cutoffMillis: Long): Int =
        writableDatabase.delete("translations", "ts < ?", arrayOf(cutoffMillis.toString()))

    fun delete(id: Long): Int = writableDatabase.delete("translations", "id = ?", arrayOf(id.toString()))

    fun deleteAll() {
        writableDatabase.delete("translations", null, null)
        writableDatabase.execSQL("VACUUM")
    }

    /** Bytes used on the phone by the history database. */
    fun sizeBytes(context: Context): Long {
        val f = context.getDatabasePath(databaseName ?: return 0)
        return listOf(f, java.io.File(f.path + "-journal"), java.io.File(f.path + "-wal"))
            .filter { it.exists() }.sumOf { it.length() }
    }

    private fun read(where: String?, args: Array<String>?, order: String, limit: String?): List<TranslationRecord> =
        readableDatabase.query("translations", null, where, args, null, null, order, limit).use { c ->
            val out = ArrayList<TranslationRecord>(c.count)
            while (c.moveToNext()) {
                out += TranslationRecord(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    timeMillis = c.getLong(c.getColumnIndexOrThrow("ts")),
                    sessionId = c.getLong(c.getColumnIndexOrThrow("session")),
                    source = runCatching { Lang.valueOf(c.getString(c.getColumnIndexOrThrow("src"))) }.getOrDefault(Lang.ES),
                    original = c.getString(c.getColumnIndexOrThrow("original")),
                    translated = c.getString(c.getColumnIndexOrThrow("translated")),
                )
            }
            out
        }

    companion object {
        @Volatile private var instance: HistoryDb? = null
        fun get(context: Context): HistoryDb =
            instance ?: synchronized(this) { instance ?: HistoryDb(context).also { instance = it } }
    }
}
