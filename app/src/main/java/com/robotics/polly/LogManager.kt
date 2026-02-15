package com.robotics.polly

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

object LogManager {
    
    private val logs = CopyOnWriteArrayList<LogEntry>()
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private const val MAX_LOGS = 100
    
    enum class LogLevel {
        INFO, SUCCESS, ERROR, WARN, TX, RX
    }
    
    data class LogEntry(
        val timestamp: String,
        val level: LogLevel,
        val message: String
    )
    
    fun log(level: LogLevel, message: String) {
        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            level = level,
            message = message
        )
        
        logs.add(entry)
        
        // Keep only last MAX_LOGS entries
        while (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
        
        // Notify listeners
        listeners.forEach { it(entry) }
    }
    
    fun info(message: String) = log(LogLevel.INFO, message)
    fun success(message: String) = log(LogLevel.SUCCESS, message)
    fun error(message: String) = log(LogLevel.ERROR, message)
    fun warn(message: String) = log(LogLevel.WARN, "!! $message")
    fun tx(message: String) = log(LogLevel.TX, "TX: $message")
    fun rx(message: String) = log(LogLevel.RX, "RX: $message")
    
    fun addListener(listener: (LogEntry) -> Unit) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }
    
    fun getLogs(): List<LogEntry> = logs.toList()
    
    fun getFormattedLogs(): String {
        return logs.joinToString("\n") { entry ->
            val prefix = when (entry.level) {
                LogLevel.INFO -> "ℹ️"
                LogLevel.SUCCESS -> "✅"
                LogLevel.ERROR -> "❌"
                LogLevel.WARN -> "⚠️"
                LogLevel.TX -> "📤"
                LogLevel.RX -> "📥"
            }
            "${entry.timestamp} $prefix ${entry.message}"
        }
    }
    
    fun clear() {
        logs.clear()
    }
}
