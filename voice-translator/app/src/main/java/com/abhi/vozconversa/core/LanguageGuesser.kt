package com.abhi.vozconversa.core

/**
 * Lightweight, offline Spanish-vs-English guess from text. Used when the speech recognizer
 * does not report which language it heard.
 */
object LanguageGuesser {
    private val spanish = setOf(
        "el", "la", "los", "las", "de", "del", "que", "y", "en", "un", "una", "es", "por", "con",
        "para", "pero", "más", "mas", "muy", "yo", "tú", "usted", "ustedes", "está", "estoy",
        "están", "estás", "hola", "gracias", "sí", "qué", "cómo", "dónde", "cuándo", "cuánto",
        "por favor", "bien", "buenos", "buenas", "días", "tardes", "noches", "mi", "su", "lo",
        "le", "se", "me", "te", "nos", "al", "este", "esta", "eso", "esto", "aquí", "allí",
        "ahora", "hoy", "mañana", "ayer", "tengo", "tiene", "quiero", "puedo", "puede", "hay",
        "somos", "soy", "eres", "son", "fue", "era", "hacer", "vamos", "necesito", "también",
        "porque", "cuando", "donde", "como", "nada", "todo", "mucho", "poco", "señor", "señora",
        "amigo", "trabajo", "casa", "agua", "comida", "dinero", "hora", "tiempo",
    )
    private val english = setOf(
        "the", "and", "is", "are", "you", "i", "to", "of", "it", "that", "this", "what", "with",
        "have", "has", "for", "not", "be", "was", "were", "we", "they", "my", "your", "do", "does",
        "did", "can", "will", "would", "hello", "hi", "thanks", "thank", "please", "where", "how",
        "when", "why", "who", "yes", "good", "morning", "afternoon", "evening", "night", "here",
        "there", "now", "today", "tomorrow", "yesterday", "want", "need", "am", "he", "she", "him",
        "her", "our", "their", "from", "at", "on", "in", "about", "just", "very", "much", "many",
        "some", "time", "work", "house", "water", "food", "money", "friend", "sir", "okay", "ok",
        "right", "going", "go", "come", "know", "think", "see", "let's", "don't", "i'm", "it's",
        "could", "should", "an", "or", "but", "if", "so", "because", "all", "nothing", "everything",
    )

    /** Returns (spanishScore, englishScore). */
    fun score(text: String): Pair<Int, Int> {
        var es = 0
        var en = 0
        if (text.any { it == '¿' || it == '¡' }) es += 3
        es += 2 * text.count { it in "ñÑáéíóúÁÉÍÓÚü" }
        val words = text.lowercase()
            .split(Regex("[^\\p{L}']+"))
            .filter { it.isNotEmpty() }
        for (w in words) {
            if (w in spanish) es++
            if (w in english) en++
        }
        return es to en
    }

    /** Spanish/English guess, or null when the text gives no clear signal. */
    fun guess(text: String): Lang? {
        val (es, en) = score(text)
        return when {
            es > en -> Lang.ES
            en > es -> Lang.EN
            else -> null
        }
    }
}

/** Decides which language a finished sentence was spoken in. */
object DirectionDecider {
    /**
     * @param detected language reported by the recognizer (Android 14+), if any
     * @param listening language the recognizer was primed for
     */
    fun sourceOf(text: String, mode: ListenMode, detected: Lang?, listening: Lang): Lang = when (mode) {
        ListenMode.SPANISH -> Lang.ES
        ListenMode.ENGLISH -> Lang.EN
        ListenMode.AUTO -> {
            val guess = LanguageGuesser.guess(text)
            val (es, en) = LanguageGuesser.score(text)
            val strongGuess = guess != null && kotlin.math.abs(es - en) >= 2
            when {
                // Clear text evidence wins over a recognizer detection that disagrees.
                strongGuess -> guess!!
                detected != null -> detected
                guess != null -> guess
                else -> listening
            }
        }
    }
}
