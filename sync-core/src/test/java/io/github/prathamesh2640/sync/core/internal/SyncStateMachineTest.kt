package io.github.prathamesh2640.sync.core.internal

import io.github.prathamesh2640.sync.core.model.SyncState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive contract for [SyncStateMachine]: every one of the 25 ordered
 * (from, to) pairs is classified as valid or invalid, and each is asserted.
 */
class SyncStateMachineTest {

    /** The complete set of legal transitions per the [SyncState] lifecycle. */
    private val validTransitions: Set<Pair<SyncState, SyncState>> = setOf(
        SyncState.PENDING to SyncState.SYNCING,
        SyncState.SYNCING to SyncState.SYNCED,
        SyncState.SYNCING to SyncState.FAILED,
        SyncState.SYNCING to SyncState.CONFLICT,
        SyncState.SYNCED to SyncState.PENDING,
        SyncState.FAILED to SyncState.PENDING,
        SyncState.CONFLICT to SyncState.PENDING,
    )

    @Test
    fun `every valid transition is applied and updates the state flow`() = runTest {
        for ((from, to) in validTransitions) {
            val machine = SyncStateMachine(initialState = from)

            val applied = machine.transitionTo(to)

            assertTrue("Expected $from -> $to to be allowed", applied)
            assertEquals("state flow must reflect the move", to, machine.current)
            assertEquals(to, machine.state.value)
        }
    }

    @Test
    fun `every invalid transition is rejected and leaves state unchanged`() = runTest {
        val allPairs = SyncState.entries.flatMap { from ->
            SyncState.entries.map { to -> from to to }
        }
        val invalidTransitions = allPairs - validTransitions

        // Sanity: 5x5 grid minus 7 valid moves = 18 invalid pairs.
        assertEquals(18, invalidTransitions.size)

        for ((from, to) in invalidTransitions) {
            val machine = SyncStateMachine(initialState = from)

            val applied = machine.transitionTo(to)

            assertFalse("Expected $from -> $to to be rejected", applied)
            assertEquals("rejected transition must not mutate state", from, machine.current)
        }
    }

    @Test
    fun `self transitions are always invalid`() = runTest {
        for (state in SyncState.entries) {
            val machine = SyncStateMachine(initialState = state)
            val applied = machine.transitionTo(state)
            assertFalse("$state -> $state must be rejected", applied)
            assertEquals(state, machine.current)
        }
    }

    @Test
    fun `default initial state is PENDING`() = runTest {
        assertEquals(SyncState.PENDING, SyncStateMachine().current)
    }

    @Test
    fun `full happy-path cycle PENDING SYNCING SYNCED PENDING`() = runTest {
        val machine = SyncStateMachine()

        assertTrue(machine.transitionTo(SyncState.SYNCING))
        assertTrue(machine.transitionTo(SyncState.SYNCED))
        assertTrue(machine.transitionTo(SyncState.PENDING))

        assertEquals(SyncState.PENDING, machine.current)
    }

    @Test
    fun `retry path SYNCING FAILED PENDING SYNCING`() = runTest {
        val machine = SyncStateMachine()
        machine.transitionTo(SyncState.SYNCING)

        assertTrue(machine.transitionTo(SyncState.FAILED))
        assertTrue(machine.transitionTo(SyncState.PENDING))
        assertTrue(machine.transitionTo(SyncState.SYNCING))
    }

    @Test
    fun `state flow exposes the same instance for repeated reads`() = runTest {
        val machine = SyncStateMachine()
        assertSame(machine.state, machine.state)
    }
}
