package com.abhi.vozconversa

import androidx.test.core.app.ApplicationProvider
import com.abhi.vozconversa.core.ConversationEngine
import com.abhi.vozconversa.core.Corrector
import com.abhi.vozconversa.core.CorrectionDictionary
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.Lang.EN
import com.abhi.vozconversa.core.Lang.ES
import com.abhi.vozconversa.core.ListenMode
import com.abhi.vozconversa.core.SentenceAuditor
import com.abhi.vozconversa.core.SentenceWords
import com.abhi.vozconversa.core.TextTranslator
import com.abhi.vozconversa.core.TranslationRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One auditor check: what the recognizer heard, and what the auditor must do with it.
 * @param taught word fixes the user taught earlier (wrong → right)
 * @param alternatives the recognizer's other guesses
 * @param fixed expected sentence after the automatic fixes
 * @param flagged words the auditor must point at (in order), with suggestions if given
 * @param edit the word the user taps and what they type (word → replacement)
 * @param thenHeard a later, different sentence with the same mistake, and how it must come out
 * @param finalTranslation expected translation after the user's fix (full-flow cases)
 */
data class AuditCase(
    val group: String,
    val lang: Lang,
    val heard: String,
    val fixed: String = heard,
    val flagged: List<String> = emptyList(),
    val suggestions: Map<String, List<String>> = emptyMap(),
    val taught: Map<String, String> = emptyMap(),
    val alternatives: List<String> = emptyList(),
    val edit: Pair<String, String>? = null,
    val learn: Boolean = true,
    val afterEdit: String? = null,
    val thenHeard: Pair<String, String>? = null,
    val finalTranslation: String? = null,
)

object AuditCases {
    private const val TAUGHT = "learned word fixed automatically"
    private const val DOUBLE = "word said twice"
    private const val ACCENT = "missing Spanish accent"
    private const val QUESTION = "Spanish question word"
    private const val EN_IN_ES = "English word inside Spanish"
    private const val ES_IN_EN = "Spanish word inside English"
    private const val UNSURE = "recognizer unsure of a word"
    private const val TAP_FIX = "tap one word to fix it, app learns it"
    private const val CLEAN = "correct sentence left alone"
    private const val FLOW = "full flow: heard, audited, fixed, re-translated, saved"

