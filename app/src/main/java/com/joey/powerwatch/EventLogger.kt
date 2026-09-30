package com.joey.powerwatch

import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Append-only, flushed event records. All service calls use one worker thread. */
class EventLogger(private val file: File) {
    fun append(
        event: String, target: String, state: String, time: EventTime,
        bootCount: Int, durationMs: Long? = null, detail: String = ""
    ) = synchronized(fileLock) {
        val timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(time.wallMs).atZone(ZoneId.systemDefault()))
        val row = listOf(timestamp, event, target, state, durationMs?.toString().orEmpty(),
            detail, time.elapsedMs.toString(), bootCount.toString())
            .joinToString(",") { escape(it) } + "\n"
        FileOutputStream(file, true).use { output ->
            if (file.length() == 0L) {
                output.write(HEADER.toByteArray(Charsets.UTF_8))
            }
            output.write(row.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    fun recentLines(limit: Int = 50): String = synchronized(fileLock) {
        if (!file.exists()) return "No events recorded yet."
        val lines = ArrayDeque<String>()
        file.bufferedReader().useLines { source ->
            source.forEach { line ->
                if (lines.size == limit) lines.removeFirst()
                lines.addLast(line)
            }
        }
        lines.joinToString("\n")
    }

    companion object {
        private val fileLock = Any()
        const val HEADER = "timestamp,event,target,state,duration_ms,detail,elapsed_ms,boot_count\n"
        private fun escape(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
                "\"" + value.replace("\"", "\"\"") + "\""
            else value
    }
}
