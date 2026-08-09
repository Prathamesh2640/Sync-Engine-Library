package io.github.prathamesh2640.sync.core.model

/**
 * The engine's sync lifecycle for one [SyncableEntity], keyed by its [SyncableEntity.id].
 *
 * Library-owned: hosts do not construct this directly in normal use — a
 * [io.github.prathamesh2640.sync.core.store.LocalSyncStore] implementation reads
 * and writes it (e.g. `RoomSyncAdapter` backs it with a small side table). See
 * ADL-022 in `memory.md` for why sync state lives here instead of as fields on
 * the host's own entity.
 *
 * @property syncState this entity's current position in the sync lifecycle.
 * @property isDeleted `true` if this entity has been soft-deleted locally and is
 *   awaiting tombstone confirmation from the server. Entities with `isDeleted =
 *   true` must be excluded from the host's own business-logic queries but must
 *   remain in storage until the server confirms deletion.
 */
public data class SyncMetadata(
    public val syncState: SyncState,
    public val isDeleted: Boolean = false,
)
