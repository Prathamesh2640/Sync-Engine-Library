package io.github.prathamesh2640.sync.core.scheduler

/**
 * Schedules automatic background syncs.
 *
 * The library defines the contract here in `:sync-core` (framework-free) and
 * implements it in a platform module — `:sync-workmanager` provides
 * `WorkManagerSyncScheduler`, which runs a periodic worker that calls
 * [io.github.prathamesh2640.sync.core.engine.SyncEngine.triggerSync]. Host apps
 * depend on this interface so they are not coupled to WorkManager directly.
 *
 * Implementations must be safe to call from the main thread — scheduling is a
 * cheap, non-blocking hand-off to the platform scheduler.
 */
public interface SyncScheduler {

    /**
     * Register (or re-register) a periodic background sync.
     *
     * Idempotent: calling it again replaces any existing schedule rather than
     * stacking a second one. The cadence and constraints (network, backoff) are
     * fixed by the implementation.
     */
    public fun schedulePeriodicSync()

    /** Cancel any periodic sync previously scheduled by [schedulePeriodicSync]. */
    public fun cancelSync()
}
