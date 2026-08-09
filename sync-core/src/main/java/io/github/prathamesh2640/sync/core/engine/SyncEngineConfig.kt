package io.github.prathamesh2640.sync.core.engine

/**
 * Immutable configuration for a [SyncEngine].
 *
 * Instances are created through the [SyncEngineConfig] DSL (Kotlin) or the
 * [Builder] (Java) — the primary constructor is `internal`, so the set of
 * options can grow in future versions without breaking call sites that only use
 * the builder. Every option has a sensible default, so `SyncEngineConfig {}`
 * yields a fully valid configuration.
 *
 * ```kotlin
 * val config = SyncEngineConfig {
 *     batchSize = 100
 *     maxRetries = 5
 *     logLevel = LogLevel.DEBUG
 * }
 * ```
 *
 * ```java
 * // Java
 * SyncEngineConfig config = new SyncEngineConfig.Builder()
 *     .setBatchSize(100)
 *     .setMaxRetries(5)
 *     .build();
 * ```
 *
 * @property batchSize how many pending entities one sync run drains from the
 *   queue. Note this is **not** a per-request batch: each entity is pushed as its
 *   own [io.github.prathamesh2640.sync.core.adapter.SyncNetworkAdapter.push] call
 *   so a single item's failure is attributed to that item and never aborts its
 *   siblings. `batchSize` therefore caps the work per run, not the size of a
 *   request. Must be `> 0` and at most [MAX_BATCH_SIZE].
 * @property maxRetries how many times a [io.github.prathamesh2640.sync.core.model.SyncState.FAILED]
 *   entity is retried — on the next [io.github.prathamesh2640.sync.core.engine.SyncEngine.triggerSync]
 *   call, no engine-internal delay — before it is left failed. The cadence between runs (and any
 *   backoff) is entirely the host [io.github.prathamesh2640.sync.core.scheduler.SyncScheduler]'s concern.
 *   Must be `>= 0`.
 * @property tombstoneRetentionDays how long a confirmed-deleted (tombstoned)
 *   record is kept locally before it is hard-deleted. Must be `>= 0`.
 * @property maxConcurrentPushes upper bound on simultaneous in-flight pushes
 *   within one batch (SEC-11 hardening). Must be `> 0`.
 * @property logLevel diagnostic logging verbosity. See [LogLevel].
 */
public class SyncEngineConfig internal constructor(
    public val batchSize: Int,
    public val maxRetries: Int,
    public val tombstoneRetentionDays: Int,
    public val maxConcurrentPushes: Int,
    public val logLevel: LogLevel,
) {

    /**
     * Mutable builder for [SyncEngineConfig]. Each property is pre-filled with
     * its default; set only what you want to change. Reused by the Kotlin DSL.
     */
    public class Builder {
        /** @see SyncEngineConfig.batchSize */
        public var batchSize: Int = DEFAULT_BATCH_SIZE

        /** @see SyncEngineConfig.maxRetries */
        public var maxRetries: Int = DEFAULT_MAX_RETRIES

        /** @see SyncEngineConfig.tombstoneRetentionDays */
        public var tombstoneRetentionDays: Int = DEFAULT_TOMBSTONE_RETENTION_DAYS

        /** @see SyncEngineConfig.logLevel */
        public var logLevel: LogLevel = LogLevel.NONE

        /** @see SyncEngineConfig.maxConcurrentPushes */
        public var maxConcurrentPushes: Int = DEFAULT_MAX_CONCURRENT_PUSHES

        /**
         * Validate the current values and build an immutable [SyncEngineConfig].
         *
         * @throws IllegalArgumentException if any value is out of range. This is
         *   a setup-time programmer error, surfaced immediately rather than
         *   allowed to misconfigure a running engine.
         */
        public fun build(): SyncEngineConfig {
            require(batchSize > 0) { "batchSize must be > 0, was $batchSize" }
            require(batchSize <= MAX_BATCH_SIZE) { "batchSize must be <= $MAX_BATCH_SIZE, was $batchSize" }
            require(maxRetries >= 0) { "maxRetries must be >= 0, was $maxRetries" }
            require(tombstoneRetentionDays >= 0) {
                "tombstoneRetentionDays must be >= 0, was $tombstoneRetentionDays"
            }
            require(maxConcurrentPushes > 0) {
                "maxConcurrentPushes must be > 0, was $maxConcurrentPushes"
            }
            return SyncEngineConfig(
                batchSize = batchSize,
                maxRetries = maxRetries,
                tombstoneRetentionDays = tombstoneRetentionDays,
                maxConcurrentPushes = maxConcurrentPushes,
                logLevel = logLevel,
            )
        }
    }

    public companion object {
        /** Default [batchSize]. */
        public const val DEFAULT_BATCH_SIZE: Int = 50

        /**
         * Upper bound on [batchSize] — caps the total number of requests one run
         * can issue. (How many of those are in flight at once is capped separately,
         * and much lower, inside the engine.)
         */
        public const val MAX_BATCH_SIZE: Int = 1000

        /** Default [maxRetries]. */
        public const val DEFAULT_MAX_RETRIES: Int = 3

        /** Default [tombstoneRetentionDays]. */
        public const val DEFAULT_TOMBSTONE_RETENTION_DAYS: Int = 30

        /** Default [maxConcurrentPushes]. */
        public const val DEFAULT_MAX_CONCURRENT_PUSHES: Int = 20
    }
}

/**
 * DSL entry point for building a [SyncEngineConfig] in Kotlin.
 *
 * ```kotlin
 * val config = SyncEngineConfig { batchSize = 100 }
 * ```
 *
 * @param block configures a [SyncEngineConfig.Builder]; unset options keep their defaults.
 * @return a validated, immutable [SyncEngineConfig].
 */
public fun SyncEngineConfig(block: SyncEngineConfig.Builder.() -> Unit): SyncEngineConfig =
    SyncEngineConfig.Builder().apply(block).build()
