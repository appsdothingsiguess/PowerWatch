package com.joey.powerwatch

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LogPresentationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun logger() = EventLogger(temporary.newFile())
    private fun read(logger: EventLogger, filter: LogFilter = LogFilter.ALL, limit: Int = 100) =
        logger.readSnapshot { LogPresentation.read(it, filter, limit) }

    @Test fun filtersByCategoryNotByLastHundredUnfilteredEvents() {
        val log = logger()
        log.append("outage_start", "power", "down", EventTime(1000, 10), 4)
        repeat(150) { log.append("heartbeat", "service", "up", EventTime(2000, 20L + it), 4) }
        log.append("network_changed", "wifi", "connected", EventTime(3000, 200), 4)
        log.append("initial_state", "router", "up", EventTime(3000, 201), 4)
        log.append("initial_state", "internet", "up", EventTime(3000, 202), 4)
        log.append("monitoring_error", "service", "unknown", EventTime(3000, 203), 4)
        assertEquals(100, read(log).entries.size)
        assertEquals(155L, read(log).matchingCount)
        assertEquals("power", read(log, LogFilter.POWER).entries.single().row.target)
        assertEquals(150L, read(log, LogFilter.HEARTBEATS).matchingCount)
        assertEquals(2, read(log, LogFilter.WIFI).entries.size)
        assertEquals("internet", read(log, LogFilter.INTERNET).entries.single().row.target)
        assertEquals("monitoring_error", read(log, LogFilter.SYSTEM).entries.single().row.event)
        assertEquals(1, read(log, LogFilter.OUTAGES).entries.size)
    }

    @Test fun pairsRecoveryWithStartAndRetainsRecordedMonotonicDurationDespiteWallClockReversal() {
        val log = logger()
        log.append("outage_start", "power", "down", EventTime(100_000, 1000), 4)
        log.append("outage_end", "power", "up", EventTime(0, 3809), 4, 2809)
        val view = read(log)
        val recovery = view.entries.first()
        assertEquals(1000L, recovery.start?.elapsedMs)
        assertTrue(recovery.body().contains("2.809s"))
        assertTrue(recovery.body().contains("Started:"))
        assertTrue(recovery.body().contains("Restored:"))
        assertTrue(view.activeOutages.isEmpty())
        assertEquals("outage_end", view.latestOutage?.row?.event)
    }

    @Test fun gapsAndBootChangesNeverPairOutagesAcrossUnobservedSessions() {
        listOf("monitoring_stopped", "monitoring_interrupted", "monitoring_started", "service_restart").forEach { gap ->
            val log = logger()
            log.append("outage_start", "router", "down", EventTime(1000, 100), 4)
            log.append(gap, "service", "unknown", EventTime(2000, 200), 4)
            assertTrue(read(log).activeOutages.isEmpty())
            assertTrue(read(log).latestOutage!!.interrupted)
            log.append("outage_end", "router", "up", EventTime(3000, 300), 4)
            assertNull(read(log).entries.first().start)
            assertTrue(read(log).entries.first().body().contains("Unknown / observation gap"))
        }
        val log = logger()
        log.append("outage_start", "power", "down", EventTime(1000, 100), 4)
        log.append("outage_end", "power", "up", EventTime(2000, 20), 5)
        assertNull(read(log).entries.first().start)
    }

    @Test fun simultaneousOutagesAreTrackedSeparatelyAndFilterDoesNotHideBanner() {
        val log = logger()
        log.append("outage_start", "router", "down", EventTime(1000, 100), 4)
        log.append("outage_start", "internet", "down", EventTime(1000, 110), 4)
        log.append("outage_end", "router", "up", EventTime(2000, 200), 4, 100)
        val view = read(log, LogFilter.HEARTBEATS)
        assertTrue(view.entries.isEmpty())
        assertEquals("internet", view.activeOutages.single().row.target)
        assertEquals("router", view.latestOutage?.row?.target)
    }

    @Test fun liveDurationRequiresSameKnownBootAndConfirmedMonitoring() {
        val entry = LogEntry(LogRecord("2026-09-30T10:00:00-07:00", "outage_start", "power",
            "down", null, "", 100, 4))
        assertTrue(entry.outageSummary(2909, 4, true).contains("2.809s · ongoing"))
        assertTrue(entry.outageSummary(2909, 5, true).contains("Unknown"))
        assertTrue(entry.outageSummary(2909, -1, true).contains("Unknown"))
        assertTrue(entry.outageSummary(2909, 4, false).contains("Unknown"))
        assertTrue(entry.copy(interrupted = true).outageSummary(2909, 4, true).contains("Unknown"))
        assertTrue(entry.outageSummary(90, 4, true).contains("Unknown"))
    }

    @Test fun initialOfflineHasUnknownOnsetAndIsNotFabricatedAsDetectedOutage() {
        val log = logger()
        log.append("initial_state", "internet", "down", EventTime(1000, 100), 4)
        val view = read(log, LogFilter.OUTAGES)
        assertTrue(view.entries.single().title.contains("Already offline"))
        assertTrue(view.entries.single().body().contains("unknown"))
        assertTrue(view.activeOutages.isEmpty())
        assertNull(view.latestOutage)
    }

    @Test fun csvSupportsQuotedCommasQuotesCrLfAndMultilineDetails() {
        val log = logger()
        val detail = "comma, quote \"sample\"\nnext line\r\n<script>evil & text</script>"
        log.append("monitoring_error", "service", "unknown", EventTime(0, 0), 4, detail = detail)
        assertEquals(detail, read(log).entries.single().row.detail)
        val csv = EventLogger.HEADER.replace("\n", "\r\n") +
            "2026-09-30T10:00:00-07:00,heartbeat,service,up,,\"hello, \"\"world\"\"\",100,4\r\n"
        assertEquals("hello, \"world\"", LogPresentation.read(csv.byteInputStream()).entries.single().row.detail)
    }

    @Test fun reportIsFullFilteredHistoryEscapesHtmlAndHasNoExternalAssets() {
        val log = logger()
        repeat(120) {
            log.append("heartbeat", "service", "up", EventTime(it.toLong(), it.toLong()), 4,
                detail = "<script>bad & \"quoted\"</script>")
        }
        log.append("outage_start", "power", "down", EventTime(1000, 1000), 4)
        val output = ByteArrayOutputStream()
        log.readSnapshot {
            LogPresentation.exportReport(it, output, LogFilter.HEARTBEATS, "2026-09-30T10:00:00-07:00")
        }
        val html = output.toString("UTF-8")
        assertEquals(120, Regex("<article").findAll(html).count())
        assertTrue(html.contains("120 matching events"))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;bad &amp; &quot;quoted&quot;"))
        assertFalse(html.contains("<h2>Outage detected"))
        assertFalse(html.contains("src="))
        assertFalse(html.contains("href="))
        assertFalse(html.contains("No restoration recorded for:"))
        val outageOutput = ByteArrayOutputStream()
        log.readSnapshot {
            LogPresentation.exportReport(it, outageOutput, LogFilter.OUTAGES, "2026-09-30T10:00:00-07:00")
        }
        assertTrue(outageOutput.toString("UTF-8").contains("final duration is unknown"))
    }

    @Test fun reportDestinationErrorPreservesOriginalLogAndReleasesSnapshot() {
        val log = logger()
        log.append("heartbeat", "service", "up", EventTime(1, 1), 4)
        val broken = object : java.io.OutputStream() {
            override fun write(value: Int) { throw IOException("Destination full") }
        }
        try {
            log.readSnapshot { LogPresentation.exportReport(it, broken, LogFilter.ALL, "now") }
            fail("Destination errors must be reported")
        } catch (error: IOException) { assertEquals("Destination full", error.message) }
        assertEquals(1, read(log).entries.size)
        log.clear()
        assertTrue(read(log).entries.isEmpty())
    }

    @Test fun malformedCsvAndNegativeDurationsAreReportedNotSilentlySkipped() {
        listOf(
            EventLogger.HEADER + "too,few,fields\n",
            EventLogger.HEADER + "t,outage_end,power,up,-1,,2,4\n",
            EventLogger.HEADER + "t,heartbeat,service,up,,\"unfinished,2,4",
            EventLogger.HEADER + "t,heartbeat,service,up,,\"closed\"oops,2,4\n"
        ).forEach {
            try { LogPresentation.read(it.byteInputStream()); fail("Invalid record must be reported") }
            catch (_: IOException) { }
        }
    }

    @Test fun emptyLogIsReadableAndDurationFormattingHandlesDaysAndMilliseconds() {
        val log = logger()
        assertTrue(read(log).entries.isEmpty())
        assertEquals("0s", LogPresentation.duration(0))
        assertEquals("0.01s", LogPresentation.duration(10))
        assertEquals("7m 43.784s", LogPresentation.duration(463784))
        assertEquals("1d 1h 1m 1.001s", LogPresentation.duration(90_061_001))
    }

    @Test fun snapshotReaderDoesNotBlockAppenderOrIncludeNewRowsAndProtectsAgainstClear() {
        val log = logger()
        log.append("heartbeat", "service", "up", EventTime(0, 0), 4)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val view = log.readSnapshot { input ->
                executor.submit { log.append("heartbeat", "service", "up", EventTime(1, 1), 4) }
                    .get(2, TimeUnit.SECONDS)
                try { log.clear(); fail("Active snapshot must be protected") } catch (_: IOException) { }
                LogPresentation.read(input)
            }
            assertEquals(1, view.entries.size)
            assertEquals(2, read(log).entries.size)
            try { log.readSnapshot<Unit> { throw IOException("reader failed") } } catch (_: IOException) { }
            log.clear()
            assertTrue(read(log).entries.isEmpty())
        } finally { executor.shutdownNow() }
    }
}
