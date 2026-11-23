package com.abhi.vozconversa

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.abhi.vozconversa.core.Correction
import com.abhi.vozconversa.core.CorrectionDictionary
import com.abhi.vozconversa.core.CorrectionStore
import com.abhi.vozconversa.core.HistoryStore
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.TStatus
import com.abhi.vozconversa.core.TranslationRecord

/** Saved translations. Text only (no audio), so it stays small: about 200 bytes per sentence. */
class HistoryDb(context: Context, name: String? = "history.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 3), HistoryStore, CorrectionStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE translations (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "session INTEGER NOT NULL, src TEXT NOT NULL, original TEXT NOT NULL, translated TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_ts ON translations(ts)")
        upgradeTo2(db)
        upgradeTo3(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) upgradeTo2(db)
        if (oldVersion < 3) upgradeTo3(db)
    }

    /** Version 3 (app 1.6): translation status, so sentences are saved first and translated in the background. */
    private fun upgradeTo3(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE translations ADD COLUMN status TEXT NOT NULL DEFAULT 'DONE'")
        db.execSQL("UPDATE translations SET status = 'FAILED' WHERE translated = ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_status ON translations(status)")
    }

    override fun pending(): List<TranslationRecord> =
        read("status IN ('PENDING', 'FAILED')", null, "ts ASC, id ASC", null)

    fun waitingCount(): Long = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM translations WHERE status IN ('PENDING', 'FAILED')", null
    ).use { it.moveToFirst(); it.getLong(0) }

    /** Version 2 (app 1.3): recognizer alternatives per sentence, and learned word fixes. Old rows are kept. */
    private fun upgradeTo2(db: SQLiteDatabase) {
        val hasAlts = db.rawQuery("PRAGMA table_info(translations)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(c.getColumnIndexOrThrow("name")) == "alts") found = true
            found
        }
        if (!hasAlts) db.execSQL("ALTER TABLE translations ADD COLUMN alts TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS corrections (lang TEXT NOT NULL, wrong TEXT NOT NULL, " +
                "right_text TEXT NOT NULL, PRIMARY KEY (lang, wrong))"
        )
    }

    @Synchronized
    override fun update(record: TranslationRecord) {
        val v = ContentValues().apply {
            put("original", record.original)
            put("translated", record.translated)
            put("src", record.source.name)
            put("alts", record.alternatives.joinToString("\n"))
            put("status", record.status.name)
        }
        writableDatabase.update("translations", v, "id = ?", arrayOf(record.id.toString()))
    }

    fun byId(id: Long): TranslationRecord? = read("id = ?", arrayOf(id.toString()), "id", "1").firstOrNull()

    override fun loadCorrections(): List<Correction> =
        readableDatabase.query("corrections", null, null, null, null, null, "rowid").use { c ->
            val out = ArrayList<Correction>()
            while (c.moveToNext()) {
                val lang = runCatching { Lang.valueOf(c.getString(0)) }.getOrNull() ?: continue
                out += Correction(lang, c.getString(1), c.getString(2))
            }
            out
        }

    override fun saveCorrection(c: Correction) {
        writableDatabase.insertWithOnConflict("corrections", null, ContentValues().apply {
            put("lang", c.lang.name); put("wrong", c.wrong); put("right_text", c.right)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun deleteCorrection(c: Correction) {
        writableDatabase.delete("corrections", "lang = ? AND wrong = ?", arrayOf(c.lang.name, c.wrong))
    }

    @Synchronized
    override fun save(record: TranslationRecord): Long {
        val v = ContentValues().apply {
            put("ts", record.timeMillis)
            put("session", record.sessionId)
            put("src", record.source.name)
            put("original", record.original)
            put("translated", record.translated)
            put("alts", record.alternatives.joinToString("\n"))
            put("status", record.status.name)
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
                    alternatives = c.getString(c.getColumnIndexOrThrow("alts")).split('\n').filter { it.isNotBlank() },
                    status = runCatching { TStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status"))) }.getOrDefault(TStatus.DONE),
                )
            }
            out
        }

    companion object {
        @Volatile private var instance: HistoryDb? = null
        fun get(context: Context): HistoryDb =
            instance ?: synchronized(this) { instance ?: HistoryDb(context).also { instance = it } }

        /** Tests only: forget the shared database so each test starts empty. */
        internal fun resetForTests() = synchronized(this) {
            instance?.close(); instance = null
            Corrections.resetForTests()
        }
    }
}

/** The one word-fix dictionary for the whole app (service and screens share it). */
object Corrections {
    @Volatile private var dict: CorrectionDictionary? = null
    fun dictionary(context: Context): CorrectionDictionary =
        dict ?: synchronized(this) { dict ?: CorrectionDictionary(HistoryDb.get(context)).also { dict = it } }

    internal fun resetForTests() { dict = null }
}
