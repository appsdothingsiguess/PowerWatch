package com.joey.powerwatch

/** Both times are captured at broadcast receipt, before any disk work. */
data class EventTime(val wallMs: Long, val elapsedMs: Long)

data class PowerEvent(
    val event: String,
    val state: String,
    val time: EventTime,
    val durationMs: Long? = null,
    val detail: String = ""
)

/** Power has no debounce: every actual change is recorded immediately. */
class PowerStateMachine {
    var connected: Boolean? = null
        private set
    var outageStart: EventTime? = null
        private set

    fun observe(value: Boolean, time: EventTime): PowerEvent? {
        val previous = connected
        if (previous == value) return null
        connected = value
        if (previous == null) {
            return PowerEvent("initial_state", if (value) "up" else "down", time,
                detail = if (value) "External charger power connected"
                else "Already disconnected; outage start unknown")
        }
        if (!value) {
            outageStart = time
            return PowerEvent("outage_start", "down", time,
                detail = "External charger power lost")
        }
        val start = outageStart
        outageStart = null
        return PowerEvent("outage_end", "up", time,
            durationMs = start?.let { (time.elapsedMs - it.elapsedMs).coerceAtLeast(0) },
            detail = if (start == null) "External charger power restored; duration unknown"
            else "External charger power restored")
    }
}
