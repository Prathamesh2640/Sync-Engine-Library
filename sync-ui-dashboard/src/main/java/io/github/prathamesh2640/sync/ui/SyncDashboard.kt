package io.github.prathamesh2640.sync.ui

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
 */
public object SyncDashboard {

    @Volatile
    internal var stateFlow: StateFlow<SyncDashboardState>? = null
        private set

    @Volatile
    internal var onTriggerSync: (() -> Unit)? = null
        private set

    /**
     * Register the state source and trigger callback [SyncDashboardActivity] uses.
     *
     * @param state the observable dashboard state.
     * @param onTriggerSync invoked when the user taps "Sync now".
     */
    public fun install(state: StateFlow<SyncDashboardState>, onTriggerSync: () -> Unit) {
        this.stateFlow = state
        this.onTriggerSync = onTriggerSync
    }

    /** Detach the installed source (e.g. on app teardown). */
    public fun clear() {
        stateFlow = null
        onTriggerSync = null
    }
}
