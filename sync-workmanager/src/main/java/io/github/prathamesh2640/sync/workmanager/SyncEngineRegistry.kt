package io.github.prathamesh2640.sync.workmanager

import io.github.prathamesh2640.sync.core.engine.SyncEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-global holder that hands each [SyncWorker] run the right [SyncEngine].
 *
 * WorkManager instantiates workers reflectively, so a worker cannot receive
 * constructor dependencies directly. [WorkManagerSyncScheduler] registers a
 * provider here, keyed by its `engineKey`, at construction; the worker resolves
 * the engine for its run's key from it. The provider — not the engine — is
 * stored, so a fresh engine is obtained per run and the host keeps full
 * ownership of the engine's lifecycle. Keying lets more than one
 * [WorkManagerSyncScheduler]/engine pair coexist in the same process (e.g. two
 * independent sync targets).
 *
 * Internal: host apps never touch this; they configure everything through
 * [WorkManagerSyncScheduler].
 */
internal object SyncEngineRegistry {

    private val providers = ConcurrentHashMap<String, () -> SyncEngine>()

    fun register(key: String, provider: () -> SyncEngine) {
        providers[key] = provider
    }

    fun clear(key: String) {
        providers.remove(key)
    }

    /** The engine for this run under [key], or `null` if none has been registered. */
    fun acquire(key: String): SyncEngine? = providers[key]?.invoke()
}
