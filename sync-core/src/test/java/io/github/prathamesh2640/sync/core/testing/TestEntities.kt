package io.github.prathamesh2640.sync.core.testing

import io.github.prathamesh2640.sync.core.model.SyncableEntity

/**
 * A minimal realistic [SyncableEntity] used across the sync-core contract tests.
 *
 * Mirrors the `Note` example documented on [SyncableEntity], and proves the
 * public generics (`ConflictResolver<T : SyncableEntity>`,
 * `SyncNetworkAdapter<T : SyncableEntity>`) accept a concrete host-app type.
 * Sync lifecycle (state, tombstone flag) is tracked separately via
 * `SyncMetadata`, not as fields here — see ADL-022.
 */
internal data class Note(
    override val id: String,
    val title: String,
    val body: String = "",
    override val lastModified: Long,
) : SyncableEntity
