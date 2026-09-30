package com.joey.powerwatch

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PushbackReader
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

enum class LogFilter(val label: String) {
    ALL("All events"), OUTAGES("Outages"), HEARTBEATS("Heartbeats"),
    POWER("Power"), WIFI("Wi-Fi / router"), INTERNET("Internet"), SYSTEM("System");
    fun accepts(row: LogRecord): Boolean = when (this) {
        ALL -> true
        OUTAGES -> row.event.startsWith("outage_") || row.event == "initial_state" && row.state == "down"
        HEARTBEATS -> row.event == "heartbeat"
        POWER -> row.target == "power"
        WIFI -> row.target == "wifi" || row.target == "router"
        INTERNET -> row.target == "internet"
        SYSTEM -> row.target == "service" && row.event != "heartbeat"
    }
}

data class LogRecord(
    val timestamp: String, val event: String, val target: String, val state: String,
    val durationMs: Long?, val detail: String, val elapsedMs: Long, val bootCount: Int
)

data class LogEntry(val row: LogRecord, val start: LogRecord? = null, val interrupted: Boolean = false) {
    val isOutage: Boolean get() = row.event.startsWith("outage_")
    val title: String get() = when (row.event) {
        "outage_start" -> "Outage detected · " + LogPresentation.targetName(row.target)
        "outage_end" -> "Restored · " + LogPresentation.targetName(row.target)
        "heartbeat" -> "Heartbeat · Monitoring check-in"
        "monitoring_started" -> "Monitoring started"
        "monitoring_stopped" -> "Monitoring stopped"
        "service_restart" -> "Monitoring restarted"
        "monitoring_interrupted" -> "Monitoring interrupted · Observation gap"
        "network_changed" -> if (row.state == "down") "Wi-Fi disconnected" else "Wi-Fi connection changed"
        "initial_state" -> LogPresentation.targetName(row.target) + " · " +
            if (row.state == "down") "Already offline at start" else "Online at start"
        "monitoring_error" -> "Monitoring error"
        else -> row.event.replace('_', ' ') + " · " + LogPresentation.targetName(row.target)
    }
    fun body(): String = buildString {
        if (row.event != "outage_end") append(LogPresentation.date(row.timestamp))
        if (row.event == "outage_start") append("\nDuration not known at detection; see restoration event.")
        if (row.event == "outage_end") {
            append("Started: " + (start?.let { LogPresentation.date(it.timestamp) } ?: "Unknown / not observed"))
            append("\nRestored: " + LogPresentation.date(row.timestamp))
            append("\nDuration: " + (row.durationMs?.let(LogPresentation::duration) ?: "Unknown / observation gap"))
        }
        if (row.event == "initial_state" && row.state == "down") append("\nActual outage start and duration unknown.")
        if (row.detail.isNotBlank()) append("\n" + row.detail)
    }

    fun outageSummary(nowElapsedMs: Long, currentBoot: Int, monitoring: Boolean): String {
        if (row.event == "outage_end") return title +
            "\nStarted: " + (start?.let { LogPresentation.date(it.timestamp) } ?: "Unknown / not observed") +
            "\nRestored: " + LogPresentation.date(row.timestamp) +
            "\nDuration: " + (row.durationMs?.let(LogPresentation::duration) ?: "Unknown / observation gap")
        val live = !interrupted && monitoring && currentBoot >= 0 &&
            row.bootCount == currentBoot && nowElapsedMs >= row.elapsedMs
        return title + "\nStarted: " + LogPresentation.date(row.timestamp) + "\nDuration: " +
            if (live) LogPresentation.duration(nowElapsedMs - row.elapsedMs) + " · ongoing"
            else "Unknown · observation ended or monitoring not confirmed"
    }
}

data class LogView(
    val entries: List<LogEntry>, val matchingCount: Long, val activeOutages: List<LogEntry>,
    val latestOutage: LogEntry?
)

/** Human-readable views of the original CSV. Durations always come from monotonic records. */
object LogPresentation {
    private val dateFormat = DateTimeFormatter.ofPattern("MMM d, uuuu · HH:mm:ss.SSS xxx", Locale.US)
    fun date(timestamp: String): String = try {
        dateFormat.format(OffsetDateTime.parse(timestamp))
    } catch (_: DateTimeParseException) { timestamp }

    fun targetName(target: String): String = when (target) {
        "power" -> "External charger power"
        "router" -> "Wi-Fi router"
        "internet" -> "Internet via Wi-Fi"
        "wifi" -> "Wi-Fi"
        "service" -> "Monitoring"
        else -> target
    }

