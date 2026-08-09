package io.github.prathamesh2640.sync.ui

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.StateFlow

/**
 * Wiring point between the host app and the standalone [SyncDashboardActivity].
 *
 * Because an `Activity` is instantiated by the framework, it cannot be handed a
 * state source through its constructor. The host installs one here (typically in
 * `Application.onCreate`, debug builds only); the activity reads it when launched.
 *
 * Apps that embed [SyncDashboardRoute] directly in their own screen do not need
 * this — they pass state and the trigger callback straight to the composable.
 *
 * ```kotlin
 * // Application.onCreate (debug only):
 * SyncDashboard.install(
 *     state = dashboardStateFlow,       // StateFlow<SyncDashboardState>
 *     onTriggerSync = { scope.launch { engine.triggerSync() } },
 * )
 * ```
 *
 * More than one engine's dashboard can be installed in the same process — pass a
 * distinct [key] per engine and launch [SyncDashboardActivity] with the matching
 * [SyncDashboardActivity.EXTRA_ENGINE_KEY] intent extra.
 */
public object SyncDashboard {

    /** Key used when a host installs only one dashboard source. */
    public const val DEFAULT_KEY: String = "default"

    private data class Entry(val stateFlow: StateFlow<SyncDashboardState>, val onTriggerSync: () -> Unit)

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Register the state source and trigger callback [SyncDashboardActivity] uses
     * for [key]. Installing again under the same key replaces the previous entry
     * (the expected `Application.onCreate` re-run pattern).
     *
     * @param state the observable dashboard state.
     * @param onTriggerSync invoked when the user taps "Sync now".
     * @param key identifies this dashboard source; defaults to [DEFAULT_KEY].
     */
    public fun install(
        state: StateFlow<SyncDashboardState>,
        onTriggerSync: () -> Unit,
        key: String = DEFAULT_KEY,
    ) {
        entries[key] = Entry(state, onTriggerSync)
    }

    /** Detach the installed source for [key] (e.g. on app teardown). */
    public fun clear(key: String = DEFAULT_KEY) {
        entries.remove(key)
    }

    internal fun stateFlowFor(key: String): StateFlow<SyncDashboardState>? = entries[key]?.stateFlow

    internal fun onTriggerSyncFor(key: String): (() -> Unit)? = entries[key]?.onTriggerSync
}
