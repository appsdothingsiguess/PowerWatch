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
    private val exportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportLog(uri)
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { startMonitoring() }
    private val refresh = object : Runnable {
        override fun run() {
            showStatus()
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
        findViewById<Button>(R.id.view_log).setOnClickListener { showLog() }
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
                    ?: throw IllegalArgumentException("Enter a failure threshold"))
            val preferences = getSharedPreferences(MonitoringService.PREFS, MODE_PRIVATE)
            // Save outside the UI thread; the next service start logs the applied configuration.
            val restartAfterBoot = findViewById<CheckBox>(R.id.start_after_reboot).isChecked
            savingConfiguration = true
            showStatus()
            logReader.execute {
                val saved = preferences.edit()
                    .putLong("interval_ms", config.intervalMs)
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
        val last = if (status.lastOutage == "None recorded") {
            getSharedPreferences(MonitoringService.PREFS, MODE_PRIVATE)
                .getString("last_outage", "None recorded")
        } else status.lastOutage
        val notificationNote = if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) "\nNotifications disabled in Android settings." else ""
        findViewById<TextView>(R.id.status).text =
            "Monitoring: " + (if (status.running) "Running" else "Stopped") +
            "\nExternal charger power: " + (if (status.running) status.power else "Not monitored") +
            "\nLast power outage: " + last +
            "\nRouter: " + (if (status.running) status.router else "Not monitored") +
            "\nInternet via Wi-Fi: " + (if (status.running) status.internet else "Not monitored") +
            "\n" + (if (status.running) status.wifi else "") +
            "\nLast network outage: " + status.lastNetworkOutage +
            "\n" + status.configuration + "\nHeartbeat: 5 minutes." +
            "\nRestart after reboot: " + (if (getSharedPreferences(MonitoringService.PREFS, MODE_PRIVATE)
                .getBoolean("start_after_reboot", true)) "Enabled when monitoring was running" else "Disabled") +
            "\n" + getSharedPreferences(MonitoringService.PREFS, MODE_PRIVATE).getString("boot_start_error", "").orEmpty() + notificationNote +
            (if (status.error.isNotEmpty()) "\n" + status.error else "") +
            (if (localError.isNotEmpty()) "\n" + localError else "")
        findViewById<Button>(R.id.start_monitoring).isEnabled = canEdit
        findViewById<Button>(R.id.save_configuration).isEnabled = canEdit
        listOf(R.id.check_interval, R.id.gateway_override, R.id.internet_target, R.id.failure_threshold)
            .forEach { findViewById<EditText>(it).isEnabled = canEdit }
        findViewById<CheckBox>(R.id.start_after_reboot).isEnabled = canEdit
        findViewById<Button>(R.id.stop_monitoring).isEnabled = status.running
        findViewById<Button>(R.id.export_csv).isEnabled = !exporting && !clearing
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

    private fun exportLog(uri: Uri) {
        exporting = true
        localError = "Exporting CSV…"
        showStatus()
        logReader.execute {
            val result = try {
                val output = contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("Unable to open the selected document")
                output.use { EventLogger(File(filesDir, "events.csv")).exportSnapshot(it) }
                "CSV exported"
            } catch (error: IOException) {
                "CSV export failed; the destination may be incomplete: " + error.message
            } catch (error: SecurityException) {
                "CSV export permission denied: " + error.message
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
        logReader.execute {
            val text = try {
                EventLogger(File(filesDir, "events.csv")).recentLines()
            } catch (error: IOException) {
                "Unable to read event log: " + error.message
            }
            runOnUiThread {
                if (!isDestroyed) findViewById<TextView>(R.id.event_log).text = text
            }
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
