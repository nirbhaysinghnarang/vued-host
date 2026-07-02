package com.nsn8.vued

import android.content.Context
import android.os.Build
import android.util.Log
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

object DiagnosticsLogger {
    private const val TAG = "VuedDiagnostics"
    private const val RETENTION_MS = 48L * 60L * 60L * 1000L
    private const val FLUSH_INTERVAL_MS = 1_500L
    private const val MAX_BUFFERED_LINES = 50
    private const val MAX_BUFFERED_BYTES = 64 * 1024
    private const val PRUNE_INTERVAL_MS = 60L * 60L * 1000L
    private const val MAX_STRING_CHARS = 2048
    private const val MAX_LINE_CHARS = 8192
    private val secretKeyRegex = Regex("(authorization|access[_-]?token|refresh[_-]?token|api[_-]?key|secret|passphrase|password|private[_-]?key)", RegexOption.IGNORE_CASE)
    private val bearerRegex = Regex("\\bBearer\\s+[A-Za-z0-9._~+/=-]{16,}", RegexOption.IGNORE_CASE)
    private val jwtRegex = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")
    private val longBlobRegex = Regex("\\b[A-Za-z0-9+/]{80,}={0,2}\\b")
    private val hourFormat = SimpleDateFormat("yyyyMMddHH", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<String>(capacity = Channel.UNLIMITED)

    @Volatile
    private var dir: File? = null

    @Volatile
    private var lastPruneMs: Long = 0

    fun init(context: Context) {
        if (dir != null) return
        val diagnosticsDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics")
        diagnosticsDir.mkdirs()
        dir = diagnosticsDir
        pruneAndCompress()
        scope.launch {
            writeBatches()
        }
        info("app_started", mapOf(
            "versionName" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE,
            "sdk" to Build.VERSION.SDK_INT,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        ))
    }

    fun info(event: String, data: Map<String, Any?> = emptyMap()) = write("info", event, data, null, false)

    fun warn(event: String, data: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) =
        write("warn", event, data, throwable, false)

    fun error(event: String, data: Map<String, Any?> = emptyMap(), throwable: Throwable? = null, sentry: Boolean = true) =
        write("error", event, data, throwable, sentry)

    fun fatal(event: String, data: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) {
        val line = lineFor("fatal", event, data, throwable)
        appendLines(listOf(line))
        if (throwable != null) runCatching { Sentry.captureException(throwable) }
    }

    fun diagnosticsDir(): File? = dir

    private fun write(level: String, event: String, data: Map<String, Any?>, throwable: Throwable?, sentry: Boolean) {
        val line = lineFor(level, event, data, throwable)
        channel.trySend(line)
        when (level) {
            "warn" -> Log.w(TAG, event, throwable)
            "error", "fatal" -> Log.e(TAG, event, throwable)
        }
        if (sentry && throwable != null) runCatching { Sentry.captureException(throwable) }
    }

    private suspend fun writeBatches() {
        while (true) {
            val lines = mutableListOf(channel.receive())
            var bytes = lines.first().toByteArray().size
            val deadlineMs = System.currentTimeMillis() + FLUSH_INTERVAL_MS
            while (lines.size < MAX_BUFFERED_LINES && bytes < MAX_BUFFERED_BYTES) {
                val remainingMs = deadlineMs - System.currentTimeMillis()
                if (remainingMs <= 0) break
                val next = withTimeoutOrNull(remainingMs) { channel.receive() } ?: break
                lines += next
                bytes += next.toByteArray().size
            }
            appendLines(lines)
        }
    }

    private fun lineFor(level: String, event: String, data: Map<String, Any?>, throwable: Throwable?): String {
        val json = JSONObject()
            .put("ts", Date().toInstant().toString())
            .put("level", level)
            .put("runtime", "vued-host")
            .put("event", event)
            .put("data", sanitizeMap(data))
        if (throwable != null) {
            json.put("error", JSONObject()
                .put("name", throwable.javaClass.name)
                .put("message", sanitizeString(throwable.message ?: throwable.toString()))
                .put("stack", sanitizeString(stackTrace(throwable)).take(4096)))
        }
        val raw = json.toString()
        return if (raw.length <= MAX_LINE_CHARS) "$raw\n" else JSONObject()
            .put("ts", Date().toInstant().toString())
            .put("level", level)
            .put("runtime", "vued-host")
            .put("event", event)
            .put("truncated", true)
            .toString() + "\n"
    }

    @Synchronized
    private fun appendLines(lines: List<String>) {
        if (lines.isEmpty()) return
        val diagnosticsDir = dir ?: return
        runCatching {
            diagnosticsDir.mkdirs()
            activeFile().appendText(lines.joinToString(separator = ""))
            maybePruneAndCompress()
        }
    }

    private fun maybePruneAndCompress() {
        val now = System.currentTimeMillis()
        if (now - lastPruneMs < PRUNE_INTERVAL_MS) return
        lastPruneMs = now
        pruneAndCompress(now)
    }

    @Synchronized
    private fun pruneAndCompress(now: Long = System.currentTimeMillis()) {
        val diagnosticsDir = dir ?: return
        val active = activeFile().absolutePath
        diagnosticsDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.lastModified() < now - RETENTION_MS) {
                file.delete()
            } else if (file.extension == "jsonl" && file.absolutePath != active) {
                gzip(file)
            }
        }
    }

    private fun activeFile(): File {
        val diagnosticsDir: File = dir ?: kotlin.error("DiagnosticsLogger not initialized")
        return File(diagnosticsDir, "vued-host-${hourFormat.format(Date())}.jsonl")
    }

    private fun gzip(file: File) {
        val parent: File = file.parentFile ?: return
        val gz = File(parent, "${file.name}.gz")
        if (gz.exists()) {
            file.delete()
            return
        }
        GZIPOutputStream(FileOutputStream(gz)).use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        }
        gz.setLastModified(file.lastModified())
        file.delete()
    }

    private fun sanitizeMap(map: Map<String, Any?>): JSONObject {
        val out = JSONObject()
        map.entries.take(80).forEach { (key, value) ->
            out.put(key, if (secretKeyRegex.containsMatchIn(key)) "[Redacted]" else sanitizeValue(value))
        }
        return out
    }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is String -> sanitizeString(value)
        is Number, is Boolean -> value
        is Map<*, *> -> sanitizeMap(value.entries.associate { it.key.toString() to it.value })
        is Iterable<*> -> JSONArray(value.take(50).map { sanitizeValue(it) })
        else -> sanitizeString(value.toString())
    }

    private fun sanitizeString(value: String): String =
        value
            .replace(bearerRegex, "Bearer [Redacted]")
            .replace(jwtRegex, "[RedactedJwt]")
            .replace(longBlobRegex, "[RedactedBlob]")
            .take(MAX_STRING_CHARS)

    private fun stackTrace(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}
