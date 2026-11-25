package id.vern.antigravity.util

import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val MAX_RING_ENTRIES = 200

    data class LogEntry(
        val timestamp: Long,
        val timeFormatted: String,
        val tag: String,
        val level: String,
        val message: String
    )

    private var logHandler: ((String, String, Boolean) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    private val ringLock = Any()
    private val ringBuffer = ArrayDeque<LogEntry>(MAX_RING_ENTRIES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun setHandler(handler: (String, String, Boolean) -> Unit) {
        logHandler = handler
    }

    fun d(tag: String, message: String) {
        log(tag, message, false)
    }

    fun e(tag: String, message: String) {
        log(tag, message, true)
    }

    fun getRecent(tail: Int = 50): List<LogEntry> {
        val count = tail.coerceIn(1, MAX_RING_ENTRIES)
        synchronized(ringLock) {
            if (ringBuffer.size <= count) return ringBuffer.toList()
            return ringBuffer.toList().takeLast(count)
        }
    }

    fun getRecentJson(tail: Int = 50): JSONArray {
        val arr = JSONArray()
        for (entry in getRecent(tail)) {
            arr.put(
                JSONObject()
                    .put("ts", entry.timestamp)
                    .put("time", entry.timeFormatted)
                    .put("tag", entry.tag)
                    .put("level", entry.level)
                    .put("message", entry.message)
            )
        }
        return arr
    }

    fun log(tag: String, message: String, isError: Boolean = false) {
        val now = System.currentTimeMillis()
        val timeStr = synchronized(ringLock) {
            val formatted = timeFormat.format(Date(now))
            if (ringBuffer.size >= MAX_RING_ENTRIES) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(
                LogEntry(
                    timestamp = now,
                    timeFormatted = formatted,
                    tag = tag,
                    level = if (isError) "ERROR" else "INFO",
                    message = message
                )
            )
            formatted
        }

        try {
            logHandler?.invoke(tag, message, isError)
        } catch (e: Throwable) {}

        scope.launch {
            try {
                val baseDir = Environment.getExternalStorageDirectory() ?: return@launch
                val logDir = File(baseDir, "Antigravity")
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }
                val logFile = File(logDir, "logs.txt")
                val line = "[$timeStr] [$tag] $message\n"
                synchronized(lock) {
                    logFile.appendText(line, Charsets.UTF_8)
                }
            } catch (e: Throwable) {
            }
        }
    }
}
