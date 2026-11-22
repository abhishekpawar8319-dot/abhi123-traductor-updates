package com.abhi.vozconversa

import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import com.abhi.vozconversa.core.ConversationEngine
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.Lang.EN
import com.abhi.vozconversa.core.Lang.ES
import com.abhi.vozconversa.core.ListenMode
import com.abhi.vozconversa.core.OfflineController
import com.abhi.vozconversa.core.OfflineController.Reaction
import com.abhi.vozconversa.core.OfflineReadiness
import com.abhi.vozconversa.core.RecognizerSetup
import com.abhi.vozconversa.core.RestartPolicy
import com.abhi.vozconversa.core.SentenceAuditor
import com.abhi.vozconversa.core.TextTranslator
import com.abhi.vozconversa.core.TranslationRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Online/offline decisions (pure logic). */
class OfflineControllerTest {
    private var now = 0L
    private fun ctl(sdk: Int = 34) = OfflineController(sdk) { now }

    @Test fun onlineUsesGoogleWithAutoDetect() {
        val c = ctl()
        assertFalse(c.offline)
        assertEquals(RecognizerSetup(onDevice = false, preferOffline = false, multiLanguageExtras = true), c.setup(ES))
    }

    @Test fun losingInternetSwitchesToOfflineAndBack() {
        val c = ctl()
        assertTrue("change reported", c.networkChanged(false))
        assertTrue(c.offline)
        val s = c.setup(EN)
        assertTrue(s.preferOffline)
        assertFalse("offline packs cannot auto-detect", s.multiLanguageExtras)
        assertFalse("no change", c.networkChanged(false))
        assertTrue(c.networkChanged(true))
        assertFalse(c.setup(EN).preferOffline)
    }

    @Test fun networkErrorWhileAndroidSaysOnlineGoesOfflineAtOnceForTwoMinutes() {
        // e.g. Wi-Fi without internet, or one bar of signal on site
        val c = ctl()
        val online = c.setup(ES)
        assertEquals(Reaction.RETRY_OFFLINE_NOW, c.onError(RestartPolicy.ERROR_NETWORK, ES, online))
        assertTrue(c.offline)
        assertTrue(c.setup(ES).preferOffline)
        now += OfflineController.FORCED_OFFLINE_MS - 1
        assertTrue(c.offline)
        now += 2
        assertFalse("tries online again after 2 minutes", c.offline)
        listOf(RestartPolicy.ERROR_NETWORK_TIMEOUT, RestartPolicy.ERROR_SERVER, RestartPolicy.ERROR_SERVER_DISCONNECTED).forEach {
            now += OfflineController.FORCED_OFFLINE_MS + 1
            assertEquals(Reaction.RETRY_OFFLINE_NOW, ctl().onError(it, ES, online))
        }
    }

    @Test fun onDeviceRecognizerUsedOnlyForInstalledLanguages() {
        val c = ctl(sdk = 34)
        c.onDeviceAvailable = true
        c.installedOnDevice = setOf(ES)
        c.networkChanged(false)
        assertTrue(c.setup(ES).onDevice)
        assertFalse(c.setup(EN).onDevice)
        assertTrue(c.setup(EN).preferOffline)
    }

    @Test fun oldAndroidNeverUsesOnDeviceRecognizer() {
        val c = ctl(sdk = 30)
        c.onDeviceAvailable = true
        c.installedOnDevice = setOf(ES, EN)
        c.networkChanged(false)
        assertFalse(c.setup(ES).onDevice)
        assertTrue(c.setup(ES).preferOffline)
    }

    @Test fun missingPackFallsBackThenReportsThenRecovers() {
        val c = ctl(sdk = 34)
        c.onDeviceAvailable = true
        c.installedOnDevice = setOf(ES, EN)
        c.networkChanged(false)
        // On-device model unusable -> retry with Google's offline pack
        assertEquals(Reaction.RETRY_OFFLINE_NOW, c.onError(RestartPolicy.ERROR_LANGUAGE_UNAVAILABLE, EN, c.setup(EN)))
        assertFalse(c.setup(EN).onDevice)
        // That fails too -> English offline speech is missing
        assertEquals(Reaction.OFFLINE_PACK_MISSING, c.onError(RestartPolicy.ERROR_NETWORK, EN, c.setup(EN)))
        assertEquals(setOf(EN), c.missingOffline)
        assertEquals("Auto mode keeps listening in Spanish", ES, c.usableLanguage(EN))
        assertEquals(ES, c.usableLanguage(ES))
        // Spanish also fails
        c.installedOnDevice = emptySet()
        assertEquals(Reaction.OFFLINE_PACK_MISSING, c.onError(RestartPolicy.ERROR_LANGUAGE_NOT_SUPPORTED, ES, c.setup(ES)))
        assertNull(c.usableLanguage(ES))
        // A sentence recognized offline proves the pack works
        c.onSuccess(ES)
        assertEquals(ES, c.usableLanguage(EN))
        // Internet back: everything is tried again
        c.networkChanged(true)
        assertTrue(c.missingOffline.isEmpty())
    }

