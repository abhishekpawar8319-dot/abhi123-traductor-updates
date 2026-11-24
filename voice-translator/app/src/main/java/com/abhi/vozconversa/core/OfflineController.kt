package com.abhi.vozconversa.core

/** How to start the speech recognizer for the next sentence. */
data class RecognizerSetup(
    /** Use the phone's on-device recognizer (Android 13+), which never needs the internet. */
    val onDevice: Boolean,
    /** Ask Google's recognizer to use its downloaded offline language pack. */
    val preferOffline: Boolean,
    /** Auto-detect / two-language options; only the online recognizer supports them. */
    val multiLanguageExtras: Boolean,
)

/**
 * Decides between online and offline speech recognition and reacts to network errors, so listening
 * keeps working when the internet goes away (no signal on site, airplane mode, no data plan).
 */
class OfflineController(private val sdk: Int, private val clock: () -> Long = System::currentTimeMillis) {

    enum class Reaction {
        /** Try again straight away with the offline set-up. */
        RETRY_OFFLINE_NOW,
        /** Offline speech for this language is not on the phone. */
        OFFLINE_PACK_MISSING,
        /** Not an internet problem; normal handling. */
        NOT_NETWORK,
    }

    companion object {
        const val FORCED_OFFLINE_MS = 120_000L
        private val networkErrors = setOf(
            RestartPolicy.ERROR_NETWORK_TIMEOUT, RestartPolicy.ERROR_NETWORK, RestartPolicy.ERROR_SERVER,
            RestartPolicy.ERROR_SERVER_DISCONNECTED, RestartPolicy.ERROR_TOO_MANY_REQUESTS,
        )
        private val languageErrors = setOf(RestartPolicy.ERROR_LANGUAGE_NOT_SUPPORTED, RestartPolicy.ERROR_LANGUAGE_UNAVAILABLE)
    }

    /** What Android says about the connection. */
    var systemOnline = true
        private set

    /** The phone has an on-device recognizer (Android 12+). */
    var onDeviceAvailable = false

    /** Languages whose on-device speech model is installed (known on Android 13+). */
    var installedOnDevice: Set<Lang> = emptySet()

    /** Languages that failed offline even with every fallback; cleared when the internet comes back. */
    var missingOffline: Set<Lang> = emptySet()
        private set

    private var forcedOfflineUntil = 0L

    /** Offline when Android says so, or for a while after the online recognizer failed with a network error. */
    val offline: Boolean get() = !systemOnline || clock() < forcedOfflineUntil

    fun setup(lang: Lang): RecognizerSetup = when {
        !offline -> RecognizerSetup(onDevice = false, preferOffline = false, multiLanguageExtras = true)
        sdk >= 33 && onDeviceAvailable && lang in installedOnDevice ->
            RecognizerSetup(onDevice = true, preferOffline = true, multiLanguageExtras = false)
        else -> RecognizerSetup(onDevice = false, preferOffline = true, multiLanguageExtras = false)
    }

    /** Returns true when the online/offline choice changed (the recognizer should restart). */
    fun networkChanged(online: Boolean): Boolean {
        val before = offline
        systemOnline = online
        if (online) {
            forcedOfflineUntil = 0L
            missingOffline = emptySet()
        }
        return before != offline
    }

    fun onError(code: Int, lang: Lang, used: RecognizerSetup): Reaction {
        val network = code in networkErrors
        val language = code in languageErrors
        if (!network && !language) return Reaction.NOT_NETWORK
        if (!offline) {
            if (!network) return Reaction.NOT_NETWORK
            // Android thinks we are online but the recognizer could not reach Google (weak signal,
            // Wi-Fi without internet): go offline for a while instead of waiting and retrying.
            forcedOfflineUntil = clock() + FORCED_OFFLINE_MS
            return Reaction.RETRY_OFFLINE_NOW
        }
        if (used.onDevice) {
            // The on-device model for this language is not usable: fall back to Google's offline pack.
            installedOnDevice = installedOnDevice - lang
            return Reaction.RETRY_OFFLINE_NOW
        }
        missingOffline = missingOffline + lang
        return Reaction.OFFLINE_PACK_MISSING
    }

    /** A sentence was recognized: this language works offline. */
    fun onSuccess(lang: Lang) {
        if (offline) missingOffline = missingOffline - lang
    }

    /** In Auto mode, the language to listen for next when [lang] cannot be recognized offline. */
    fun usableLanguage(lang: Lang): Lang? = when {
        lang !in missingOffline -> lang
        lang.other !in missingOffline -> lang.other
        else -> null
    }
}

/** What the phone has for working without internet. null = this Android version cannot tell. */
data class OfflineReadiness(
    val translationPack: Boolean,
    val speech: Map<Lang, Boolean?>,
    val voice: Map<Lang, Boolean?>,
) {
    val ready: Boolean get() = translationPack && speech.values.all { it != false } && voice.values.all { it != false }

    /** Short list of what is still missing, for the screen. */
    fun missing(): List<String> = buildList {
        if (!translationPack) add("translation pack")
        speech.forEach { (l, ok) -> if (ok == false) add("${if (l == Lang.ES) "Spanish" else "English"} speech") }
        voice.forEach { (l, ok) -> if (ok == false) add("${if (l == Lang.ES) "Spanish" else "English"} voice") }
    }
}
