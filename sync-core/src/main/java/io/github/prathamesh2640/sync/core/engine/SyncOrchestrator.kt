package io.github.prathamesh2640.sync.core.engine

import io.github.prathamesh2640.sync.core.model.SyncableEntity

/**
 * Internal coordinator that drives the sync lifecycle for a single entity.
 *
 * Not part of the public API. Host apps interact with [SyncEngine] only.
 * Full implementation arrives in Commit 6 once [SyncStateMachine] and the
 * adapter interfaces are in place.
 */
internal class SyncOrchestrator {

    fun sync(entity: SyncableEntity) {
        // TODO: Commit 6 — wire SyncStateMachine, SyncNetworkAdapter, ConflictResolver
    }
}
