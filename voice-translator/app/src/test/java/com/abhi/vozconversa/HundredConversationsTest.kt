package com.abhi.vozconversa

import androidx.test.core.app.ApplicationProvider
import com.abhi.vozconversa.core.ConversationEngine
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.ListenMode
import com.abhi.vozconversa.core.SentenceText
import com.abhi.vozconversa.core.TextTranslator
import com.abhi.vozconversa.core.TranscriptExporter
import com.abhi.vozconversa.core.TranslationRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A sentence and the translation the test expects. */
data class Pair2(val said: String, val translation: String)

object SentenceData {
    private fun load(name: String): List<Pair2> =
        SentenceData::class.java.classLoader!!.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8)
            .readLines().filter { it.isNotBlank() }
            .map { line -> line.split('\t').let { Pair2(it[0].trim(), it[1].trim()) } }

    /** 100 different Spanish sentences with their English translations. */
    val spanish: List<Pair2> by lazy { load("spanish_sentences.tsv") }

    /** 100 different English sentences with their Spanish translations. */
    val english: List<Pair2> by lazy { load("english_sentences.tsv") }
}

/**
 * Stands in for ML Kit (which only runs on a real phone): it knows the expected translation of every
 * test sentence, and records which direction it was asked to translate.
 */
class PhraseBook(private val failFirstAttempt: Boolean = false) : TextTranslator {
    val calls = mutableListOf<Pair<String, Lang>>()
    private val esEn = SentenceData.spanish.associate { it.said.lowercase() to it.translation }
    private val enEs = SentenceData.english.associate { it.said.lowercase() to it.translation }
    private val failed = mutableSetOf<String>()

    override suspend fun translate(text: String, source: Lang): String {
        calls += text to source
        if (failFirstAttempt && failed.add(text)) throw IllegalStateException("model busy")
        val book = if (source == Lang.ES) esEn else enEs
        return book[text.lowercase()] ?: "[${source.other.short}] $text"
    }
}

/** One recognizer result: the words it heard, the language it reported, and the pause after it. */
data class Heard(val text: String, val detected: Lang? = null, val pauseMs: Long = 3000, val mode: ListenMode? = null)

@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.runConversation(
    db: HistoryDb, lines: List<Heard>, mode: ListenMode = ListenMode.AUTO,
    translator: TextTranslator = PhraseBook(), startListening: Lang = Lang.ES,
): List<TranslationRecord> {
    val delivered = mutableListOf<TranslationRecord>()
    val engine = ConversationEngine(
        this, translator, db, sessionId = 1, clock = { testScheduler.currentTime }, holdMillis = 1800,
        listener = object : ConversationEngine.Listener {
            override fun onTranslated(record: TranslationRecord) { delivered += record }
            override fun onError(message: String, record: TranslationRecord) { delivered += record }
        },
    )
    // Same steps as TranslatorService: in AUTO mode the recognizer is primed for the other language next.
    var listening = startListening
    for (h in lines) {
        val m = h.mode ?: mode
        if (m == ListenMode.SPANISH) listening = Lang.ES
        if (m == ListenMode.ENGLISH) listening = Lang.EN
        val src = engine.onUtterance(h.text, m, h.detected, listening)
        if (m == ListenMode.AUTO && src != null) listening = src.other
        runCurrent()
        advanceTimeBy(h.pauseMs)
    }
    advanceTimeBy(10_000)
    runCurrent()
    return delivered
}

/** Splits a sentence where a speaker would naturally pause mid-way ("... con la" | "torre"). */
fun splitMidSentence(s: String): Pair<String, String> {
    val words = s.split(" ")
    for (k in 2 until words.size) {
        val first = words.subList(0, k).joinToString(" ")
        if (SentenceText.looksUnfinished(first)) {
            // The recognizer starts each new result with a capital letter.
            val second = words.subList(k, words.size).joinToString(" ").replaceFirstChar { it.uppercase() }
            return first to second
        }
    }
    throw AssertionError("no natural pause point in: $s")
}

