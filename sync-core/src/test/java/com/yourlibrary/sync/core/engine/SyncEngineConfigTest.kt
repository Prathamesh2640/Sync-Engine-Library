package com.yourlibrary.sync.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [SyncEngineConfig] and its DSL.
 *
 * Verifies defaults, the DSL override path, the Java-style [SyncEngineConfig.Builder],
 * and that invalid values fail fast at build time.
 */
class SyncEngineConfigTest {

    @Test
    fun `empty DSL yields all defaults`() {
        val config = SyncEngineConfig {}
        assertEquals(SyncEngineConfig.DEFAULT_BATCH_SIZE, config.batchSize)
        assertEquals(SyncEngineConfig.DEFAULT_MAX_RETRIES, config.maxRetries)
        assertEquals(SyncEngineConfig.DEFAULT_TOMBSTONE_RETENTION_DAYS, config.tombstoneRetentionDays)
        assertEquals(LogLevel.NONE, config.logLevel)
    }

    @Test
    fun `documented default values are correct`() {
        assertEquals(50, SyncEngineConfig.DEFAULT_BATCH_SIZE)
        assertEquals(3, SyncEngineConfig.DEFAULT_MAX_RETRIES)
        assertEquals(30, SyncEngineConfig.DEFAULT_TOMBSTONE_RETENTION_DAYS)
    }

    @Test
    fun `DSL overrides only the properties set`() {
        val config = SyncEngineConfig {
            batchSize = 100
            logLevel = LogLevel.DEBUG
        }
        assertEquals(100, config.batchSize)
        assertEquals(LogLevel.DEBUG, config.logLevel)
        // untouched -> defaults
        assertEquals(SyncEngineConfig.DEFAULT_MAX_RETRIES, config.maxRetries)
        assertEquals(SyncEngineConfig.DEFAULT_TOMBSTONE_RETENTION_DAYS, config.tombstoneRetentionDays)
    }

    @Test
    fun `Builder produces the same result as the DSL`() {
        val fromBuilder = SyncEngineConfig.Builder().apply {
            batchSize = 25
            maxRetries = 1
            tombstoneRetentionDays = 7
            logLevel = LogLevel.INFO
        }.build()
        val fromDsl = SyncEngineConfig {
            batchSize = 25
            maxRetries = 1
            tombstoneRetentionDays = 7
            logLevel = LogLevel.INFO
        }
        assertEquals(fromDsl.batchSize, fromBuilder.batchSize)
        assertEquals(fromDsl.maxRetries, fromBuilder.maxRetries)
        assertEquals(fromDsl.tombstoneRetentionDays, fromBuilder.tombstoneRetentionDays)
        assertEquals(fromDsl.logLevel, fromBuilder.logLevel)
    }

    @Test
    fun `batchSize must be positive`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            SyncEngineConfig { batchSize = 0 }
        }
        assertEquals("batchSize must be > 0, was 0", ex.message)
    }

    @Test
    fun `maxRetries may not be negative`() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncEngineConfig { maxRetries = -1 }
        }
    }

    @Test
    fun `tombstoneRetentionDays may not be negative`() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncEngineConfig { tombstoneRetentionDays = -1 }
        }
    }

    @Test
    fun `zero retries and zero retention are allowed`() {
        val config = SyncEngineConfig {
            maxRetries = 0
            tombstoneRetentionDays = 0
        }
        assertEquals(0, config.maxRetries)
        assertEquals(0, config.tombstoneRetentionDays)
    }
}
