package io.github.prathamesh2640.sync.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LogLevel].
 *
 * Locks the level names, count, and least-to-most-verbose ordering so a rename
 * or reorder fails the build before it reaches consumers.
 */
class LogLevelTest {

    @Test
    fun `exactly five levels are defined`() {
        assertEquals(5, LogLevel.entries.size)
    }

    @Test
    fun `level names are stable`() {
        assertEquals("NONE", LogLevel.NONE.name)
        assertEquals("ERROR", LogLevel.ERROR.name)
        assertEquals("WARN", LogLevel.WARN.name)
        assertEquals("INFO", LogLevel.INFO.name)
        assertEquals("DEBUG", LogLevel.DEBUG.name)
    }

    @Test
    fun `ordering runs least to most verbose`() {
        assertEquals(0, LogLevel.NONE.ordinal)
        assertTrue(LogLevel.NONE.ordinal < LogLevel.ERROR.ordinal)
        assertTrue(LogLevel.ERROR.ordinal < LogLevel.WARN.ordinal)
        assertTrue(LogLevel.WARN.ordinal < LogLevel.INFO.ordinal)
        assertTrue(LogLevel.INFO.ordinal < LogLevel.DEBUG.ordinal)
    }

    @Test
    fun `NONE is the default configured level`() {
        assertEquals(LogLevel.NONE, SyncEngineConfig {}.logLevel)
    }
}
