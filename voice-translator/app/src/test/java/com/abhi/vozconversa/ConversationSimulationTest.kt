package com.abhi.vozconversa

import androidx.test.core.app.ApplicationProvider
import com.abhi.vozconversa.core.ConversationEngine
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.ListenMode
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phrase-book translator standing in for ML Kit (which only runs on a real phone). */
class PhraseBook(private val failOn: Set<String> = emptySet()) : TextTranslator {
    val calls = mutableListOf<Pair<String, Lang>>()
    private val esEn = mapOf(
        "Hola, buenos días" to "Hello, good morning",
        "¿Cómo está usted?" to "How are you?",
        "Necesito ayuda con la torre" to "I need help with the tower",
        "Quiero ir a la tienda y comprar pan" to "I want to go to the store and buy bread",
        "¿Dónde está el baño?" to "Where is the bathroom?",
        "Mañana trabajamos a las siete" to "Tomorrow we work at seven",
        "Gracias por todo" to "Thanks for everything",
        "Sí" to "Yes",
        "No tengo dinero para el autobús" to "I don't have money for the bus",
        "El camión llega en una hora" to "The truck arrives in an hour",
    )
    private val enEs = mapOf(
        "Good morning, how are you?" to "Buenos días, ¿cómo estás?",
        "I am fine, thank you" to "Estoy bien, gracias",
        "Where is the truck?" to "¿Dónde está el camión?",
        "We need to finish the tower today" to "Necesitamos terminar la torre hoy",
        "Please bring the tools and the cables" to "Por favor trae las herramientas y los cables",
        "The meeting is at three" to "La reunión es a las tres",
        "Yes" to "Sí",
        "Can you help me with the bags?" to "¿Puedes ayudarme con las bolsas?",
        "See you tomorrow" to "Hasta mañana",
    )

    override suspend fun translate(text: String, source: Lang): String {
        calls += text to source
        if (text in failOn) throw IllegalStateException("model not downloaded")
        val book = if (source == Lang.ES) esEn else enEs
        return book[text] ?: "[${source.other.short}] $text"
    }
}

/** One recognizer result: what it heard, the language it reported, and the pause after it. */
data class Heard(val text: String, val detected: Lang? = null, val pauseMs: Long = 3000)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationSimulationTest {
    private lateinit var db: HistoryDb

    @Before fun setUp() {
        db = HistoryDb(ApplicationProvider.getApplicationContext(), null) // in-memory database
    }

    @After fun tearDown() = db.close()

    /** Feeds a conversation through the engine exactly like the service does, including AUTO alternation. */
    private fun TestScope.converse(
        lines: List<Heard>, mode: ListenMode = ListenMode.AUTO, translator: PhraseBook = PhraseBook(),
    ): List<TranslationRecord> {
        val delivered = mutableListOf<TranslationRecord>()
        val engine = ConversationEngine(
            this, translator, db, sessionId = 1, clock = { testScheduler.currentTime }, holdMillis = 1800,
            listener = object : ConversationEngine.Listener {
                override fun onTranslated(record: TranslationRecord) { delivered += record }
                override fun onError(message: String, record: TranslationRecord) { delivered += record }
            },
        )
        var listening = if (mode == ListenMode.ENGLISH) Lang.EN else Lang.ES
        for (h in lines) {
            val src = engine.onUtterance(h.text, mode, h.detected, listening)
            if (mode == ListenMode.AUTO && src != null) listening = src.other
            runCurrent()
            advanceTimeBy(h.pauseMs)
        }
        advanceTimeBy(10_000)
        runCurrent()
        return delivered
    }

    private fun saved() = db.all()

    // 1
    @Test fun test01_basicTwoPersonConversation() = runTest {
        val out = converse(
            listOf(
                Heard("hola, buenos días", Lang.ES),
                Heard("Good morning, how are you?", Lang.EN),
                Heard("¿Cómo está usted?", Lang.ES),
                Heard("I am fine, thank you", Lang.EN),
            )
        )
        assertEquals(listOf(Lang.ES, Lang.EN, Lang.ES, Lang.EN), out.map { it.source })
        assertEquals(
            listOf("Hello, good morning", "Buenos días, ¿cómo estás?", "How are you?", "Estoy bien, gracias"),
            out.map { it.translated },
        )
        assertEquals(4, saved().size)
    }

    // 2
    @Test fun test02_waitsForWholeSentenceWhenSpeakerPausesMidway() = runTest {
        val out = converse(
            listOf(
                Heard("quiero ir a la tienda y", Lang.ES, pauseMs = 900), // pause after "y"
                Heard("comprar pan", Lang.ES),
                Heard("Please bring the tools and", Lang.EN, pauseMs = 1200),
                Heard("the cables", Lang.EN),
            )
        )
        assertEquals(2, out.size)
        assertEquals("Quiero ir a la tienda y comprar pan", out[0].original)
        assertEquals("I want to go to the store and buy bread", out[0].translated)
        assertEquals("Please bring the tools and the cables", out[1].original)
        assertEquals("Por favor trae las herramientas y los cables", out[1].translated)
    }