    val all: List<AuditCase> = listOf(
        // 1–10: words the user taught earlier are fixed automatically
        AuditCase(TAUGHT, ES, "Sube a la tore número cinco", "Sube a la torre número cinco", taught = mapOf("tore" to "torre")),
        AuditCase(TAUGHT, EN, "The crane is near the tour", "The crane is near the tower", taught = mapOf("tour" to "tower")),
        AuditCase(TAUGHT, ES, "Trae los pernos del contener", "Trae los pernos del contenedor", taught = mapOf("contener" to "contenedor")),
        AuditCase(TAUGHT, EN, "We need more bowls for the base", "We need more bolts for the base", taught = mapOf("bowls" to "bolts")),
        AuditCase(TAUGHT, ES, "El capitas llega a las nueve", "El capataz llega a las nueve", taught = mapOf("capitas" to "capataz")),
        AuditCase(TAUGHT, EN, "The four man wants a report", "The foreman wants a report", taught = mapOf("four man" to "foreman")),
        AuditCase(TAUGHT, ES, "Mi amigo Abi está en la obra", "Mi amigo Abhishek está en la obra", taught = mapOf("abi" to "Abhishek")),
        AuditCase(TAUGHT, EN, "Send the photos to Abi Shek", "Send the photos to Abhishek", taught = mapOf("abi shek" to "Abhishek", "abi" to "Abhi")),
        AuditCase(TAUGHT, ES, "Gruya parada desde ayer", "Grúa parada desde ayer", taught = mapOf("gruya" to "grúa")),
        AuditCase(TAUGHT, EN, "Call the sir Vayer tomorrow", "Call the surveyor tomorrow", taught = mapOf("sir vayer" to "surveyor")),

        // 11–20: a small word said twice is removed; other repeats are only pointed out
        AuditCase(DOUBLE, EN, "Put the the gloves in the box", "Put the gloves in the box"),
        AuditCase(DOUBLE, ES, "Vamos a la la oficina ahora", "Vamos a la oficina ahora"),
        AuditCase(DOUBLE, EN, "I need to to call my brother", "I need to call my brother"),
        AuditCase(DOUBLE, ES, "Hay que limpiar el el camión", "Hay que limpiar el camión"),
        AuditCase(DOUBLE, EN, "She left the keys in in the car", "She left the keys in the car"),
        AuditCase(DOUBLE, ES, "Los cables de de cobre llegaron", "Los cables de cobre llegaron"),
        AuditCase(DOUBLE, EN, "Bring a a hammer and some nails", "Bring a hammer and some nails"),
        AuditCase(DOUBLE, ES, "Compré pan y y leche en la tienda", "Compré pan y leche en la tienda"),
        AuditCase(DOUBLE, EN, "We work work every Saturday", flagged = listOf("work")),
        AuditCase(DOUBLE, ES, "No no, eso está mal"),

        // 21–30: Spanish words heard without their accent
        AuditCase(ACCENT, ES, "Mañana es mi dia libre", "Mañana es mi día libre"),
        AuditCase(ACCENT, ES, "Tambien necesito guantes", "También necesito guantes"),
        AuditCase(ACCENT, ES, "El camion llegó tarde", "El camión llegó tarde"),
        AuditCase(ACCENT, ES, "Despues de comer seguimos", "Después de comer seguimos"),
        AuditCase(ACCENT, ES, "Mi numero de telefono cambió", "Mi número de teléfono cambió"),
        AuditCase(ACCENT, ES, "La reunion es el sabado", "La reunión es el sábado"),
        AuditCase(ACCENT, ES, "Ven aqui por favor", "Ven aquí por favor"),
        AuditCase(ACCENT, ES, "Necesito un medico rapido", "Necesito un médico rápido"),
        AuditCase(ACCENT, ES, "Hablo un poco de ingles", "Hablo un poco de inglés"),
        AuditCase(ACCENT, ES, "Feliz ano nuevo señor", "Feliz año nuevo señor"),

        // 31–40: Spanish questions get their accent and opening ¿
        AuditCase(QUESTION, ES, "Donde está el baño?", "¿Dónde está el baño?"),
        AuditCase(QUESTION, ES, "Como te llamas?", "¿Cómo te llamas?"),
        AuditCase(QUESTION, ES, "Cuando llega el camión?", "¿Cuándo llega el camión?"),
        AuditCase(QUESTION, ES, "Que hora es?", "¿Qué hora es?"),
        AuditCase(QUESTION, ES, "Quien tiene las llaves?", "¿Quién tiene las llaves?"),
        AuditCase(QUESTION, ES, "Cuanto cuesta el arnés?", "¿Cuánto cuesta el arnés?"),
        AuditCase(QUESTION, ES, "Cual es tu casco?", "¿Cuál es tu casco?"),
        AuditCase(QUESTION, ES, "¿Donde vives ahora?", "¿Dónde vives ahora?"),
        AuditCase(QUESTION, ES, "Por que no vino Pedro?", "¿Por qué no vino Pedro?"),
        AuditCase(QUESTION, ES, "Donde trabajo hay mucho ruido"),

        // 41–50: an English word inside a Spanish sentence is pointed out
        AuditCase(EN_IN_ES, ES, "Necesito the herramientas", flagged = listOf("the")),
        AuditCase(EN_IN_ES, ES, "El camión is aquí", "El camión is aquí", flagged = listOf("is")),
        AuditCase(EN_IN_ES, ES, "Vamos with Pedro mañana", flagged = listOf("with")),
        AuditCase(EN_IN_ES, ES, "Trae el casco and los guantes", flagged = listOf("and")),
        AuditCase(EN_IN_ES, ES, "La torre of acero está lista", flagged = listOf("of")),
        AuditCase(EN_IN_ES, ES, "Mi hermano you viene hoy", flagged = listOf("you")),
        AuditCase(EN_IN_ES, ES, "Hay agua what en la nevera", flagged = listOf("what")),
        AuditCase(EN_IN_ES, ES, "Los trabajadores are cansados", flagged = listOf("are")),
        AuditCase(EN_IN_ES, ES, "Pon el cable this aquí", flagged = listOf("this")),
        AuditCase(EN_IN_ES, ES, "Llama al jefe por teléfono"),

        // 51–60: a Spanish word inside an English sentence is pointed out
        AuditCase(ES_IN_EN, EN, "Bring the cables para the tower", flagged = listOf("para")),
        AuditCase(ES_IN_EN, EN, "The truck está outside", flagged = listOf("está")),
        AuditCase(ES_IN_EN, EN, "We need los bolts now", flagged = listOf("los")),
        AuditCase(ES_IN_EN, EN, "Call me cuando you arrive", flagged = listOf("cuando")),
        AuditCase(ES_IN_EN, EN, "The meeting es at nine", flagged = listOf("es")),
        AuditCase(ES_IN_EN, EN, "My boss quiere to stop", flagged = listOf("quiere")),
        AuditCase(ES_IN_EN, EN, "Put it in the box por favor", flagged = listOf("por")),
        AuditCase(ES_IN_EN, EN, "The ladder is muy tall", flagged = listOf("muy")),
        AuditCase(ES_IN_EN, EN, "Thanks, señor, see you tomorrow", flagged = listOf("señor")),
        AuditCase(ES_IN_EN, EN, "Ask the driver if the road is open"),

        // 61–70: the recognizer's other guesses show which word it was unsure about
        AuditCase(UNSURE, ES, "Sube a la tore cinco", flagged = listOf("tore"), suggestions = mapOf("tore" to listOf("torre")),
            alternatives = listOf("Sube a la torre cinco")),
        AuditCase(UNSURE, EN, "The bolts are in the back", flagged = listOf("bolts"), suggestions = mapOf("bolts" to listOf("bowls", "boats")),
            alternatives = listOf("The bowls are in the back", "The boats are in the back")),
        AuditCase(UNSURE, ES, "Necesito una cuerda larga", alternatives = listOf("necesito una cuerda larga")),
        AuditCase(UNSURE, EN, "Meet me at the gate at noon", flagged = listOf("noon"), suggestions = mapOf("noon" to listOf("nine")),
            alternatives = listOf("Meet me at the gate at nine")),
        AuditCase(UNSURE, ES, "El perro come carne", flagged = listOf("perro"), suggestions = mapOf("perro" to listOf("pero")),
            alternatives = listOf("El pero come carne")),
        AuditCase(UNSURE, EN, "Turn left at the station", flagged = listOf("left"), suggestions = mapOf("left" to listOf("right")),
            alternatives = listOf("Turn right at the station")),
        AuditCase(UNSURE, ES, "Compra seis botellas de agua", flagged = listOf("seis"), suggestions = mapOf("seis" to listOf("tres")),
            alternatives = listOf("Compra tres botellas de agua")),
        AuditCase(UNSURE, EN, "The fourth tower is ready", flagged = listOf("fourth"), suggestions = mapOf("fourth" to listOf("forth", "four")),
            alternatives = listOf("The forth tower is ready", "The four tower is ready")),
        AuditCase(UNSURE, EN, "We finished the base", flagged = listOf("finished"), suggestions = mapOf("finished" to listOf("finish")),
            alternatives = listOf("We finish the base today")),
        AuditCase(UNSURE, ES, "Llegamos a las ocho", alternatives = listOf("Llegamos a las ocho y media")),

        // 71–80: tap the wrong word, type the right one; the app learns it for next time
        AuditCase(TAP_FIX, ES, "Revisa la cadena de la grulla", edit = "grulla" to "grúa",
            afterEdit = "Revisa la cadena de la grúa", thenHeard = "La grulla no funciona" to "La grúa no funciona"),
        AuditCase(TAP_FIX, EN, "Check the harnest before climbing", edit = "harnest" to "harness",
            afterEdit = "Check the harness before climbing", thenHeard = "Where is my harnest" to "Where is my harness"),
        AuditCase(TAP_FIX, ES, "El ingeniero Ramires llegó", edit = "Ramires" to "Ramírez",
            afterEdit = "El ingeniero Ramírez llegó", thenHeard = "Llama a Ramires" to "Llama a Ramírez"),
        AuditCase(TAP_FIX, EN, "Lower the panel with the winsh", edit = "winsh" to "winch",
            afterEdit = "Lower the panel with the winch", thenHeard = "The winsh is broken" to "The winch is broken"),
        AuditCase(TAP_FIX, ES, "Aprieta el tornilo grande", edit = "tornilo" to "tornillo",
            afterEdit = "Aprieta el tornillo grande", thenHeard = "Falta un tornilo" to "Falta un tornillo"),
        AuditCase(TAP_FIX, EN, "Ask Abhisek about the plan", edit = "Abhisek" to "Abhishek",
            afterEdit = "Ask Abhishek about the plan", thenHeard = "Abhisek is on site" to "Abhishek is on site"),
        AuditCase(TAP_FIX, ES, "Pon la escalera junto al poster", edit = "poster" to "poste",
            afterEdit = "Pon la escalera junto al poste", thenHeard = "El poster está torcido" to "El poste está torcido"),
        AuditCase(TAP_FIX, EN, "The grounding rot is missing", edit = "rot" to "rod",
            afterEdit = "The grounding rod is missing", thenHeard = "Bring a new rot" to "Bring a new rod"),
        AuditCase(TAP_FIX, ES, "La ruta pasa por Monterey", edit = "Monterey" to "Monterrey",
            afterEdit = "La ruta pasa por Monterrey", thenHeard = "Vivo en Monterey" to "Vivo en Monterrey"),
        AuditCase(TAP_FIX, EN, "Stack the steal beams here", edit = "steal" to "steel",
            afterEdit = "Stack the steel beams here", thenHeard = "The steal is rusty" to "The steel is rusty"),

        // 81–90: correct sentences must come through untouched and unflagged
        AuditCase(CLEAN, ES, "Buenas tardes, ¿cómo está la familia?"),
        AuditCase(CLEAN, EN, "The foreman approved the new schedule"),
        AuditCase(CLEAN, ES, "Los aisladores de vidrio son frágiles"),
        AuditCase(CLEAN, EN, "Please wear your helmet on site"),
        AuditCase(CLEAN, ES, "Terminamos la torre antes de la lluvia"),
        AuditCase(CLEAN, EN, "I will be back in twenty minutes"),
        AuditCase(CLEAN, ES, "Él dijo que mañana no hay trabajo"),
        AuditCase(CLEAN, EN, "That that is true does not matter"),
        AuditCase(CLEAN, ES, "Ya ya, entiendo lo que dices"),
        AuditCase(CLEAN, EN, "Can you hear me now?"),

        // 91–100: the whole flow in the app — heard, audited, translated, saved, one word fixed, re-translated
        AuditCase(FLOW, ES, "Necesito ayuda con la tore", edit = "tore" to "torre",
            afterEdit = "Necesito ayuda con la torre", finalTranslation = "I need help with the tower"),
        AuditCase(FLOW, EN, "The the crane needs a new hock", "The crane needs a new hock", edit = "hock" to "hook",
            afterEdit = "The crane needs a new hook", finalTranslation = "La grúa necesita un gancho nuevo"),
        AuditCase(FLOW, ES, "El camion de la tore llegó", "El camión de la tore llegó", edit = "tore" to "torre",
            afterEdit = "El camión de la torre llegó", finalTranslation = "The tower truck arrived"),
        AuditCase(FLOW, EN, "Bring the sement bags inside", edit = "sement" to "cement",
            afterEdit = "Bring the cement bags inside", finalTranslation = "Mete las bolsas de cemento"),
        AuditCase(FLOW, ES, "Donde está la pinsa?", "¿Dónde está la pinsa?", edit = "pinsa" to "pinza",
            afterEdit = "¿Dónde está la pinza?", finalTranslation = "Where are the pliers?"),
        AuditCase(FLOW, EN, "Our cruise starts at seven", edit = "cruise" to "crew",
            afterEdit = "Our crew starts at seven", finalTranslation = "Nuestra cuadrilla empieza a las siete"),
        AuditCase(FLOW, ES, "Mi jefe se llama Abisek", edit = "Abisek" to "Abhishek",
            afterEdit = "Mi jefe se llama Abhishek", finalTranslation = "My boss's name is Abhishek"),
        AuditCase(FLOW, EN, "The weather is to windy today", edit = "to" to "too", learn = false,
            afterEdit = "The weather is too windy today", finalTranslation = "Hoy hace demasiado viento"),
        AuditCase(FLOW, ES, "Hay que pintar la vace de la torre", edit = "vace" to "base",
            afterEdit = "Hay que pintar la base de la torre", finalTranslation = "The base of the tower has to be painted"),
        AuditCase(FLOW, EN, "Count the bowls before lifting", edit = "bowls" to "bolts",
            afterEdit = "Count the bolts before lifting", finalTranslation = "Cuenta los pernos antes de levantar"),
    )
}

