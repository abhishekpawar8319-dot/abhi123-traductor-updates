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

/** AUTO detects who is speaking; the other two lock the direction. */
enum class ListenMode { AUTO, SPANISH, ENGLISH }

data class TranslationRecord(
    val id: Long = 0,
    val timeMillis: Long,
    val sessionId: Long,
    val source: Lang,
    val original: String,
    val translated: String,
) {
    val target: Lang get() = source.other
}
