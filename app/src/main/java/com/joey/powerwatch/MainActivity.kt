package com.joey.powerwatch

import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.view.View
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.RelativeSizeSpan
import android.graphics.Typeface
import java.time.OffsetDateTime
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** Controls and displays status only; monitoring belongs to MonitoringService. */
class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val logReader = Executors.newSingleThreadExecutor()
    private var localError = ""
    private var savingConfiguration = false
    private var exporting = false
    private var clearing = false
    private var logLoading = false
    private var logReloadRequested = false
    private var loadedRevision = -1L
    private var logView: LogView? = null
    private var filter = LogFilter.ALL
    private var reportFilter = LogFilter.ALL
    private var bootCount = -1
    private val exportReportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) exportLog(uri, report = true, selectedFilter = reportFilter)
    }
    private val exportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportLog(uri)
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { startMonitoring() }
    private val refresh = object : Runnable {
        override fun run() {
            showStatus()
            showOutageSummary()
            if (loadedRevision != MonitoringService.Status.logRevision && !logLoading) showLog()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<Button>(R.id.start_monitoring).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else startMonitoring()
        }
        findViewById<Button>(R.id.stop_monitoring).setOnClickListener {
            if (MonitoringService.Status.running) {
                startService(Intent(this, MonitoringService::class.java)
                    .setAction(MonitoringService.ACTION_STOP))
            }
        }
        bootCount = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val preferencesForView = getSharedPreferences(MonitoringService.PREFS, MODE_PRIVATE)
        filter = LogFilter.entries.getOrElse(savedInstanceState?.getInt("filter")
            ?: preferencesForView.getInt("log_filter", 0)) { LogFilter.ALL }
        reportFilter = LogFilter.entries.getOrElse(savedInstanceState?.getInt("report_filter") ?: 0) { LogFilter.ALL }
        if (savedInstanceState == null && intent?.action == MonitoringService.ACTION_OUTAGES) filter = LogFilter.OUTAGES
        findViewById<Spinner>(R.id.log_filter).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item,
                LogFilter.entries.map { it.label }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(filter.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    filter = LogFilter.entries[position]
                    preferencesForView.edit().putInt("log_filter", position).apply()
                    showLog()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        findViewById<View>(R.id.configuration_fields).visibility =
            if (savedInstanceState?.getBoolean("configuration_open") == true) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.toggle_configuration).setOnClickListener {
            val fields = findViewById<View>(R.id.configuration_fields)
            fields.visibility = if (fields.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.view_log).setOnClickListener { showLog() }
        findViewById<Button>(R.id.export_report).setOnClickListener {
            reportFilter = filter
            try {
                exportReportDocument.launch("PowerWatch-${filter.name.lowercase()}-${System.currentTimeMillis()}.html")
            } catch (error: ActivityNotFoundException) {
                localError = "No document picker available: " + error.message
                showStatus()
            }
        }
        findViewById<Button>(R.id.clear_log).setOnClickListener {
            if (!canClearLog()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Clear saved event log?")
                .setMessage("Deletes the internal CSV history. Export a copy first if you want to keep it. Exported copies are retained.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear log") { _, _ -> clearLog() }
                .show()
        }
        findViewById<Button>(R.id.export_csv).setOnClickListener {
            try {
                exportDocument.launch("PowerWatch-${System.currentTimeMillis()}.csv")
            } catch (error: ActivityNotFoundException) {
                localError = "No document picker available: " + error.message
                showStatus()
            }
        }
        val preferences = getSharedPreferences(MonitoringService.PREFS, MODE_PRIVATE)
        findViewById<EditText>(R.id.check_interval).setText(preferences.getLong("interval_ms", 1000).toString())
        findViewById<EditText>(R.id.heartbeat_interval).setText((preferences.getLong("heartbeat_ms", 300_000) / 1000).toString())
        findViewById<EditText>(R.id.gateway_override).setText(preferences.getString("gateway_override", ""))
        findViewById<EditText>(R.id.internet_target).setText(preferences.getString("internet_ip", "1.1.1.1"))
        findViewById<EditText>(R.id.failure_threshold).setText(preferences.getInt("failure_threshold", 2).toString())
        findViewById<CheckBox>(R.id.start_after_reboot).isChecked = preferences.getBoolean("start_after_reboot", true)
        findViewById<Button>(R.id.save_configuration).setOnClickListener { saveConfiguration() }
    }

    private fun saveConfiguration() {
        if (MonitoringService.Status.running) return
        try {
            val config = MonitoringConfig(
                findViewById<EditText>(R.id.check_interval).text.toString().toLongOrNull()
                    ?: throw IllegalArgumentException("Enter an interval in milliseconds"),
                findViewById<EditText>(R.id.gateway_override).text.toString().trim(),
                findViewById<EditText>(R.id.internet_target).text.toString().trim(),
                findViewById<EditText>(R.id.failure_threshold).text.toString().toIntOrNull()
                    ?: throw IllegalArgumentException("Enter a failure threshold"),
                heartbeatMs = (findViewById<EditText>(R.id.heartbeat_interval).text.toString().toLongOrNull()
                    ?.takeIf { it in 10..3600 }
                    ?: throw IllegalArgumentException("Heartbeat must be 10–3600 seconds")) * 1000)
            val preferences = getSharedPreferences(MonitoringService.PREFS, MODE_PRIVATE)
            // Save outside the UI thread; the next service start logs the applied configuration.
            val restartAfterBoot = findViewById<CheckBox>(R.id.start_after_reboot).isChecked
            savingConfiguration = true
            showStatus()
            logReader.execute {
                val saved = preferences.edit()
                    .putLong("interval_ms", config.intervalMs)
                    .putLong("heartbeat_ms", config.heartbeatMs)
                    .putString("gateway_override", config.gatewayOverride)
                    .putString("internet_ip", config.internetIp)
                    .putInt("failure_threshold", config.failureThreshold)
                    .putBoolean("start_after_reboot", restartAfterBoot).commit()
                runOnUiThread {
                    if (!isDestroyed) {
                        savingConfiguration = false
                        localError = if (saved) "Configuration saved" else "Configuration write failed"
                        showStatus()
                    }
                }
            }
        } catch (error: IllegalArgumentException) {
            localError = error.message.orEmpty()
            showStatus()
        }
    }

    private fun startMonitoring() {
        localError = ""
        try {
            ContextCompat.startForegroundService(this, Intent(this, MonitoringService::class.java))
        } catch (error: IllegalStateException) {
            localError = "Unable to start monitoring: " + error.message
        } catch (error: SecurityException) {
            localError = "Monitoring permission missing: " + error.message
        }
        showStatus()
    }

    private fun showStatus() {
        val status = MonitoringService.Status
        val canEdit = !status.running && !status.stopping && !savingConfiguration && !clearing
        val notificationNote = if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) "\nNotifications disabled in Android settings." else ""
        findViewById<TextView>(R.id.status).text =
            "Monitoring: " + (if (status.running) "Running" else "Stopped") +
            "\nExternal charger power: " + (if (status.running) status.power else "Not monitored") +
            "\nRouter: " + (if (status.running) status.router else "Not monitored") +
            "\nInternet via Wi-Fi: " + (if (status.running) status.internet else "Not monitored") +
            "\n" + (if (status.running) status.wifi else "") +
            "\nHeartbeat: " +
            (getSharedPreferences(MonitoringService.PREFS, MODE_PRIVATE).getLong("heartbeat_ms", 300_000) / 1000) +
            " seconds (service check-in)." +
            "\nRestart after reboot: " + (if (getSharedPreferences(MonitoringService.PREFS, MODE_PRIVATE)
                .getBoolean("start_after_reboot", true)) "Enabled when monitoring was running" else "Disabled") +
            "\n" + getSharedPreferences(MonitoringService.PREFS, MODE_PRIVATE).getString("boot_start_error", "").orEmpty() + notificationNote +
            (if (status.error.isNotEmpty()) "\n" + status.error else "") +
            (if (localError.isNotEmpty()) "\n" + localError else "")
        findViewById<Button>(R.id.start_monitoring).isEnabled = canEdit
        findViewById<Button>(R.id.save_configuration).isEnabled = canEdit
        listOf(R.id.check_interval, R.id.heartbeat_interval, R.id.gateway_override, R.id.internet_target, R.id.failure_threshold)
            .forEach { findViewById<EditText>(it).isEnabled = canEdit }
        findViewById<CheckBox>(R.id.start_after_reboot).isEnabled = canEdit
        findViewById<Button>(R.id.stop_monitoring).isEnabled = status.running
        findViewById<Button>(R.id.export_csv).isEnabled = !exporting && !clearing
        findViewById<Button>(R.id.export_report).isEnabled = !exporting && !clearing
        findViewById<Button>(R.id.clear_log).isEnabled = canClearLog()
    }

    private fun canClearLog(): Boolean {
        val status = MonitoringService.Status
        return !status.running && !status.stopping && !exporting && !clearing && !savingConfiguration &&
            !getSharedPreferences(MonitoringService.PREFS, MODE_PRIVATE).getBoolean("active", false)
    }

    private fun clearLog() {
        if (!canClearLog()) return
        clearing = true
        showStatus()
        logReader.execute {
            val result = try {
                if (MonitoringService.Status.running || MonitoringService.Status.stopping ||
                    getSharedPreferences(MonitoringService.PREFS, MODE_PRIVATE).getBoolean("active", false)) {
                    throw IOException("Stop monitoring before clearing")
                }
                EventLogger(File(filesDir, "events.csv")).clear()
                "Internal event log cleared; exported copies retained"
            } catch (error: IOException) {
                "Unable to clear log: " + error.message
            } catch (error: SecurityException) {
                "Unable to clear log: " + error.message
            }
            runOnUiThread {
                if (!isDestroyed) {
                    clearing = false
                    localError = result
                    showStatus()
                    showLog()
                }
            }
        }
    }

    private fun exportLog(uri: Uri, report: Boolean = false, selectedFilter: LogFilter = LogFilter.ALL) {
        exporting = true
        val format = if (report) "Report" else "CSV"
        localError = "Exporting $format…"
        showStatus()
        logReader.execute {
            val result = try {
                val output = contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("Unable to open the selected document")
                output.use { destination ->
                    val logger = EventLogger(File(filesDir, "events.csv"))
                    if (report) logger.readSnapshot { source ->
                        LogPresentation.exportReport(source, destination, selectedFilter, OffsetDateTime.now().toString())
                    } else logger.exportSnapshot(destination)
                }
                "$format exported" + if (report) " · " + selectedFilter.label + " · full history" else ""
            } catch (error: IOException) {
                "$format export failed; the destination may be incomplete: " + error.message
            } catch (error: SecurityException) {
                "$format export permission denied: " + error.message
            }
            runOnUiThread {
                if (!isDestroyed) {
                    exporting = false
                    localError = result
                    showStatus()
                }
            }
        }
    }

    private fun showLog() {
        if (logLoading) { logReloadRequested = true; return }
        logLoading = true
        logReloadRequested = false
        val requestedFilter = filter
        val revision = MonitoringService.Status.logRevision
        logReader.execute {
            var errorText = ""
            val result = try {
                EventLogger(File(filesDir, "events.csv")).readSnapshot {
                    LogPresentation.read(it, requestedFilter)
                }
            } catch (error: IOException) {
                errorText = "Unable to read event log: " + error.message
                null
            } catch (error: SecurityException) {
                errorText = "Unable to access event log: " + error.message
                null
            }
            runOnUiThread {
                if (!isDestroyed) {
                    logLoading = false
                    if (requestedFilter == filter) {
                        loadedRevision = revision
                        logView = result
                        val text = SpannableStringBuilder()
                        result?.entries?.forEach { entry ->
                            val start = text.length
                            text.append(entry.title).append("\n")
                            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            text.setSpan(RelativeSizeSpan(1.12f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            text.append(entry.body()).append("\n\n")
                        }
                        findViewById<TextView>(R.id.event_log).text = if (errorText.isNotEmpty()) errorText
                            else if (text.isEmpty()) "No matching events recorded." else text
                        findViewById<TextView>(R.id.log_count).text = if (result == null) "Log unavailable" else
                            "${requestedFilter.label} · ${result.entries.size} of ${result.matchingCount} events · newest first"
                        showOutageSummary()
                    }
                    if (logReloadRequested || requestedFilter != filter) showLog()
                }
            }
        }
    }

    private fun showOutageSummary() {
        val view = logView
        val entries = view?.activeOutages?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(view?.latestOutage)
        findViewById<TextView>(R.id.outage_summary).text = if (view == null) {
            if (loadedRevision < 0) "Loading outage history…" else "Outage history unavailable · see event log error"
        } else if (entries.isEmpty()) {
            "No outages recorded."
        } else entries.joinToString("\n\n") {
            it.outageSummary(SystemClock.elapsedRealtime(), bootCount, MonitoringService.Status.running)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("filter", filter.ordinal)
        outState.putInt("report_filter", reportFilter.ordinal)
        outState.putBoolean("configuration_open", findViewById<View>(R.id.configuration_fields).visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == MonitoringService.ACTION_OUTAGES) {
            findViewById<Spinner>(R.id.log_filter).setSelection(LogFilter.OUTAGES.ordinal)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
        showLog()
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        logReader.shutdown()
        super.onDestroy()
    }
}
