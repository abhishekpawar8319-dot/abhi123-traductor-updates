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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import com.abhi.vozconversa.core.CaptureTracker
import com.abhi.vozconversa.core.ConversationEngine
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.ListenMode
import com.abhi.vozconversa.core.RestartPolicy
import com.abhi.vozconversa.core.OfflineController
import com.abhi.vozconversa.core.RecognizerSetup
import com.abhi.vozconversa.core.Retention
import com.abhi.vozconversa.core.SentenceAuditor
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
        const val ACTION_SPEAKER = "com.abhi.vozconversa.SPEAKER"
        const val ACTION_SAY = "com.abhi.vozconversa.SAY"
        const val ACTION_PAUSE = "com.abhi.vozconversa.PAUSE"
        const val ACTION_RESUME = "com.abhi.vozconversa.RESUME"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LANG = "lang"
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

        /** Speaker on/off while running (also from the notification). */
        fun setSpeaker(ctx: Context, on: Boolean) {
            Prefs(ctx).speakAloud = on
            if (running) ctx.startService(Intent(ctx, TranslatorService::class.java).setAction(ACTION_SPEAKER).putExtra("on", on))
        }

        /** Reads one translation aloud while the service is listening (pausing the microphone). */
        fun say(ctx: Context, text: String, lang: Lang) {
            ctx.startService(
                Intent(ctx, TranslatorService::class.java).setAction(ACTION_SAY)
                    .putExtra(EXTRA_TEXT, text).putExtra(EXTRA_LANG, lang.name)
            )
        }

        /** Frees the microphone for a moment (to say one word for a fix), then [resume]. */
        fun pause(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, TranslatorService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, TranslatorService::class.java).setAction(ACTION_RESUME))
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
    private var lastRetry = 0L
    private var languageErrors = 0
    /** Turned off if this phone's recognizer rejects the Android 14 language-detection options. */
    private var useLanguageDetection = true

    private val startRunnable = Runnable { startListening() }
    /** Keeps every word heard, even when a session is cut short. */
    private val capture = CaptureTracker { text, alts -> onHeard(text, alts) }
    /** Continuous (segmented) listening, Android 13+; turned off if this phone's recognizer refuses it. */
    private var useSegmented = Build.VERSION.SDK_INT >= 33
    private var segmentedFailures = 0
    private var sessionHadSpeech = false
    private val offlineCtl = OfflineController(Build.VERSION.SDK_INT)
    private var currentSetup = RecognizerSetup(onDevice = false, preferOffline = false, multiLanguageExtras = true)
    private var recognizerOnDevice = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var paused = false
    private val autoResume = Runnable { if (paused) { paused = false; startListening() } }

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
            ACTION_SPEAKER -> {
                if (running) {
                    val on = if (intent.hasExtra("on")) intent.getBooleanExtra("on", true) else !prefs.speakAloud
                    applySpeaker(on)
                }
                return START_STICKY
            }
            ACTION_PAUSE -> {
                if (running) {
                    paused = true
                    main.removeCallbacks(startRunnable)
                    cancelRecognizer()
                    main.removeCallbacks(autoResume)
                    main.postDelayed(autoResume, 90_000) // never stay deaf if resume is missed
                }
                return START_STICKY
            }
            ACTION_RESUME -> {
                if (running && paused) { paused = false; main.removeCallbacks(autoResume); scheduleListen(400) }
                return START_STICKY
            }
            ACTION_SAY -> {
                if (running) {
                    val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                    val lang = runCatching { Lang.valueOf(intent.getStringExtra(EXTRA_LANG)!!) }.getOrDefault(Lang.EN)
                    if (text.isNotBlank()) speak(text, lang, "say-${SystemClock.elapsedRealtime()}")
                }
                return START_STICKY
            }
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
        engine = ConversationEngine(
            scope, translator, db, sessionId, listener = engineListener,
            auditor = SentenceAuditor(Corrections.dictionary(this)),
            workContext = Dispatchers.IO, // translation never slows down listening
        )
        // Sentences recorded earlier but not translated yet (app closed, pack missing): translate them now.
        scope.launch(Dispatchers.IO) { engine?.resumePending() }
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(ttsListener)
        }
        watchNetwork()
        scope.launch { prepareModels() }
        prune()
        listeningLang = if (prefs.mode == ListenMode.ENGLISH) Lang.EN else Lang.ES
        startListening()
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    /** Follows the internet connection and switches between online and offline listening. */
    private fun watchNetwork() {
        offlineCtl.networkChanged(Net.isOnline(this))
        offlineCtl.onDeviceAvailable = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        scope.launch { refreshInstalledSpeech() }
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { onNetworkMaybeChanged() } }
            override fun onLost(network: Network) { main.post { onNetworkMaybeChanged() } }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { main.post { onNetworkMaybeChanged() } }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); networkCallback = cb }
    }

    private fun stopWatchingNetwork() {
        val cb = networkCallback ?: return
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        networkCallback = null
    }

    private suspend fun refreshInstalledSpeech() {
        OfflineCheck(this).installedSpeech()?.let { offlineCtl.installedOnDevice = it }
    }

    private fun onNetworkMaybeChanged() {
        if (!running) return
        if (offlineCtl.networkChanged(Net.isOnline(this))) {
            // Restart listening with the right recognizer for the new connection.
            if (speakingCount == 0 && !paused) { destroyRecognizer(); scheduleListen(300) }
            status(null)
        }
    }

    private suspend fun prepareModels() {
        var attempt = 0
        // Already on the phone: nothing to download, translation works offline.
        if (translator.isReady()) { modelsReady = true; return }
        while (running && !modelsReady) {
            if (!Net.isOnline(this)) {
                status(getString(R.string.status_translation_pack_missing))
                delay(15_000)
                if (translator.isReady()) modelsReady = true
                continue
            }
            status(getString(R.string.status_downloading))
            try {
                translator.prepare()
                modelsReady = true
                engine?.retryFailed()
                status(null)
            } catch (e: Exception) {
                attempt++
                status(getString(R.string.status_download_failed))
                delay(minOf(120_000L, 5_000L shl minOf(attempt, 5)))
            }
        }
    }

    // ---------------------------------------------------------------- listening

    private fun startListening() {
        main.removeCallbacks(startRunnable)
        if (!running || paused || speakingCount > 0 || listening) return
        val mode = prefs.mode
        when (mode) {
            ListenMode.SPANISH, ListenMode.MIXED_ES -> listeningLang = Lang.ES
            ListenMode.ENGLISH -> listeningLang = Lang.EN
            ListenMode.AUTO -> {
                // Offline, only languages whose speech pack works can be heard.
                val usable = offlineCtl.usableLanguage(listeningLang)
                if (usable == null) {
                    status(getString(R.string.status_offline_speech_missing_both))
                    scheduleListen(30_000)
                    return
                }
                listeningLang = usable
            }
        }
        val setup = offlineCtl.setup(listeningLang)
        val available = if (setup.onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        else SpeechRecognizer.isRecognitionAvailable(this)
        if (!available) {
            status(getString(R.string.status_no_recognizer))
            scheduleListen(30_000)
            return
        }
        // Online and offline use different recognizers: swap when the set-up changes.
        if (recognizer != null && recognizerOnDevice != setup.onDevice) destroyRecognizer()
        val r = recognizer ?: (
            if (setup.onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else SpeechRecognizer.createSpeechRecognizer(this)
            ).also {
            it.setRecognitionListener(recognitionListener)
            recognizer = it
            recognizerOnDevice = setup.onDevice
        }
        currentSetup = setup
        detectedLang = null
        lastEventAt = SystemClock.elapsedRealtime()
        try {
            sessionHadSpeech = false
            r.startListening(buildRecognizerIntent(packageName, listeningLang, mode, setup, useLanguageDetection, segmented = useSegmented))
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
        capture.onInterrupted() // keep words already heard
    }

    private fun destroyRecognizer() {
        listening = false
        capture.onInterrupted()
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { touch(); status(null) }
        override fun onBeginningOfSpeech() { touch(); sessionHadSpeech = true }
        override fun onRmsChanged(rmsdB: Float) = touch()
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() = touch()
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            touch()
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                sessionHadSpeech = true
                capture.onPartial(text)
                LiveBus.post(LiveBus.Event.Partial(text))
            }
        }

        // Continuous listening (Android 13+): each sentence arrives here while the microphone stays on.
        override fun onSegmentResults(segmentResults: Bundle) {
            touch()
            policy.onSuccess()
            segmentedFailures = 0
            offlineCtl.onSuccess(listeningLang)
            LiveBus.post(LiveBus.Event.Partial(""))
            capture.onFinal(segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
        }

        override fun onEndOfSegmentedSession() {
            touch()
            listening = false
            capture.onInterrupted()
            scheduleListen(0) // straight back to listening, no gap
        }

        override fun onLanguageDetection(results: Bundle) {
            touch()
            Lang.fromTag(results.getString(SpeechRecognizer.DETECTED_LANGUAGE))?.let { detectedLang = it }
        }

        override fun onResults(results: Bundle?) {
            touch()
            listening = false
            policy.onSuccess()
            offlineCtl.onSuccess(listeningLang)
            val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            LiveBus.post(LiveBus.Event.Partial(""))
            capture.onFinal(all)
            scheduleListen(0) // restart at once: speech right after a sentence is not lost
        }

        override fun onError(error: Int) {
            touch()
            listening = false
            capture.onInterrupted() // the session stopped early: keep the words it had already heard
            if (!running) return
            // A recognizer that refuses continuous mode fails at once, before hearing anything.
            if (useSegmented && !sessionHadSpeech &&
                error != RestartPolicy.ERROR_NO_MATCH && error != RestartPolicy.ERROR_SPEECH_TIMEOUT &&
                ++segmentedFailures >= 3
            ) {
                useSegmented = false
            }
            when (offlineCtl.onError(error, listeningLang, currentSetup)) {
                OfflineController.Reaction.RETRY_OFFLINE_NOW -> {
                    // No internet: switch to offline listening at once instead of waiting.
                    destroyRecognizer()
                    status(null)
                    scheduleListen(200)
                    return
                }
                OfflineController.Reaction.OFFLINE_PACK_MISSING -> {
                    destroyRecognizer()
                    val other = listeningLang.other
                    if (prefs.mode == ListenMode.AUTO && offlineCtl.usableLanguage(other) == other) {
                        listeningLang = other
                        scheduleListen(300)
                    } else {
                        status(getString(
                            R.string.status_offline_speech_missing,
                            getString(if (listeningLang == Lang.ES) R.string.lang_spanish else R.string.lang_english),
                        ))
                        scheduleListen(30_000)
                    }
                    return
                }
                OfflineController.Reaction.NOT_NETWORK -> Unit
            }
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
            if (paused) {
                touch()
            } else if (speakingCount == 0) {
                if (listening && idle > 25_000) { destroyRecognizer(); startListening() }
                else if (!listening && idle > 15_000) startListening()
            } else if (idle > 60_000) {
                // TTS never reported "done"; recover.
                speakingCount = 0
                startListening()
            }
            if (wakeLock?.isHeld != true) acquireWakeLock()
            if (System.currentTimeMillis() - lastPrune > 6 * 3600_000L) prune()
            // Translations that failed (no pack yet, error) are tried again every minute: none is skipped.
            if (SystemClock.elapsedRealtime() - lastRetry > 60_000) {
                lastRetry = SystemClock.elapsedRealtime()
                scope.launch(Dispatchers.IO) { engine?.retryFailed() }
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    // ---------------------------------------------------------------- results

    /** A sentence was heard: record it at once (translation follows in the background). */
    private fun onHeard(text: String, alternatives: List<String>) {
        val mode = prefs.mode
        val source = engine?.onUtterance(text, mode, detectedLang, listeningLang, alternatives)
        // Auto mode, two people: the next sentence is most likely the other language.
        if (mode == ListenMode.AUTO && source != null) listeningLang = source.other
    }

    // Called from the background translation thread: hand over to the main thread.
    private val engineListener = object : ConversationEngine.Listener {
        override fun onCaptured(record: TranslationRecord) {
            sentences++
            LiveBus.post(LiveBus.Event.Captured(record))
        }
        override fun onTranslated(record: TranslationRecord) { main.post { deliver(record) } }
        override fun onError(message: String, record: TranslationRecord) {
            main.post { deliver(record); status(getString(R.string.status_translate_failed)) }
        }
    }

    private fun deliver(record: TranslationRecord) {
        lastLine = record.translated.ifBlank { record.original }
        LiveBus.post(LiveBus.Event.Translated(record))
        updateNotification()
        // Group mode: read on screen, so the microphone never pauses and no one is missed.
        val group = prefs.mode == ListenMode.MIXED_ES
        if (!group && prefs.speakAloud && record.translated.isNotBlank()) speak(record.translated, record.target, "utt-${record.id}")
    }

    private fun applySpeaker(on: Boolean) {
        prefs.speakAloud = on
        if (!on && speakingCount > 0) {
            // Turned off in the middle of a sentence: go quiet at once and listen again.
            runCatching { tts?.stop() }
            speakingCount = 0
            abandonFocus()
            scheduleListen(150)
        }
        LiveBus.post(LiveBus.Event.Speaker(on))
        updateNotification()
    }

    private fun speak(text: String, lang: Lang, id: String) {
        val t = tts ?: return
        if (!ttsReady) return
        // Pause the microphone so the phone does not translate its own voice.
        speakingCount++
        cancelRecognizer()
        main.removeCallbacks(startRunnable)
        requestDuckingFocus()
        // Offline: use a voice stored on the phone (online-only voices would stay silent).
        TtsVoices.applyTo(t, lang, online = !offlineCtl.offline)
        if (t.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) onSpoken()
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
        wakeLock = (wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Abhishek:listen")).also {
            it.setReferenceCounted(false)
            it.acquire(2 * 3600_000L)
        }
    }

    private fun status(text: String?) {
        val s = text ?: when (listeningLang) {
            Lang.ES -> getString(R.string.status_listening_es)
            Lang.EN -> getString(R.string.status_listening_en)
        }.let {
            when (prefs.mode) {
                ListenMode.AUTO -> getString(R.string.status_listening_auto)
                ListenMode.MIXED_ES -> getString(R.string.status_listening_group)
                else -> it
            }
        }
            .let { if (offlineCtl.offline) it + getString(R.string.status_offline_suffix) else it }
        LiveBus.post(LiveBus.Event.Status(running, s))
    }

    private fun shutdown() {
        val wasRunning = running
        running = false
        prefs.wasRunning = false
        main.removeCallbacksAndMessages(null)
        stopWatchingNetwork()
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
        stopWatchingNetwork()
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
        val speakerOn = prefs.speakAloud
        val speaker = PendingIntent.getService(
            this, 2, Intent(this, TranslatorService::class.java).setAction(ACTION_SPEAKER).putExtra("on", !speakerOn),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TranslatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                engine?.backlog?.takeIf { it > 0 }?.let { getString(R.string.notif_title_waiting, sentences, it) }
                    ?: getString(R.string.notif_title, sentences)
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, getString(if (speakerOn) R.string.speaker_turn_off else R.string.speaker_turn_on), speaker)
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
