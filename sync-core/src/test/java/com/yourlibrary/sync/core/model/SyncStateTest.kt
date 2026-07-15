package com.yourlibrary.sync.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SyncState].
 *
 * These tests lock the state names, count, and ordinal ordering so a rename
 * or accidental removal triggers a failing build before it reaches production.
 */
class SyncStateTest {

    // ── Completeness ──────────────────────────────────────────────────────────

    @Test
    fun `exactly five states are defined`() {
        assertEquals(
            "State count changed — update the state machine and migration guide",
            5,
            SyncState.entries.size
        )
    }

    // ── Name contract ─────────────────────────────────────────────────────────

    @Test
    fun `PENDING state name is correct`() {
        assertEquals("PENDING", SyncState.PENDING.name)
    }

    @Test
    fun `SYNCING state name is correct`() {
        assertEquals("SYNCING", SyncState.SYNCING.name)
    }

    @Test
    fun `SYNCED state name is correct`() {
        assertEquals("SYNCED", SyncState.SYNCED.name)
    }

    @Test
    fun `FAILED state name is correct`() {
        assertEquals("FAILED", SyncState.FAILED.name)
    }

    @Test
    fun `CONFLICT state name is correct`() {
        assertEquals("CONFLICT", SyncState.CONFLICT.name)
    }

    // ── Old name guard ────────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `NOT_SYNCED no longer exists — old name must not be present`() {
        // If this does NOT throw, the old name was re-introduced — that is a bug.
        SyncState.valueOf("NOT_SYNCED")
    }

    // ── valueOf round-trip ────────────────────────────────────────────────────

    @Test
    fun `all states are recoverable from their name string`() {
        for (state in SyncState.entries) {
            assertEquals(state, SyncState.valueOf(state.name))
        }
    }

    // ── Ordinal ordering (lifecycle sequence) ─────────────────────────────────

    @Test
    fun `PENDING ordinal precedes SYNCING`() {
        assertTrue(SyncState.PENDING.ordinal < SyncState.SYNCING.ordinal)
    }

    @Test
    fun `SYNCING ordinal precedes SYNCED`() {
        assertTrue(SyncState.SYNCING.ordinal < SyncState.SYNCED.ordinal)
    }

    // ── Inequality ────────────────────────────────────────────────────────────

    @Test
    fun `PENDING is not equal to SYNCED`() {
        assertNotEquals(SyncState.PENDING, SyncState.SYNCED)
    }

    @Test
    fun `FAILED is not equal to CONFLICT`() {
        assertNotEquals(SyncState.FAILED, SyncState.CONFLICT)
    }
}