    // 3
    @Test fun test03_unfinishedFragmentIsStillTranslatedAfterTimeout() = runTest {
        val out = converse(listOf(Heard("Necesito ayuda con la", Lang.ES, pauseMs = 5000)))
        assertEquals(1, out.size)
        assertEquals("Necesito ayuda con la", out[0].original)
        assertEquals(Lang.ES, out[0].source)
    }

    // 4
    @Test fun test04_autoModeWithoutRecognizerDetection() = runTest {
        // Older phones (Android 13 and below) do not report the language: text decides.
        val out = converse(
            listOf(
                Heard("¿Dónde está el baño?"),
                Heard("Where is the truck?"),
                Heard("El camión llega en una hora"),
                Heard("We need to finish the tower today"),
            )
        )
        assertEquals(listOf(Lang.ES, Lang.EN, Lang.ES, Lang.EN), out.map { it.source })
        assertEquals("Where is the bathroom?", out[0].translated)
        assertEquals("Necesitamos terminar la torre hoy", out[3].translated)
    }

    // 5
    @Test fun test05_fillerSoundsAndSilenceAreIgnored() = runTest {
        val out = converse(
            listOf(Heard("mm"), Heard("eh"), Heard("   "), Heard("Gracias por todo", Lang.ES), Heard("hmm"))
        )
        assertEquals(1, out.size)
        assertEquals("Thanks for everything", out[0].translated)
        assertEquals(1, saved().size)
    }

    // 6
    @Test fun test06_lockedSpanishMode() = runTest {
        val out = converse(
            listOf(Heard("Mañana trabajamos a las siete"), Heard("Sí"), Heard("No tengo dinero para el autobús")),
            mode = ListenMode.SPANISH,
        )
        assertTrue(out.all { it.source == Lang.ES && it.target == Lang.EN })
        assertEquals(listOf("Tomorrow we work at seven", "Yes", "I don't have money for the bus"), out.map { it.translated })
    }

    // 7
    @Test fun test07_lockedEnglishMode() = runTest {
        val out = converse(
            listOf(Heard("The meeting is at three"), Heard("Yes"), Heard("See you tomorrow")),
            mode = ListenMode.ENGLISH,
        )
        assertTrue(out.all { it.source == Lang.EN })
        assertEquals(listOf("La reunión es a las tres", "Sí", "Hasta mañana"), out.map { it.translated })
    }

    // 8
    @Test fun test08_translationFailureStillSavesTheSentence() = runTest {
        val book = PhraseBook(failOn = setOf("Where is the truck?"))
        val out = converse(listOf(Heard("Where is the truck?", Lang.EN), Heard("Sí", Lang.ES)), translator = book)
        assertEquals(2, out.size)
        assertEquals("", out[0].translated)          // saved untranslated
        assertEquals("Where is the truck?", saved()[0].original)
        assertEquals(2, book.calls.count { it.first == "Where is the truck?" }) // retried once
        assertEquals("Yes", out[1].translated)       // conversation keeps going
    }

    // 9
    @Test fun test09_longRunningConversationKeepsOrderAndSavesEverything() = runTest {
        // About one sentence every 5 seconds for 2 hours, alternating speakers.
        val lines = (0 until 1440).map { i ->
            if (i % 2 == 0) Heard("Gracias por todo", Lang.ES, pauseMs = 5000)
            else Heard("Can you help me with the bags?", Lang.EN, pauseMs = 5000)
        }
        val out = converse(lines)
        assertEquals(1440, out.size)
        assertEquals(1440, saved().size)
        assertTrue(out.zipWithNext().all { (a, b) -> a.timeMillis <= b.timeMillis && a.id < b.id })
        assertTrue(out.zipWithNext().all { (a, b) -> a.source != b.source })
        // Text only: 1,440 sentences take well under 1 MB.
        val bytes = TranscriptExporter.toCsv(saved()).toByteArray().size
        assertTrue("csv size $bytes", bytes < 200_000)
    }

    // 10
    @Test fun test10_mixedRealisticConversationAndExport() = runTest {
        converse(
            listOf(
                Heard("hola, buenos días", Lang.ES),
                Heard("mm"),
                Heard("Good morning, how are you?", Lang.EN),
                Heard("necesito ayuda con", Lang.ES, pauseMs = 800),
                Heard("la torre", Lang.ES),
                Heard("Can you help me with the bags?", null),
                Heard("sí", Lang.ES),
                Heard("See you tomorrow", Lang.EN),
            )
        )
        val all = saved()
        assertEquals(6, all.size)
        assertEquals("I need help with the tower", all[2].translated)
        val txt = TranscriptExporter.toText(all)
        listOf("Hello, good morning", "Buenos días, ¿cómo estás?", "I need help with the tower",
            "¿Puedes ayudarme con las bolsas?", "Yes", "Hasta mañana").forEach {
            assertTrue("missing $it", txt.contains(it))
        }
    }
}
