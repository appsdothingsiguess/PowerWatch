package com.joey.powerwatch

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    @Test fun exportIncludesAllRowsAndExcludesAppendsWhileDestinationIsWriting() {
        val file = temporary.newFile("events.csv")
        val logger = EventLogger(file)
        repeat(60) {
            logger.append("heartbeat", "service", "up", EventTime(it.toLong(), it.toLong()), 1)
        }
        val original = file.readBytes()
        val worker = Executors.newSingleThreadExecutor()
        var appended = false
        val output = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (!appended) {
                    appended = true
                    // A separate monitoring writer must proceed even while export is writing.
                    worker.submit {
                        logger.append("outage_start", "router", "down", EventTime(100, 100), 1)
                    }.get(2, TimeUnit.SECONDS)
                }
                super.write(buffer, offset, length)
            }
        }
        try {
            logger.exportSnapshot(output)
            assertArrayEquals(original, output.toByteArray())
            assertEquals(62, file.readLines().size)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun missingOrEmptyLogExportsOnlyHeaderWithoutCreatingLocalEvents() {
        val missing = java.io.File(temporary.root, "missing.csv")
        val empty = temporary.newFile("empty.csv")
        listOf(missing, empty).forEach { file ->
            val output = ByteArrayOutputStream()
            EventLogger(file).exportSnapshot(output)
            assertEquals(EventLogger.HEADER, output.toString("UTF-8"))
        }
        assertFalse(missing.exists())
        assertEquals(0L, empty.length())
    }

    @Test fun failedExportPreservesLogAndAllowsFurtherAppends() {
        val file = temporary.newFile("events.csv")
        val logger = EventLogger(file)
        logger.append("heartbeat", "service", "up", EventTime(1, 1), 1)
        val original = file.readBytes()
        val broken = object : OutputStream() {
            override fun write(value: Int) { throw IOException("Destination full") }
        }
        try {
            logger.exportSnapshot(broken)
            fail("Export should report the destination error")
        } catch (error: IOException) {
            assertEquals("Destination full", error.message)
        }
        assertArrayEquals(original, file.readBytes())
        logger.append("heartbeat", "service", "up", EventTime(2, 2), 1)
        assertEquals(3, file.readLines().size)
    }

    @Test fun clearRetainsHeaderAndAllowsNewEvents() {
        val file = temporary.newFile("events.csv")
        val logger = EventLogger(file)
        logger.append("heartbeat", "service", "up", EventTime(1, 1), 1)
        logger.clear()
        assertEquals(EventLogger.HEADER, file.readText())
        logger.append("monitoring_started", "service", "up", EventTime(2, 2), 1)
        assertEquals(2, file.readLines().size)
    }

    @Test fun clearIsRejectedDuringExportAndAllowedAfterward() {
        val file = temporary.newFile("events.csv")
        val logger = EventLogger(file)
        logger.append("heartbeat", "service", "up", EventTime(1, 1), 1)
        val original = file.readBytes()
        val output = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                try {
                    logger.clear()
                    fail("Clear must not truncate an active export")
                } catch (error: IOException) {
                    assertTrue(error.message.orEmpty().contains("export"))
                }
                super.write(buffer, offset, length)
            }
        }
        logger.exportSnapshot(output)
        assertArrayEquals(original, output.toByteArray())
        logger.clear()
        assertEquals(EventLogger.HEADER, file.readText())
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
