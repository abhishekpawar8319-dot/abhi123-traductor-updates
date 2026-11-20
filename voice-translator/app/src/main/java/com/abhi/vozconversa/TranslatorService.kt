package com.abhi.vozconversa

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.abhi.vozconversa.core.ConversationEngine
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.ListenMode
import com.abhi.vozconversa.core.RestartPolicy
import com.abhi.vozconversa.core.Retention
import com.abhi.vozconversa.core.TranslationRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Listens continuously (foreground service, survives screen-off), waits for each whole sentence,
 * translates Spanish ⇄ English on the phone, saves it, and optionally speaks the translation.
 */
class TranslatorService : Service() {

    companion object {
        const val ACTION_START = "com.abhi.vozconversa.START"
        const val ACTION_STOP = "com.abhi.vozconversa.STOP"
        const val ACTION_SETTINGS_CHANGED = "com.abhi.vozconversa.SETTINGS"
        private const val CHANNEL = "voz_running"
        private const val NOTIF_ID = 42
        private const val WATCHDOG_MS = 10_000L

        @Volatile var running = false
            private set

        fun start(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, TranslatorService::class.java).setAction(ACTION_START)
        )

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TranslatorService::class.java).setAction(ACTION_STOP))
        }

        fun settingsChanged(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, TranslatorService::class.java).setAction(ACTION_SETTINGS_CHANGED))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var db: HistoryDb
    private val translator by lazy { MlKitTranslator() }
    private var engine: ConversationEngine? = null

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakingCount = 0
    private var focusRequest: AudioFocusRequest? = null

    private var listening = false
    private var listeningLang = Lang.ES
    private var detectedLang: Lang? = null
    private val policy = RestartPolicy()
    private var lastEventAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var modelsReady = false
    private var sentences = 0
    private var lastLine = ""
    private var lastPrune = 0L
    private var languageErrors = 0
    /** Turned off if this phone's recognizer rejects the Android 14 language-detection options. */
    private var useLanguageDetection = true

    private val startRunnable = Runnable { startListening() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        db = HistoryDb.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_SETTINGS_CHANGED -> {
                if (running) { cancelRecognizer(); scheduleListen(150) }
                return START_STICKY
            }
        }
        if (!running) begin()
        return START_STICKY
    }

    private fun begin() {
        // Must call startForeground promptly after startForegroundService.
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(getString(R.string.status_starting)), type)
        } catch (e: Exception) {
            // Android blocks microphone services started from the background (e.g. a restart
            // after the system killed the app). Ask the user for one tap to resume.
            LiveBus.post(LiveBus.Event.Status(false, getString(R.string.status_open_app)))
            if (prefs.wasRunning) BootReceiver.showResume(this)
            stopSelf()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            LiveBus.post(LiveBus.Event.Status(false, getString(R.string.status_need_mic)))
            stopSelf()
            return
        }
        running = true
        prefs.wasRunning = true
        acquireWakeLock()
        val sessionId = System.currentTimeMillis()
        engine = ConversationEngine(scope, translator, db, sessionId, listener = engineListener)
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(ttsListener)
        }
        scope.launch { prepareModels() }
        prune()
        listeningLang = if (prefs.mode == ListenMode.ENGLISH) Lang.EN else Lang.ES
        startListening()
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    private suspend fun prepareModels() {
        var attempt = 0
        while (running && !modelsReady) {
            status(getString(R.string.status_downloading))
            try {
                translator.prepare()
                modelsReady = true
                status(null)
            } catch (e: Exception) {
                attempt++
                status(getString(R.string.status_download_failed))
                delay(minOf(120_000L, 5_000L shl minOf(attempt, 5)))
            }
        }
    }

    // ---------------------------------------------------------------- listening

    private fun recognizerIntent(mode: ListenMode): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, listeningLang.locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // Wait for a real pause before ending the sentence, so whole sentences are captured.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
            if (mode == ListenMode.AUTO) {
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(listeningLang.other.locale))
                if (Build.VERSION.SDK_INT >= 34 && useLanguageDetection) {
                    val both = arrayListOf(Lang.ES.locale, Lang.EN.locale)
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, both)
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, both)
                }
            }
        }

    private fun startListening() {
        main.removeCallbacks(startRunnable)
        if (!running || speakingCount > 0 || listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status(getString(R.string.status_no_recognizer))
            scheduleListen(30_000)
            return
        }
        val mode = prefs.mode
        when (mode) {
            ListenMode.SPANISH -> listeningLang = Lang.ES
            ListenMode.ENGLISH -> listeningLang = Lang.EN
            ListenMode.AUTO -> Unit
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(recognitionListener)
            recognizer = it
        }
        detectedLang = null
        lastEventAt = SystemClock.elapsedRealtime()
        try {
            r.startListening(recognizerIntent(mode))
            listening = true
        } catch (e: Exception) {
            destroyRecognizer()
            scheduleListen(1000)
        }
    }

    private fun scheduleListen(delayMs: Long) {
        main.removeCallbacks(startRunnable)
        main.postDelayed(startRunnable, delayMs)
    }

    private fun cancelRecognizer() {
        listening = false
        runCatching { recognizer?.cancel() }
    }

    private fun destroyRecognizer() {
        listening = false
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { touch(); status(null) }
        override fun onBeginningOfSpeech() = touch()
        override fun onRmsChanged(rmsdB: Float) = touch()
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() = touch()
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            touch()
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) LiveBus.post(LiveBus.Event.Partial(text))
        }

        override fun onLanguageDetection(results: Bundle) {
            touch()
            Lang.fromTag(results.getString(SpeechRecognizer.DETECTED_LANGUAGE))?.let { detectedLang = it }
        }

        override fun onResults(results: Bundle?) {
            touch()
            listening = false
            policy.onSuccess()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            LiveBus.post(LiveBus.Event.Partial(""))
            if (!text.isNullOrBlank()) {
                val mode = prefs.mode
                val source = engine?.onUtterance(text, mode, detectedLang, listeningLang)
                // In a two-person conversation the next sentence is most likely the other language.
                if (mode == ListenMode.AUTO && source != null) listeningLang = source.other
            }
            scheduleListen(50)
        }

        override fun onError(error: Int) {
            touch()
            listening = false
            if (!running) return
            val wait = policy.delayAfterError(error)
            if (wait == RestartPolicy.STOP) {
                status(getString(R.string.status_need_mic))
                shutdown()
                return
            }
            if (policy.isLanguageProblem(error) && prefs.mode == ListenMode.AUTO) {
                languageErrors++
                if (languageErrors >= 2) useLanguageDetection = false
                listeningLang = listeningLang.other
            }
            if (policy.needsRecreate(error)) destroyRecognizer()
            scheduleListen(wait)
        }
    }

    private fun touch() { lastEventAt = SystemClock.elapsedRealtime() }

    /** Restarts the recognizer if it silently stops calling back, and keeps the CPU awake. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val idle = SystemClock.elapsedRealtime() - lastEventAt
            if (speakingCount == 0) {
                if (listening && idle > 25_000) { destroyRecognizer(); startListening() }
                else if (!listening && idle > 15_000) startListening()
            } else if (idle > 60_000) {
                // TTS never reported "done"; recover.
                speakingCount = 0
                startListening()
            }
            if (wakeLock?.isHeld != true) acquireWakeLock()
            if (System.currentTimeMillis() - lastPrune > 6 * 3600_000L) prune()
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    // ---------------------------------------------------------------- results

    private val engineListener = object : ConversationEngine.Listener {
        override fun onTranslated(record: TranslationRecord) = deliver(record)
        override fun onError(message: String, record: TranslationRecord) {
            deliver(record)
            status(getString(R.string.status_translate_failed))
        }
    }

    private fun deliver(record: TranslationRecord) {
        sentences++
        lastLine = record.translated.ifBlank { record.original }
        LiveBus.post(LiveBus.Event.Translated(record))
        updateNotification()
        if (prefs.speakAloud && ttsReady && record.translated.isNotBlank()) speak(record)
    }

    private fun speak(record: TranslationRecord) {
        val t = tts ?: return
        // Pause the microphone so the phone does not translate its own voice.
        speakingCount++
        cancelRecognizer()
        main.removeCallbacks(startRunnable)
        requestDuckingFocus()
        t.language = Locale.forLanguageTag(record.target.locale)
        t.speak(record.translated, TextToSpeech.QUEUE_ADD, null, "utt-${record.id}")
    }

    private val ttsListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { main.post { touch() } }
        override fun onDone(utteranceId: String?) { main.post { onSpoken() } }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { main.post { onSpoken() } }
    }

    private fun onSpoken() {
        touch()
        speakingCount = (speakingCount - 1).coerceAtLeast(0)
        if (speakingCount == 0) {
            abandonFocus()
            scheduleListen(250)
        }
    }

    private fun requestDuckingFocus() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                ).build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonFocus() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    // ---------------------------------------------------------------- housekeeping

    private fun prune() {
        lastPrune = System.currentTimeMillis()
        val cutoff = Retention.cutoff(lastPrune, prefs.retentionDays) ?: return
        scope.launch(Dispatchers.IO) { runCatching { db.deleteOlderThan(cutoff) } }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = (wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VozTraductor:listen")).also {
            it.setReferenceCounted(false)
            it.acquire(2 * 3600_000L)
        }
    }

    private fun status(text: String?) {
        val s = text ?: when (listeningLang) {
            Lang.ES -> getString(R.string.status_listening_es)
            Lang.EN -> getString(R.string.status_listening_en)
        }.let { if (prefs.mode == ListenMode.AUTO) getString(R.string.status_listening_auto) else it }
        LiveBus.post(LiveBus.Event.Status(running, s))
    }

    private fun shutdown() {
        val wasRunning = running
        running = false
        prefs.wasRunning = false
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        abandonFocus()
        LiveBus.post(LiveBus.Event.Status(false, getString(R.string.status_stopped)))
        if (!wasRunning) { stopSelf(); return }
        // Translate and save anything still held before stopping.
        scope.launch {
            runCatching { engine?.drain() }
            runCatching { wakeLock?.release() }
            ServiceCompat.stopForeground(this@TranslatorService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (running) {
            running = false
            main.removeCallbacksAndMessages(null)
            destroyRecognizer()
            runCatching { tts?.shutdown() }
            engine?.flush()
        }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { translator.close() }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Low importance: no sound, no vibration, no pop-up.
            val ch = NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TranslatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title, sentences))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(lastLine))
    }
}
