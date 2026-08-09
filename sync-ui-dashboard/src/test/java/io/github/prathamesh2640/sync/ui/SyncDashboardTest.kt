package io.github.prathamesh2640.sync.ui

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Pure JVM checks for [SyncDashboard]'s keyed install/clear (no Compose runtime needed). */
class SyncDashboardTest {

    @After
    fun tearDown() {
        SyncDashboard.clear("notes")
        SyncDashboard.clear("reminders")
        SyncDashboard.clear()
    }

    @Test
    fun two_keys_stay_isolated() {
        val notesState = MutableStateFlow(SyncDashboardState())
        val remindersState = MutableStateFlow(SyncDashboardState())
        var notesTriggered = false
        var remindersTriggered = false

        SyncDashboard.install(notesState, { notesTriggered = true }, key = "notes")
        SyncDashboard.install(remindersState, { remindersTriggered = true }, key = "reminders")

        assertSame(notesState, SyncDashboard.stateFlowFor("notes"))
        assertSame(remindersState, SyncDashboard.stateFlowFor("reminders"))

        SyncDashboard.onTriggerSyncFor("notes")?.invoke()
        assertEquals(true, notesTriggered)
        assertEquals(false, remindersTriggered)
    }

    @Test
    fun clear_only_clears_its_own_key() {
        val notesState = MutableStateFlow(SyncDashboardState())
        val remindersState = MutableStateFlow(SyncDashboardState())
        SyncDashboard.install(notesState, {}, key = "notes")
        SyncDashboard.install(remindersState, {}, key = "reminders")

        SyncDashboard.clear("notes")

        assertNull(SyncDashboard.stateFlowFor("notes"))
        assertSame(remindersState, SyncDashboard.stateFlowFor("reminders"))
    }

    @Test
    fun default_key_used_when_none_given() {
        val state = MutableStateFlow(SyncDashboardState())
        SyncDashboard.install(state, {})

        assertSame(state, SyncDashboard.stateFlowFor(SyncDashboard.DEFAULT_KEY))
    }

    @Test
    fun uninstalled_key_returns_null() {
        assertNull(SyncDashboard.stateFlowFor("never-installed"))
        assertNull(SyncDashboard.onTriggerSyncFor("never-installed"))
    }
}
