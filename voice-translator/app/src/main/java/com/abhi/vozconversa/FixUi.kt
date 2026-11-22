package com.abhi.vozconversa

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.speech.RecognizerIntent
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.abhi.vozconversa.core.AuditKind
import com.abhi.vozconversa.core.AuditResult
import com.abhi.vozconversa.core.Corrector
import com.abhi.vozconversa.core.SentenceAuditor
import com.abhi.vozconversa.core.SentenceWords
import com.abhi.vozconversa.core.TranslationRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val WARN = 0xFFF59E0B.toInt()

/** Marks the words the auditor doubts (orange, underlined) in a sentence. */
fun highlightIssues(text: String, audit: AuditResult): CharSequence {
    if (audit.issues.isEmpty()) return text
    val s = SpannableString(text)
    val toks = SentenceWords.tokens(text)
    audit.issues.map { it.wordIndex }.distinct().forEach { i ->
        toks.getOrNull(i)?.let { t ->
            s.setSpan(ForegroundColorSpan(WARN), t.coreStart, t.coreEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(UnderlineSpan(), t.coreStart, t.coreEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return s
}

/** Explains in plain words why a word was flagged. */
fun issueReason(activity: AppCompatActivity, kind: AuditKind, word: String, suggestions: List<String>): String = when (kind) {
    AuditKind.REPEATED_WORD -> activity.getString(R.string.issue_repeated, word)
    AuditKind.OTHER_LANGUAGE -> activity.getString(R.string.issue_other_language, word)
    AuditKind.UNCERTAIN -> activity.getString(R.string.issue_uncertain, word, suggestions.joinToString(", "))
    else -> word
}

/**
 * The "fix a word" screens: tap the word that was heard wrong, choose a suggestion, type it, or say just
 * that word. The sentence is translated again and the saved entry updated — no need to repeat the sentence.
 */
class FixUi(private val activity: AppCompatActivity, private val onUpdated: (TranslationRecord) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val translator = lazy { MlKitTranslator() }
    private val dictionary = Corrections.dictionary(activity)
    val auditor = SentenceAuditor(dictionary)
    private val corrector by lazy { Corrector(translator.value, HistoryDb.get(activity), dictionary) }
    private val prefs = Prefs(activity)
    private var voiceResult: ((List<String>) -> Unit)? = null

    private val voiceLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        TranslatorService.resume(activity)
        val words = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        voiceResult?.invoke(words)
        voiceResult = null
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    /** Step 1: show the sentence; every word can be tapped. */
    fun open(record: TranslationRecord) {
        val audit = auditor.audit(record.original, record.source, record.alternatives)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(TextView(activity).apply { text = activity.getString(R.string.fix_tap_word); textSize = 14f })
        val sentence = TextView(activity).apply {
            textSize = 24f
            setPadding(0, dp(12), 0, dp(12))
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
        box.addView(sentence)
        if (audit.issues.isNotEmpty()) {
            val reasons = audit.issues.groupBy { it.wordIndex }.values.joinToString("\n") { list ->
                "⚠ " + list.joinToString("; ") { issueReason(activity, it.kind, it.word, it.suggestions) }
            }
            box.addView(TextView(activity).apply { text = reasons; setTextColor(WARN); textSize = 13f })
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.fix_title)
            .setView(box)
            .setNeutralButton(R.string.fix_whole) { _, _ -> wholeSentence(record) }
            .setNegativeButton(R.string.close, null)
            .create()

        val text = record.original
        val sb = SpannableStringBuilder(text)
        val flagged = audit.issues.map { it.wordIndex }.toSet()
        SentenceWords.tokens(text).forEachIndexed { i, t ->
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    dialog.dismiss()
                    val word = text.substring(t.coreStart, t.coreEnd)
                    val suggestions = audit.issuesAt(i).flatMap { it.suggestions }.distinct()
                    wordDialog(record, i, word, suggestions)
                }
                override fun updateDrawState(ds: TextPaint) {
                    ds.color = if (i in flagged) WARN else ContextCompat.getColor(activity, R.color.text)
                    ds.isUnderlineText = true
                }
            }, t.coreStart, t.coreEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (i in flagged) sb.setSpan(StyleSpan(Typeface.BOLD), t.coreStart, t.coreEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sentence.text = sb
        dialog.show()
    }

    /** Step 2: change one word. */
    private fun wordDialog(record: TranslationRecord, index: Int, word: String, suggestions: List<String>) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val edit = EditText(activity).apply {
            setText(word)
            setSelectAllOnFocus(true)
            textSize = 22f
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }
        val chips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun addChips(list: List<String>) {
            chips.removeAllViews()
            list.filter { !it.equals(word, ignoreCase = true) }.distinct().take(6).forEach { s ->
                chips.addView(Button(activity).apply {
                    text = s
                    isAllCaps = false
                    setOnClickListener { edit.setText(s); edit.setSelection(s.length) }
                })
            }
            chips.visibility = if (chips.childCount > 0) View.VISIBLE else View.GONE
        }
        addChips(suggestions)
        box.addView(HorizontalScrollView(activity).apply { addView(chips) })
        box.addView(edit)
        val mic = Button(activity).apply {
            text = activity.getString(R.string.fix_say_word)
            isAllCaps = false
        }
        box.addView(mic)
        val learn = CheckBox(activity).apply {
            text = activity.getString(R.string.fix_always, word)
            isChecked = true
        }
        box.addView(learn)

        mic.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, record.source.locale)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                // Works without internet when the offline speech pack is on the phone.
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !Net.isOnline(activity))
                .putExtra(RecognizerIntent.EXTRA_PROMPT, activity.getString(R.string.fix_say_prompt, word))
            voiceResult = { heard ->
                if (heard.isNotEmpty()) {
                    edit.setText(heard[0])
                    edit.setSelection(heard[0].length)
                    addChips(heard + suggestions)
                }
            }
            TranslatorService.pause(activity)
            try {
                voiceLauncher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                TranslatorService.resume(activity)
                Toast.makeText(activity, R.string.status_no_recognizer, Toast.LENGTH_LONG).show()
            }
        }

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.fix_word_title, word))
            .setView(box)
            .setPositiveButton(R.string.fix_change) { _, _ ->
                val rep = edit.text.toString().trim()
                if (rep.isNotEmpty() && rep != word) apply { corrector.replaceWord(record, index, rep, learn.isChecked) }
            }
            .setNeutralButton(R.string.fix_delete_word) { _, _ -> apply { corrector.replaceWord(record, index, "", false) } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        edit.requestFocus()
    }

    /** Edit the whole sentence as text. */
    private fun wholeSentence(record: TranslationRecord) {
        val edit = EditText(activity).apply {
            setText(record.original)
            textSize = 18f
            setSelection(record.original.length)
        }
        val box = LinearLayout(activity).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(edit) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.fix_whole)
            .setView(box)
            .setPositiveButton(R.string.fix_change) { _, _ ->
                val t = edit.text.toString().trim()
                if (t.isNotEmpty() && t != record.original) apply { corrector.rewrite(record, t) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun apply(block: suspend () -> TranslationRecord) {
        scope.launch {
            val updated = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                Toast.makeText(activity, e.message ?: "Error", Toast.LENGTH_LONG).show()
                return@launch
            }
            onUpdated(updated)
            Toast.makeText(
                activity,
                if (updated.translated.isBlank()) R.string.fix_saved_untranslated else R.string.fix_done,
                Toast.LENGTH_SHORT,
            ).show()
            if (prefs.speakAloud && updated.translated.isNotBlank()) {
                if (TranslatorService.running) TranslatorService.say(activity, updated.translated, updated.target)
            }
        }
    }

    /** Lets the user see and remove the word fixes the app has learned. */
    fun showLearned() {
        val list = dictionary.all()
        if (list.isEmpty()) {
            AlertDialog.Builder(activity).setTitle(R.string.learned_title).setMessage(R.string.learned_empty)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val labels = list.map { "${it.lang.short}:  ${it.wrong}  →  ${it.right}" }.toTypedArray()
        AlertDialog.Builder(activity).setTitle(R.string.learned_title)
            .setItems(labels) { _, which ->
                val c = list[which]
                AlertDialog.Builder(activity).setMessage(activity.getString(R.string.learned_forget, c.wrong, c.right))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        scope.launch(Dispatchers.IO) { dictionary.forget(c) }
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            .setNegativeButton(R.string.close, null).show()
    }

    fun close() {
        scope.cancel()
        if (translator.isInitialized()) translator.value.close()
    }
}
