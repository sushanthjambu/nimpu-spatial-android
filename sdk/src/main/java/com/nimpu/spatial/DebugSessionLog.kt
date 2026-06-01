package com.nimpu.spatial.sdk

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Small in-app session log for untethered field testing.
 * Stores only human-meaningful events so the log can be shared from the app.
 */
object DebugSessionLog {
    private const val MAX_LINES = 400

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>(MAX_LINES)
    private var sessionLabel: String = "Nimpu Spatial"

    fun startSession(label: String) {
        synchronized(lock) {
            lines.clear()
            sessionLabel = label
            appendInternal("SESSION", "Started $label")
        }
    }

    fun append(tag: String, message: String) {
        synchronized(lock) {
            appendInternal(tag, message)
        }
    }

    fun snapshot(): String {
        synchronized(lock) {
            return buildString {
                appendLine(sessionLabel)
                appendLine()
                lines.forEach { appendLine(it) }
            }
        }
    }

    private fun appendInternal(tag: String, message: String) {
        if (lines.size >= MAX_LINES) {
            lines.removeFirst()
        }
        lines.addLast("${timeFormat.format(Date())} [$tag] $message")
    }
}
