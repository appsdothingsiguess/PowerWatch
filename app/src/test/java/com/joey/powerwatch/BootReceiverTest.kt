package com.joey.powerwatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test fun resumesOnlyIfMonitoringWasEnabledAndRebootRestartIsEnabled() {
        assertTrue(shouldResumeAfterBoot(true, true))
        assertFalse(shouldResumeAfterBoot(false, true))
        assertFalse(shouldResumeAfterBoot(true, false))
        assertFalse(shouldResumeAfterBoot(false, false))
    }
}