    @Test fun ordinaryErrorsAreNotTreatedAsNetwork() {
        val c = ctl()
        listOf(RestartPolicy.ERROR_NO_MATCH, RestartPolicy.ERROR_SPEECH_TIMEOUT, RestartPolicy.ERROR_AUDIO,
            RestartPolicy.ERROR_RECOGNIZER_BUSY, RestartPolicy.ERROR_CLIENT).forEach {
            assertEquals(Reaction.NOT_NETWORK, c.onError(it, ES, c.setup(ES)))
        }
        // Online language error is the existing Auto-detect fallback, not an offline problem.
        assertEquals(Reaction.NOT_NETWORK, c.onError(RestartPolicy.ERROR_LANGUAGE_NOT_SUPPORTED, ES, c.setup(ES)))
        assertFalse(c.offline)
    }

    @Test fun readinessSummary() {
        val all = OfflineReadiness(true, mapOf(ES to true, EN to true), mapOf(ES to true, EN to true))
        assertTrue(all.ready)
        assertTrue(all.missing().isEmpty())
        val unknownOldPhone = OfflineReadiness(true, mapOf(ES to null, EN to null), mapOf(ES to true, EN to true))
        assertTrue("can't check on old Android: not blocking", unknownOldPhone.ready)
        val some = OfflineReadiness(false, mapOf(ES to true, EN to false), mapOf(ES to false, EN to true))
        assertFalse(some.ready)
        assertEquals(listOf("translation pack", "English speech", "Spanish voice"), some.missing())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineAndroidTest {

    @Test fun offlineRequestUsesStoredSpeechPackAndNoOnlineOnlyOptions() {
        val off = buildRecognizerIntent("p", ES, ListenMode.AUTO, RecognizerSetup(false, true, false), true, sdk = 34)
        assertTrue(off.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertFalse(off.hasExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES"))
        assertFalse(off.hasExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION))
        assertFalse(off.hasExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH))
        assertEquals("es-ES", off.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(3, off.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))

        val on = buildRecognizerIntent("p", EN, ListenMode.AUTO, RecognizerSetup(false, false, true), true, sdk = 34)
        assertFalse(on.hasExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE))
        assertTrue(on.hasExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION))
        assertTrue(on.hasExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES"))

        val locked = buildRecognizerIntent("p", EN, ListenMode.ENGLISH, RecognizerSetup(false, false, true), true, sdk = 34)
        assertFalse(locked.hasExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES"))
    }

    private fun voice(name: String, tag: String, network: Boolean, quality: Int = Voice.QUALITY_NORMAL, notInstalled: Boolean = false) =
        Voice(name, Locale.forLanguageTag(tag), quality, Voice.LATENCY_NORMAL, network,
            if (notInstalled) setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) else emptySet())

    @Test fun speakerPicksAVoiceStoredOnThePhoneWhenOffline() {
        val voices = listOf(
            voice("es-net-best", "es-ES", network = true, quality = Voice.QUALITY_VERY_HIGH),
            voice("es-local", "es-ES", network = false),
            voice("es-mx-local", "es-MX", network = false, quality = Voice.QUALITY_HIGH),
            voice("en-net", "en-US", network = true),
            voice("en-local-missing", "en-US", network = false, notInstalled = true),
            voice("en-gb-local", "en-GB", network = false),
        )
        assertEquals("es-local", TtsVoices.pick(voices, ES, online = false)!!.name)       // same country, on phone
        assertEquals("en-gb-local", TtsVoices.pick(voices, EN, online = false)!!.name)    // only installed offline English
        assertEquals("es-local", TtsVoices.pick(voices, ES, online = true)!!.name)        // on-phone voice first even online
        assertEquals("en-net", TtsVoices.pick(voices, EN, online = true)!!.name)          // US voice when online
        assertNull(TtsVoices.pick(listOf(voice("es-net", "es-ES", network = true)), ES, online = false))
        assertNull(TtsVoices.pick(null, ES, online = false))
    }
}

/**
 * A conversation while the internet goes away and comes back, run through the same decisions as the
 * listening service. Every sentence is different. The phone has the offline speech and translation packs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineConversationTest {

    /** Translation pack on the phone: works with or without internet. */
    private class OnPhoneTranslator : TextTranslator {
        private val book = mapOf(
            "La señal se fue en la montaña" to "The signal went out on the mountain",
            "No hay internet en la torre veinte" to "There is no internet at tower twenty",
            "We still need to finish the jumper cables" to "Todavía tenemos que terminar los cables puente",
            "Sube con cuidado por la escalera" to "Climb the ladder carefully",
            "The radio battery is getting low" to "La batería del radio se está acabando",
            "Bajamos a comer a las dos" to "We go down to eat at two",
            "Did the signal come back yet?" to "¿Ya volvió la señal?",
            "Ya tenemos señal otra vez" to "We have signal again",
        )
        override suspend fun translate(text: String, source: Lang) = book[text] ?: error("unexpected: $text")
    }

