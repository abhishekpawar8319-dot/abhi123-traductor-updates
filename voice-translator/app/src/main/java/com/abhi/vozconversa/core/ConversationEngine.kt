package com.abhi.vozconversa.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TextTranslator {
    suspend fun translate(text: String, source: Lang): String
}

interface HistoryStore {
    /** Saves the record and returns its id. */
    fun save(record: TranslationRecord): Long

    /** Replaces a saved record (same id) after the user fixed it. */
    fun update(record: TranslationRecord) {}
}

/**
 * Turns finished utterances from the recognizer into saved translations.
 *
 * - Waits for the whole sentence: if an utterance ends on a word like "and"/"que", it is held
 *   for [holdMillis] so the continuation can be joined before translating.
 * - Translates and saves one sentence at a time, in the order they were spoken.
 * - If translation fails, the original sentence is still saved (with an empty translation).
 */
class ConversationEngine(
    private val scope: CoroutineScope,
    private val translator: TextTranslator,
    private val store: HistoryStore,
    private val sessionId: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val holdMillis: Long = 1800,
    private val listener: Listener,
    /** Checks and auto-fixes each sentence before translating; null = translate as heard. */
    private val auditor: SentenceAuditor? = null,
) {
    interface Listener {
        fun onTranslated(record: TranslationRecord)
        fun onError(message: String, record: TranslationRecord) {}
    }

    private data class Pending(
        val text: String, val mode: ListenMode, val detected: Lang?, val listening: Lang, val time: Long,
        val alternatives: List<String> = emptyList(),
    )

    private val mutex = Mutex()
    private var pending: Pending? = null
    private var holdJob: Job? = null
    private val work = mutableListOf<Job>()

    /** Called with each final recognizer result. Returns the language it decided on, or null if ignored. */
    fun onUtterance(
        raw: String, mode: ListenMode, detected: Lang?, listening: Lang, alternatives: List<String> = emptyList(),
    ): Lang? {
        val clean = SentenceText.normalize(raw)
        if (SentenceText.isNoise(clean)) return null
        holdJob?.cancel()
        val prev = pending
        val combined = if (prev != null) SentenceText.join(prev.text, clean) else clean
        // Alternatives only describe a single recognizer result, not a joined sentence.
        val alts = if (prev == null) alternatives.filter { it.isNotBlank() && !it.equals(raw, ignoreCase = true) } else emptyList()
        val item = Pending(combined, mode, detected ?: prev?.detected, listening, prev?.time ?: clock(), alts)
        val source = DirectionDecider.sourceOf(item.text, item.mode, item.detected, item.listening)
        if (SentenceText.looksUnfinished(combined)) {
            pending = item
            holdJob = scope.launch {
                delay(holdMillis)
                flush()
            }
        } else {
            pending = null
            process(item)
        }
        return source
    }

    /** Translates any held fragment right away. */
    fun flush() {
        val p = pending ?: return
        pending = null
        holdJob?.cancel()
        process(p)
    }

    /** Flushes and waits until every sentence is translated and saved. */
    suspend fun drain() {
        flush()
        val jobs = synchronized(work) { work.toList() }
        jobs.forEach { it.join() }
    }

    private fun process(p: Pending) {
        val job = scope.launch {
            mutex.withLock {
                val source = DirectionDecider.sourceOf(p.text, p.mode, p.detected, p.listening)
                val text = auditor?.audit(p.text, source, p.alternatives)?.text ?: p.text
                var translated = ""
                var error: String? = null
                for (attempt in 0 until 2) {
                    try {
                        translated = translator.translate(text, source).trim()
                        error = null
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                    }
                }
                val rec = TranslationRecord(
                    timeMillis = p.time, sessionId = sessionId, source = source,
                    original = text, translated = translated, alternatives = p.alternatives,
                )
                val saved = rec.copy(id = store.save(rec))
                if (error != null) listener.onError(error, saved) else listener.onTranslated(saved)
            }
        }
        synchronized(work) {
            work.removeAll { it.isCompleted }
            work.add(job)
        }
    }
}
