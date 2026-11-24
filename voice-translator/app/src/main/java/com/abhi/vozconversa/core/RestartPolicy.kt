package com.abhi.vozconversa.core

/**
 * How long to wait before listening again after a recognizer error, so the app keeps listening
 * 24/7 without hammering the recognizer. Error codes mirror android.speech.SpeechRecognizer.
 */
class RestartPolicy {
    companion object {
        const val ERROR_NETWORK_TIMEOUT = 1
        const val ERROR_NETWORK = 2
        const val ERROR_AUDIO = 3
        const val ERROR_SERVER = 4
        const val ERROR_CLIENT = 5
        const val ERROR_SPEECH_TIMEOUT = 6
        const val ERROR_NO_MATCH = 7
        const val ERROR_RECOGNIZER_BUSY = 8
        const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        const val ERROR_TOO_MANY_REQUESTS = 10
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13

        const val STOP = -1L
        const val MAX_BACKOFF = 30_000L
        const val MAX_SHORT_WAIT = 2_000L
    }

    private var failures = 0

    fun onSuccess() { failures = 0 }

    /** Milliseconds to wait before listening again, or [STOP] if listening cannot continue. */
    fun delayAfterError(code: Int): Long = when (code) {
        // Silence: nobody spoke. Not a failure, listen again at once.
        ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH -> 100L
        ERROR_INSUFFICIENT_PERMISSIONS -> STOP
        // Language not available / mixed speech confused the recognizer: listen again at once (the service
        // drops the two-language options). Not a failure; never a long wait.
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> 150L
        // Busy recognizer: short waits only, so listening never stalls in a busy conversation.
        ERROR_RECOGNIZER_BUSY, ERROR_CLIENT -> { failures++; minOf(MAX_SHORT_WAIT, 300L * failures) }
        ERROR_NETWORK, ERROR_NETWORK_TIMEOUT, ERROR_SERVER, ERROR_SERVER_DISCONNECTED,
        ERROR_TOO_MANY_REQUESTS -> {
            failures++
            minOf(MAX_BACKOFF, 1000L shl minOf(failures - 1, 5))
        }
        ERROR_AUDIO -> { failures++; 2000L }
        else -> { failures++; minOf(MAX_BACKOFF, 1000L * failures) }
    }

    /** Errors after which the recognizer object should be destroyed and created again. */
    fun needsRecreate(code: Int): Boolean = code in setOf(
        ERROR_CLIENT, ERROR_RECOGNIZER_BUSY, ERROR_SERVER, ERROR_SERVER_DISCONNECTED, ERROR_AUDIO,
    ) || failures >= 3

    /** In AUTO mode the recognizer only offers Spanish/English if those packs are missing. */
    fun isLanguageProblem(code: Int) = code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE
}

object Retention {
    /** Records older than the returned time are deleted; null keeps everything. */
    fun cutoff(nowMillis: Long, keepDays: Int): Long? =
        if (keepDays <= 0) null else nowMillis - keepDays * 24L * 60 * 60 * 1000
}
