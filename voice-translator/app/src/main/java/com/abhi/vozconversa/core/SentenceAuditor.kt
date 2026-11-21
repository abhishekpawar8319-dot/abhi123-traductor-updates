package com.abhi.vozconversa.core

import kotlinx.coroutines.CancellationException

/** Splits a sentence into words, keeping punctuation attached ("¿Dónde", "baño?") but separable. */
object SentenceWords {
    /** A word: [start, end) is the whole token, [coreStart, coreEnd) the word without punctuation. */
    data class Token(val start: Int, val end: Int, val coreStart: Int, val coreEnd: Int)

    private const val LEAD = "¿¡\"'“‘(«["
    private const val TRAIL = ".,;:!?\"'”’)»]…"

    fun tokens(text: String): List<Token> = Regex("\\S+").findAll(text).mapNotNull { m ->
        var cs = m.range.first
        val end = m.range.last + 1
        while (cs < end && text[cs] in LEAD) cs++
        var ce = end
        while (ce > cs && text[ce - 1] in TRAIL) ce--
        if (ce > cs) Token(m.range.first, end, cs, ce) else null
    }.toList()

    fun cores(text: String): List<String> = tokens(text).map { text.substring(it.coreStart, it.coreEnd) }

    /** Replaces word [index] with [replacement] (blank = delete the word), keeping punctuation. */
    fun replace(text: String, index: Int, replacement: String): String {
        val toks = tokens(text)
        require(index in toks.indices) { "no word $index in '$text'" }
        val t = toks[index]
        val rep = replacement.trim().replace(Regex("\\s+"), " ")
        val wasCapital = text.substring(t.coreStart, t.coreEnd).firstOrNull()?.isUpperCase() == true
        val out = if (rep.isEmpty()) {
            val prefix = text.substring(t.start, t.coreStart)
            val suffix = text.substring(t.coreEnd, t.end)
            val before = text.substring(0, t.start).trimEnd() + suffix
            val after = prefix + text.substring(t.end).trimStart()
            listOf(before, after).filter { it.isNotBlank() }.joinToString(" ")
        } else {
            val r = if (index == 0 && wasCapital) capitalize(rep) else rep
            text.substring(0, t.coreStart) + r + text.substring(t.coreEnd)
        }
        val tidy = out.replace(Regex("\\s+"), " ").trim()
        return if (index == 0 && wasCapital) capitalize(tidy) else tidy
    }

    /** Uppercases the first letter, skipping leading punctuation such as "¿". */
    fun capitalize(s: String): String {
        val i = s.indexOfFirst { it.isLetter() }
        if (i < 0 || s[i].isUpperCase()) return s
        return s.substring(0, i) + s[i].uppercase() + s.substring(i + 1)
    }
}

enum class AuditKind { LEARNED_FIX, REPEATED_WORD, ACCENT, OTHER_LANGUAGE, UNCERTAIN }

/** A change the auditor made by itself. */
data class Fix(val from: String, val to: String, val kind: AuditKind)

/** A word the auditor thinks may have been heard wrong; the user is asked to check it. */
data class AuditIssue(val wordIndex: Int, val word: String, val kind: AuditKind, val suggestions: List<String>)

data class AuditResult(val text: String, val fixes: List<Fix>, val issues: List<AuditIssue>) {
    val flaggedWords: List<String> get() = issues.map { it.word }.distinct()
    fun issuesAt(index: Int) = issues.filter { it.wordIndex == index }
}

data class Correction(val lang: Lang, val wrong: String, val right: String)

interface CorrectionStore {
    fun loadCorrections(): List<Correction>
    fun saveCorrection(c: Correction)
    fun deleteCorrection(c: Correction)
}

/** Word fixes the user taught the app ("tore" → "torre"). Applied automatically to new sentences. */
class CorrectionDictionary(private val store: CorrectionStore? = null) {
    private val map = LinkedHashMap<Pair<Lang, String>, Correction>()

    init {
        store?.loadCorrections()?.forEach { map[it.lang to it.wrong.lowercase()] = it }
    }

    /** Remembers a fix. Returns false if there is nothing sensible to learn. */
    @Synchronized
    fun learn(lang: Lang, wrong: String, right: String): Boolean {
        val w = wrong.trim().replace(Regex("\\s+"), " ").lowercase()
        val r = right.trim().replace(Regex("\\s+"), " ")
        if (w.isEmpty() || r.isEmpty() || w.equals(r, ignoreCase = true) || w.split(' ').size > 4) return false
        val c = Correction(lang, w, r)
        map[lang to w] = c
        store?.saveCorrection(c)
        return true
    }