/**
 * 100 tests, each a short conversation with its own Spanish sentence and its own English sentence
 * (200 different sentences in total, none repeated). Ten situations rotate across the 100 tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class HundredConversationsTest(private val n: Int, @Suppress("unused") private val label: String) {

    companion object {
        private val situations = listOf(
            "both speakers, phone reports language",
            "both speakers, older phone (no language report)",
            "Spanish speaker pauses mid-sentence",
            "English speaker pauses mid-sentence",
            "filler sounds and noise around speech",
            "direction locked with the ES/EN buttons",
            "English first, mic primed for the wrong language",
            "translator busy on first try (retry)",
            "messy recognizer text (lowercase, extra spaces)",
            "quick back-and-forth, short pauses",
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "test {0}: {1}")
        fun cases(): List<Array<Any>> = (1..100).map { arrayOf<Any>(it, situations[(it - 1) % 10]) }
    }

    private lateinit var db: HistoryDb
    private val es get() = SentenceData.spanish[n - 1]
    private val en get() = SentenceData.english[n - 1]

    @Before fun setUp() { db = HistoryDb(ApplicationProvider.getApplicationContext(), null) }
    @After fun tearDown() = db.close()

    @Test fun conversation() = runTest {
        val book = PhraseBook(failFirstAttempt = (n - 1) % 10 == 7)
        val englishFirst = (n - 1) % 10 == 6
        val lines: List<Heard> = when ((n - 1) % 10) {
            0 -> listOf(Heard(es.said, Lang.ES), Heard(en.said, Lang.EN))
            1 -> listOf(Heard(es.said), Heard(en.said))
            2 -> splitMidSentence(es.said).let { (a, b) ->
                listOf(Heard(a, Lang.ES, pauseMs = 900), Heard(b, Lang.ES), Heard(en.said, Lang.EN))
            }
            3 -> splitMidSentence(en.said).let { (a, b) ->
                listOf(Heard(es.said, Lang.ES), Heard(a, Lang.EN, pauseMs = 1200), Heard(b, Lang.EN))
            }
            4 -> listOf(Heard("mm"), Heard(es.said, Lang.ES), Heard("eh"), Heard("  "), Heard(en.said, Lang.EN), Heard("hmm"))
            5 -> listOf(Heard(es.said, mode = ListenMode.SPANISH), Heard(en.said, mode = ListenMode.ENGLISH))
            6 -> listOf(Heard(en.said), Heard(es.said))
            7 -> listOf(Heard(es.said, Lang.ES), Heard(en.said, Lang.EN))
            8 -> listOf(
                Heard("  " + es.said.lowercase().replace(" ", "   ") + " "),
                Heard(en.said.lowercase().replace(" ", "  "), Lang.EN),
            )
            else -> listOf(Heard(es.said, Lang.ES, pauseMs = 300), Heard(en.said, Lang.EN, pauseMs = 300))
        }
        val out = runConversation(db, lines, translator = book, startListening = Lang.ES)

        // Exactly two sentences, each translated in the right direction, in the order spoken.
        val expected = if (englishFirst) listOf(en to Lang.EN, es to Lang.ES) else listOf(es to Lang.ES, en to Lang.EN)
        assertEquals("sentences delivered: ${out.map { it.original }}", 2, out.size)
        out.zip(expected).forEach { (rec, exp) ->
            val (pair, lang) = exp
            assertEquals("direction of '${pair.said}'", lang, rec.source)
            assertTrue("whole sentence kept: '${rec.original}' vs '${pair.said}'", rec.original.equals(pair.said, ignoreCase = true))
            assertEquals("translation of '${pair.said}'", pair.translation, rec.translated)
        }
        assertTrue(out[0].timeMillis <= out[1].timeMillis)

        // Both are saved on the phone and appear in the exported file.
        val saved = db.all()
        assertEquals(out.map { it.original to it.translated }, saved.map { it.original to it.translated })
        val txt = TranscriptExporter.toText(saved)
        assertTrue(txt.contains(es.translation) && txt.contains(en.translation))

        // Each sentence went to the translator once, or twice when the first try failed.
        val perSentence = if ((n - 1) % 10 == 7) 2 else 1
        assertEquals(2 * perSentence, book.calls.size)
    }
}

/** Checks on the test data itself, and a long non-stop session with all-new sentences. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SentenceDataAndLongRunTest {
    private lateinit var db: HistoryDb
    @Before fun setUp() { db = HistoryDb(ApplicationProvider.getApplicationContext(), null) }
    @After fun tearDown() = db.close()

    @Test fun twoHundredSentencesAllDifferent() {
        val said = SentenceData.spanish.map { it.said } + SentenceData.english.map { it.said }
        assertEquals(100, SentenceData.spanish.size)
        assertEquals(100, SentenceData.english.size)
        assertEquals("a sentence is repeated", 200, said.map { it.lowercase() }.toSet().size)
        val translations = SentenceData.spanish.map { it.translation } + SentenceData.english.map { it.translation }
        assertEquals("a translation is repeated", 200, translations.map { it.lowercase() }.toSet().size)
    }

    @Test fun twoHourSessionWith1440DifferentSentences() = runTest {
        // One sentence every 5 seconds for 2 hours. Every sentence is different.
        val lines = (1..1440).map { i ->
            if (i % 2 == 1) Heard("La torre número $i necesita ${i * 2} pernos nuevos", Lang.ES, pauseMs = 5000)
            else Heard("Section $i of the line is ready for ${i * 3} workers", Lang.EN, pauseMs = 5000)
        }
        assertEquals(1440, lines.map { it.text }.toSet().size)
        val out = runConversation(db, lines)
        assertEquals(1440, out.size)
        assertEquals(1440, db.count())
        assertEquals(lines.map { it.text }, out.map { it.original })                // order kept, nothing merged
        assertTrue(out.zipWithNext().all { (a, b) -> a.source != b.source })        // alternating directions
        val bytes = TranscriptExporter.toCsv(db.all()).toByteArray().size
        assertTrue("csv size $bytes", bytes < 300_000)
    }
}
