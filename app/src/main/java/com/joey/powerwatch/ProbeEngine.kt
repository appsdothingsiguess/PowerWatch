package com.joey.powerwatch

import android.os.Handler
import android.os.SystemClock
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors

data class ProbeResult(val success: Boolean, val latencyMs: Long, val detail: String)
interface HostProbe {
    fun check(path: WifiPath, host: String): ProbeResult
}

/** Android's initial ICMP/TCP-echo method, explicitly scoped to the Wi-Fi interface. */
class RouterProbe : HostProbe {
    override fun check(path: WifiPath, host: String): ProbeResult {
        val started = SystemClock.elapsedRealtime()
        return try {
            val name = path.interfaceName ?: return ProbeResult(false, 0, "Wi-Fi interface unavailable")
            val iface = NetworkInterface.getByName(name)
                ?: return ProbeResult(false, 0, "Wi-Fi interface disappeared")
            val address = InetAddress.getByName(host)
            // isReachable must not be allowed to choose a default source/interface.
            if (Collections.list(iface.inetAddresses).none { it.javaClass == address.javaClass }) {
                return ProbeResult(false, 0, "No Wi-Fi source address for gateway")
            }
            val success = address.isReachable(iface, 2, TIMEOUT_MS)
            ProbeResult(success, SystemClock.elapsedRealtime() - started,
                if (success) "Wi-Fi interface ICMP/TCP echo" else "Gateway probe timeout/unreachable")
        } catch (error: IOException) {
            ProbeResult(false, SystemClock.elapsedRealtime() - started, "Gateway I/O: " + error.javaClass.simpleName)
        } catch (error: SecurityException) {
            ProbeResult(false, SystemClock.elapsedRealtime() - started, "Gateway permission denied")
        }
    }

    companion object { const val TIMEOUT_MS = 700 }
}

/** A TCP handshake to the configured IP:443 proves reachability on this Wi-Fi Network only. */
class InternetProbe : HostProbe {
    override fun check(path: WifiPath, host: String): ProbeResult {
        val started = SystemClock.elapsedRealtime()
        return try {
            Socket().use { socket ->
                path.network.bindSocket(socket)
                socket.connect(InetSocketAddress(InetAddress.getByName(host), 443), RouterProbe.TIMEOUT_MS)
            }
            ProbeResult(true, SystemClock.elapsedRealtime() - started, "Wi-Fi TCP port 443")
        } catch (error: IOException) {
            ProbeResult(false, SystemClock.elapsedRealtime() - started, "Wi-Fi TCP: " + error.javaClass.simpleName)
        } catch (error: SecurityException) {
            ProbeResult(false, SystemClock.elapsedRealtime() - started, "Internet permission denied")
        }
    }
}

/** At most two concurrent probes, one round at a time. Lifecycle/state callbacks use worker. */
class ProbeEngine(
    private val worker: Handler,
    private val config: MonitoringConfig,
    private val onResult: (String, ProbeResult, EventTime) -> Unit,
    private val routerProbe: HostProbe = RouterProbe(),
    private val internetProbe: HostProbe = InternetProbe()
) {
    private val executor = Executors.newFixedThreadPool(2)
    private var path: WifiPath? = null
    private var generation = 0L
    private var active = false
    private var inFlight = false
    private val tick = Runnable { round() }

    fun start() {
        active = true
        worker.postDelayed(tick, config.intervalMs)
    }

    fun setPath(value: WifiPath?) {
        path = value
        generation++
    }

    private fun round() {
        if (!active || inFlight) return
        val current = path
        if (current == null) {
            val time = PowerMonitor.now()
            onResult("router", ProbeResult(false, 0, "No active Wi-Fi network"), time)
            onResult("internet", ProbeResult(false, 0, "No active Wi-Fi network; cellular excluded"), time)
            if (active) worker.postDelayed(tick, config.intervalMs)
            return
        }
        inFlight = true
        val started = SystemClock.elapsedRealtime()
        val roundGeneration = generation
        var remaining = 2
        val gateway = config.gatewayOverride.ifEmpty { current.gateway.orEmpty() }
        listOf(Triple("router", gateway, routerProbe),
            Triple("internet", config.internetIp, internetProbe)).forEach { (target, host, probe) ->
            executor.execute {
                val result = if (host.isEmpty()) ProbeResult(false, 0, "Gateway unavailable")
                    else probe.check(current, host)
                val time = PowerMonitor.now()
                worker.post {
                    if (active && ProbeResultPolicy.accept(path != null, roundGeneration, generation)) {
                        onResult(target, result, time)
                    }
                    remaining--
                    if (remaining == 0) {
                        inFlight = false
                        if (active) worker.postDelayed(tick,
                            (config.intervalMs - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
                    }
                }
            }
        }
    }

    fun stop() {
        active = false
        generation++
        worker.removeCallbacks(tick)
        executor.shutdownNow()
    }
}