    @Synchronized
    fun forget(c: Correction) {
        map.remove(c.lang to c.wrong.lowercase())
        store?.deleteCorrection(c)
    }

    @Synchronized
    fun all(): List<Correction> = map.values.toList()

    @Synchronized
    fun apply(text: String, lang: Lang, fixes: MutableList<Fix>? = null): String {
        var out = text
        for (c in map.values.filter { it.lang == lang }.sortedByDescending { it.wrong.length }) {
            val body = c.wrong.split(' ').joinToString("\\s+") { Regex.escape(it) }
            val regex = Regex("(?<![\\p{L}\\p{N}'])$body(?![\\p{L}\\p{N}'])", RegexOption.IGNORE_CASE)
            out = regex.replace(out) { m ->
                val rep = if (m.value.first().isUpperCase()) SentenceWords.capitalize(c.right) else c.right
                fixes?.add(Fix(m.value, rep, AuditKind.LEARNED_FIX))
                rep
            }
        }
        return out
    }
}

/**
 * Checks each recognized sentence before it is translated.
 * Fixes by itself only what is safe: words the user taught it, doubled small words ("the the"),
 * and missing Spanish accents. Words that look misheard are flagged so the user can fix just that word.
 */
class SentenceAuditor(private val dictionary: CorrectionDictionary = CorrectionDictionary()) {

    companion object {
        /** Small words that are never said twice in a row on purpose. */
        val removableDoubles = setOf(
            "the", "a", "an", "to", "of", "in", "on", "at", "for", "and", "is", "with", "from",
            "el", "la", "los", "las", "de", "del", "en", "un", "una", "y", "con", "por", "para", "al",
        )

        /** Words people do repeat ("no no", "ya ya", "that that"). */
        val allowedDoubles = setOf(
            "no", "sí", "si", "yes", "very", "muy", "bye", "adiós", "okay", "ok", "ya", "ha", "that",
            "had", "so", "go", "ah", "oh", "bueno", "now", "come", "ven", "rápido", "poco", "más", "really",
            "hey", "hola", "please", "vamos", "dale", "mm", "eh",
        )

        /** Spanish words written without their accent, where the accented form is the only real word. */
        val accentFixes = mapOf(
            "dia" to "día", "dias" to "días", "tambien" to "también", "despues" to "después",
            "aqui" to "aquí", "alli" to "allí", "ahi" to "ahí", "asi" to "así", "ademas" to "además",
            "quizas" to "quizás", "facil" to "fácil", "dificil" to "difícil", "rapido" to "rápido",
            "rapida" to "rápida", "numero" to "número", "telefono" to "teléfono", "musica" to "música",
            "medico" to "médico", "pagina" to "página", "ingles" to "inglés", "adios" to "adiós",
            "cancion" to "canción", "camion" to "camión", "reunion" to "reunión", "informacion" to "información",
            "direccion" to "dirección", "estacion" to "estación", "situacion" to "situación", "sabado" to "sábado",
            "miercoles" to "miércoles", "maquina" to "máquina", "electrico" to "eléctrico", "electrica" to "eléctrica",
            "segun" to "según", "jamas" to "jamás", "ultimo" to "último", "ultima" to "última",
            "proximo" to "próximo", "proxima" to "próxima", "manana" to "mañana", "ano" to "año", "anos" to "años",
            "senor" to "señor", "senora" to "señora", "espanol" to "español", "pequeno" to "pequeño",
            "pequena" to "pequeña", "nino" to "niño", "nina" to "niña", "bano" to "baño", "grua" to "grúa",
            "arnes" to "arnés", "tecnico" to "técnico", "linea" to "línea", "energia" to "energía",
            "policia" to "policía", "todavia" to "todavía", "ingenieria" to "ingeniería",
            "compania" to "compañía", "rapidamente" to "rápidamente", "facilmente" to "fácilmente",
        )

        /** Spanish question words: accented when they start a question. */
        val questionWords = mapOf(
            "como" to "cómo", "donde" to "dónde", "que" to "qué", "cuando" to "cuándo", "cuanto" to "cuánto",
            "cuanta" to "cuánta", "cuantos" to "cuántos", "cuantas" to "cuántas", "quien" to "quién",
            "quienes" to "quiénes", "cual" to "cuál", "cuales" to "cuáles", "adonde" to "adónde",
        )

        /** English words that do not exist in Spanish: inside a Spanish sentence they were probably misheard. */
        val englishWords = setOf(
            "the", "and", "is", "are", "was", "were", "you", "of", "with", "what", "this", "that", "have",
            "has", "will", "would", "my", "your", "we", "they", "she", "it", "from", "for", "to", "in", "on",
            "at", "i", "i'm", "it's", "don't", "can't", "please", "hello", "thanks", "yes", "where", "when",
            "why", "who", "how", "there", "here", "been", "does", "did", "not", "but", "or", "if", "our",
            "their", "them", "his", "her", "these", "those", "which", "should", "could",
        )

        /** Spanish words that do not exist in English. */
        val spanishWords = setOf(
            "el", "los", "las", "del", "que", "por", "para", "está", "están", "estoy", "es", "pero", "muy",
            "también", "gracias", "hola", "cuando", "donde", "dónde", "quiere", "tengo", "necesito", "una",
            "y", "de", "usted", "señor", "señora", "porque", "aquí", "ahora", "mañana", "bueno", "nosotros",
            "ellos", "eso", "esto", "qué", "cómo", "muchas", "estás", "hay", "tiene", "vamos", "sí",
        )

        /** English words written with Spanish letters that are still normal in English. */
        private val loanwords = setOf("café", "josé", "résumé", "cliché", "fiancé", "jalapeño", "piñata", "señorita")
        private val spanishLetters = Regex("[ñáéíóú]")
    }