    fun duration(ms: Long): String {
        val value = ms.coerceAtLeast(0)
        val days = value / 86_400_000
        val hours = value / 3_600_000 % 24
        val minutes = value / 60_000 % 60
        val seconds = value / 1000 % 60
        val fraction = value % 1000
        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0) append("${hours}h ")
            if (minutes > 0) append("${minutes}m ")
            append(seconds)
            if (fraction > 0) append("." + fraction.toString().padStart(3, '0').trimEnd('0'))
            append("s")
        }
    }

    fun read(input: InputStream, filter: LogFilter = LogFilter.ALL, limit: Int = 100): LogView {
        require(limit > 0)
        val recent = ArrayDeque<LogEntry>()
        var count = 0L
        val state = scan(input) { entry ->
            if (filter.accepts(entry.row)) {
                count++
                if (recent.size == limit) recent.removeFirst()
                recent.addLast(entry)
            }
        }
        return LogView(recent.reversed(), count, state.first, state.second)
    }

    /** Streams the entire selected history to a self-contained, printable HTML report. */
    fun exportReport(input: InputStream, output: OutputStream, filter: LogFilter, generated: String) {
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        writer.write("""<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PowerWatch event report</title>
<style>
:root{color-scheme:light dark}body{font:16px/1.5 system-ui,sans-serif;max-width:960px;margin:2em auto;padding:0 1em}
h1{margin-bottom:0}header,p{margin-bottom:1.2em}.note{border-left:4px solid #708090;padding-left:1em}
article{border:1px solid #888;border-radius:10px;padding:1em;margin:1em 0;break-inside:avoid}
article.outage{border-left:6px solid #b85a15}h2{font-size:1.15em;margin:0 0 .4em}
pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;margin:0}
@media print{:root{color-scheme:light}body{margin:0;max-width:none}article{border-color:#aaa}}
</style><body><header><h1>PowerWatch</h1><p>Event report · """ + html(filter.label) +
            "<br>Generated: " + html(date(generated)) + """</p></header>
<p class="note">Full selected history, oldest first. Outage detection and restoration are separate events.
Durations use the phone's monotonic clock, not wall-clock subtraction. Observation gaps have unknown duration.
Network detection precision is bounded by the configured probe interval, timeout and failure threshold.
Power events describe this phone's external charger power, not proof of a household outage.</p>
""")
        var count = 0L
        val state = scan(input) { entry ->
            if (filter.accepts(entry.row)) {
                count++
                writer.write("<article" + (if (entry.isOutage) " class=\"outage\"" else "") +
                    "><h2>" + html(entry.title) + "</h2><pre>" + html(entry.body()) + "</pre></article>\n")
            }
        }
        if (count == 0L) writer.write("<p>No matching events recorded.</p>")
        val open = state.first.filter { filter.accepts(it.row) }
        if (open.isNotEmpty()) {
            writer.write("<p class=\"note\">No restoration recorded for: " +
                html(open.joinToString { targetName(it.row.target) }) +
                ". Current status is not verified by this exported snapshot; final duration is unknown.</p>")
        }
        writer.write("<footer><p>${count} matching events. Original CSV remains on the phone.</p></footer></body></html>")
        writer.flush()
    }

    private fun html(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    /** Pair starts only inside the same observed session and boot; never bridge a gap. */
    private fun scan(input: InputStream, consume: (LogEntry) -> Unit): Pair<List<LogEntry>, LogEntry?> {
        val pending = mutableMapOf<String, LogRecord>()
        var latest: LogEntry? = null
        var boot: Int? = null
        var rowNumber = 0
        csv(input) { cells ->
            rowNumber++
            if (cells.size == 1 && cells[0].isEmpty()) return@csv
            if (rowNumber == 1 && cells.firstOrNull() == "timestamp") return@csv
            if (cells.size != 8) throw IOException("Invalid CSV record $rowNumber: expected 8 fields")
            val row = LogRecord(cells[0], cells[1], cells[2], cells[3],
                cells[4].takeIf { it.isNotBlank() }?.let {
                    it.toLongOrNull()?.takeIf { n -> n >= 0 }
                        ?: throw IOException("Invalid duration in CSV record $rowNumber")
                }, cells[5], cells[6].toLongOrNull() ?: throw IOException("Invalid elapsed time in CSV record $rowNumber"),
                cells[7].toIntOrNull() ?: throw IOException("Invalid boot count in CSV record $rowNumber"))
            val gap = boot != null && boot != row.bootCount ||
                row.event in setOf("monitoring_started", "service_restart", "monitoring_stopped", "monitoring_interrupted")
            if (gap) {
                pending.clear()
                if (latest?.row?.event == "outage_start") latest = latest?.copy(interrupted = true)
            }
            boot = row.bootCount
            val entry = when (row.event) {
                "outage_start" -> LogEntry(row).also { pending[row.target] = row }
                "outage_end" -> LogEntry(row, pending.remove(row.target)?.takeIf { it.elapsedMs <= row.elapsedMs })
                else -> LogEntry(row)
            }
            if (entry.isOutage) latest = entry
            consume(entry)
        }
        return pending.values.map { LogEntry(it) } to latest
    }

    /** RFC-style CSV reader, including quoted commas, escaped quotes and multiline details. */
    private fun csv(input: InputStream, consume: (List<String>) -> Unit) {
        val reader = PushbackReader(input.reader(Charsets.UTF_8).buffered(), 1)
        val cells = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var touched = false
        fun fieldDone() { cells.add(field.toString()); field.setLength(0); closedQuote = false }
        fun rowDone() { fieldDone(); consume(cells.toList()); cells.clear(); touched = false }
        while (true) {
            val value = reader.read()
            if (value == -1) break
            val ch = value.toChar()
            touched = true
            if (quoted) {
                if (ch == '"') {
                    val next = reader.read()
                    if (next == '"'.code) field.append('"')
                    else { quoted = false; closedQuote = true; if (next != -1) reader.unread(next) }
                } else field.append(ch)
            } else when (ch) {
                '"' -> {
                    if (field.isNotEmpty() || closedQuote) throw IOException("Invalid CSV quote")
                    quoted = true
                }
                ',' -> fieldDone()
                '\n', '\r' -> {
                    if (ch == '\r') { val next = reader.read(); if (next != '\n'.code && next != -1) reader.unread(next) }
                    rowDone()
                }
                else -> {
                    if (closedQuote) throw IOException("Unexpected text after CSV quote")
                    field.append(ch)
                }
            }
            if (field.length > 1_048_576 || cells.size > 8) throw IOException("CSV record exceeds supported size")
        }
        if (quoted) throw IOException("Incomplete quoted CSV record")
        if (touched || cells.isNotEmpty() || field.isNotEmpty()) rowDone()
    }
}
