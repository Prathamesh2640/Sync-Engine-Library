package io.github.prathamesh2640.sync.core.model

/**
 * Represents the synchronisation lifecycle state of a [SyncableEntity].
 *
 * Every entity moves through a well-defined state machine. The library
 * enforces valid transitions internally — host apps must not write
 * [syncState] directly; update it only through the SyncEngine API.
 *
 * ## State transition diagram
 * ```
 *  ┌─────────┐     ┌─────────┐     ┌────────┐
 *  │ PENDING │────►│ SYNCING │────►│ SYNCED │
 *  └─────────┘     └────┬────┘     └────────┘
 *       ▲           ┌───┴────────────────┐
 *       │           │                    │
 *       │     ┌─────▼──────┐    ┌────────▼────┐
 *       └─────│   FAILED   │    │  CONFLICT   │
 *             └────────────┘    └─────────────┘
 *              (retried)          (resolved)
 * ```
 *
 * Both [FAILED] and [CONFLICT] are independent terminal error states reached
 * directly from [SYNCING]. Each transitions back to [PENDING] when the engine
 * re-queues the entity — FAILED via immediate retry on the next
 * [io.github.prathamesh2640.sync.core.engine.SyncEngine.triggerSync] call (no
 * engine-internal delay; any backoff between runs is the host scheduler's
 * concern), CONFLICT via the host app's ConflictResolver.
 */
public enum class SyncState {

    /**
     * The entity has local changes not yet submitted to the sync queue.
     * This is the starting state for any new or modified entity.
     */
    PENDING,

    /**
     * The entity is actively being pushed to or pulled from the remote.
     * No further writes should be accepted while in this state.
     */
    SYNCING,

    /**
     * The entity is fully consistent with the remote.
     * No pending local changes exist.
     */
    SYNCED,

    /**
     * The last sync attempt failed (network error, server error, timeout).
     * Transitions back to [PENDING] and is retried on the next
     * [io.github.prathamesh2640.sync.core.engine.SyncEngine.triggerSync] call
     * (up to [io.github.prathamesh2640.sync.core.engine.SyncEngineConfig.maxRetries]
     * times) — there is no engine-internal delay between attempts.
     */
    FAILED,

    /**
     * A conflict was detected between the local version and the remote version.
     * The host app's ConflictResolver will be invoked to determine the winner.
     * Transitions back to [PENDING] once resolved.
     */
    CONFLICT
}
