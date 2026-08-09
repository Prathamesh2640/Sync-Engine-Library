package io.github.prathamesh2640.sync.workmanager

import androidx.work.BackoffPolicy
import androidx.work.NetworkType

/**
 * Network condition [WorkManagerSyncScheduler] requires before running a sync.
 *
 * Mirrors a subset of `androidx.work.NetworkType` — kept as a library-owned enum
 * so no `androidx.work` type appears in the public API (module-guide).
 */
public enum class SyncNetworkRequirement {
    /** Run regardless of network state. */
    ANY,

    /** Any usable network connection. */
    CONNECTED,

    /** A connection not marked metered (e.g. Wi-Fi). */
    UNMETERED,

    /** A connection not marked as roaming. */
    NOT_ROAMING,
}

/**
 * Retry backoff strategy [WorkManagerSyncScheduler] applies after a failed run.
 *
 * Mirrors `androidx.work.BackoffPolicy` — kept as a library-owned enum so no
 * `androidx.work` type appears in the public API (module-guide).
 */
public enum class SyncBackoffPolicy {
    /** Delay grows linearly with each retry. */
    LINEAR,

    /** Delay doubles with each retry. */
    EXPONENTIAL,
}

internal fun SyncNetworkRequirement.toWorkManagerType(): NetworkType = when (this) {
    SyncNetworkRequirement.ANY -> NetworkType.NOT_REQUIRED
    SyncNetworkRequirement.CONNECTED -> NetworkType.CONNECTED
    SyncNetworkRequirement.UNMETERED -> NetworkType.UNMETERED
    SyncNetworkRequirement.NOT_ROAMING -> NetworkType.NOT_ROAMING
}

internal fun SyncBackoffPolicy.toWorkManagerPolicy(): BackoffPolicy = when (this) {
    SyncBackoffPolicy.LINEAR -> BackoffPolicy.LINEAR
    SyncBackoffPolicy.EXPONENTIAL -> BackoffPolicy.EXPONENTIAL
}
