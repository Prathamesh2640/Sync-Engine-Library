package io.github.prathamesh2640.sync.ui

import io.github.prathamesh2640.sync.core.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM checks for the dashboard's state model (no Compose runtime needed). */
class SyncDashboardStateTest {

    @Test
    fun defaults_are_an_idle_empty_dashboard() {
        val state = SyncDashboardState()

        assertEquals(SyncState.PENDING, state.syncState)
        assertNull(state.lastSyncTimestamp)
        assertEquals(0, state.pendingCount)
        assertEquals(0, state.failedCount)
        assertEquals(0, state.conflictCount)
        assertNull(state.lastError)
    }

    @Test
    fun copy_updates_only_the_named_fields() {
        val base = SyncDashboardState()

        val updated = base.copy(syncState = SyncState.SYNCED, pendingCount = 3, lastSyncTimestamp = 42L)

        assertEquals(SyncState.SYNCED, updated.syncState)
        assertEquals(3, updated.pendingCount)
        assertEquals(42L, updated.lastSyncTimestamp)
        assertEquals("other fields untouched", 0, updated.failedCount)
    }
}
