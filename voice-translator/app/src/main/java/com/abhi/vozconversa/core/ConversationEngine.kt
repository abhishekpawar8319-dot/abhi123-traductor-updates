package com.abhi.vozconversa.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

interface TextTranslator {
    suspend fun translate(text: String, source: Lang): String
}

interface HistoryStore {
    /** Saves the record and returns its id. */
    fun save(record: TranslationRecord): Long

    /** Replaces a saved record (same id): translation finished, or the user fixed it. */
    fun update(record: TranslationRecord) {}

    /** Sentences still waiting for a translation (PENDING or FAILED), oldest first. */
    fun pending(): List<TranslationRecord> = emptyList()
}

/**
 * Turns what the recognizer hears into saved sentences and translations, in two separate stages so a busy
 * conversation never makes the app fall behind:
 *
 * 1. **Record** (instant): each sentence is checked by the auditor and saved straight away, before any
 *    translation. Nothing that was heard can be lost, even if the app is closed.
 * 2. **Translate** (background queue): sentences are translated one after another, in the order spoken,
 *    while listening carries on. A failed translation is retried, never skipped. In [ListenMode.MIXED_ES]
 *    English sentences are recorded but need no translation, which keeps the queue short.
 *
 * It also waits for the whole sentence: an utterance ending on a word like "and"/"que" is held for
 * [holdMillis] so its continuation can be joined.
 */
