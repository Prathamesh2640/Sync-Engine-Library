package io.github.prathamesh2640.sync.core.internal

import io.github.prathamesh2640.sync.core.model.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialised, guarded holder for a single [SyncState] value.
 *
 * This is the one place a [SyncState] is allowed to change. Every mutation goes
 * through [transitionTo], which validates the move against the lifecycle graph
 * (see [SyncState]) under a [Mutex] so that concurrent callers can never observe
 * or produce a half-applied transition (SEC-11 — single serialised write path).
 *
 * The machine is deliberately **internal**: host apps drive state only through
 * the public `SyncEngine` API. The current value is exposed reactively as a
 * conflated [StateFlow] so the engine can surface it as `SyncEngine.syncState`.
 *
 * ## Valid transitions
 * ```
 *   PENDING  -> SYNCING
 *   SYNCING  -> SYNCED | FAILED | CONFLICT
 *   SYNCED   -> PENDING            (entity edited again locally / next run)
 *   FAILED   -> PENDING            (re-queued for retry)
 *   CONFLICT -> PENDING            (resolved, re-queued)
 * ```
 * Every other ordered pair — including any self-transition such as
 * `SYNCING -> SYNCING` — is invalid and is rejected without mutating state.
 *
 * @param initialState the state the machine starts in. Defaults to
 *   [SyncState.PENDING] (idle with possible queued work).
 */
internal class SyncStateMachine(initialState: SyncState = SyncState.PENDING) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(initialState)

    /** The current state as a conflated, always-populated [StateFlow]. */
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * A non-suspending snapshot of the current state.
     *
     * Safe to read from any thread ([StateFlow.value] is thread-safe). Callers
     * that need read-then-write atomicity must use [transitionTo], which holds
     * the mutex across the check and the update.
     */
    val current: SyncState get() = _state.value

    /**
     * Attempt to move to [target].
     *
     * The check and the write happen atomically under the mutex, so two
     * coroutines racing to transition can never both succeed on stale state.
     *
     * @param target the state to move to.
     * @return [TransitionResult.Moved] if the transition was valid and applied,
     *   or [TransitionResult.Rejected] if it was illegal (state is left unchanged).
     */
    suspend fun transitionTo(target: SyncState): TransitionResult = mutex.withLock {
        val from = _state.value
        if (target in allowedTargetsOf(from)) {
            _state.value = target
            TransitionResult.Moved(from, target)
        } else {
            TransitionResult.Rejected(from, target)
        }
    }

    private fun allowedTargetsOf(from: SyncState): Set<SyncState> = when (from) {
        SyncState.PENDING -> PENDING_TARGETS
        SyncState.SYNCING -> SYNCING_TARGETS
        SyncState.SYNCED -> TERMINAL_TARGETS
        SyncState.FAILED -> TERMINAL_TARGETS
        SyncState.CONFLICT -> TERMINAL_TARGETS
    }

    private companion object {
        // Precomputed transition table — allocation-free on the hot path.
        val PENDING_TARGETS = setOf(SyncState.SYNCING)
        val SYNCING_TARGETS = setOf(SyncState.SYNCED, SyncState.FAILED, SyncState.CONFLICT)
        val TERMINAL_TARGETS = setOf(SyncState.PENDING)
    }
}

/**
 * Outcome of a [SyncStateMachine.transitionTo] call.
 *
 * Internal by design: an illegal transition is a library-internal invariant
 * violation, not a value the host app should reason about, so it is deliberately
 * kept out of the public `SyncError` hierarchy (adding a branch there would be a
 * source-breaking change to the locked public API).
 */
internal sealed interface TransitionResult {

    /** The state the machine was in before the attempt. */
    val from: SyncState

    /** The state that was requested. */
    val to: SyncState

    /** The transition was valid and has been applied. */
    data class Moved(override val from: SyncState, override val to: SyncState) : TransitionResult

    /** The transition was illegal; the machine's state is unchanged. */
    data class Rejected(override val from: SyncState, override val to: SyncState) : TransitionResult
}
