package com.robotics.polly

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class LogManagerTest {

    @Before
    fun setUp() {
        LogManager.clear()
    }

    @Test
    fun `info adds entry`() {
        LogManager.info("test")
        val logs = LogManager.getLogs()
        assertEquals(1, logs.size)
        assertEquals(LogManager.LogLevel.INFO, logs[0].level)
        assertEquals("test", logs[0].message)
    }

    @Test
    fun `buffer caps at 100`() {
        repeat(110) { i ->
            LogManager.info("msg $i")
        }
        val logs = LogManager.getLogs()
        assertEquals(100, logs.size)
        // Oldest (0-9) should be dropped; first remaining is msg 10
        assertEquals("msg 10", logs[0].message)
    }

    @Test
    fun `clear empties logs`() {
        LogManager.info("a")
        LogManager.info("b")
        LogManager.clear()
        assertTrue(LogManager.getLogs().isEmpty())
    }

    @Test
    fun `warn prepends !! prefix`() {
        LogManager.warn("msg")
        val logs = LogManager.getLogs()
        assertEquals("!! msg", logs[0].message)
        assertEquals(LogManager.LogLevel.WARN, logs[0].level)
    }

    @Test
    fun `tx prepends TX prefix`() {
        LogManager.tx("msg")
        val logs = LogManager.getLogs()
        assertEquals("TX: msg", logs[0].message)
        assertEquals(LogManager.LogLevel.TX, logs[0].level)
    }

    @Test
    fun `rx prepends RX prefix`() {
        LogManager.rx("msg")
        val logs = LogManager.getLogs()
        assertEquals("RX: msg", logs[0].message)
        assertEquals(LogManager.LogLevel.RX, logs[0].level)
    }

    @Test
    fun `listener is notified`() {
        var notified: LogManager.LogEntry? = null
        val listener: (LogManager.LogEntry) -> Unit = { notified = it }
        LogManager.addListener(listener)

        LogManager.info("hello")

        assertNotNull(notified)
        assertEquals("hello", notified!!.message)

        LogManager.removeListener(listener)
    }

    @Test
    fun `removeListener stops notifications`() {
        var callCount = 0
        val listener: (LogManager.LogEntry) -> Unit = { callCount++ }
        LogManager.addListener(listener)

        LogManager.info("first")
        assertEquals(1, callCount)

        LogManager.removeListener(listener)

        LogManager.info("second")
        assertEquals(1, callCount)
    }
}
