package com.abhi.vozconversa.core

/** The only two languages this app works with. */
enum class Lang(val code: String, val locale: String, val short: String) {
    ES("es", "es-ES", "ES"),
    EN("en", "en-US", "EN");

    val other: Lang get() = if (this == ES) EN else ES

    companion object {
        /** Maps a BCP-47 tag from the recognizer ("es-MX", "en_GB", "spa") to a Lang, or null. */
        fun fromTag(tag: String?): Lang? {
            val t = tag?.trim()?.lowercase() ?: return null
            return when {
                t.startsWith("es") || t.startsWith("spa") -> ES
                t.startsWith("en") -> EN
                else -> null
            }
        }
    }
}

/**
 * MIXED_ES: group conversation with Spanish and English mixed; every sentence is recorded, only Spanish
 * is translated (into English). AUTO translates both ways. SPANISH / ENGLISH lock the direction.
 */
enum class ListenMode { MIXED_ES, AUTO, SPANISH, ENGLISH }

/** Where a sentence is in the background translation queue. */
enum class TStatus {
    /** Recorded, waiting to be translated. */
    PENDING,
    DONE,
    /** Translation failed; it is retried later, never dropped. */
    FAILED,
    /** Already in English in Spanish → English mode: nothing to translate. */
    NOT_NEEDED,
}

data class TranslationRecord(
    val id: Long = 0,
    val timeMillis: Long,
    val sessionId: Long,
    val source: Lang,
    val original: String,
    val translated: String,
    /** The recognizer's other guesses for this sentence, used to point at doubtful words. */
    val alternatives: List<String> = emptyList(),
    val status: TStatus = TStatus.DONE,
) {
    val target: Lang get() = source.other
}
