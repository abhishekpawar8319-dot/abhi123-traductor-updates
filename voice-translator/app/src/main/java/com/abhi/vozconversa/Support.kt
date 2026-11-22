package com.abhi.vozconversa

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.ListenMode
import com.abhi.vozconversa.core.TextTranslator
import com.abhi.vozconversa.core.TranslationRecord
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.CopyOnWriteArraySet

class Prefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("voz", Context.MODE_PRIVATE)

    var mode: ListenMode
        get() = runCatching { ListenMode.valueOf(p.getString("mode", null) ?: "AUTO") }.getOrDefault(ListenMode.AUTO)
        set(v) = p.edit().putString("mode", v.name).apply()

    var speakAloud: Boolean
        get() = p.getBoolean("speak", true)
        set(v) = p.edit().putBoolean("speak", v).apply()

    /** 0 = keep forever. */
    var retentionDays: Int
        get() = p.getInt("retention", 365)
        set(v) = p.edit().putInt("retention", v).apply()

    var wasRunning: Boolean
        get() = p.getBoolean("running", false)
        set(v) = p.edit().putBoolean("running", v).apply()
}

/** In-process events from the service to whatever screen is open. */
object LiveBus {
    sealed class Event {
        data class Status(val running: Boolean, val text: String) : Event()
        data class Partial(val text: String) : Event()
        data class Translated(val record: TranslationRecord) : Event()
        data class Speaker(val on: Boolean) : Event()
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Event) -> Unit>()

    @Volatile var lastStatus = Event.Status(false, "")
        private set

    fun post(e: Event) {
        if (e is Event.Status) lastStatus = e
        main.post { listeners.forEach { it(e) } }
    }

    fun add(l: (Event) -> Unit) { listeners += l }
    fun remove(l: (Event) -> Unit) { listeners -= l }
}

/** On-device Google ML Kit translation. One language pack (~30 MB) is downloaded once. */
class MlKitTranslator : TextTranslator {
    private val esEn = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.SPANISH)
            .setTargetLanguage(TranslateLanguage.ENGLISH).build()
    )
    private val enEs = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.SPANISH).build()
    )

    private val spanishModel = TranslateRemoteModel.Builder(TranslateLanguage.SPANISH).build()
    @Volatile private var packOnPhone = false

    /** True when the Spanish–English pack is stored on the phone (then no internet is needed). */
    suspend fun isReady(): Boolean {
        if (!packOnPhone) {
            packOnPhone = runCatching { RemoteModelManager.getInstance().isModelDownloaded(spanishModel).await() }.getOrDefault(false)
        }
        return packOnPhone
    }

    /** Downloads the pack once (needs internet). Does nothing if it is already on the phone. */
    suspend fun prepare() {
        if (isReady()) return
        val any = DownloadConditions.Builder().build()
        esEn.downloadModelIfNeeded(any).await()
        enEs.downloadModelIfNeeded(any).await()
        packOnPhone = true
    }

    override suspend fun translate(text: String, source: Lang): String {
        val client = if (source == Lang.ES) esEn else enEs
        // Pack on the phone: translate straight away, fully offline. Otherwise try to fetch it first.
        if (!isReady()) prepare()
        return client.translate(text).await()
    }

    fun close() {
        esEn.close()
        enEs.close()
    }
}

/** Reads a single translation aloud when the listening service is not running. */
class Speaker(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var queued: Pair<String, Lang>? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            queued?.let { (t, l) -> queued = null; say(t, l) }
        }
    }

    fun say(text: String, lang: Lang) {
        val t = tts ?: return
        if (!ready) { queued = text to lang; return }
        TtsVoices.applyTo(t, lang, online = Net.isOnline(appContext))
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "replay")
    }

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
    }
}
