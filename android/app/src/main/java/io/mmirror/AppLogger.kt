package io.mmirror

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object AppLogger {
    private const val MAX_LOGS = 300
    private val buffer = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        addEntry("INFO", tag, msg)
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        addEntry("DEBUG", tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        val fullMsg = if (tr != null) "$msg (원인: ${tr.javaClass.simpleName}: ${tr.message})" else msg
        addEntry("WARN", tag, fullMsg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        val exDetail = tr?.let {
            val traceSnippet = it.stackTrace.take(3).joinToString(" -> ") { el ->
                "${el.className.substringAfterLast('.')}.${el.methodName}:${el.lineNumber}"
            }
            "\n  ↳ [${it.javaClass.simpleName}]: ${it.message ?: "원인 미상"} ($traceSnippet)"
        } ?: ""
        addEntry("ERROR", tag, "$msg$exDetail")
    }

    private fun addEntry(level: String, tag: String, msg: String) {
        val time = timeFormat.format(Date())
        buffer.add("[$time] [$level] [$tag] $msg")
        while (buffer.size > MAX_LOGS) {
            buffer.pollFirst()
        }
    }

    fun getLogs(): List<String> = buffer.toList()

    fun clear() {
        buffer.clear()
    }
}
