package com.abhi.vozconversa.core

/** Cleans recognizer text and tells whether a sentence was cut off mid-way. */
object SentenceText {
    private val danglingEs = setOf(
        "y", "o", "pero", "que", "de", "del", "la", "el", "los", "las", "un", "una", "en", "con",
        "para", "por", "a", "al", "porque", "cuando", "mi", "tu", "su", "muy",
        "sin", "entre", "hasta", "desde", "sobre", "nuestro", "nuestra", "este", "esta",
    )
    private val danglingEn = setOf(
        "and", "or", "but", "that", "the", "a", "an", "of", "to", "in", "on", "with", "for",
        "because", "if", "my", "your", "our", "their", "very",
        "at", "from", "into", "than",
        // Not here on purpose: words that can end a real sentence ("Okay then", "I think so", "Me too",
        // "Yes I can", "That's how it was", "Nada más", "Creo que sí").
    )
    private val fillers = setOf("mm", "mmm", "hmm", "hm", "eh", "ah", "uh", "um", "em", "ehm", "uhm", "este...")

    fun normalize(raw: String): String {
        val t = raw.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return t
        return t.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /** True for empty text, pure punctuation, or filler sounds like "mm" / "eh". */
    fun isNoise(text: String): Boolean {
        val t = text.trim().lowercase().trimEnd('.', ',', '!', '?')
        if (t.none { it.isLetterOrDigit() }) return true
        return t in fillers
    }

    /** True when the sentence ends with a word that cannot end a sentence ("and", "de", ...). */
    fun looksUnfinished(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.endsWith(",") || t.endsWith("...") || t.endsWith("…")) return true
        if (t.last() in ".?!") return false
        val last = t.lowercase().split(Regex("[^\\p{L}']+")).lastOrNull { it.isNotEmpty() } ?: return false
        return last in danglingEs || last in danglingEn
    }

    /** Joins a held fragment with its continuation into one sentence. */
    fun join(first: String, second: String): String {
        val a = first.trim().trimEnd('…').removeSuffix("...").trimEnd()
        val b = second.trim()
        val words = b.split(" ")
        val firstWord = words.first()
        // The recognizer capitalizes the start of each piece. Lowercase it only for ordinary words:
        // names keep their capital ("in Houston", "en Los Ángeles", "Good afternoon, Charles").
        val nextIsCapital = words.getOrNull(1)?.firstOrNull()?.isUpperCase() == true
        val ordinary = firstWord.lowercase().trim(',', '.', '?', '!') in commonStarts && !nextIsCapital
        val bAdjusted = if (b.length > 1 && b[0].isUpperCase() && ordinary && firstWord != "I" && !firstWord.startsWith("I'")) {
            b.replaceFirstChar { it.lowercase() }
        } else b
        return normalize("$a $bAdjusted")
    }

    /** Ordinary words that can start the second half of a sentence (lowercased when joined). */
    private val commonStarts: Set<String> by lazy { danglingEs + danglingEn + LanguageGuesser.commonWords() }
}