    fun audit(text: String, source: Lang, alternatives: List<String> = emptyList()): AuditResult {
        val fixes = mutableListOf<Fix>()
        val fixed = autoFix(text, source, fixes)
        val words = SentenceWords.cores(fixed)
        val lower = words.map { it.lowercase() }
        val issues = mutableListOf<AuditIssue>()

        // 1. The same word twice in a row ("we work work").
        for (i in 1 until words.size) {
            if (lower[i] == lower[i - 1] && lower[i] !in allowedDoubles) {
                issues += AuditIssue(i, words[i], AuditKind.REPEATED_WORD, emptyList())
            }
        }

        // 2. A word from the other language in the middle of the sentence.
        fun capital(k: Int) = words.getOrNull(k)?.firstOrNull()?.isUpperCase() == true
        for (i in words.indices) {
            val w = lower[i]
            // Part of a name such as "Los Angeles", "El Paso" or "Rio de Janeiro": not a misheard word.
            // (The first word of a sentence is capitalized anyway, so it only counts as a name when it starts one.)
            val inName = (capital(i) && capital(i + 1)) || (i > 1 && capital(i - 1) && capital(i + 1))
            if (inName) continue
            val foreign = when (source) {
                Lang.ES -> w in englishWords
                Lang.EN -> w in spanishWords || (spanishLetters.containsMatchIn(w) && w !in loanwords)
            }
            if (foreign) issues += AuditIssue(i, words[i], AuditKind.OTHER_LANGUAGE, emptyList())
        }

        // 3. Words the recognizer itself was unsure about (its other guesses differ there).
        val suggestions = LinkedHashMap<Int, MutableList<String>>()
        for (alt in alternatives) {
            val altWords = SentenceWords.cores(autoFix(alt, source, null))
            for ((index, suggestion) in differences(words, altWords)) {
                if (!suggestion.equals(words[index], ignoreCase = true)) {
                    val list = suggestions.getOrPut(index) { mutableListOf() }
                    if (list.none { it.equals(suggestion, ignoreCase = true) }) list += suggestion
                }
            }
        }
        suggestions.forEach { (i, s) -> issues += AuditIssue(i, words[i], AuditKind.UNCERTAIN, s) }

        return AuditResult(fixed, fixes, issues.sortedWith(compareBy({ it.wordIndex }, { it.kind.ordinal })))
    }

    /** The safe, automatic part of the audit. */
    fun autoFix(text: String, source: Lang, fixes: MutableList<Fix>?): String {
        var t = text.replace(Regex("\\s+"), " ").trim()
        t = dictionary.apply(t, source, fixes)
        t = removeDoubles(t, fixes)
        if (source == Lang.ES) t = fixAccents(t, fixes)
        return t
    }

