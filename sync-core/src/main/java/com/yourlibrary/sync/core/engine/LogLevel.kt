package com.yourlibrary.sync.core.engine

/**
 * Verbosity of the engine's internal diagnostic logging.
 *
 * Levels are ordered from least to most verbose, matching their [ordinal].
 * A message at a given level is emitted when that level is less than or equal to
 * the configured level — so [DEBUG] shows everything and [NONE] silences the
 * engine entirely. Default is [NONE]: a library should be silent unless the host
 * app opts in.
 *
 * Configure via [SyncEngineConfig.logLevel].
 */
public enum class LogLevel {

    /** No logging. The default for release builds. */
    NONE,

    /** Only unrecoverable errors. */
    ERROR,

    /** Errors and recoverable warnings (e.g. a retried failure). */
    WARN,

    /** High-level lifecycle events (sync started, batch completed). */
    INFO,

    /** Everything, including per-entity state transitions. Debug builds only. */
    DEBUG,
}
