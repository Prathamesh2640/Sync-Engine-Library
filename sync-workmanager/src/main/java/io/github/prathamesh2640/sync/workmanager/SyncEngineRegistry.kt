package io.github.prathamesh2640.sync.workmanager

import io.github.prathamesh2640.sync.core.engine.SyncEngine

/**
 * Process-global holder that hands the [SyncWorker] the host's [SyncEngine].
 *
 * WorkManager instantiates workers reflectively, so a worker cannot receive
 * constructor dependencies directly. [WorkManagerSyncScheduler] registers a
 * provider here at construction; the worker resolves the engine from it when it
 * runs. The provider — not the engine — is stored, so a fresh engine is obtained
 * per run and the host keeps full ownership of the engine's lifecycle.
 *
 * Internal: host apps never touch this; they configure everything through
 * [WorkManagerSyncScheduler].
 */
internal object SyncEngineRegistry {

    @Volatile
    private var provider: (() -> SyncEngine)? = null

    fun register(provider: () -> SyncEngine) {
        this.provider = provider
    }

    fun clear() {
        provider = null
    }

    /** The engine for this run, or `null` if none has been registered. */
    fun acquire(): SyncEngine? = provider?.invoke()
}
