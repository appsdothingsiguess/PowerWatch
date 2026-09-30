package com.joey.powerwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.IOException
import java.time.Instant

/** Owns monitoring independently of the Activity. Disk writes are serialized off the main thread. */
class MonitoringService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var logger: EventLogger
    private lateinit var monitor: PowerMonitor
    private var networkMonitor: NetworkMonitor? = null
    private var probes: ProbeEngine? = null
    private lateinit var config: MonitoringConfig
    private lateinit var routerMachine: OutageStateMachine
    private lateinit var internetMachine: OutageStateMachine
    private var wakeLock: PowerManager.WakeLock? = null
    private val machine = PowerStateMachine()
    private val main = Handler(android.os.Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val bootCount by lazy {
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
    }
    private var started = false
    @Volatile private var stopRequested = false

    private val heartbeat = object : Runnable {
        override fun run() {
            guarded {
                record("heartbeat", "service", "up", PowerMonitor.now(),
                    detail = "Power=" + Status.power + "; router=" + Status.router + "; internet=" + Status.internet + "; " + Status.wifi)
                worker.postDelayed(this, config.heartbeatMs)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("PowerWatchLog").apply { start() }
        worker = Handler(thread.looper)
        logger = EventLogger(File(filesDir, "events.csv"))
        monitor = PowerMonitor(this) { connected, time ->
            worker.post {
                guarded {
                    val event = machine.observe(connected, time) ?: return@guarded
                    record(event.event, "power", event.state, time, event.durationMs, event.detail)
                    Status.power = if (connected) "Connected" else "Disconnected"
                    if (event.event == "outage_start") {
                        Status.lastOutage = "Started " + Instant.ofEpochMilli(time.wallMs)
                    } else if (event.event == "outage_end") {
                        Status.lastOutage = event.durationMs?.let { "Duration: $it ms" }
                            ?: "Restored; duration unknown"
                    }
                    checkpoint(true)
                    updateNotification()
                }
            }
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Power monitoring", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            stopSelf()
            return START_NOT_STICKY
        }
        if ((intent == null || intent.action == ACTION_BOOT) && !preferences.getBoolean("active", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        config = MonitoringConfig(
            preferences.getLong("interval_ms", 1000),
            preferences.getString("gateway_override", "").orEmpty(),
            preferences.getString("internet_ip", "1.1.1.1").orEmpty(),
            preferences.getInt("failure_threshold", 2),
            preferences.getLong("heartbeat_ms", 300_000))
        routerMachine = OutageStateMachine(config.failureThreshold)
        internetMachine = OutageStateMachine(config.failureThreshold)
        Status.power = "Unknown"
        Status.router = "Unknown"
        Status.internet = "Unknown"
        Status.wifi = "Awaiting Wi-Fi callback"
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        started = true
        Status.error = ""
        Status.running = true
        // Keeps one-second network probes, heartbeats, and event persistence active with the screen off.
        // No screen lock or Wi-Fi lock is used.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PowerWatch:Monitoring").apply {
                setReferenceCounted(false)
                acquire()
            }
        val startTime = PowerMonitor.now()
        worker.post {
            guarded {
                Status.lastOutage = preferences.getString("last_outage", "None recorded")!!
                Status.lastNetworkOutage = preferences.getString("last_network_outage", "None recorded")!!
                Status.configuration = "Interval: ${config.intervalMs} ms; failures: ${config.failureThreshold}; " +
                    "gateway: ${config.gatewayOverride.ifEmpty { "auto" }}; internet: ${config.internetIp}:443"
                if (preferences.getBoolean("active", false)) {
                    record("monitoring_interrupted", "service", "unknown", startTime,
                        detail = "Previous checkpoint: power=" + preferences.getString("power", "unknown") +
                            "; outage_wall_ms=" + preferences.getLong("outage_wall_ms", -1) +
                            "; outage_elapsed_ms=" + preferences.getLong("outage_elapsed_ms", -1) +
                            "; boot_count=" + preferences.getInt("boot_count", -1) +
                            "; router=" + preferences.getString("router_state", "unknown") +
                            "; router_start_elapsed_ms=" + preferences.getLong("router_start_elapsed_ms", -1) +
                            "; internet=" + preferences.getString("internet_state", "unknown") +
                            "; internet_start_elapsed_ms=" + preferences.getLong("internet_start_elapsed_ms", -1) +
                            "; gap unobserved; durations will not cross this gap")
                    if (preferences.getLong("router_start_elapsed_ms", -1) >= 0 ||
                        preferences.getLong("internet_start_elapsed_ms", -1) >= 0) {
                        Status.lastNetworkOutage = "Interrupted network outage; duration unknown"
                    }
                    if (preferences.getLong("outage_wall_ms", -1) >= 0) {
                        Status.lastOutage = "Interrupted outage; duration unknown. " + Status.lastOutage
                    }
                }
                preferences.edit().remove("boot_start_error").apply()
                record(if (intent == null || intent.action == ACTION_BOOT) "service_restart" else "monitoring_started",
                    "service", "up", startTime, detail = Status.configuration + "; heartbeat every ${config.heartbeatMs / 1000} seconds" +
                        (if (intent?.action == ACTION_BOOT) "; resumed after reboot" else ""))
                probes = ProbeEngine(worker, config, ::onProbe)
                networkMonitor = NetworkMonitor(this, worker) { path ->
                    guarded {
                        if (!Status.running) return@guarded
                        probes?.setPath(path)
                        val gateway = config.gatewayOverride.ifEmpty { path?.gateway.orEmpty() }
                        Status.wifi = if (path == null) "No active Wi-Fi; cellular excluded"
                            else "Wi-Fi interface: ${path.interfaceName ?: "pending"}; gateway: ${gateway.ifEmpty { "unavailable" }}"
                        record("network_changed", "wifi", if (path == null) "down" else "connected",
                            PowerMonitor.now(), detail = Status.wifi)
                        updateNotification()
                    }
                }.also { it.start() }
                probes?.start()
                checkpoint(true)
                worker.postDelayed(heartbeat, config.heartbeatMs)
            }
        }
        if (!monitor.start()) {
            worker.post {
                guarded {
                    record("monitoring_error", "power", "unknown", PowerMonitor.now(),
                        detail = "Initial battery plugged state unavailable; awaiting power event")
                }
            }
        }
        return START_STICKY
    }

    private fun record(
        event: String, target: String, state: String, time: EventTime,
        durationMs: Long? = null, detail: String = ""
    ) {
        logger.append(event, target, state, time, bootCount, durationMs, detail)
        Log.i("PowerWatch", "$event $target $state duration_ms=$durationMs")
    }

    private fun onProbe(target: String, result: ProbeResult, time: EventTime) {
        guarded {
            if (!Status.running) return@guarded
            val networkMachine = if (target == "router") routerMachine else internetMachine
            val event = networkMachine.observe(result.success, time)
            val display = when (networkMachine.state) {
                NetworkHealth.UP -> "Online · ${result.latencyMs} ms"
                NetworkHealth.SUSPECTED_DOWN -> "Suspected offline · " + result.detail
                NetworkHealth.DOWN -> "Offline · " + result.detail
                NetworkHealth.UNKNOWN -> "Unknown"
            }
            val previous = if (target == "router") Status.router else Status.internet
            if (target == "router") Status.router = display else Status.internet = display
            if (event != null) {
                record(event.event, target, event.state, event.time, event.durationMs, result.detail)
                if (event.event == "outage_start") {
                    Status.lastNetworkOutage = target + " started " + Instant.ofEpochMilli(event.time.wallMs)
                } else if (event.event == "outage_end") {
                    Status.lastNetworkOutage = target + ": " +
                        (event.durationMs?.let { "Duration: $it ms" } ?: "Restored; duration unknown")
                }
                checkpoint(true)
            }
            // Notification health changes only, never refreshed on each successful latency measurement.
            if (previous.substringBefore(" · ") != display.substringBefore(" · ")) updateNotification()
        }
    }

    private fun checkpoint(active: Boolean) {
        val saved = preferences.edit()
            .putBoolean("active", active)
            .putString("power", Status.power)
            .putString("last_outage", Status.lastOutage)
            .putLong("outage_wall_ms", machine.outageStart?.wallMs ?: -1)
            .putLong("outage_elapsed_ms", machine.outageStart?.elapsedMs ?: -1)
            .putString("last_network_outage", Status.lastNetworkOutage)
            .putString("router_state", routerMachine.state.name)
            .putLong("router_start_wall_ms", routerMachine.outageStart?.wallMs ?: -1)
            .putLong("router_start_elapsed_ms", routerMachine.outageStart?.elapsedMs ?: -1)
            .putString("internet_state", internetMachine.state.name)
            .putLong("internet_start_wall_ms", internetMachine.outageStart?.wallMs ?: -1)
            .putLong("internet_start_elapsed_ms", internetMachine.outageStart?.elapsedMs ?: -1)
            .putInt("boot_count", bootCount)
            .commit()
        if (!saved) throw IOException("Monitoring checkpoint write failed")
    }

    private fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (error: SecurityException) {
            Status.error = "Monitoring permission denied; monitoring stopped: " + error.message
            Log.e("PowerWatch", Status.error, error)
            stopRequested = true
            main.post { stopSelf() }
        } catch (error: IOException) {
            Status.error = "Logging failed; monitoring stopped: " + error.message
            Log.e("PowerWatch", Status.error, error)
            stopRequested = true
            main.post { stopSelf() }
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, MonitoringService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_power_monitor)
            .setContentTitle("PowerWatch")
            .setContentText("Power: ${Status.power}; router: ${Status.router.substringBefore(" · ")}; internet: ${Status.internet.substringBefore(" · ")}")
            .setStyle(Notification.BigTextStyle().bigText(
                "External charger power: ${Status.power}\nRouter: ${Status.router}\nInternet: ${Status.internet}\n${Status.wifi}"))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop monitoring", stop).build())
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    override fun onDestroy() {
        if (started) {
            Status.stopping = true
            monitor.stop()
            worker.removeCallbacks(heartbeat)
            val endTime = PowerMonitor.now()
            Status.running = false
            worker.post {
                try {
                    probes?.stop()
                    networkMonitor?.stop()
                    guarded {
                        record(if (stopRequested) "monitoring_stopped" else "monitoring_interrupted",
                            "service", if (stopRequested) "stopped" else "unknown", endTime,
                            detail = "Observation ended; any open outage has an unknown end")
                        if (machine.outageStart != null) {
                            Status.lastOutage = "Interrupted outage; duration unknown. " + Status.lastOutage
                        }
                        if (routerMachine.outageStart != null || internetMachine.outageStart != null) {
                            Status.lastNetworkOutage = "Interrupted network outage; duration unknown"
                        }
                        checkpoint(!stopRequested)
                    }
                } finally {
                    wakeLock?.let { if (it.isHeld) it.release() }
                    Status.stopping = false
                    thread.quitSafely()
                }
            }
        } else {
            thread.quitSafely()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.joey.powerwatch.STOP"
        const val ACTION_BOOT = "com.joey.powerwatch.BOOT"
        const val PREFS = "monitoring"
        private const val CHANNEL = "power_monitoring"
        private const val NOTIFICATION_ID = 1
    }

    /** Live process state; persisted 'active' is restart intent, not proof of a running service. */
    object Status {
        @Volatile var running = false
        @Volatile var stopping = false
        @Volatile var power = "Unknown"
        @Volatile var lastOutage = "None recorded"
        @Volatile var error = ""
        @Volatile var router = "Unknown"
        @Volatile var internet = "Unknown"
        @Volatile var wifi = "Not monitored"
        @Volatile var lastNetworkOutage = "None recorded"
        @Volatile var configuration = ""
    }
}