    private fun removeDoubles(text: String, fixes: MutableList<Fix>?): String {
        val toks = SentenceWords.tokens(text)
        if (toks.size < 2) return text
        val keep = ArrayList<String>()
        var prevCore: String? = null
        var prevHadTrailingPunct = false
        for (t in toks) {
            val core = text.substring(t.coreStart, t.coreEnd)
            val whole = text.substring(t.start, t.end)
            val lead = t.coreStart > t.start
            if (prevCore != null && !prevHadTrailingPunct && !lead &&
                core.equals(prevCore, ignoreCase = true) && core.lowercase() in removableDoubles
            ) {
                fixes?.add(Fix("$prevCore $core", prevCore, AuditKind.REPEATED_WORD))
                // Keep the second copy's trailing punctuation ("in in, the" -> "in, the").
                val trail = text.substring(t.coreEnd, t.end)
                if (trail.isNotEmpty()) keep[keep.size - 1] = keep.last() + trail
                prevHadTrailingPunct = trail.isNotEmpty()
                continue
            }
            keep += whole
            prevCore = core
            prevHadTrailingPunct = t.coreEnd < t.end
        }
        return keep.joinToString(" ")
    }

    private fun fixAccents(text: String, fixes: MutableList<Fix>?): String {
        val toks = SentenceWords.tokens(text)
        if (toks.isEmpty()) return text
        val sb = StringBuilder(text)
        val isQuestion = text.trimEnd().endsWith("?")
        // Replace from the end so earlier positions stay valid.
        for ((i, t) in toks.withIndex().reversed()) {
            val core = text.substring(t.coreStart, t.coreEnd)
            val lw = core.lowercase()
            val firstWord = text.substring(toks[0].coreStart, toks[0].coreEnd).lowercase()
            val accented = when {
                accentFixes.containsKey(lw) -> accentFixes[lw]
                i == 0 && isQuestion -> questionWords[lw]
                i == 1 && isQuestion && lw == "que" && firstWord == "por" -> "qué"
                else -> null
            }
            if (accented != null) {
                val rep = if (core.first().isUpperCase()) SentenceWords.capitalize(accented) else accented
                fixes?.add(Fix(core, rep, AuditKind.ACCENT))
                sb.replace(t.coreStart, t.coreEnd, rep)
            }
        }
        var out = sb.toString()
        val first = SentenceWords.cores(out).firstOrNull()?.lowercase()
        val startsQuestion = first != null && (first in questionWords.values || first == "por" && out.contains(Regex("^\\S+\\s+qué", RegexOption.IGNORE_CASE)))
        if (isQuestion && startsQuestion && !out.startsWith("¿")) {
            out = "¿$out"
            fixes?.add(Fix("", "¿", AuditKind.ACCENT))
        }
        return out
    }

    /**
     * Word-by-word differences between the chosen sentence and another guess, as
     * (index in [best], suggested replacement). Extra or missing words are ignored.
     */
    private fun differences(best: List<String>, alt: List<String>): List<Pair<Int, String>> {
        val a = best.map { it.lowercase() }
        val b = alt.map { it.lowercase() }
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = mutableListOf<Pair<Int, String>>()
        var i = 0
        var j = 0
        val segA = mutableListOf<Int>()
        val segB = mutableListOf<Int>()
        fun closeSegment() {
            if (segA.isNotEmpty() && segB.isNotEmpty()) {
                if (segA.size == segB.size) segA.zip(segB).forEach { (x, y) -> out += x to alt[y] }
                else out += segA.first() to segB.joinToString(" ") { alt[it] }
            }
            segA.clear(); segB.clear()
        }
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { closeSegment(); i++; j++ }
                j >= b.size || (i < a.size && lcs[i + 1][j] >= lcs[i][j + 1]) -> { segA += i; i++ }
                else -> { segB += j; j++ }
            }
        }
        closeSegment()
        return out
    }
}

/** Fixes a saved sentence (one word or the whole text), translates it again and updates the record. */
class Corrector(
    private val translator: TextTranslator,
    private val store: HistoryStore,
    private val dictionary: CorrectionDictionary,
) {
    suspend fun replaceWord(record: TranslationRecord, wordIndex: Int, replacement: String, learn: Boolean): TranslationRecord {
        val words = SentenceWords.cores(record.original)
        require(wordIndex in words.indices)
        if (learn && replacement.isNotBlank()) dictionary.learn(record.source, words[wordIndex], replacement)
        return rewrite(record, SentenceWords.replace(record.original, wordIndex, replacement))
    }

    suspend fun rewrite(record: TranslationRecord, newText: String): TranslationRecord {
        val clean = SentenceWords.capitalize(newText.replace(Regex("\\s+"), " ").trim())
        val translated = try {
            translator.translate(clean, record.source).trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
        val updated = record.copy(original = clean, translated = translated, alternatives = emptyList())
        store.update(updated)
        return updated
    }
}
