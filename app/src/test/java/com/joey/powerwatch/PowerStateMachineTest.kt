package com.joey.powerwatch

import org.junit.Assert.*
import org.junit.Test

class PowerStateMachineTest {
    @Test fun firstObservationIsBaselineNotInventedOutage() {
        val machine = PowerStateMachine()
        val baseline = machine.observe(false, EventTime(1000, 100))!!
        assertEquals("initial_state", baseline.event)
        assertNull(machine.outageStart)
        val recovery = machine.observe(true, EventTime(2000, 1100))!!
        assertEquals("outage_end", recovery.event)
        assertNull(recovery.durationMs)
    }

    @Test fun lossIsImmediateAndDuplicateEventsAreSuppressed() {
        val machine = PowerStateMachine()
        machine.observe(true, EventTime(1000, 100))
        val loss = machine.observe(false, EventTime(1500, 600))!!
        assertEquals("outage_start", loss.event)
        assertEquals(EventTime(1500, 600), machine.outageStart)
        assertNull(machine.observe(false, EventTime(1600, 700)))
        assertEquals(EventTime(1500, 600), machine.outageStart)
        assertEquals(500L, machine.observe(true, EventTime(2000, 1100))!!.durationMs)
        assertNull(machine.observe(true, EventTime(2100, 1200)))
        assertNull(machine.outageStart)
    }

    @Test fun durationUsesMonotonicTimeDespiteWallClockJump() {
        val machine = PowerStateMachine()
        machine.observe(true, EventTime(100_000, 100))
        machine.observe(false, EventTime(101_000, 1100))
        val recovery = machine.observe(true, EventTime(1, 5286))!!
        assertEquals(4186L, recovery.durationMs)
    }

    @Test fun subsequentOutageHasItsOwnStart() {
        val machine = PowerStateMachine()
        machine.observe(true, EventTime(0, 0))
        machine.observe(false, EventTime(1, 1))
        machine.observe(true, EventTime(5, 5))
        machine.observe(false, EventTime(10, 10))
        assertEquals(2L, machine.observe(true, EventTime(12, 12))!!.durationMs)
    }

    @Test fun restartedMachineDoesNotClaimDurationAcrossObservationGap() {
        val previous = PowerStateMachine()
        previous.observe(true, EventTime(0, 0))
        previous.observe(false, EventTime(1, 1))
        val restarted = PowerStateMachine()
        restarted.observe(false, EventTime(100, 100))
        assertNull(restarted.observe(true, EventTime(200, 200))!!.durationMs)
    }
}
