package com.joey.powerwatch

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventLoggerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun appendsAcrossLoggerRecreationWithOneHeaderAndExactClocks() {
        val file = temporary.newFile("events.csv")
        EventLogger(file).append("outage_start", "power", "down", EventTime(1000, 50), 3)
        EventLogger(file).append("outage_end", "power", "up", EventTime(0, 150), 3, 100)
        val rows = file.readLines()
        assertEquals(3, rows.size)
        assertEquals(EventLogger.HEADER.trimEnd(), rows[0])
        assertTrue(rows[1].endsWith(",outage_start,power,down,,,50,3"))
        assertTrue(rows[2].endsWith(",outage_end,power,up,100,,150,3"))
    }

    @Test fun escapesCsvAndLimitsDisplayedRowsWithoutDeletingEvents() {
        val file = temporary.newFile("events.csv")
        val logger = EventLogger(file)
        logger.append("error", "service", "unknown", EventTime(0, 0), 1,
            detail = "comma, quote \"example\"")
        assertTrue(file.readText().contains("\"comma, quote \"\"example\"\"\""))
        repeat(5) { logger.append("heartbeat", "service", "up", EventTime(it.toLong(), it.toLong()), 1) }
        assertEquals(2, logger.recentLines(2).lines().size)
        assertEquals(7, file.readLines().size)
    }
}