    private data class Heard(val text: String, val lang: Lang)

    @Test fun conversationKeepsGoingWhenInternetDropsAndReturns() = runTest {
        var clock = 0L
        val ctl = OfflineController(34) { clock }
        ctl.onDeviceAvailable = true
        ctl.installedOnDevice = setOf(ES, EN)
        val db = HistoryDb(ApplicationProvider.getApplicationContext(), null)
        val saved = mutableListOf<TranslationRecord>()
        val engine = ConversationEngine(
            this, OnPhoneTranslator(), db, 1, clock = { testScheduler.currentTime },
            listener = object : ConversationEngine.Listener { override fun onTranslated(record: TranslationRecord) { saved += record } },
            auditor = SentenceAuditor(),
        )
        // What the phone's recognizer does: the online one needs internet, offline ones need the pack.
        var internet = true
        fun recognize(setup: RecognizerSetup, lang: Lang): Int? = when {
            !setup.preferOffline && !internet -> RestartPolicy.ERROR_NETWORK
            setup.preferOffline && !setup.onDevice && lang !in setOf(ES, EN) -> RestartPolicy.ERROR_LANGUAGE_UNAVAILABLE
            else -> null
        }

        val script = listOf(
            "online" to Heard("La señal se fue en la montaña", ES),
            "drop" to Heard("No hay internet en la torre veinte", ES),    // network lost, Android not told yet
            "offline" to Heard("We still need to finish the jumper cables", EN),
            "offline" to Heard("Sube con cuidado por la escalera", ES),
            "offline" to Heard("The radio battery is getting low", EN),
            "offline" to Heard("Bajamos a comer a las dos", ES),
            "offline" to Heard("Did the signal come back yet?", EN),
            "back" to Heard("Ya tenemos señal otra vez", ES),
        )
        var longestWaitMs = 0L
        val usedOnDevice = mutableListOf<Boolean>()
        for ((phase, h) in script) {
            when (phase) {
                "drop" -> internet = false                                   // Android still says online
                "back" -> { internet = true; ctl.networkChanged(true) }
            }
            var waited = 0L
            var tries = 0
            while (true) {
                val setup = ctl.setup(h.lang)
                val err = recognize(setup, h.lang)
                if (err == null) {
                    ctl.onSuccess(h.lang)
                    usedOnDevice += setup.onDevice
                    engine.onUtterance(h.text, ListenMode.AUTO, h.lang, h.lang)
                    break
                }
                val reaction = ctl.onError(err, h.lang, setup)
                assertEquals("phase $phase", Reaction.RETRY_OFFLINE_NOW, reaction)
                waited += 200                                                 // the service retries after 200 ms
                assertTrue("stuck", ++tries < 3)
            }
            longestWaitMs = maxOf(longestWaitMs, waited)
            clock += 6_000
            if (phase == "offline" && h.text.startsWith("The radio")) ctl.networkChanged(false) // Android notices
            runCurrent(); advanceTimeBy(3_000); runCurrent()
        }

        assertEquals(8, saved.size)
        assertEquals(script.map { it.second.text }, saved.map { it.original })
        assertTrue(saved.all { it.translated.isNotBlank() })
        assertEquals(8, db.count())
        assertTrue("never waits long when the internet drops: $longestWaitMs ms", longestWaitMs <= 200)
        assertEquals(listOf(false, true, true, true, true, true, true, false), usedOnDevice)
        db.close()
    }
}
