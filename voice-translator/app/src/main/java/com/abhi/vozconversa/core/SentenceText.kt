package com.abhi.vozconversa.core

/** Cleans recognizer text and tells whether a sentence was cut off mid-way. */
object SentenceText {
    private val danglingEs = setOf(
        "y", "o", "pero", "que", "de", "del", "la", "el", "los", "las", "un", "una", "en", "con",
        "para", "por", "a", "al", "porque", "cuando", "si", "mi", "tu", "su", "muy", "más", "como",
        "sin", "entre", "hasta", "desde", "sobre", "nuestro", "nuestra", "este", "esta",
    )
    private val danglingEn = setOf(
        "and", "or", "but", "that", "the", "a", "an", "of", "to", "in", "on", "with", "for",
        "because", "when", "if", "my", "your", "his", "her", "our", "their", "is", "are", "very",
        "so", "at", "from", "about", "into", "than", "then", "was", "were", "will", "would",
        "can", "could", "should", "this", "these", "those",
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
        val bAdjusted = if (b.length > 1 && b[0].isUpperCase() && b[1].isLowerCase() && !b.startsWith("I ")) {
            b.replaceFirstChar { it.lowercase() }
        } else b
        return normalize("$a $bAdjusted")
    }
}
