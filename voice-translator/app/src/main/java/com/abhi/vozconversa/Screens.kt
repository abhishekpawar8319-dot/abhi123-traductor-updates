package com.abhi.vozconversa

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.abhi.vozconversa.core.ListenMode
import com.abhi.vozconversa.core.TranscriptExporter
import com.abhi.vozconversa.core.TranslationRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class TranslationAdapter : RecyclerView.Adapter<TranslationAdapter.VH>() {
    private val items = ArrayList<TranslationRecord>()
    private val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    var onLongPress: ((TranslationRecord) -> Unit)? = null
    var onPlay: ((TranslationRecord) -> Unit)? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tag: TextView = v.findViewById(R.id.tag)
        val original: TextView = v.findViewById(R.id.original)
        val translated: TextView = v.findViewById(R.id.translated)
        val play: Button = v.findViewById(R.id.play)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setAll(list: List<TranslationRecord>) { items.clear(); items.addAll(list); notifyDataSetChanged() }

    fun addTop(r: TranslationRecord) { items.add(0, r); notifyItemInserted(0) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_translation, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = items[position]
        val day = if (DateUtils.isToday(r.timeMillis)) "" else DateUtils.formatDateTime(
            h.itemView.context, r.timeMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        ) + " "
        h.tag.text = "${r.source.short} → ${r.target.short} · $day${time.format(Date(r.timeMillis))}"
        h.original.text = r.original
        h.translated.text = r.translated.ifBlank { h.itemView.context.getString(R.string.not_translated) }
        h.itemView.setOnLongClickListener { onLongPress?.invoke(r); onLongPress != null }
        h.play.visibility = if (r.translated.isBlank()) View.INVISIBLE else View.VISIBLE
        h.play.setOnClickListener { onPlay?.invoke(r) }
    }
}

private val io = Executors.newSingleThreadExecutor()

