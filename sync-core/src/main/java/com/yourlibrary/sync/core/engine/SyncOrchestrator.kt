package com.yourlibrary.sync.core.engine

import com.yourlibrary.sync.core.model.SyncableEntity

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
