package com.abhi.vozconversa

import androidx.test.core.app.ApplicationProvider
import com.abhi.vozconversa.core.ConversationEngine
import com.abhi.vozconversa.core.CorrectionDictionary
import com.abhi.vozconversa.core.Corrector
import com.abhi.vozconversa.core.DirectionDecider
import com.abhi.vozconversa.core.HistoryStore
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.LanguageGuesser
import com.abhi.vozconversa.core.ListenMode
import com.abhi.vozconversa.core.Mix
import com.abhi.vozconversa.core.RestartPolicy
import com.abhi.vozconversa.core.SentenceAuditor
import com.abhi.vozconversa.core.TStatus
import com.abhi.vozconversa.core.TextTranslator
import com.abhi.vozconversa.core.TranslationRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1,000 different sentences that mix Spanish and English in the same sentence, as people really talk.
 * Report: app/build/reports/mixed-1000/report.txt
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MixedSpeech1000Test {

    data class Row(val frame: Lang, val text: String)

    private val rows: List<Row> by lazy {
        javaClass.classLoader!!.getResourceAsStream("mixed_1000.tsv")!!.bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() }.map { it.split('\t').let { p -> Row(Lang.valueOf(p[0]), p[1]) } }
    }

    /** What version 1.6 did: majority vote of the clues. */
    private fun oldDecision(text: String): Lang {
        val guess = LanguageGuesser.guess(text)
        return guess ?: Lang.ES
    }

    /** Records every call; answers with a tag showing which way it translated. */
    private class Recorder : TextTranslator {
        val calls = mutableListOf<Pair<String, Lang>>()
        override suspend fun translate(text: String, source: Lang): String {
            calls += text to source
            return "[${source.short}→${source.other.short}] $text"
        }
    }

    private class MemStore : HistoryStore {
        val rows = mutableListOf<TranslationRecord>()
        override fun save(record: TranslationRecord): Long { rows += record.copy(id = rows.size + 1L); return rows.size.toLong() }
        override fun update(record: TranslationRecord) { rows[(record.id - 1).toInt()] = record }
    }

    @Test fun thousandMixedSentences() = runTest {
        assertEquals(1000, rows.size)
        assertEquals("a sentence is repeated", 1000, rows.map { it.text.lowercase() }.toSet().size)
        val auditor = SentenceAuditor()
        val fails = LinkedHashMap<String, MutableList<String>>()
        val passed = LinkedHashMap<String, Int>()
        fun check(name: String, ok: Boolean, detail: () -> String) {
            if (ok) passed[name] = (passed[name] ?: 0) + 1 else fails.getOrPut(name) { mutableListOf() } += detail()
        }
        var oldWrong = 0
        var oldFlagged = 0

        for (r in rows) {
            val mix = LanguageGuesser.mix(r.text)
            check("0 seen as mixed", mix == Mix.MIXED) { "'${r.text}' -> $mix ${LanguageGuesser.score(r.text)}" }

            // 1. Recorded in full: the auditor keeps every word.
            val a = auditor.audit(r.text, DirectionDecider.sourceOf(r.text, ListenMode.MIXED_ES, null, Lang.ES))
            check("1 recorded in full", a.text == r.text) { "'${r.text}' -> '${a.text}'" }

            // 2 + 3. Translated into English in Group and Auto mode (whatever the recognizer reported).
            for (detected in listOf(null, Lang.ES, Lang.EN)) {
                val g = DirectionDecider.sourceOf(r.text, ListenMode.MIXED_ES, detected, Lang.ES)
                check("2 group mode → English", g == Lang.ES) { "'${r.text}' (recognizer said $detected) -> source $g" }
                val au = DirectionDecider.sourceOf(r.text, ListenMode.AUTO, detected, Lang.EN)
                check("3 auto mode → English", au == Lang.ES) { "'${r.text}' (recognizer said $detected) -> source $au" }
            }
            if (oldDecision(r.text) == Lang.EN) oldWrong++

            // 4. No confusing "maybe misheard" warnings on real mixed speech.
            check("4 no false warnings", a.issues.isEmpty()) { "'${r.text}' flagged ${a.flaggedWords}" }
            val oldAuditorWouldFlag = SentenceAuditor.englishWords.any { w -> r.text.lowercase().split(Regex("[^\\p{L}']+")).contains(w) }
            if (oldAuditorWouldFlag) oldFlagged++
        }

        // 5. Group mode pipeline: every sentence saved at once, translated ES→EN in the background, none skipped.
        val store = MemStore()
        val rec = Recorder()
        val engine = ConversationEngine(this, rec, store, 1, listener = object : ConversationEngine.Listener {
            override fun onTranslated(record: TranslationRecord) {}
        }, auditor = auditor)
        rows.forEach { engine.onUtterance(it.text, ListenMode.MIXED_ES, null, Lang.ES) }
        advanceUntilIdle()
        val byText = store.rows.associateBy { it.original }
        for (r in rows) {
            val s = byText[r.text]
            check("5 group flow: saved + translated → English", s != null && s.status == TStatus.DONE && s.translated == "[ES→EN] ${r.text}") {
                "'${r.text}' -> ${s?.status} '${s?.translated}'"
            }
        }
        check("5 group flow: saved + translated → English", store.rows.size == 1000) { "saved ${store.rows.size}" }

        // 6. Manual: → English and → Español on any sentence, either way, any time.
        val manualStore = MemStore()
        val manual = Recorder()
        val corrector = Corrector(manual, manualStore, CorrectionDictionary())
        for (r in rows) {
            val id = manualStore.save(TranslationRecord(0, 0, 1, Lang.EN, r.text, "", status = TStatus.ON_REQUEST))
            val saved = manualStore.rows[(id - 1).toInt()]
            val en = corrector.translateTo(saved, Lang.EN)
            val es = corrector.translateTo(en, Lang.ES)
            check("6 manual → English / → Español", en.translated == "[ES→EN] ${r.text}" && en.target == Lang.EN &&
                es.translated == "[EN→ES] ${r.text}" && es.target == Lang.ES && manualStore.rows[(id - 1).toInt()] == es) {
                "'${r.text}' -> EN '${en.translated}' / ES '${es.translated}'"
            }
        }

        // 7. Auto-translate OFF: everything recorded in full, nothing translated until asked.
        val offStore = MemStore()
        val offTranslator = Recorder()
        val off = ConversationEngine(this, offTranslator, offStore, 2, listener = object : ConversationEngine.Listener {
            override fun onTranslated(record: TranslationRecord) {}
        }, auditor = auditor, autoTranslate = { false })
        rows.forEach { off.onUtterance(it.text, ListenMode.MIXED_ES, null, Lang.ES) }
        advanceUntilIdle()
        for ((i, r) in rows.withIndex()) {
            val s = offStore.rows.getOrNull(i)
            check("7 auto off: recorded, waits for a tap", s != null && s.original == r.text && s.status == TStatus.ON_REQUEST) {
                "'${r.text}' -> ${s?.status} '${s?.original}'"
            }
        }
        check("7 auto off: recorded, waits for a tap", offTranslator.calls.isEmpty()) { "translator was called ${offTranslator.calls.size} times" }

        val report = buildString {
            appendLine("ABHISHEK – 1,000 MIXED SPANISH/ENGLISH SENTENCES")
            appendLine("Sentences: 1000 different (${rows.count { it.frame == Lang.ES }} Spanish with English words, " +
                "${rows.count { it.frame == Lang.EN }} English with Spanish words)")
            appendLine()
            appendLine("Before (version 1.6): ${oldWrong} of 1000 were treated as English and NOT translated in Group mode;")
            appendLine("                      ${oldFlagged} of 1000 got orange 'maybe misheard' warnings.")
            appendLine()
            val names = listOf("0 seen as mixed", "1 recorded in full", "2 group mode → English", "3 auto mode → English",
                "4 no false warnings", "5 group flow: saved + translated → English", "6 manual → English / → Español",
                "7 auto off: recorded, waits for a tap")
            for (n in names) appendLine(String.format("%-45s %5d passed, %d failed", n, passed[n] ?: 0, fails[n]?.size ?: 0))
            if (fails.isNotEmpty()) {
                appendLine(); appendLine("FAILURES")
                fails.forEach { (n, l) -> appendLine("-- $n: ${l.size}"); l.take(60).forEach { appendLine("   $it") } }
            }
        }
        File("build/reports/mixed-1000").apply { mkdirs() }.resolve("report.txt").writeText(report)
        println(report)
        assertTrue(report, fails.isEmpty())
    }

    @Test fun mixedSpeechNeverStallsListening() {
        val p = RestartPolicy()
        // Mixed speech can make Google's recognizer report a language error again and again.
        repeat(50) { assertEquals(150L, p.delayAfterError(RestartPolicy.ERROR_LANGUAGE_NOT_SUPPORTED)) }
        repeat(50) { assertEquals(150L, p.delayAfterError(RestartPolicy.ERROR_LANGUAGE_UNAVAILABLE)) }
        // A busy recognizer in a busy room: never more than 2 seconds.
        repeat(100) { assertTrue(p.delayAfterError(RestartPolicy.ERROR_RECOGNIZER_BUSY) <= 2_000) }
        repeat(100) { assertTrue(p.delayAfterError(RestartPolicy.ERROR_CLIENT) <= 2_000) }
    }

    @Test fun manualTranslationWorksOnARealDatabase() = runTest {
        val db = HistoryDb(ApplicationProvider.getApplicationContext(), null)
        val rec = Recorder()
        val engine = ConversationEngine(this, rec, db, 1, listener = object : ConversationEngine.Listener {
            override fun onTranslated(record: TranslationRecord) {}
        }, autoTranslate = { false })
        engine.onUtterance("Pásame the wrench, por favor", ListenMode.MIXED_ES, null, Lang.ES)
        advanceUntilIdle()
        val saved = db.all().single()
        assertEquals(TStatus.ON_REQUEST, saved.status)
        assertTrue(db.pending().isEmpty())                       // not in the automatic queue
        val corrector = Corrector(rec, db, CorrectionDictionary())
        val en = corrector.translateTo(saved, Lang.EN)
        assertEquals("[ES→EN] Pásame the wrench, por favor", db.byId(saved.id)!!.translated)
        corrector.translateTo(en, Lang.ES)
        assertEquals("[EN→ES] Pásame the wrench, por favor", db.byId(saved.id)!!.translated)
        assertEquals(TStatus.DONE, db.byId(saved.id)!!.status)
        db.close()
    }
}
