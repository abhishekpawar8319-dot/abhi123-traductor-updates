package com.abhi.vozconversa

import com.abhi.vozconversa.core.ConversationEngine
import com.abhi.vozconversa.core.CorrectionDictionary
import com.abhi.vozconversa.core.DirectionDecider
import com.abhi.vozconversa.core.HistoryStore
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.LanguageGuesser
import com.abhi.vozconversa.core.ListenMode
import com.abhi.vozconversa.core.OfflineController
import com.abhi.vozconversa.core.SentenceAuditor
import com.abhi.vozconversa.core.SentenceWords
import com.abhi.vozconversa.core.TextTranslator
import com.abhi.vozconversa.core.TranscriptExporter
import com.abhi.vozconversa.core.TranslationRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The offline 1,000-sentence check. Every sentence (500 Spanish, 500 English, 50 kinds, none repeated) goes
 * through five checks the way the app works with no internet. A report is written to
 * app/build/reports/offline-1000/report.txt. Run: ./run-offline-1000.sh
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Offline1000Test {

    data class Row(val lang: Lang, val kind: String, val text: String)

    private val rows: List<Row> by lazy {
        javaClass.classLoader!!.getResourceAsStream("offline_1000.tsv")!!.bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() }
            .map { it.split('\t').let { p -> Row(Lang.valueOf(p[0]), p[1], p[2]) } }
    }

    private class Result {
        val failures = LinkedHashMap<String, MutableList<String>>()   // check -> problems
        val passed = LinkedHashMap<String, Int>()
        val byKind = LinkedHashMap<String, IntArray>()                // kind -> [checks run, checks failed]
        fun record(check: String, kind: String, ok: Boolean, detail: () -> String) {
            val k = byKind.getOrPut(kind) { IntArray(2) }
            k[0]++
            if (ok) passed[check] = (passed[check] ?: 0) + 1
            else { k[1]++; failures.getOrPut(check) { mutableListOf() } += detail() }
        }
    }

    /** Builds one recognizer-style mistake for [r]; returns (heard text, expected text, expected flags, taught fix). */
    private fun injectMistake(i: Int, r: Row): Quad {
        val words = r.text.split(" ")
        val core = { w: String -> SentenceWords.cores(w).firstOrNull()?.lowercase() ?: "" }
        val plainOf = SentenceAuditor.accentFixes.entries.associate { (plain, accented) -> accented to plain }
        val accentAt = if (r.lang == Lang.ES) words.indexOfFirst { core(it) in plainOf && it == it.trim(',', '?', '¿') } else -1
        val doubleAt = words.indexOfFirst { core(it) in SentenceAuditor.removableDoubles && it.lowercase() == core(it) }
        val longAt = words.indexOfFirst { w -> core(w).length >= 6 && core(w).all { it.isLetter() } && w == w.trim(',', '?', '¿') }
        val choice = listOf("accent", "double", "foreign", "taught")[i % 4]
        return when {
            choice == "accent" && accentAt >= 0 -> {
                val w = words[accentAt]
                val plain = plainOf[core(w)]!!.let { if (w[0].isUpperCase()) SentenceWords.capitalize(it) else it }
                Quad("missing accent", words.toMutableList().also { it[accentAt] = plain }.joinToString(" "), r.text, emptyList(), null)
            }
            (choice == "double" || choice == "accent") && doubleAt >= 0 ->
                Quad("word said twice", words.toMutableList().also { it.add(doubleAt, words[doubleAt]) }.joinToString(" "), r.text, emptyList(), null)
            choice == "taught" && longAt >= 0 -> {
                val w = words[longAt]
                val wrong = w.dropLast(1) + w.last() + w.last()          // e.g. "escalera" heard as "escaleraa"
                Quad("taught fix", words.toMutableList().also { it[longAt] = wrong }.joinToString(" "), r.text, emptyList(), wrong to w)
            }
            else -> {
                val foreign = if (r.lang == Lang.ES) "the" else "los"
                val at = minOf(1, words.size)
                val heard = words.toMutableList().also { it.add(at, foreign) }.joinToString(" ")
                Quad("other-language word", heard, heard, listOf(foreign), null)
            }
        }
    }

    data class Quad(val type: String, val heard: String, val expected: String, val flags: List<String>, val taught: Pair<String, String>?)

    @Test fun offlineThousandSentences() = runTest {
        // ---- the list itself
        assertEquals(1000, rows.size)
        assertEquals("a sentence is repeated", 1000, rows.map { it.text.lowercase() }.toSet().size)
        assertEquals(500, rows.count { it.lang == Lang.ES })

        val res = Result()
        val auditor = SentenceAuditor()
        val noClue = mutableListOf<String>()
        val mistakeTypes = LinkedHashMap<String, Int>()

        for ((i, r) in rows.withIndex()) {
            // 1. Offline, the recognizer heard it in its own language: direction must be right.
            val d1 = DirectionDecider.sourceOf(r.text, ListenMode.AUTO, null, r.lang)
            res.record("1 direction (offline)", r.kind, d1 == r.lang) { "${r.lang} '${r.text}' -> $d1" }

            // 2. Worst case: recognizer was set for the other language. The words alone must decide.
            val d2 = DirectionDecider.sourceOf(r.text, ListenMode.AUTO, null, r.lang.other)
            if (LanguageGuesser.guess(r.text) == null) noClue += "${r.lang} '${r.text}'"
            res.record("2 direction (words only)", r.kind, d2 == r.lang) {
                val (es, en) = LanguageGuesser.score(r.text); "${r.lang} '${r.text}' -> $d2 (es=$es en=$en)"
            }

            // 3. A correct sentence must come through the auditor untouched.
            val a = auditor.audit(r.text, r.lang)
            res.record("3 no false alarm", r.kind, a.text == r.text && a.issues.isEmpty()) {
                "${r.lang} '${r.text}' -> '${a.text}' flagged ${a.flaggedWords}"
            }

            // 4. One recognizer mistake must be fixed, or pointed out exactly.
            val m = injectMistake(i, r)
            mistakeTypes[m.type] = (mistakeTypes[m.type] ?: 0) + 1
            val dict = CorrectionDictionary()
            m.taught?.let { (w, right) -> dict.learn(r.lang, SentenceWords.cores(w).first(), SentenceWords.cores(right).first()) }
            val got = SentenceAuditor(dict).audit(m.heard, r.lang)
            res.record("4 mistake caught", r.kind, got.text == m.expected && got.flaggedWords == m.flags) {
                "${r.lang} [${m.type}] '${m.heard}' -> '${got.text}' ${got.flaggedWords} (expected '${m.expected}' ${m.flags})"
            }
        }

        // 5. The whole conversation offline: offline set-up, auditor on, every sentence translated the right way and saved.
        val ctl = OfflineController(34) { 0L }
        ctl.networkChanged(false)
        val store = object : HistoryStore {
            val saved = mutableListOf<TranslationRecord>()
            override fun save(record: TranslationRecord): Long { saved += record; return saved.size.toLong() }
            override fun update(record: TranslationRecord) { saved[(record.id - 1).toInt()] = record }
        }
        val asked = mutableListOf<Pair<String, Lang>>()
        val translator = object : TextTranslator {
            override suspend fun translate(text: String, source: Lang): String { asked += text to source; return "[${source.other.short}] $text" }
        }
        val engine = ConversationEngine(this, translator, store, 1, clock = { testScheduler.currentTime },
            listener = object : ConversationEngine.Listener { override fun onTranslated(record: TranslationRecord) {} },
            auditor = SentenceAuditor())
        for (r in rows) {
            val setup = ctl.setup(r.lang)
            check(setup.preferOffline && !setup.multiLanguageExtras)
            engine.onUtterance(r.text, ListenMode.AUTO, null, r.lang)
            runCurrent(); advanceTimeBy(4_000); runCurrent()
        }
        advanceTimeBy(10_000); runCurrent()
        val byText = store.saved.associateBy { it.original }
        for (r in rows) {
            val s = byText[r.text]
            res.record("5 offline flow saved", r.kind, s != null && s.source == r.lang && s.translated == "[${r.lang.other.short}] ${r.text}") {
                "${r.lang} '${r.text}' -> ${s?.let { "${it.source} '${it.original}'" } ?: "NOT SAVED"}"
            }
        }
        val inOrder = store.saved.map { it.original } == rows.map { it.text }
        val export = TranscriptExporter.toText(store.saved)
        val exported = rows.all { export.contains(it.text) }

        // ---- report
        val report = buildString {
            appendLine("ABHISHEK – OFFLINE 1,000-SENTENCE CHECK")
            appendLine("Sentences: ${rows.size} (Spanish ${rows.count { it.lang == Lang.ES }}, English ${rows.count { it.lang == Lang.EN }}), " +
                "kinds: ${rows.map { it.lang to it.kind }.toSet().size}, repeated: 0")
            appendLine("Mistakes injected in check 4: $mistakeTypes")
            appendLine()
            val checks = listOf("1 direction (offline)", "2 direction (words only)", "3 no false alarm", "4 mistake caught", "5 offline flow saved")
            for (c in checks) appendLine(String.format("%-26s %4d / 1000 passed", c, res.passed[c] ?: 0))
            appendLine(String.format("%-26s %s", "all saved in order", if (inOrder) "yes" else "NO"))
            appendLine(String.format("%-26s %s", "all in exported file", if (exported) "yes" else "NO"))
            appendLine()
            appendLine("Sentences with no Spanish/English clue in the words (use the recognizer's language): ${noClue.size}")
            noClue.forEach { appendLine("   $it") }
            appendLine()
            appendLine("By kind of sentence (checks run / failed):")
            res.byKind.forEach { (k, v) -> appendLine(String.format("   %-22s %4d / %d", k, v[0], v[1])) }
            if (res.failures.isNotEmpty()) {
                appendLine(); appendLine("FAILURES")
                res.failures.forEach { (c, list) -> appendLine("-- $c: ${list.size}"); list.forEach { appendLine("   $it") } }
            }
        }
        val dir = File("build/reports/offline-1000").apply { mkdirs() }
        File(dir, "report.txt").writeText(report)
        println(report)

        val total = res.failures.values.sumOf { it.size }
        assertTrue("all saved in order", inOrder)
        assertTrue("all exported", exported)
        assertEquals("failures (see build/reports/offline-1000/report.txt):\n$report", 0, total)
    }

    /**
     * Fair check: sentences the word lists were NOT tuned on (the earlier conversation and auditor test sets).
     * Offline, with the recognizer set for the wrong language, the words alone must decide the direction.
     */
    @Test fun heldOutSentencesWordsOnly() {
        fun load(n: String) = javaClass.classLoader!!.getResourceAsStream(n)!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val heldOut = SentenceData.spanish.map { it.said to Lang.ES } + SentenceData.english.map { it.said to Lang.EN } +
            load("round2_spanish.txt").map { it to Lang.ES } + load("round2_english.txt").map { it to Lang.EN } +
            AuditCases.all.filter { it.group.contains("correct") || it.group.contains("accent") || it.group.contains("question") }
                .map { it.heard to it.lang }
        val misses = heldOut.filter { (t, l) -> DirectionDecider.sourceOf(t, ListenMode.AUTO, null, l.other) != l }
        val report = "Held-out direction (words only): ${heldOut.size - misses.size} / ${heldOut.size}\n" +
            misses.joinToString("\n") { (t, l) -> val (es, en) = LanguageGuesser.score(t); "   $l '$t' (es=$es en=$en)" }
        File("build/reports/offline-1000").apply { mkdirs() }.resolve("held-out.txt").writeText(report)
        println(report)
        assertTrue(report, misses.isEmpty())
    }
}
