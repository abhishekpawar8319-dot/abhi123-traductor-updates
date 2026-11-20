package com.abhi.vozconversa

import com.abhi.vozconversa.core.DirectionDecider
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.LanguageGuesser
import com.abhi.vozconversa.core.ListenMode
import com.abhi.vozconversa.core.RestartPolicy
import com.abhi.vozconversa.core.Retention
import com.abhi.vozconversa.core.SentenceText
import com.abhi.vozconversa.core.TranscriptExporter
import com.abhi.vozconversa.core.TranslationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CoreLogicTest {

    @Test fun guessesSpanish() {
        listOf(
            "¿Dónde está el baño?", "Hola, buenos días señor", "Necesito agua por favor",
            "Mañana vamos a trabajar en la torre", "Gracias, muy amable",
        ).forEach { assertEquals(it, Lang.ES, LanguageGuesser.guess(it)) }
    }

    @Test fun guessesEnglish() {
        listOf(
            "Where is the bathroom?", "Good morning sir", "I need water please",
            "Tomorrow we are going to work on the tower", "Thank you very much",
        ).forEach { assertEquals(it, Lang.EN, LanguageGuesser.guess(it)) }
    }

    @Test fun unclearTextGivesNoGuess() {
        assertNull(LanguageGuesser.guess("Pedro"))
        assertNull(LanguageGuesser.guess("12 345"))
    }

    @Test fun lockedModesIgnoreText() {
        assertEquals(Lang.ES, DirectionDecider.sourceOf("Hello friend", ListenMode.SPANISH, Lang.EN, Lang.EN))
        assertEquals(Lang.EN, DirectionDecider.sourceOf("Hola amigo", ListenMode.ENGLISH, Lang.ES, Lang.ES))
    }

    @Test fun autoModeUsesRecognizerThenTextThenListening() {
        // Recognizer detection is used when the text is ambiguous.
        assertEquals(Lang.EN, DirectionDecider.sourceOf("Pedro", ListenMode.AUTO, Lang.EN, Lang.ES))
        // Strong text evidence beats a wrong detection.
        assertEquals(Lang.ES, DirectionDecider.sourceOf("¿Dónde está el señor?", ListenMode.AUTO, Lang.EN, Lang.EN))
        // No detection: use the text.
        assertEquals(Lang.EN, DirectionDecider.sourceOf("Where is the truck", ListenMode.AUTO, null, Lang.ES))
        // Nothing to go on: the language the recognizer was listening for.
        assertEquals(Lang.ES, DirectionDecider.sourceOf("Pedro", ListenMode.AUTO, null, Lang.ES))
    }

    @Test fun fromTagMapsRegions() {
        assertEquals(Lang.ES, Lang.fromTag("es-MX"))
        assertEquals(Lang.EN, Lang.fromTag("en_GB"))
        assertNull(Lang.fromTag("fr-FR"))
        assertNull(Lang.fromTag(null))
    }

    @Test fun normalizeAndNoise() {
        assertEquals("Hola amigo", SentenceText.normalize("  hola   amigo "))
        assertTrue(SentenceText.isNoise("mm"))
        assertTrue(SentenceText.isNoise("  ...  "))
        assertTrue(SentenceText.isNoise("Eh."))
        assertFalse(SentenceText.isNoise("Sí"))
        assertFalse(SentenceText.isNoise("Yes"))
    }

    @Test fun detectsUnfinishedSentences() {
        assertTrue(SentenceText.looksUnfinished("Quiero ir a la tienda y"))
        assertTrue(SentenceText.looksUnfinished("I went to the"))
        assertTrue(SentenceText.looksUnfinished("Necesito que,"))
        assertFalse(SentenceText.looksUnfinished("Quiero ir a la tienda"))
        assertFalse(SentenceText.looksUnfinished("I went home."))
        assertFalse(SentenceText.looksUnfinished("Where are you going?"))
    }

    @Test fun joinsFragments() {
        assertEquals("Quiero ir a la tienda y comprar pan", SentenceText.join("Quiero ir a la tienda y", "Comprar pan"))
        assertEquals("I went to the store", SentenceText.join("I went to the", "store"))
        assertEquals("Tell him and I will go", SentenceText.join("Tell him and", "I will go"))
    }

    @Test fun silenceRestartsImmediatelyErrorsBackOff() {
        val p = RestartPolicy()
        assertEquals(100L, p.delayAfterError(RestartPolicy.ERROR_NO_MATCH))
        assertEquals(100L, p.delayAfterError(RestartPolicy.ERROR_SPEECH_TIMEOUT))
        val d1 = p.delayAfterError(RestartPolicy.ERROR_NETWORK)
        val d2 = p.delayAfterError(RestartPolicy.ERROR_NETWORK)
        val d3 = p.delayAfterError(RestartPolicy.ERROR_NETWORK)
        assertTrue(d1 < d2 && d2 < d3)
        repeat(20) { p.delayAfterError(RestartPolicy.ERROR_SERVER) }
        assertEquals(RestartPolicy.MAX_BACKOFF, p.delayAfterError(RestartPolicy.ERROR_SERVER))
        assertTrue(p.needsRecreate(RestartPolicy.ERROR_NETWORK)) // after many failures
        p.onSuccess()
        assertEquals(1000L, p.delayAfterError(RestartPolicy.ERROR_NETWORK))
        assertEquals(RestartPolicy.STOP, p.delayAfterError(RestartPolicy.ERROR_INSUFFICIENT_PERMISSIONS))
    }

    @Test fun retentionCutoff() {
        val now = 100L * 24 * 3600_000
        assertNull(Retention.cutoff(now, 0))
        assertEquals(now - 30L * 24 * 3600_000, Retention.cutoff(now, 30))
    }

    @Test fun exportsTextAndCsv() {
        val utc = TimeZone.getTimeZone("UTC")
        val recs = listOf(
            TranslationRecord(1, 3_600_000, 1, Lang.EN, "Hello, \"boss\"", "Hola, \"jefe\""),
            TranslationRecord(2, 0, 1, Lang.ES, "Hola", "Hello"),
            TranslationRecord(3, 90_000_000, 1, Lang.ES, "Adiós", ""),
        )
        val txt = TranscriptExporter.toText(recs, utc)
        assertTrue(txt.indexOf("Hola\n") < txt.indexOf("Hello, \"boss\""))  // sorted by time
        assertTrue(txt.contains("=== Thursday 1 January 1970 ==="))
        assertTrue(txt.contains("=== Friday 2 January 1970 ==="))
        assertTrue(txt.contains("[01:00:00] EN → ES"))
        assertTrue(txt.contains("(not translated)"))
        val csv = TranscriptExporter.toCsv(recs, utc)
        assertTrue(csv.contains("1970-01-01 01:00:00,EN,ES,\"Hello, \"\"boss\"\"\",\"Hola, \"\"jefe\"\"\""))
        assertEquals(4, csv.trim().lines().size)
    }
}
