package com.joey.powerwatch

import java.io.File
import java.io.FileOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.io.InputStream
import java.io.ByteArrayInputStream
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

    /** Copies complete rows present at capture time without holding up new monitoring events. */
    fun exportSnapshot(output: OutputStream) {
        val snapshot = synchronized(fileLock) {
            if (!file.exists() || file.length() == 0L) null
            else (file.inputStream() to file.length()).also { activeExports++ }
        }
        if (snapshot == null) {
            output.write(HEADER.toByteArray(Charsets.UTF_8))
            return
        }
        try {
            snapshot.first.use { input ->
                var remaining = snapshot.second
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw EOFException("Event log changed during export")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        } finally {
            synchronized(fileLock) { activeExports-- }
        }
    }

    /** Parse/render outside the writer lock, bounded to complete rows present at capture time. */
    fun <T> readSnapshot(action: (InputStream) -> T): T {
        val snapshot = synchronized(fileLock) {
            if (!file.exists() || file.length() == 0L) null
            else (file.inputStream() to file.length()).also { activeExports++ }
        }
        if (snapshot == null) return ByteArrayInputStream(HEADER.toByteArray(Charsets.UTF_8)).use(action)
        try {
            return snapshot.first.use { source ->
                val bounded = object : InputStream() {
                    private var remaining = snapshot.second
                    override fun read(): Int {
                        if (remaining == 0L) return -1
                        val value = source.read()
                        if (value < 0) throw EOFException("Event log changed during read")
                        remaining--
                        return value
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (length == 0) return 0
                        if (remaining == 0L) return -1
                        val count = source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                        if (count < 0) throw EOFException("Event log changed during read")
                        remaining -= count
                        return count
                    }
                }
                action(bounded)
            }
        } finally {
            synchronized(fileLock) { activeExports-- }
        }
    }

    /** Caller must stop monitoring and confirm with the user before clearing. */
    fun clear() = synchronized(fileLock) {
        if (activeExports > 0) throw IOException("Wait for log reading or export to finish before clearing")
        FileOutputStream(file, false).use { output ->
            output.write(HEADER.toByteArray(Charsets.UTF_8))
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
        private var activeExports = 0
        const val HEADER = "timestamp,event,target,state,duration_ms,detail,elapsed_ms,boot_count\n"
        private fun escape(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
                "\"" + value.replace("\"", "\"\"") + "\""
            else value
    }
}
