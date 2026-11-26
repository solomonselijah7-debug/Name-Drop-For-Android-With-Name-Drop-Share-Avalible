package com.example.data

import com.example.model.DebugLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object DebugLogger {
    private const val MAX_ENTRIES = 200
    private val counter = AtomicLong(1L)
    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _logs = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val logs: StateFlow<List<DebugLogEntry>> = _logs.asStateFlow()

    @Synchronized
    fun log(tag: String, message: String, level: String = "INFO") {
        val entry = DebugLogEntry(
            id = counter.getAndIncrement(),
            timestamp = timeFormatter.format(Date()),
            tag = tag,
            level = level,
            message = message
        )
        val updated = (_logs.value + entry).takeLast(MAX_ENTRIES)
        _logs.value = updated
    }

    @Synchronized
    fun clear() {
        _logs.value = emptyList()
    }

    fun exportAsText(): String {
        return _logs.value.joinToString("\n") {
            "[${it.timestamp}] [${it.level}] ${it.tag}: ${it.message}"
        }
    }
}