/** Plays one translation: through the service if it is listening (so it pauses the mic), else directly. */
internal fun playTranslation(activity: AppCompatActivity, speaker: Lazy<Speaker>, r: TranslationRecord) {
    if (r.translated.isBlank()) return
    if (TranslatorService.running) TranslatorService.say(activity, r.translated, r.target)
    else speaker.value.say(r.translated, r.target)
}

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var partial: TextView
    private val adapter = TranslationAdapter()
    private lateinit var modeButtons: Map<ListenMode, Button>
    private lateinit var speakerButton: Button
    private val speaker = lazy { Speaker(this) }

    private val busListener: (LiveBus.Event) -> Unit = { e ->
        when (e) {
            is LiveBus.Event.Status -> showStatus(e)
            is LiveBus.Event.Partial -> partial.text = e.text
            is LiveBus.Event.Speaker -> showSpeaker()
            is LiveBus.Event.Translated -> {
                adapter.addTop(e.record)
                findViewById<RecyclerView>(R.id.list).scrollToPosition(0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        partial = findViewById(R.id.partial)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        modeButtons = mapOf(
            ListenMode.AUTO to findViewById(R.id.modeAuto),
            ListenMode.SPANISH to findViewById(R.id.modeEs),
            ListenMode.ENGLISH to findViewById(R.id.modeEn),
        )
        modeButtons.forEach { (mode, b) ->
            b.setOnClickListener {
                prefs.mode = mode
                showMode()
                TranslatorService.settingsChanged(this)
            }
        }
        speakerButton = findViewById(R.id.speaker)
        speakerButton.setOnClickListener {
            TranslatorService.setSpeaker(this, !prefs.speakAloud)
            showSpeaker()
        }
        adapter.onPlay = { r -> playTranslation(this, speaker, r) }
        showSpeaker()
        toggle.setOnClickListener {
            if (TranslatorService.running) TranslatorService.stop(this) else startWithPermissions()
        }
        findViewById<Button>(R.id.history).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<Button>(R.id.battery).setOnClickListener { askBatteryExemption() }
        showMode()
        // Opened from the "resume after restart" notification.
        if (savedInstanceState == null && intent.getBooleanExtra("autostart", false) && !TranslatorService.running) {
            startWithPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        LiveBus.add(busListener)
        showStatus(LiveBus.lastStatus.copy(running = TranslatorService.running))
        showSpeaker() // may have been changed from the notification
        io.execute {
            val list = HistoryDb.get(this).recent(100)
            runOnUiThread { adapter.setAll(list) }
        }
        updateBatteryButton()
    }

    override fun onStop() {
        LiveBus.remove(busListener)
        super.onStop()
    }

    override fun onDestroy() {
        if (speaker.isInitialized()) speaker.value.shutdown()
        super.onDestroy()
    }

    private fun showSpeaker() {
        val on = prefs.speakAloud
        speakerButton.isActivated = on
        speakerButton.text = getString(if (on) R.string.speaker_on else R.string.speaker_off)
    }

    private fun showStatus(s: LiveBus.Event.Status) {
        val on = s.running || TranslatorService.running
        toggle.text = getString(if (on) R.string.stop else R.string.start)
        toggle.isActivated = on
        status.text = s.text.ifBlank { getString(if (on) R.string.status_listening_auto else R.string.status_stopped) }
        if (!on) partial.text = ""
    }

    private fun showMode() {
        modeButtons.forEach { (mode, b) -> b.isSelected = mode == prefs.mode }
    }

    private fun startWithPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) TranslatorService.start(this)
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            TranslatorService.start(this)
            if (!isIgnoringBattery()) askBatteryExemption()
        } else {
            Toast.makeText(this, R.string.status_need_mic, Toast.LENGTH_LONG).show()
        }
    }

    private fun isIgnoringBattery(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun updateBatteryButton() {
        findViewById<Button>(R.id.battery).visibility = if (isIgnoringBattery()) View.GONE else View.VISIBLE
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        // Without this, Android may pause the app at night and the 24/7 listening would stop.
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}

class HistoryActivity : AppCompatActivity() {
    private val adapter = TranslationAdapter()
    private lateinit var db: HistoryDb
    private lateinit var info: TextView
    private var query = ""
    private val speaker = lazy { Speaker(this) }
    private val retentionChoices = intArrayOf(30, 90, 180, 365, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        db = HistoryDb.get(this)
        info = findViewById(R.id.info)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = this@HistoryActivity.adapter
        }
        adapter.onPlay = { r -> playTranslation(this, speaker, r) }
        adapter.onLongPress = { r ->
            AlertDialog.Builder(this).setMessage(R.string.delete_one)
                .setPositiveButton(R.string.delete) { _, _ -> io.execute { db.delete(r.id); reload() } }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); reload() }
        })
        val prefs = Prefs(this)
        findViewById<Spinner>(R.id.retention).apply {
            adapter = ArrayAdapter(
                this@HistoryActivity, android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.retention_labels)
            )
            setSelection(retentionChoices.indexOf(prefs.retentionDays).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (prefs.retentionDays == retentionChoices[pos]) return
                    prefs.retentionDays = retentionChoices[pos]
                    com.abhi.vozconversa.core.Retention.cutoff(System.currentTimeMillis(), prefs.retentionDays)?.let { c ->
                        io.execute { db.deleteOlderThan(c); reload() }
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        findViewById<Button>(R.id.exportTxt).setOnClickListener { export(false) }
        findViewById<Button>(R.id.exportCsv).setOnClickListener { export(true) }
        findViewById<Button>(R.id.deleteAll).setOnClickListener {
            AlertDialog.Builder(this).setMessage(R.string.delete_all_confirm)
                .setPositiveButton(R.string.delete) { _, _ -> io.execute { db.deleteAll(); reload() } }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        reload()
    }

    override fun onDestroy() {
        if (speaker.isInitialized()) speaker.value.shutdown()
        super.onDestroy()
    }

    private fun reload() {
        val q = query
        io.execute {
            val list = db.recent(1000, q)
            val count = db.count()
            val size = Formatter.formatShortFileSize(this, db.sizeBytes(this))
            runOnUiThread {
                adapter.setAll(list)
                info.text = getString(R.string.history_info, count, size)
            }
        }
    }

    private fun export(csv: Boolean) {
        io.execute {
            val all = db.all()
            val dir = File(cacheDir, "export").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            val file = File(dir, "translations_$stamp." + if (csv) "csv" else "txt")
            file.writeText(if (csv) TranscriptExporter.toCsv(all) else TranscriptExporter.toText(all))
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (csv) "text/csv" else "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runOnUiThread { startActivity(Intent.createChooser(send, getString(R.string.export))) }
        }
    }
}

/** After a reboot Android does not let apps turn the microphone on by themselves; show a one-tap reminder. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs(context).wasRunning) showResume(context)
    }

    companion object {
    fun showResume(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel("voz_resume", context.getString(R.string.channel_resume), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { setSound(null, null) }
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).putExtra("autostart", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            43, NotificationCompat.Builder(context, "voz_resume")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.resume_title))
                .setContentText(context.getString(R.string.resume_text))
                .setSilent(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        )
    }
    }
}