/** Phrase book for the full-flow cases: knows the translation of each fixed sentence. */
private class FixBook : TextTranslator {
    val calls = mutableListOf<String>()
    private val book = AuditCases.all.filter { it.finalTranslation != null }
        .associate { it.afterEdit!!.lowercase() to it.finalTranslation!! }
    override suspend fun translate(text: String, source: Lang): String {
        calls += text
        return book[text.lowercase()] ?: "[${source.other.short}] $text"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class AuditorHundredTest(private val n: Int, @Suppress("unused") private val label: String) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "audit {0}: {1}")
        fun cases(): List<Array<Any>> = AuditCases.all.mapIndexed { i, c -> arrayOf<Any>(i + 1, c.group) }
    }

    private val c get() = AuditCases.all[n - 1]
    private lateinit var db: HistoryDb

    @Before fun setUp() { db = HistoryDb(ApplicationProvider.getApplicationContext(), null) }
    @After fun tearDown() = db.close()

    @Test fun audit() = runTest {
        val dict = CorrectionDictionary(db)
        c.taught.forEach { (w, r) -> assertTrue(dict.learn(c.lang, w, r)) }
        val auditor = SentenceAuditor(dict)

        if (c.finalTranslation != null) return@runTest fullFlow(dict, auditor)

        // 1. Automatic fixes and flags.
        val result = auditor.audit(c.heard, c.lang, c.alternatives)
        assertEquals("fixed text", c.fixed, result.text)
        assertEquals("flagged words", c.flagged, result.flaggedWords)
        c.suggestions.forEach { (word, expected) ->
            val got = result.issues.filter { it.word == word }.flatMap { it.suggestions }
            assertEquals("suggestions for '$word'", expected, got)
        }
        // Auditing again changes nothing more (stable).
        assertEquals(result.text, auditor.audit(result.text, c.lang).text)
        if (c.fixed == c.heard) assertTrue("nothing changed, so no fixes listed", result.fixes.isEmpty())

        // 2. The user taps one word and types the right one.
        if (c.edit != null) {
            val (wrong, right) = c.edit!!
            val record = TranslationRecord(0, 1, 1, c.lang, result.text, "[old]")
            val saved = record.copy(id = db.save(record))
            val index = SentenceWords.cores(saved.original).indexOf(wrong)
            assertTrue("'$wrong' is a tappable word", index >= 0)
            val book = FixBook()
            val updated = Corrector(book, db, dict).replaceWord(saved, index, right, c.learn)
            assertEquals(c.afterEdit, updated.original)
            assertEquals(c.afterEdit, db.byId(saved.id)!!.original)        // saved entry updated, same id
            assertEquals(1, db.count())                                     // not saved twice
            assertEquals(listOf(c.afterEdit), book.calls)                   // only the fixed sentence re-translated
            // 3. Next time the same mistake is fixed automatically.
            c.thenHeard?.let { (later, expected) -> assertEquals(expected, auditor.audit(later, c.lang).text) }
            // The fix survives an app restart.
            if (c.learn) assertTrue(CorrectionDictionary(db).all().any { it.wrong == wrong.lowercase() && it.right == right })
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.fullFlow(dict: CorrectionDictionary, auditor: SentenceAuditor) {
        val book = FixBook()
        val delivered = mutableListOf<TranslationRecord>()
        val engine = ConversationEngine(
            this, book, db, sessionId = 1, clock = { testScheduler.currentTime },
            listener = object : ConversationEngine.Listener {
                override fun onTranslated(record: TranslationRecord) { delivered += record }
            },
            auditor = auditor,
        )
        engine.onUtterance(c.heard, ListenMode.AUTO, c.lang, c.lang)
        runCurrent(); advanceTimeBy(5000); runCurrent()
        assertEquals(1, delivered.size)
        val first = delivered[0]
        assertEquals("audited before translating", c.fixed, first.original)
        assertEquals(c.lang, first.source)
        assertEquals(listOf(c.fixed), book.calls)                           // translator got the audited text

        val (wrong, right) = c.edit!!
        val index = SentenceWords.cores(first.original).indexOf(wrong)
        val fixed = Corrector(book, db, dict).replaceWord(first, index, right, c.learn)
        assertEquals(c.afterEdit, fixed.original)
        assertEquals(c.finalTranslation, fixed.translated)
        val stored = db.all().single()
        assertEquals(c.afterEdit, stored.original)
        assertEquals(c.finalTranslation, stored.translated)
        assertEquals(first.id, stored.id)
        assertEquals("learned only when asked", c.learn, dict.all().any { it.wrong == wrong.lowercase() })
    }
}

/** Checks on the 100 cases themselves, the database upgrade, and no false alarms on the 200 earlier sentences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditorSafetyTest {
    @Test fun hundredCasesAllDifferentSentences() {
        assertEquals(100, AuditCases.all.size)
        val sentences = AuditCases.all.map { it.heard } + AuditCases.all.mapNotNull { it.thenHeard?.first }
        assertEquals("a sentence is repeated", sentences.size, sentences.map { it.lowercase() }.toSet().size)
        assertEquals(10, AuditCases.all.groupBy { it.group }.size)
        assertTrue(AuditCases.all.groupBy { it.group }.values.all { it.size == 10 })
    }

    @Test fun noFalseAlarmsOnTwoHundredCorrectSentences() {
        val auditor = SentenceAuditor()
        val problems = mutableListOf<String>()
        SentenceData.spanish.forEach { p ->
            val r = auditor.audit(p.said, ES)
            if (r.text != p.said || r.issues.isNotEmpty()) problems += "ES '${p.said}' -> '${r.text}' ${r.flaggedWords}"
        }
        SentenceData.english.forEach { p ->
            val r = auditor.audit(p.said, EN)
            if (r.text != p.said || r.issues.isNotEmpty()) problems += "EN '${p.said}' -> '${r.text}' ${r.flaggedWords}"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Round 2: 100 new correct sentences full of tricky words. The auditor must leave every one alone. */
    @Test fun roundTwoHundredNewCorrectSentencesUntouched() {
        fun load(n: String) = javaClass.classLoader!!.getResourceAsStream(n)!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val es = load("round2_spanish.txt")
        val en = load("round2_english.txt")
        assertEquals(100, es.size + en.size)
        val earlier = (SentenceData.spanish + SentenceData.english).map { it.said.lowercase() } +
            AuditCases.all.map { it.heard.lowercase() }
        val all = (es + en).map { it.lowercase() }
        assertEquals("round 2 repeats a sentence", 100, all.toSet().size)
        assertTrue("round 2 reuses an earlier sentence", all.none { it in earlier })
        val auditor = SentenceAuditor()
        val problems = es.mapNotNull { s -> auditor.audit(s, ES).takeIf { it.text != s || it.issues.isNotEmpty() }?.let { "ES '$s' -> '${it.text}' ${it.flaggedWords}" } } +
            en.mapNotNull { s -> auditor.audit(s, EN).takeIf { it.text != s || it.issues.isNotEmpty() }?.let { "EN '$s' -> '${it.text}' ${it.flaggedWords}" } }
        assertTrue("${problems.size} false alarms:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * Round 3: 100 new mistakes. Each correct sentence from the conversation tests gets one recognizer-style
     * mistake injected; the auditor must repair it exactly, or point at exactly the wrong word.
     */
    @Test fun roundThreeHundredInjectedMistakesCaught() {
        val auditor = SentenceAuditor()
        val accentless = SentenceAuditor.accentFixes.entries.associate { (plain, accented) -> accented to plain }
        val problems = mutableListOf<String>()
        val made = mutableSetOf<String>()
        var count = 0
        val kinds = mutableMapOf<String, Int>()
        var kind = ""
        val pool = SentenceData.spanish.map { it.said to ES } + SentenceData.english.map { it.said to EN }
        for ((k, entry) in pool.withIndex()) {
            val (good, lang) = entry
            val words = good.split(" ")
            val coreOf = { w: String -> SentenceWords.cores(w).firstOrNull()?.lowercase() ?: "" }
            // Mistake A (Spanish, when possible): drop an accent the auditor knows.
            val accentAt = if (lang == ES) words.indexOfFirst { coreOf(it) in accentless } else -1
            // Mistake B: a small word said twice.
            val doubleAt = words.indexOfFirst { coreOf(it) in SentenceAuditor.removableDoubles && it == it.trimEnd(',', '?', '.') && coreOf(it) == it.lowercase() }
            val bad: String
            val expectText: String
            val expectFlag: List<String>
            when {
                accentAt >= 0 -> {
                    val w = words[accentAt]; val core = coreOf(w)
                    bad = words.toMutableList().also { it[accentAt] = w.replace(core, accentless[core]!!, ignoreCase = true).let { r -> if (w[0].isUpperCase()) SentenceWords.capitalize(r) else r } }.joinToString(" ")
                    expectText = good; expectFlag = emptyList(); kind = "missing accent"
                }
                doubleAt >= 0 && k % 3 != 2 -> {
                    bad = words.toMutableList().also { it.add(doubleAt, words[doubleAt]) }.joinToString(" ")
                    expectText = good; expectFlag = emptyList(); kind = "word said twice"
                }
                else -> {
                    val foreign = if (lang == ES) "the" else "los"
                    val at = minOf(2, words.size - 1)
                    bad = words.toMutableList().also { it.add(at, foreign) }.joinToString(" ")
                    expectText = bad; expectFlag = listOf(foreign); kind = "other-language word"
                }
            }
            if (bad == good || !made.add(bad.lowercase())) continue
            val r = auditor.audit(bad, lang)
            if (r.text != expectText || r.flaggedWords != expectFlag) problems += "$lang '$bad' -> '${r.text}' ${r.flaggedWords} (expected '$expectText' $expectFlag)"
            kinds[kind] = (kinds[kind] ?: 0) + 1
            if (++count == 100) break
        }
        assertEquals(100, count)
        println("Round 3 mix: $kinds")
        assertTrue("every mistake type well covered: $kinds", kinds.size == 3 && kinds.values.all { it >= 15 })
        assertTrue("${problems.size} missed:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun deleteAWordAndEditTheWholeSentence() = kotlinx.coroutines.test.runTest {
        val db = HistoryDb(ApplicationProvider.getApplicationContext(), null)
        val book = FixBook()
        val rec = TranslationRecord(0, 1, 1, EN, "Please um close the door", "x")
        val saved = rec.copy(id = db.save(rec))
        val corrector = Corrector(book, db, CorrectionDictionary())
        val noUm = corrector.replaceWord(saved, 1, "", learn = false)
        assertEquals("Please close the door", noUm.original)
        val rewritten = corrector.rewrite(noUm, "please lock the door")
        assertEquals("Please lock the door", rewritten.original)
        assertEquals("Please lock the door", db.byId(saved.id)!!.original)
        assertEquals("Close the door", SentenceWords.replace("Please close the door", 0, ""))
        assertEquals("¿Dónde está?", SentenceWords.replace("¿Dónde está la?", 2, ""))
        db.close()
    }

    @Test fun learnedFixCanBeForgotten() {
        val dict = CorrectionDictionary()
        assertTrue(dict.learn(ES, "tore", "torre"))
        assertFalse(dict.learn(ES, "torre", "Torre"))  // same word: nothing to learn
        assertFalse(dict.learn(ES, " ", "torre"))
        assertEquals("La torre", dict.apply("La tore", ES))
        assertEquals("The tore", dict.apply("The tore", EN))  // Spanish fix does not touch English
        assertEquals("La toreada", dict.apply("La toreada", ES)) // whole words only
        dict.forget(dict.all().single())
        assertEquals("La tore", dict.apply("La tore", ES))
    }

    @Test fun upgradeFromVersion12KeepsSavedTranslations() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Build a database exactly like app 1.0–1.2 made it.
        val old = object : android.database.sqlite.SQLiteOpenHelper(ctx, "upgrade.db", null, 1) {
            override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE translations (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                        "session INTEGER NOT NULL, src TEXT NOT NULL, original TEXT NOT NULL, translated TEXT NOT NULL)"
                )
                db.execSQL("INSERT INTO translations (ts, session, src, original, translated) VALUES (5, 1, 'ES', 'Hola jefe', 'Hello boss')")
            }
            override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, o: Int, n: Int) {}
        }
        old.writableDatabase.close(); old.close()
        HistoryDb(ctx, "upgrade.db").use { db ->
            val rows = db.all()
            assertEquals(listOf("Hola jefe" to "Hello boss"), rows.map { it.original to it.translated })
            assertTrue(rows[0].alternatives.isEmpty())
            db.saveCorrection(com.abhi.vozconversa.core.Correction(ES, "tore", "torre"))
            assertEquals(1, db.loadCorrections().size)
        }
    }
}
