package com.joey.powerwatch

enum class NetworkHealth { UNKNOWN, UP, SUSPECTED_DOWN, DOWN }

data class OutageEvent(
    val event: String,
    val state: String,
    val time: EventTime,
    val durationMs: Long? = null
)

/** One dropped probe is suspected; a confirmed outage retains its first failed sample. */
class OutageStateMachine(private val failureThreshold: Int = 2) {
    init { require(failureThreshold >= 1) }
    var state = NetworkHealth.UNKNOWN
        private set
    var outageStart: EventTime? = null
        private set
    private var firstFailure: EventTime? = null
    private var failures = 0
    private var observedUp = false

    fun observe(success: Boolean, time: EventTime): OutageEvent? {
        if (success) {
            val previous = state
            val hadObservedUp = observedUp
            val start = outageStart
            state = NetworkHealth.UP
            firstFailure = null
            outageStart = null
            failures = 0
            observedUp = true
            return when (previous) {
                NetworkHealth.DOWN -> OutageEvent("outage_end", "up", time,
                    start?.let { (time.elapsedMs - it.elapsedMs).coerceAtLeast(0) })
                NetworkHealth.UNKNOWN -> OutageEvent("initial_state", "up", time)
                NetworkHealth.SUSPECTED_DOWN -> if (hadObservedUp) null else OutageEvent("initial_state", "up", time)
                NetworkHealth.UP -> null
            }
        }
        if (state == NetworkHealth.DOWN) return null
        if (firstFailure == null) firstFailure = time
        failures++
        state = NetworkHealth.SUSPECTED_DOWN
        if (failures < failureThreshold) return null
        state = NetworkHealth.DOWN
        // Starting monitoring while already offline does not establish the real outage onset.
        outageStart = if (observedUp) firstFailure else null
        return OutageEvent(if (observedUp) "outage_start" else "initial_state", "down",
            firstFailure!!)
    }
}

/** A successful result from an old or absent Wi-Fi path can never restore current health. */
object ProbeResultPolicy {
    fun isWifiOnly(wifi: Boolean, cellular: Boolean, vpn: Boolean) = wifi && !cellular && !vpn
    fun accept(hasWifi: Boolean, startedGeneration: Long, currentGeneration: Long) =
        hasWifi && startedGeneration == currentGeneration
}
