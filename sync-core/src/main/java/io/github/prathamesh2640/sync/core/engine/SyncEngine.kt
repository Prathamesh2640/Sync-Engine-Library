package io.github.prathamesh2640.sync.core.engine

import io.github.prathamesh2640.sync.core.model.SyncState
import io.github.prathamesh2640.sync.core.result.SyncResult
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
}
