package com.yourlibrary.sync.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [SyncableEntity].
 *
 * Uses a minimal in-test implementation ([TestEntity]) to verify that the
 * interface behaves correctly for every property. Any future change to the
 * interface contract must be reflected here.
 */
class SyncableEntityContractTest {

    // ── Minimal concrete implementation used only in these tests ──────────────

    private data class TestEntity(
        override val id: String,
        override val lastModified: Long,
        override val syncState: SyncState,
        override val isDeleted: Boolean = false
    ) : SyncableEntity

    // ── id ────────────────────────────────────────────────────────────────────

    @Test
    fun `id is preserved correctly`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val entity = TestEntity(id = uuid, lastModified = 0L, syncState = SyncState.PENDING)
        assertEquals(uuid, entity.id)
    }

    @Test
    fun `two entities with different ids are not equal`() {
        val e1 = TestEntity(id = "id-A", lastModified = 100L, syncState = SyncState.PENDING)
        val e2 = TestEntity(id = "id-B", lastModified = 100L, syncState = SyncState.PENDING)
        assertNotEquals(e1, e2)
    }

    // ── lastModified ──────────────────────────────────────────────────────────

    @Test
    fun `lastModified is preserved correctly`() {
        val timestamp = 1_716_912_000_000L
        val entity = TestEntity(id = "id", lastModified = timestamp, syncState = SyncState.PENDING)
        assertEquals(timestamp, entity.lastModified)
    }

    @Test
    fun `later lastModified is greater than earlier lastModified`() {
        val older = TestEntity(id = "id", lastModified = 1_000L, syncState = SyncState.SYNCED)
        val newer = TestEntity(id = "id", lastModified = 2_000L, syncState = SyncState.SYNCED)
        assertTrue(newer.lastModified > older.lastModified)
    }

    // ── syncState ─────────────────────────────────────────────────────────────

    @Test
    fun `syncState reflects the assigned value for every state`() {
        for (state in SyncState.entries) {
            val entity = TestEntity(id = "id", lastModified = 0L, syncState = state)
            assertEquals("syncState mismatch for $state", state, entity.syncState)
        }
    }

    // ── isDeleted (tombstone) ─────────────────────────────────────────────────

    @Test
    fun `isDeleted defaults to false when not explicitly set`() {
        val entity = TestEntity(id = "id", lastModified = 0L, syncState = SyncState.PENDING)
        assertFalse(
            "isDeleted must default to false — new entities are not deleted",
            entity.isDeleted
        )
    }

    @Test
    fun `isDeleted can be explicitly set to true for a tombstone`() {
        val tombstone = TestEntity(
            id = "id",
            lastModified = 0L,
            syncState = SyncState.PENDING,
            isDeleted = true
        )
        assertTrue(tombstone.isDeleted)
    }

    @Test
    fun `a tombstone entity still carries a valid syncState`() {
        // Tombstones must still be synced — they should be PENDING until the
        // server confirms the deletion.
        val tombstone = TestEntity(
            id = "id",
            lastModified = 0L,
            syncState = SyncState.PENDING,
            isDeleted = true
        )
        assertEquals(SyncState.PENDING, tombstone.syncState)
    }

    // ── Equality and identity ─────────────────────────────────────────────────

    @Test
    fun `two entities with identical properties are equal`() {
        val e1 = TestEntity(id = "same", lastModified = 100L, syncState = SyncState.SYNCED)
        val e2 = TestEntity(id = "same", lastModified = 100L, syncState = SyncState.SYNCED)
        assertEquals(e1, e2)
    }

    @Test
    fun `entities with the same id but different syncState are not equal`() {
        val pending = TestEntity(id = "id", lastModified = 100L, syncState = SyncState.PENDING)
        val synced  = TestEntity(id = "id", lastModified = 100L, syncState = SyncState.SYNCED)
        assertNotEquals(pending, synced)
    }
}
