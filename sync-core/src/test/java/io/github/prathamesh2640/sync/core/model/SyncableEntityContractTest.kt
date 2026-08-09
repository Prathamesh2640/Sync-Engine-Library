package io.github.prathamesh2640.sync.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [SyncableEntity].
 *
 * Uses a minimal in-test implementation ([TestEntity]) to verify that the
 * interface behaves correctly for every property. Sync lifecycle (state,
 * tombstone flag) is not part of this interface — see [SyncMetadataTest] for
 * that contract.
 */
class SyncableEntityContractTest {

    // ── Minimal concrete implementation used only in these tests ──────────────

    private data class TestEntity(
        override val id: String,
        override val lastModified: Long,
    ) : SyncableEntity

    // ── id ────────────────────────────────────────────────────────────────────

    @Test
    fun `id is preserved correctly`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val entity = TestEntity(id = uuid, lastModified = 0L)
        assertEquals(uuid, entity.id)
    }

    @Test
    fun `two entities with different ids are not equal`() {
        val e1 = TestEntity(id = "id-A", lastModified = 100L)
        val e2 = TestEntity(id = "id-B", lastModified = 100L)
        assertNotEquals(e1, e2)
    }

    // ── lastModified ──────────────────────────────────────────────────────────

    @Test
    fun `lastModified is preserved correctly`() {
        val timestamp = 1_716_912_000_000L
        val entity = TestEntity(id = "id", lastModified = timestamp)
        assertEquals(timestamp, entity.lastModified)
    }

    @Test
    fun `later lastModified is greater than earlier lastModified`() {
        val older = TestEntity(id = "id", lastModified = 1_000L)
        val newer = TestEntity(id = "id", lastModified = 2_000L)
        assertTrue(newer.lastModified > older.lastModified)
    }

    // ── Equality and identity ─────────────────────────────────────────────────

    @Test
    fun `two entities with identical properties are equal`() {
        val e1 = TestEntity(id = "same", lastModified = 100L)
        val e2 = TestEntity(id = "same", lastModified = 100L)
        assertEquals(e1, e2)
    }

    @Test
    fun `entities with the same id but different lastModified are not equal`() {
        val older = TestEntity(id = "id", lastModified = 100L)
        val newer = TestEntity(id = "id", lastModified = 200L)
        assertNotEquals(older, newer)
    }
}
