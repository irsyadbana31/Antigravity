package id.vern.antigravity.gateway

import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import id.vern.antigravity.shizuku.IShizukuShellService
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Privileged Shizuku UserService executed inside the Shizuku server process (uid=2000 or uid=0).
 */
class ShizukuShellUserService : IShizukuShellService.Stub {

    @Keep
    constructor() : super()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    override fun destroy() {
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun getUid(): Int = Process.myUid()

    override fun execCommand(cmd: String, timeoutMs: Long, maxOutputBytes: Int): String {
        val startMs = System.currentTimeMillis()
        val uid = Process.myUid()
        if (cmd.isBlank()) {
            return buildResultJson(
                exitCode = -1,
                stdout = "",
                stderr = "empty command",
                durationMs = 0L,
                status = "ERROR",
                uid = uid
            )
        }

        val clampedTimeoutMs = timeoutMs.coerceIn(1_000L, 120_000L)
        val clampedMaxBytes = maxOutputBytes.coerceIn(4_096, 512 * 1024)
        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        var exitCode = -1
        var status = "ERROR"

        try {
            val process = ProcessBuilder("/system/bin/sh", "-c", cmd).start()
            val outThread = Thread({ readBoundedStream(process.inputStream, stdoutBuf, clampedMaxBytes) }, "ShizukuExecOut").apply {
                isDaemon = true
                start()
            }
            val errThread = Thread({ readBoundedStream(process.errorStream, stderrBuf, clampedMaxBytes) }, "ShizukuExecErr").apply {
                isDaemon = true
                start()
            }
            try {
                process.outputStream.close()
            } catch (_: Exception) {}

            val finished = process.waitFor(clampedTimeoutMs, TimeUnit.MILLISECONDS)
            if (finished) {
                exitCode = process.exitValue()
                try { outThread.join(500) } catch (_: InterruptedException) {}
                try { errThread.join(500) } catch (_: InterruptedException) {}
                status = if (exitCode == 0) "SUCCESS" else "FAILED"
            } else {
                process.destroyForcibly()
                try { outThread.join(250) } catch (_: InterruptedException) {}
                try { errThread.join(250) } catch (_: InterruptedException) {}
                status = "TIMEOUT"
                if (stderrBuf.isNotEmpty()) stderrBuf.append("\n")
                stderrBuf.append("[Process timed out after ${clampedTimeoutMs}ms]")
            }
        } catch (t: Throwable) {
            status = "ERROR"
            if (stderrBuf.isNotEmpty()) stderrBuf.append("\n")
            stderrBuf.append("Execution exception: ${t.message ?: t.javaClass.simpleName}")
        }

        val durationMs = System.currentTimeMillis() - startMs
        return buildResultJson(
            exitCode = exitCode,
            stdout = stdoutBuf.toString().trim(),
            stderr = stderrBuf.toString().trim(),
            durationMs = durationMs,
            status = status,
            uid = uid
        )
    }

    private fun readBoundedStream(input: InputStream, out: StringBuilder, maxBytes: Int) {
        try {
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                var line: String?
                var truncated = false
                while (reader.readLine().also { line = it } != null) {
                    if (!truncated) {
                        val nextLen = out.length + (line?.length ?: 0)
                        if (nextLen > maxBytes) {
                            out.append("\n...[output truncated at ${maxBytes / 1024} KiB]")
                            truncated = true
                        } else {
                            if (out.isNotEmpty()) out.append("\n")
                            out.append(line)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun buildResultJson(
        exitCode: Int,
        stdout: String,
        stderr: String,
        durationMs: Long,
        status: String,
        uid: Int
    ): String {
        return JSONObject()
            .put("exit_code", exitCode)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("duration_ms", durationMs)
            .put("status", status)
            .put("uid", uid)
            .toString()
    }
}