class ConversationEngine(
    private val scope: CoroutineScope,
    private val translator: TextTranslator,
    private val store: HistoryStore,
    private val sessionId: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val holdMillis: Long = 1200,
    private val listener: Listener,
    /** Checks and auto-fixes each sentence; null = keep it as heard. */
    private val auditor: SentenceAuditor? = null,
    /** Where translation runs (the service uses a background thread). */
    workContext: CoroutineContext = EmptyCoroutineContext,
    /** Off = record every sentence, translate only when the user asks. */
    private val autoTranslate: () -> Boolean = { true },
) {
    interface Listener {
        /** A sentence was heard and saved; its translation follows. */
        fun onCaptured(record: TranslationRecord) {}
        fun onTranslated(record: TranslationRecord)
        fun onError(message: String, record: TranslationRecord) {}
    }

    private data class Pending(
        val text: String, val mode: ListenMode, val detected: Lang?, val listening: Lang, val time: Long,
        val alternatives: List<String> = emptyList(),
    )

    private var pending: Pending? = null
    private var holdJob: Job? = null
    private val queue = ArrayDeque<TranslationRecord>()
    private val waiting = AtomicInteger(0)
    private var workerJob: Job? = null
    private val workContext = workContext

    /** Sentences recorded but not translated yet. */
    val backlog: Int get() = waiting.get()

    /** Called with each final recognizer result. Returns the language it decided on, or null if ignored. */
    fun onUtterance(
        raw: String, mode: ListenMode, detected: Lang?, listening: Lang, alternatives: List<String> = emptyList(),
    ): Lang? {
        val clean = SentenceText.normalize(raw)
        if (SentenceText.isNoise(clean)) return null
        holdJob?.cancel()
        var prev = pending
        // A held fragment followed by a sentence clearly in the other language is another person speaking:
        // keep them as two sentences.
        if (prev != null) {
            val a = LanguageGuesser.clearly(prev.text)
            val b = LanguageGuesser.clearly(clean)
            if (a != null && b != null && a != b) { flush(); prev = null }
        }
        // Join with the continuation exactly as heard (the recognizer only capitalizes names and "I").
        val combined = if (prev != null) SentenceText.join(prev.text, raw.trim().replace(Regex("\\s+"), " ")) else clean
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
            record(item)
        }
        return source
    }

    /** Records any held fragment right away. */
    fun flush() {
        val p = pending ?: return
        pending = null
        holdJob?.cancel()
        record(p)
    }

    /** Stage 1: save now, translate later. */
    private fun record(p: Pending) {
        val source = DirectionDecider.sourceOf(p.text, p.mode, p.detected, p.listening)
        val text = auditor?.audit(p.text, source, p.alternatives)?.text ?: p.text
        val auto = autoTranslate()
        val needed = auto && !(p.mode == ListenMode.MIXED_ES && source == Lang.EN)
        val rec = TranslationRecord(
            timeMillis = p.time, sessionId = sessionId, source = source, original = text, translated = "",
            alternatives = p.alternatives,
            status = when {
                !auto -> TStatus.ON_REQUEST
                needed -> TStatus.PENDING
                else -> TStatus.NOT_NEEDED
            },
        )
        val saved = rec.copy(id = store.save(rec))
        listener.onCaptured(saved)
        if (needed) enqueue(saved) else listener.onTranslated(saved)
    }

    /** Adds to the queue; starts the background worker if it is not running. */
    private fun enqueue(rec: TranslationRecord) {
        synchronized(queue) {
            queue.addLast(rec)
            waiting.incrementAndGet()
            if (workerJob == null) workerJob = scope.launch(workContext) { work() }
        }
    }

    private suspend fun work() {
        while (true) {
            val next = synchronized(queue) {
                queue.removeFirstOrNull() ?: run { workerJob = null; null }
            } ?: return
            try {
                translateOne(next)
            } finally {
                waiting.decrementAndGet()
            }
        }
    }

    /** Stage 2: translate one sentence (one quick retry), save the result. */
    private suspend fun translateOne(rec: TranslationRecord) {
        var translated = ""
        var error: String? = null
        for (attempt in 0 until 2) {
            try {
                translated = translator.translate(rec.original, rec.source).trim()
                error = null
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
        val done = rec.copy(translated = translated, status = if (error == null) TStatus.DONE else TStatus.FAILED)
        store.update(done)
        if (error != null) listener.onError(error, done) else listener.onTranslated(done)
    }

    /** Queues every saved sentence that still has no translation (after a restart). */
    fun resumePending(): Int {
        val list = store.pending()
        list.forEach { enqueue(it.copy(status = TStatus.PENDING)) }
        return list.size
    }

    /** Tries failed translations again (when the internet or the language pack comes back). */
    fun retryFailed(): Int {
        if (waiting.get() > 0) return 0 // the queue is still working; failed ones are retried after
        val list = store.pending().filter { it.status == TStatus.FAILED }
        list.forEach { enqueue(it.copy(status = TStatus.PENDING)) }
        return list.size
    }

    /** Records any held fragment and waits until the translation queue is empty. */
    suspend fun drain() {
        flush()
        while (waiting.get() > 0) delay(20)
    }

    fun close() {
        synchronized(queue) { workerJob?.cancel(); workerJob = null }
    }
}

/**
 * Keeps every word the recognizer heard, even when it stops early: if a listening session is cut by an
 * error or a restart, the words it had already heard (its last partial result) are kept as a sentence.
 * The only thing dropped is a true duplicate: a final result that repeats words just rescued that way.
 * Real repeats ("Sí" … "Sí" from two people) are all kept.
 */
class CaptureTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val emit: (text: String, alternatives: List<String>) -> Unit,
) {
    private var partial = ""
    private var rescued = ""
    private var rescuedAt = 0L

    fun onPartial(text: String?) {
        if (!text.isNullOrBlank()) partial = text
    }

    /** A finished sentence (normal result, or one segment of a continuous session). */
    fun onFinal(results: List<String>) {
        partial = ""
        val text = results.firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return
        val repeatOfRescued = rescued.isNotEmpty() && text.equals(rescued, ignoreCase = true) && clock() - rescuedAt < 5_000
        rescued = ""
        if (repeatOfRescued) return
        emit(text, results.drop(1))
    }

    /** The session ended without a final result (error, timeout, restart): keep what was heard. */
    fun onInterrupted() {
        val text = partial.trim()
        partial = ""
        if (text.isEmpty()) return
        rescued = text
        rescuedAt = clock()
        emit(text, emptyList())
    }

    val hasUnfinishedSpeech: Boolean get() = partial.isNotBlank()
}
