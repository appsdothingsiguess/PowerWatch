package com.joey.powerwatch

import org.junit.Assert.*
import org.junit.Test

class OutageStateMachineTest {
    @Test fun firstFailureIsSuspectedAndThresholdRetainsFirstFailedTime() {
        val machine = OutageStateMachine(2)
        machine.observe(true, EventTime(1000, 100))
        assertNull(machine.observe(false, EventTime(2000, 1100)))
        assertEquals(NetworkHealth.SUSPECTED_DOWN, machine.state)
        val down = machine.observe(false, EventTime(3000, 2100))!!
        assertEquals("outage_start", down.event)
        assertEquals(EventTime(2000, 1100), down.time)
        assertEquals(down.time, machine.outageStart)
        assertEquals(NetworkHealth.DOWN, machine.state)
    }

    @Test fun singleLossRecoversWithoutLoggingAnOutageAndResetsFailureCount() {
        val machine = OutageStateMachine(2)
        machine.observe(true, EventTime(0, 0))
        machine.observe(false, EventTime(1, 1))
        assertNull(machine.observe(true, EventTime(2, 2)))
        assertEquals(NetworkHealth.UP, machine.state)
        assertNull(machine.observe(false, EventTime(3, 3)))
        assertEquals(EventTime(3, 3), machine.observe(false, EventTime(4, 4))!!.time)
    }

    @Test fun recoveryNeedsOneSuccessAndDurationIgnoresWallClockChange() {
        val machine = OutageStateMachine()
        machine.observe(true, EventTime(100_000, 100))
        machine.observe(false, EventTime(101_000, 1100))
        machine.observe(false, EventTime(102_000, 2100))
        val up = machine.observe(true, EventTime(1, 5286))!!
        assertEquals("outage_end", up.event)
        assertEquals(4186L, up.durationMs)
        assertNull(machine.outageStart)
        assertEquals(NetworkHealth.UP, machine.state)
    }

    @Test fun repeatedDownAndUpDoNotProduceDuplicateEvents() {
        val machine = OutageStateMachine()
        machine.observe(true, EventTime(0, 0))
        assertNull(machine.observe(true, EventTime(1, 1)))
        machine.observe(false, EventTime(2, 2))
        machine.observe(false, EventTime(3, 3))
        assertNull(machine.observe(false, EventTime(4, 4)))
        machine.observe(true, EventTime(5, 5))
        assertNull(machine.observe(true, EventTime(6, 6)))
    }

    @Test fun configuredThresholdAndSubsequentOutagesAreIndependent() {
        val machine = OutageStateMachine(3)
        machine.observe(true, EventTime(0, 0))
        assertNull(machine.observe(false, EventTime(1, 1)))
        assertNull(machine.observe(false, EventTime(2, 2)))
        assertEquals(EventTime(1, 1), machine.observe(false, EventTime(3, 3))!!.time)
        assertEquals(3L, machine.observe(true, EventTime(4, 4))!!.durationMs)
        machine.observe(false, EventTime(10, 10))
        machine.observe(false, EventTime(11, 11))
        machine.observe(false, EventTime(12, 12))
        assertEquals(5L, machine.observe(true, EventTime(15, 15))!!.durationMs)
    }

    @Test fun startupOfflineDoesNotInventDurationAndRestartDoesNotBridgeGap() {
        val machine = OutageStateMachine()
        machine.observe(false, EventTime(1, 1))
        assertEquals("initial_state", machine.observe(false, EventTime(2, 2))!!.event)
        assertNull(machine.outageStart)
        assertNull(machine.observe(true, EventTime(3, 3))!!.durationMs)
        machine.observe(false, EventTime(4, 4))
        machine.observe(false, EventTime(5, 5))
        val restarted = OutageStateMachine()
        assertEquals("initial_state", restarted.observe(true, EventTime(100, 100))!!.event)
        assertNull(restarted.outageStart)
    }

    @Test fun startupFailureThenSuccessPublishesHealthyBaseline() {
        val machine = OutageStateMachine()
        machine.observe(false, EventTime(1, 1))
        assertEquals("initial_state", machine.observe(true, EventTime(2, 2))!!.event)
    }

    @Test fun cellularAndVpnPathsCannotBeSelectedEvenIfTheyAlsoAdvertiseWifi() {
        assertFalse(ProbeResultPolicy.isWifiOnly(false, true, false))
        assertFalse(ProbeResultPolicy.isWifiOnly(true, true, false))
        assertFalse(ProbeResultPolicy.isWifiOnly(true, false, true))
        assertTrue(ProbeResultPolicy.isWifiOnly(true, false, false))
    }

    @Test fun absentWifiIncludingCellularOnlyCannotAcceptSuccess() {
        assertFalse(ProbeResultPolicy.accept(false, 1, 1))
        assertFalse(ProbeResultPolicy.accept(true, 1, 2))
        assertTrue(ProbeResultPolicy.accept(true, 2, 2))
    }
}
