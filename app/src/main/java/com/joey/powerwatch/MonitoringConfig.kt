package com.joey.powerwatch

/** Numeric IPv4 settings avoid DNS traffic and unbounded name lookups during probes. */
data class MonitoringConfig(
    val intervalMs: Long = 1000,
    val gatewayOverride: String = "",
    val internetIp: String = "1.1.1.1",
    val failureThreshold: Int = 2,
    val heartbeatMs: Long = 300_000
) {
    init {
        require(intervalMs in 1000..60_000) { "Interval must be 1000–60000 ms" }
        require(gatewayOverride.isEmpty() || validIp(gatewayOverride)) { "Gateway must be a unicast IPv4 address or blank for auto" }
        require(validIp(internetIp)) { "Internet target must be a unicast IPv4 address" }
        require(failureThreshold in 1..10) { "Failure threshold must be 1–10" }
        require(heartbeatMs in 10_000..3_600_000) { "Heartbeat must be 10–3600 seconds" }
    }

    companion object {
        fun validIp(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            if (parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } ||
                    (it.length > 1 && it[0] == '0') || (it.toIntOrNull() ?: -1) !in 0..255 }) return false
            return parts[0].toInt() in 1..223 && parts[0] != "127"
        }
    }
}
