package com.abhi.vozconversa

import androidx.test.core.app.ApplicationProvider
import com.abhi.vozconversa.core.CaptureTracker
import com.abhi.vozconversa.core.ConversationEngine
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.ListenMode
import com.abhi.vozconversa.core.SentenceAuditor
import com.abhi.vozconversa.core.SentenceText
import com.abhi.vozconversa.core.TStatus
import com.abhi.vozconversa.core.TextTranslator
import com.abhi.vozconversa.core.TranscriptExporter
import com.abhi.vozconversa.core.TranslationRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/** Small checks of the new record-first pipeline. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordFirstTest {
    private fun db() = HistoryDb(ApplicationProvider.getApplicationContext(), null)

    @Test fun sentenceIsSavedBeforeItsTranslationIsReady() = runTest {
        val db = db()
        val slow = object : TextTranslator {
            override suspend fun translate(text: String, source: Lang): String { delay(5_000); return "EN: $text" }
        }
        val captured = mutableListOf<TranslationRecord>()
        val engine = ConversationEngine(this, slow, db, 1, clock = { testScheduler.currentTime },
            listener = object : ConversationEngine.Listener {
                override fun onCaptured(record: TranslationRecord) { captured += record }
                override fun onTranslated(record: TranslationRecord) {}
            })
        engine.onUtterance("Necesito la escalera grande", ListenMode.MIXED_ES, null, Lang.ES)
        assertEquals("on screen and saved at once", 1, captured.size)
        assertEquals(TStatus.PENDING, db.all().single().status)
        assertEquals(1, engine.backlog)
        advanceUntilIdle()
        assertEquals(TStatus.DONE, db.all().single().status)
        assertEquals("EN: Necesito la escalera grande", db.all().single().translated)
        assertEquals(0, engine.backlog)
        db.close()
    }

    @Test fun englishInGroupModeIsRecordedButNotTranslated() = runTest {
        val db = db()
        val calls = mutableListOf<String>()
        val t = object : TextTranslator { override suspend fun translate(text: String, source: Lang): String { calls += text; return "x" } }
        val engine = ConversationEngine(this, t, db, 1, listener = object : ConversationEngine.Listener {
            override fun onTranslated(record: TranslationRecord) {}
        })
        engine.onUtterance("The crane is ready to lift", ListenMode.MIXED_ES, null, Lang.ES)
        advanceUntilIdle()
        assertEquals(TStatus.NOT_NEEDED, db.all().single().status)
        assertTrue(calls.isEmpty())
        // In Auto mode the same sentence IS translated (to Spanish).
        engine.onUtterance("The truck is ready to leave", ListenMode.AUTO, null, Lang.ES)
        advanceUntilIdle()
        assertEquals(listOf("The truck is ready to leave"), calls)
        db.close()
    }

    @Test fun failedTranslationIsRetriedNotSkipped() = runTest {
        val db = db()
        var packReady = false
        val t = object : TextTranslator {
            override suspend fun translate(text: String, source: Lang): String =
                if (packReady) "EN: $text" else error("pack missing")
        }
        val engine = ConversationEngine(this, t, db, 1, listener = object : ConversationEngine.Listener {
            override fun onTranslated(record: TranslationRecord) {}
        })
        engine.onUtterance("El viento está muy fuerte", ListenMode.MIXED_ES, null, Lang.ES)
        advanceUntilIdle()
        assertEquals(TStatus.FAILED, db.all().single().status)
        packReady = true
        assertEquals(1, engine.retryFailed())
        advanceUntilIdle()
        assertEquals(TStatus.DONE, db.all().single().status)
        db.close()
    }

    @Test fun waitingSentencesSurviveAppRestart() = runTest {
        val db = db()
        val never = object : TextTranslator { override suspend fun translate(text: String, source: Lang): String { delay(Long.MAX_VALUE / 4); return "" } }
        val first = ConversationEngine(this, never, db, 1, listener = object : ConversationEngine.Listener {
            override fun onTranslated(record: TranslationRecord) {}
        })
        first.onUtterance("Mañana revisamos la torre", ListenMode.MIXED_ES, null, Lang.ES)
        first.onUtterance("Trae los guantes nuevos", ListenMode.MIXED_ES, null, Lang.ES)
        runCurrent()
        first.close() // app killed
        val ok = object : TextTranslator { override suspend fun translate(text: String, source: Lang) = "EN: $text" }
        val second = ConversationEngine(this, ok, db, 2, listener = object : ConversationEngine.Listener {
            override fun onTranslated(record: TranslationRecord) {}
        })
        assertEquals(2, second.resumePending())
        advanceUntilIdle()
        assertTrue(db.all().all { it.status == TStatus.DONE })
        db.close()
    }

    @Test fun twoSpeakersAreNotGluedTogetherButOneSpeakerIs() = runTest {
        val db = db()
        val t = object : TextTranslator { override suspend fun translate(text: String, source: Lang) = "x" }
        val engine = ConversationEngine(this, t, db, 1, clock = { testScheduler.currentTime },
            listener = object : ConversationEngine.Listener { override fun onTranslated(record: TranslationRecord) {} })
        // Person A stops on "the", person B answers in Spanish within a second: two sentences.
        engine.onUtterance("I need the", ListenMode.MIXED_ES, null, Lang.ES)
        advanceTimeBy(500)
        engine.onUtterance("Necesito los guantes ahora mismo", ListenMode.MIXED_ES, null, Lang.ES)
        // One person pausing before a single word: one sentence.
        engine.onUtterance("¿Dónde está el", ListenMode.MIXED_ES, null, Lang.ES)
        advanceTimeBy(500)
        engine.onUtterance("supervisor?", ListenMode.MIXED_ES, null, Lang.ES)
        advanceUntilIdle()
        assertEquals(listOf("I need the", "Necesito los guantes ahora mismo", "¿Dónde está el supervisor?"), db.all().map { it.original })
        assertEquals(listOf(TStatus.NOT_NEEDED, TStatus.DONE, TStatus.DONE), db.all().map { it.status })
        db.close()
    }

    @Test fun captureKeepsEveryWordEvenWhenASessionIsCut() {
        val got = mutableListOf<String>()
        var now = 0L
        val c = CaptureTracker({ now }) { t, _ -> got += t }
        c.onPartial("Necesito que traigas")
        c.onPartial("Necesito que traigas los pernos")
        c.onInterrupted()                                   // error mid-sentence: words kept
        c.onFinal(listOf("Necesito que traigas los pernos")) // recognizer repeats it: not saved twice
        c.onFinal(listOf("Sí"))
        c.onFinal(listOf("Sí"))                              // two people say "Sí": both kept
        c.onInterrupted()                                   // nothing heard: nothing added
        now += 10_000
        c.onPartial("Okay")
        c.onInterrupted()
        assertEquals(listOf("Necesito que traigas los pernos", "Sí", "Sí", "Okay"), got)
    }
}

/**
 * 10,000 different sentences in one long, fast group conversation, Spanish and English mixed, as the app
 * hears them in Group mode (record everything, translate Spanish to English in the background).
 * Report: app/build/reports/conversation-10000/report.txt
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Conversation10000Test {

    data class Line(val lang: Lang, val text: String)

    private val esExtras = listOf("", ", amigo", " ahora mismo", " otra vez", ", por favor", " también", ", jefe", " esta vez", " sin falta", " de nuevo")
    private val enExtras = listOf("", ", friend", " right now", " again", ", please", " too", ", boss", " this time", " for sure", " once more")

    /** 1,000 base sentences × 10 endings = 10,000 different sentences. */
    private fun sentences(): List<Line> {
        val base = javaClass.classLoader!!.getResourceAsStream("offline_1000.tsv")!!.bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() }.map { it.split('\t').let { p -> Line(Lang.valueOf(p[0]), p[2]) } }
        return (0 until 10).flatMap { v ->
            base.map { b ->
                val extra = (if (b.lang == Lang.ES) esExtras else enExtras)[v]
                val q = b.text.endsWith("?")
                val body = b.text.removeSuffix("?")
                Line(b.lang, body + extra + if (q) "?" else "")
            }
        }
    }

    @Test fun tenThousandSentenceGroupConversation() = runTest(timeout = kotlin.time.Duration.parse("20m")) {
        val rnd = Random(System.getenv("CONV_SEED")?.toIntOrNull() ?: 10_000)
        val all = sentences().shuffled(rnd)          // many speakers, languages mixed at random
        assertEquals(10_000, all.size)
        assertEquals("a sentence is repeated", 10_000, all.map { it.text.lowercase() }.toSet().size)

        val db = HistoryDb(ApplicationProvider.getApplicationContext(), null)
        val auditor = SentenceAuditor()
        val expected = all.map { auditor.audit(it.text, it.lang).text }

        // Translator: 150 ms per sentence, fails 3% of the time, and the language pack is "missing" for the
        // first 10 minutes (everything fails until then and must be retried later).
        val calls = HashMap<String, Int>()
        var englishCalls = 0
        val translator = object : TextTranslator {
            override suspend fun translate(text: String, source: Lang): String {
                if (source == Lang.EN) englishCalls++
                calls[text] = (calls[text] ?: 0) + 1
                delay(150)
                if (testScheduler.currentTime < 600_000) error("language pack not ready")
                if (rnd.nextInt(100) < 3) error("busy")
                return "EN: $text"
            }
        }

        val capturedAt = HashMap<String, Long>()
        var maxBacklog = 0
        val listener = object : ConversationEngine.Listener {
            override fun onCaptured(record: TranslationRecord) { capturedAt[record.original] = testScheduler.currentTime }
            override fun onTranslated(record: TranslationRecord) {}
        }
        fun newEngine(session: Long) = ConversationEngine(this, translator, db, session,
            clock = { testScheduler.currentTime }, listener = listener, auditor = auditor)
        var engine = newEngine(1)

        // How the recognizer delivers each sentence.
        val spokenAt = HashMap<String, Long>()
        var kinds = mutableMapOf<String, Int>()
        val debugKinds = mutableListOf<String>()
        val wallStart = System.nanoTime()
        var lastRetry = 0L
        for ((i, line) in all.withIndex()) {
            if (i == 7_000) {                       // the phone closes the app and it starts again
                engine.flush(); engine.close()
                engine = newEngine(2)
                engine.resumePending()
            }
            val tracker = CaptureTracker({ testScheduler.currentTime }) { t, alts ->
                engine.onUtterance(t, ListenMode.MIXED_ES, null, Lang.ES, alts)
            }
            val r = rnd.nextInt(100)
            val words = line.text.split(" ")
            val splitAt = (2 until words.size).firstOrNull { SentenceText.looksUnfinished(words.subList(0, it).joinToString(" ")) }
            val kind = when {
                r < 55 -> { tracker.onPartial(words.take(maxOf(1, words.size / 2)).joinToString(" ")); tracker.onFinal(listOf(line.text)); "continuous segment" }
                r < 75 -> { tracker.onFinal(listOf(line.text, line.text.lowercase())); "normal result" }
                r < 85 -> { tracker.onPartial(line.text); tracker.onInterrupted(); "session cut, words rescued" }
                r < 90 -> { tracker.onPartial(line.text); tracker.onInterrupted(); tracker.onFinal(listOf(line.text)); "rescued + repeated final" }
                r < 95 && splitAt != null -> {
                    tracker.onFinal(listOf(words.subList(0, splitAt).joinToString(" ")))
                    advanceTimeBy(500)                // short pause mid-sentence
                    // Second piece exactly as the recognizer gives it (names capitalized, other words not).
                    tracker.onFinal(listOf(words.subList(splitAt, words.size).joinToString(" ")))
                    "sentence split by a pause"
                }
                else -> { tracker.onFinal(listOf("mm")); tracker.onFinal(listOf(line.text)); "with noise before" }
            }
            kinds[kind] = (kinds[kind] ?: 0) + 1
            debugKinds += kind
            spokenAt[expected[i]] = testScheduler.currentTime
            maxBacklog = maxOf(maxBacklog, engine.backlog)
            // Fast conversation: 0–1.5 s between sentences; sentences 3000–3999 all at once (everyone talking).
            val gap = if (i in 3_000 until 4_000) 0L else rnd.nextLong(0, 1_500)
            runCurrent()
            advanceTimeBy(gap)
            // The service retries failed translations every minute.
            if (testScheduler.currentTime - lastRetry > 60_000) { lastRetry = testScheduler.currentTime; engine.retryFailed() }
        }
        engine.flush()
        val endOfTalk = testScheduler.currentTime
        // Let the background queue finish (retrying every minute as the service does).
        var rounds = 0
        while (db.waitingCount() > 0 && rounds++ < 500) { advanceTimeBy(60_000); runCurrent(); engine.retryFailed() }
        advanceUntilIdle()
        val drainSeconds = (testScheduler.currentTime - endOfTalk) / 1000
        val wallMs = (System.nanoTime() - wallStart) / 1_000_000

        // ---------------- checks: one per sentence
        val saved = db.all()
        val byText = saved.groupBy { it.original }
        val problems = mutableListOf<String>()
        var spanishDone = 0
        var englishKept = 0
        var maxCaptureDelay = 0L
        for ((i, line) in all.withIndex()) {
            val want = expected[i]
            val recs = byText[want]
            when {
                recs == null -> {
                    val near = saved.filter { it.original.contains(want.trim('¿', '?').take(18), ignoreCase = true) }.map { it.original }
                    problems += "MISSING: ${line.lang} '$want'" + if (near.isNotEmpty()) "  → saved as: $near" else ""
                }
                recs.size > 1 -> problems += "SAVED ${recs.size} TIMES: '$want'"
                recs[0].source != line.lang -> problems += "WRONG LANGUAGE ${recs[0].source}: '$want'"
                line.lang == Lang.ES && (recs[0].status != TStatus.DONE || recs[0].translated != "EN: $want") ->
                    problems += "NOT TRANSLATED (${recs[0].status}): '$want'"
                line.lang == Lang.EN && recs[0].status != TStatus.NOT_NEEDED -> problems += "ENGLISH NOT KEPT AS IS (${recs[0].status}): '$want'"
                else -> if (line.lang == Lang.ES) spanishDone++ else englishKept++
            }
            val delayMs = (capturedAt[want] ?: Long.MAX_VALUE) - (spokenAt[want] ?: 0L)
            maxCaptureDelay = maxOf(maxCaptureDelay, delayMs)
        }
        val inOrder = saved.map { it.original } == expected
        if (System.getenv("CONV_DEBUG") != null) {
            val miss = expected.indexOfFirst { byText[it] == null }
            if (miss >= 0) {
                println("DEBUG total saved=${saved.size} missing index=$miss kind=${debugKinds[miss]}")
                for (k in maxOf(0, miss - 3)..minOf(expected.size - 1, miss + 3)) println("DEBUG spoken[$k] (${debugKinds[k]}) ${expected[k]}")
                val at = saved.indexOfFirst { it.original == expected[maxOf(0, miss - 1)] }
                for (k in maxOf(0, at - 1)..minOf(saved.size - 1, at + 4)) println("DEBUG saved[$k] ${saved[k].original}")
            }
        }
        val export = TranscriptExporter.toText(saved)

        val report = buildString {
            appendLine("ABHISHEK – 10,000-SENTENCE GROUP CONVERSATION TEST (Group mode: record all, translate Spanish → English)")
            appendLine("Sentences: 10000 different (Spanish ${all.count { it.lang == Lang.ES }}, English ${all.count { it.lang == Lang.EN }}), mixed at random")
            appendLine("Talking time: ${endOfTalk / 60_000} min, gaps 0–1.5 s, 1,000 sentences with no gap at all, app restarted at sentence 7,000")
            appendLine("Translator: 150 ms per sentence, 3% random failures, language pack missing for the first 10 minutes")
            appendLine("How sentences arrived: $kinds")
            appendLine()
            appendLine("Saved (none skipped)                 ${10_000 - problems.count { it.startsWith("MISSING") }} / 10000")
            appendLine("Saved exactly once                   ${10_000 - problems.count { it.startsWith("SAVED") }} / 10000")
            appendLine("Spanish translated to English        $spanishDone / ${all.count { it.lang == Lang.ES }}")
            appendLine("English kept as said (no work)       $englishKept / ${all.count { it.lang == Lang.EN }}  (translator calls for English: $englishCalls)")
            appendLine("Saved in the order spoken            ${if (inOrder) "yes" else "NO"}")
            appendLine("All in the exported file             ${if (saved.all { export.contains(it.original) }) "yes" else "NO"}")
            appendLine("Longest wait from speech to saved    $maxCaptureDelay ms (only sentences cut on 'and'/'que' wait for their end)")
            appendLine("Most sentences waiting for translation at once: $maxBacklog; queue empty ${drainSeconds}s after talking stopped")
            appendLine("Computer time for the whole run      ${wallMs / 1000}s (${"%.2f".format(wallMs.toDouble() / 10_000)} ms per sentence incl. database)")
            if (problems.isNotEmpty()) { appendLine(); appendLine("PROBLEMS (${problems.size})"); problems.take(200).forEach { appendLine("   $it") } }
        }
        File("build/reports/conversation-10000").apply { mkdirs() }.resolve("report.txt").writeText(report)
        println(report)
        db.close()

        assertTrue(report, problems.isEmpty())
        assertTrue(report, inOrder)
        assertEquals("English must not be sent to the translator", 0, englishCalls)
        assertTrue("capture must not wait for translation: $maxCaptureDelay ms", maxCaptureDelay <= 1_300)
    }
}
