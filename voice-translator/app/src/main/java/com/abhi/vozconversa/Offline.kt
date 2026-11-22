package com.abhi.vozconversa

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.ListenMode
import com.abhi.vozconversa.core.OfflineReadiness
import com.abhi.vozconversa.core.RecognizerSetup
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

object Net {
    /** True when the phone has a working internet connection. */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/** The recognizer request for one sentence, online or offline. */
fun buildRecognizerIntent(
    packageName: String, lang: Lang, mode: ListenMode, setup: RecognizerSetup,
    useLanguageDetection: Boolean, sdk: Int = Build.VERSION.SDK_INT,
): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.locale)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    // Ask for the recognizer's other guesses too: they show which words it was unsure about.
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    // Wait for a real pause before ending the sentence, so whole sentences are captured.
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
    // Offline: use the speech pack stored on the phone instead of Google's servers.
    if (setup.preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    if (mode == ListenMode.AUTO && setup.multiLanguageExtras) {
        putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(lang.other.locale))
        if (sdk >= 34 && useLanguageDetection) {
            val both = arrayListOf(Lang.ES.locale, Lang.EN.locale)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, both)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, both)
        }
    }
}

/** Chooses a text-to-speech voice that works without internet when offline. */
object TtsVoices {
    fun pick(voices: Collection<Voice>?, lang: Lang, online: Boolean): Voice? {
        val wanted = Locale.forLanguageTag(lang.locale)
        val usable = voices.orEmpty().filter { v ->
            v.locale.language == wanted.language &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty() &&
                (online || !v.isNetworkConnectionRequired)
        }
        return usable.sortedWith(
            compareByDescending<Voice> { it.locale.country == wanted.country }
                .thenBy { it.isNetworkConnectionRequired }        // on-phone voices first: no delay, no data
                .thenByDescending { it.quality }
        ).firstOrNull()
    }

    /** Sets the voice for [lang]; returns false when no voice for it works right now. */
    fun applyTo(tts: TextToSpeech, lang: Lang, online: Boolean): Boolean {
        val v = pick(runCatching { tts.voices }.getOrNull(), lang, online)
        if (v != null) return tts.setVoice(v) == TextToSpeech.SUCCESS
        val r = tts.setLanguage(Locale.forLanguageTag(lang.locale))
        return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
    }

    fun hasOffline(tts: TextToSpeech, lang: Lang): Boolean = pick(runCatching { tts.voices }.getOrNull(), lang, online = false) != null
}

/** Checks and downloads everything the app needs to work offline. */
class OfflineCheck(private val context: Context) {
    private val spanishModel = TranslateRemoteModel.Builder(TranslateLanguage.SPANISH).build()

    suspend fun translationReady(): Boolean =
        runCatching { RemoteModelManager.getInstance().isModelDownloaded(spanishModel).await() }.getOrDefault(false)

    /** Installed on-device speech languages (Android 13+), or null if this phone cannot tell. */
    suspend fun installedSpeech(): Set<Lang>? {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return null
        return withTimeoutOrNull(5_000) {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                val found = mutableSetOf<Lang>()
                for (lang in Lang.values()) {
                    if (supportFor(lang)) found += lang
                }
                found
            }
        }
    }

    private suspend fun supportFor(lang: Lang): Boolean = suspendCancellableCoroutine { cont ->
        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.locale)
        rec.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                val installed = support.installedOnDeviceLanguages
                rec.destroy()
                if (cont.isActive) cont.resume(installed.any { Lang.fromTag(it) == lang })
            }
            override fun onError(error: Int) {
                rec.destroy()
                if (cont.isActive) cont.resume(false)
            }
        })
    }

    /** Asks Android to download the on-device speech model (Android 13+, needs internet once). */
    fun downloadSpeech(lang: Lang): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return false
        return runCatching {
            val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            rec.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.locale))
            rec.destroy()
        }.isSuccess
    }

    suspend fun voices(): Map<Lang, Boolean?> = withTimeoutOrNull(5_000) {
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var tts: TextToSpeech? = null
                tts = TextToSpeech(context) { status ->
                    val t = tts
                    val result: Map<Lang, Boolean?> =
                        if (status == TextToSpeech.SUCCESS && t != null) Lang.values().associateWith { TtsVoices.hasOffline(t, it) }
                        else Lang.values().associateWith { null }
                    t?.shutdown()
                    if (cont.isActive) cont.resume(result)
                }
            }
        }
    } ?: Lang.values().associateWith { null }

    suspend fun check(): OfflineReadiness {
        val speech = installedSpeech()
        return OfflineReadiness(
            translationPack = translationReady(),
            speech = Lang.values().associateWith { l -> speech?.let { l in it } },
            voice = voices(),
        )
    }
}

/** The "Ready for offline?" window: what is on the phone, and buttons to get the rest while online. */
class OfflineUi(private val activity: AppCompatActivity, private val onChecked: (OfflineReadiness) -> Unit) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val check = OfflineCheck(activity)

    fun refresh() {
        scope.launch { onChecked(check.check()) }
    }

    fun show() {
        scope.launch {
            val r = check.check()
            onChecked(r)
            val online = Net.isOnline(activity)
            fun line(ok: Boolean?, label: String) = when (ok) {
                true -> "✅  $label"
                false -> "❌  $label"
                null -> "❔  $label (this Android can't check – see step 2)"
            }
            val msg = buildString {
                append(activity.getString(R.string.offline_intro)).append("\n\n")
                append(line(r.translationPack, activity.getString(R.string.offline_item_translation))).append('\n')
                append(line(r.speech[Lang.ES], activity.getString(R.string.offline_item_speech_es))).append('\n')
                append(line(r.speech[Lang.EN], activity.getString(R.string.offline_item_speech_en))).append('\n')
                append(line(r.voice[Lang.ES], activity.getString(R.string.offline_item_voice_es))).append('\n')
                append(line(r.voice[Lang.EN], activity.getString(R.string.offline_item_voice_en))).append("\n\n")
                append(activity.getString(if (online) R.string.offline_online_now else R.string.offline_offline_now))
            }
            AlertDialog.Builder(activity)
                .setTitle(if (r.ready) R.string.offline_title_ready else R.string.offline_title_not_ready)
                .setMessage(msg)
                .setPositiveButton(R.string.offline_download) { _, _ -> downloadAll(online) }
                .setNeutralButton(R.string.offline_settings) { _, _ -> openSettings() }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    private fun downloadAll(online: Boolean) {
        if (!online) {
            Toast.makeText(activity, R.string.offline_need_internet, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(activity, R.string.offline_downloading, Toast.LENGTH_LONG).show()
        scope.launch {
            runCatching { MlKitTranslator().also { it.prepare() }.close() }
            Lang.values().forEach { check.downloadSpeech(it) }
            onChecked(check.check())
        }
        // Older phones: offline speech and voices are downloaded in Android's own settings.
        if (Build.VERSION.SDK_INT < 33) openSettings()
    }

    /** Opens the phone's voice-typing / text-to-speech settings, where offline languages are downloaded. */
    private fun openSettings() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.offline_settings)
            .setItems(arrayOf(activity.getString(R.string.offline_open_speech), activity.getString(R.string.offline_open_voice))) { _, which ->
                val intents = if (which == 0) listOf(
                    Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                    Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
                ) else listOf(
                    Intent("com.android.settings.TTS_SETTINGS"),
                    Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
                )
                val opened = intents.any { runCatching { activity.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
                if (!opened) Toast.makeText(activity, R.string.offline_settings_help, Toast.LENGTH_LONG).show()
            }
            .show()
    }
}
