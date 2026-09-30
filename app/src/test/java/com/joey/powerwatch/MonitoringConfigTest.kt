package com.joey.powerwatch

import org.junit.Assert.*
import org.junit.Test

class MonitoringConfigTest {
    @Test fun defaultsMatchPlan() {
        val config = MonitoringConfig()
        assertEquals(1000L, config.intervalMs)
        assertEquals(2, config.failureThreshold)
        assertEquals("", config.gatewayOverride)
        assertEquals("1.1.1.1", config.internetIp)
        assertEquals(300_000L, config.heartbeatMs)
    }

    @Test fun numericTargetsAvoidDnsAndRejectAmbiguousOrNonUnicastAddresses() {
        listOf("router.local", "1.2.3", "1.2.3.256", "01.2.3.4", "127.0.0.1",
            "0.0.0.0", "224.0.0.1", "255.255.255.255", "1.2.3.-1").forEach {
            assertFalse(it, MonitoringConfig.validIp(it))
        }
        assertTrue(MonitoringConfig.validIp("192.168.1.1"))
        assertTrue(MonitoringConfig.validIp("1.1.1.1"))
    }

    @Test fun heartbeatBoundsAreIndependentOfProbeInterval() {
        listOf(10_000L, 60_000L, 3_600_000L).forEach {
            val config = MonitoringConfig(heartbeatMs = it)
            assertEquals(it, config.heartbeatMs)
            assertEquals(1000L, config.intervalMs)
            assertEquals(2, config.failureThreshold)
        }
        listOf(0L, 9_999L, 3_600_001L, Long.MAX_VALUE).forEach {
            try {
                MonitoringConfig(heartbeatMs = it)
                fail("Invalid heartbeat accepted: $it")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("Heartbeat"))
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidIntervalCannotStartTightLoop() { MonitoringConfig(intervalMs = 0) }

    @Test(expected = IllegalArgumentException::class)
    fun invalidThresholdIsRejected() { MonitoringConfig(failureThreshold = 0) }
}
