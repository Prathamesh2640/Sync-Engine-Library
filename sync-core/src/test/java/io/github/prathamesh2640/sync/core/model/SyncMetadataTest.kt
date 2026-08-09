package io.github.prathamesh2640.sync.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract tests for [SyncMetadata] — the sync lifecycle record moved off [SyncableEntity] (ADL-022). */
class SyncMetadataTest {

    @Test
    fun `syncState reflects the assigned value for every state`() {
        for (state in SyncState.entries) {
            val metadata = SyncMetadata(syncState = state)
            assertEquals("syncState mismatch for $state", state, metadata.syncState)
        }
    }

    @Test
    fun `isDeleted defaults to false when not explicitly set`() {
        val metadata = SyncMetadata(syncState = SyncState.PENDING)
        assertFalse("isDeleted must default to false — new entities are not deleted", metadata.isDeleted)
    }

    @Test
    fun `isDeleted can be explicitly set to true for a tombstone`() {
        val tombstone = SyncMetadata(syncState = SyncState.PENDING, isDeleted = true)
        assertTrue(tombstone.isDeleted)
    }

    @Test
    fun `a tombstone still carries a valid syncState`() {
        // Tombstones must still be synced — they should be PENDING until the
        // server confirms the deletion.
        val tombstone = SyncMetadata(syncState = SyncState.PENDING, isDeleted = true)
        assertEquals(SyncState.PENDING, tombstone.syncState)
    }

    @Test
    fun `two records with identical properties are equal`() {
        val m1 = SyncMetadata(syncState = SyncState.SYNCED)
        val m2 = SyncMetadata(syncState = SyncState.SYNCED)
        assertEquals(m1, m2)
    }

    @Test
    fun `records with different syncState are not equal`() {
        val pending = SyncMetadata(syncState = SyncState.PENDING)
        val synced = SyncMetadata(syncState = SyncState.SYNCED)
        assertNotEquals(pending, synced)
    }
}
