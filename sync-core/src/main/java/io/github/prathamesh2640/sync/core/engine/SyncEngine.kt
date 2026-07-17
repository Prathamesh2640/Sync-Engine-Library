package io.github.prathamesh2640.sync.core.engine

import io.github.prathamesh2640.sync.core.adapter.ConflictResolver
import io.github.prathamesh2640.sync.core.adapter.SyncNetworkAdapter
import io.github.prathamesh2640.sync.core.internal.SyncEngineImpl
import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.model.SyncableEntity
import io.github.prathamesh2640.sync.core.result.SyncResult
import io.github.prathamesh2640.sync.core.store.LocalSyncStore
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

/**
 * The main entry point host apps hold onto to drive offline-first sync.
 *
 * An engine owns a coroutine scope and observes/mutates the sync state of the
 * entities registered with it. Host apps interact only with this interface —
 * the implementation (`SyncEngineImpl`) is `internal` and arrives in a later
 * commit, together with the `SyncEngine.create(...)` factory that constructs it.
 *
 * [SyncEngine] extends [Closeable]: call [close] (or use Kotlin's `use { }` /
 * Java try-with-resources) to cancel the engine's scope and release resources.
 * A closed engine must not be reused.
 *
 * ```kotlin
 * // Usage sketch (factory lands with the implementation):
 * engine.syncState
 *     .onEach { state -> updateUi(state) }
 *     .launchIn(uiScope)
 *
 * when (val result = engine.triggerSync()) {
 *     is SyncResult.Success        -> { /* ... */ }
 *     is SyncResult.PartialFailure -> { /* ... */ }
 *     is SyncResult.Failure        -> { /* ... */ }
 * }
 * ```
 */
public interface SyncEngine : Closeable {

    /**
     * The current, observable sync state, hot and conflated.
     *
     * Reflects the engine's overall position in the lifecycle
     * ([SyncState.PENDING] when idle with queued work, [SyncState.SYNCING] while
     * a run is in flight, and so on). Collect it to drive reactive UI. Always
     * holds a value; new collectors immediately receive the latest.
     */
    public val syncState: StateFlow<SyncState>

    /**
     * Run a sync now and suspend until it completes.
     *
     * Safe to call from any dispatcher. Does not throw — every outcome is
     * returned as a [SyncResult]. Calling this on a closed engine returns
     * [SyncResult.Failure].
     *
     * @return the outcome of the run.
     */
    public suspend fun triggerSync(): SyncResult

    /**
     * Cancel the engine's coroutine scope and release its resources.
     *
     * Idempotent — calling it more than once has no additional effect. After
     * close, [triggerSync] returns [SyncResult.Failure] and [syncState] stops
     * emitting new values.
     */
    override fun close()

    public companion object {

        /**
         * Create a ready-to-use [SyncEngine].
         *
         * The engine is constructed from its collaborators directly — it takes
         * **no Android `Context`**, which keeps `:sync-core` framework-free and
         * fully JVM-testable (see ADL-006). Host apps that use Room/WorkManager
         * wire those in through the `adapter` (and, in a later version, a storage
         * source); this module never touches `android.*`.
         *
         * Construction is cheap and does no I/O — nothing is synced until the
         * first call to [triggerSync].
         *
         * ```kotlin
         * val engine = SyncEngine.create(
         *     adapter = myNetworkAdapter,
         *     config = SyncEngineConfig { batchSize = 100 },
         *     store = myRoomSyncAdapter,   // optional: durable, offline-first queue
         *     resolver = ConflictResolver { local, remote ->  // optional: two-way sync
         *         if (local.lastModified >= remote.lastModified) local else remote
         *     },
         * )
         * engine.use {
         *     val result = it.triggerSync()
         * }
         * ```
         *
         * @param adapter the host app's [SyncNetworkAdapter]; the engine calls it
         *   to move entities across the network and never talks to the network
         *   directly.
         * @param config tuning parameters. Defaults to [SyncEngineConfig] with
         *   every option at its default.
         * @param store optional durable [LocalSyncStore] (e.g. `RoomSyncAdapter`
         *   from `:sync-storage-room`). When supplied, each run seeds its queue
         *   from storage and writes outcomes back, so pending work survives
         *   process death, and the engine performs a two-way sync (push, then pull
         *   remote changes into the store). When omitted the engine keeps an
         *   in-memory, push-only queue. Declared before [resolver] so existing
         *   `create(adapter)` / `create(adapter, config)` call sites are unaffected.
         * @param resolver optional [ConflictResolver] applied during the pull phase
         *   when a locally-pending entity also changed on the remote. Requires
         *   [store] (there is nothing to pull into without one). When omitted, a
         *   detected conflict leaves the entity in [SyncState.CONFLICT] and is
         *   reported as [io.github.prathamesh2640.sync.core.result.SyncError.ConflictUnresolvable].
         * @return a new engine. Remember to [close] it (or use `use { }`) to
         *   release its coroutine scope.
         */
        @JvmStatic
        @JvmOverloads
        public fun <T : SyncableEntity> create(
            adapter: SyncNetworkAdapter<T>,
            config: SyncEngineConfig = SyncEngineConfig {},
            store: LocalSyncStore<T>? = null,
            resolver: ConflictResolver<T>? = null,
        ): SyncEngine = SyncEngineImpl(adapter = adapter, config = config, store = store, resolver = resolver)
    }
}
