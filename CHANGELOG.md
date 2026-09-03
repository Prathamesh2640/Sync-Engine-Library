# Changelog

All notable changes to **SyncEngine** are documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- JitPack builds (README's alternative install path) no longer fail. Two independent breaks:
  JitPack's default image runs JDK 11 but this project's Gradle 9.4.1 requires 17+ (fixed via
  a new `jitpack.yml` pinning `openjdk17`), and every module's `signAllPublications()` ran
  unconditionally, so `publishToMavenLocal` (what JitPack invokes) failed looking for a `.asc`
  signature file with no signing key present in that environment — now gated behind
  `project.hasProperty("signingInMemoryKey")`, so it still signs for the real Central publish
  (CI supplies the key) but no longer requires one locally/on JitPack.

## [0.2.0] - 2026-09-03

### Fixed
- A tombstone is no longer pushed to `SyncNetworkAdapter.push` as a content upsert before
  its deletion is confirmed. Previously `RoomSyncAdapter.getPending()` had no `isDeleted`
  filter, so a deleted entity's full content was sent to `push`, which could resurrect the
  row on the server for any other device pulling in that window before the tombstone was
  confirmed. **Behavior change for hosts:** `push` now never receives a deleted entity —
  deletions are confirmed exclusively through `delete(ids)`. A tombstone whose confirmation
  fails is now marked `FAILED` (previously left untouched), so `purgeExpiredTombstones` can
  eventually reclaim a deletion that never existed server-side (created and deleted entirely
  offline).
- `RoomSyncAdapter.getMetadataByIds`/`counts()` no longer throw `IllegalArgumentException`
  on a metadata row whose `syncState` string doesn't match a known `SyncState` (a downgrade,
  a hand-edited database) — the row is skipped instead, honoring `LocalSyncStore`'s
  "must not throw" contract.
- `SyncWorker` no longer retries forever on an unresolvable conflict. A `SyncResult.Failure`/
  `PartialFailure` whose error(s) are entirely `SyncError.ConflictUnresolvable` now maps to
  `Result.success()` — nothing WorkManager's retry can fix; the conflict remains visible via
  `SyncEngine.stats`/`syncState` until the host resolves it.
- The pull watermark can no longer go negative when an unresolvable-conflict or skipped
  entity's `lastModified` is `0` (or less than the running watermark) — it now floors at `0`.
  Known remaining gap: an entity stuck at exactly `lastModified <= 0` sits at the boundary of
  `pull(since)`'s "strictly newer than `since`" contract and won't be re-delivered by a
  `since = 0` pull; real event timestamps are never `<= 0`, so this only affects contrived data.
- `SyncEngineImpl.confirmDeletions` no longer loads every tombstone into memory before
  capping at `batchSize` — it now calls the new `LocalSyncStore.getTombstones(limit)` (see
  Added below), the same bounded-query pattern `getPending(limit)` already used.

### Added
- `LocalSyncStore.markSyncedIfUnchanged(id, lastModified)` — a default method the engine now
  calls instead of `markSyncState(id, SYNCED)` after a successful push. Closes a data-loss
  window: if a host edits an entity while its push is in flight, the previous unconditional
  write-back could stamp `SYNCED` over that edit, silently losing it (it would never sync
  again). The default body is unconditional (same as before) for source compatibility;
  `RoomSyncAdapter` overrides it as one guarded `UPDATE ... WHERE lastModified = ?`.
- `RoomSyncAdapter.enqueue(entity)` override — wraps the upsert and metadata write in one
  transaction, closing a race where the pull phase could observe the row updated but its
  metadata still stale between the two previously-separate statements.
- `SyncEngineImpl.confirmDeletions` now caps at `SyncEngineConfig.batchSize` per run, same as
  the push phase — a large offline-deletion backlog no longer issues one unbounded `delete`
  request; remaining tombstones drain over subsequent runs.
- `LocalSyncStore.getWatermark()`/`setWatermark(value)` — an opt-in seam for persisting the
  pull watermark across process restarts (previously in-memory only, so every fresh process
  re-pulled everything from the start — safe, since `upsert` is idempotent, just not
  bandwidth-free). Default no-op/`0L`, so existing implementations are unaffected unless they
  opt in. `RoomSyncAdapter` gains an optional, trailing `watermarkTable` constructor param
  (default `null`): supplying it persists the watermark in a small host-created table;
  leaving it `null` requires no schema change at all.
- `LocalSyncStore.getTombstones(limit)` — a default method (delegates to `getTombstones()` +
  `take(limit)` for source compatibility) that bounds the tombstone read the same way
  `getPending(limit)` bounds the pending read. `RoomSyncAdapter` overrides it with a real
  `ORDER BY ... LIMIT`, oldest-first.

### Documentation
- `LocalSyncStore`'s "never throw" contract wording corrected: implementations must not
  throw for ordinary outcomes (empty results, absent rows), but a genuine storage/IO failure
  may propagate — the engine converts it to `SyncError.StorageError` at the `triggerSync()`
  boundary, same as it does for a misbehaving adapter. This matches `RoomSyncAdapter`'s
  actual (and correct) behavior; only the docs previously overclaimed.
- `SyncNetworkAdapter.push`'s KDoc no longer references the removed `SyncableEntity.isDeleted`
  (ADL-022) or claims `push` may carry tombstones.

## [0.1.1] - 2026-08-25

### Fixed
- `:sync-core` now ships an `AndroidManifest.xml` declaring `INTERNET` and
  `ACCESS_NETWORK_STATE`. No 0.1.0 library module declared either permission, so
  `triggerSync()` failed as `NetworkUnavailable` against a fully reachable backend on any
  host that hadn't added both permissions itself — contradicting README's "inherited via
  manifest merging" claim. Found during external integration validation
  (`validation/findings.md` FINDING-001).
- README's `RoomSyncAdapter` section now documents that the `notes_sync_meta` `Migration`
  must be paired with a `RoomDatabase.Callback.onCreate` running the same
  `CREATE TABLE IF NOT EXISTS` — Room skips `Migration`s on a fresh install, so following
  the previous guidance as written threw `SQLiteException` on first launch
  (`validation/findings.md` FINDING-002).

## [0.1.0] - 2026-08-22

First public release.

### Added
- `SyncEngine.stats: StateFlow<SyncStats>` — pending/failed/conflict counts, the last run's finish
  timestamp, and its first error, backed by a new `LocalSyncStore.counts(): SyncCounts`. Previously the
  engine's only observable signal was the single `syncState` enum, so every host hand-counted via its own
  DAO queries to build a dashboard; `SyncDashboardState` can now be built directly from
  `syncState`+`stats` with no host-side counting. Updated once at the end of every `triggerSync()` run
  (plus a best-effort initial count at construction so pre-existing pending/failed/conflict work isn't
  reported as zero before the first sync). Sample app's dashboard snapshot now derives from
  `engine.stats` instead of `NotesRepository` hand-counting via `dao.countOf(...)`.
- `LocalSyncStore.enqueue(entity)` — a default method combining `upsert(listOf(entity))` +
  `markSyncState(entity.id, SyncState.PENDING)`. Forgetting the `markSyncState` call after a local write
  previously left an entity silently un-enqueued (no error, nothing in `getPending()` — it just never
  synced); `enqueue` collapses the two-call pattern the docs already recommended so that mistake isn't
  possible to make by omission. Built only from existing abstract members, so every implementation gets
  it automatically.
- `LocalSyncStore.getByIds`/`getMetadataByIds` — batched counterparts to `getById`/`getMetadata`. The
  engine's pull phase now looks up a whole batch's local state in two queries instead of two per
  entity (an N+1 the `SyncMetadata` side-table redesign introduced).
- `:sync-core` — public API contracts: `SyncableEntity`, `SyncState`, `SyncEngine`, `SyncEngineConfig`
  (DSL), `SyncResult` / `SyncError` (sealed), `ConflictResolver`, `SyncNetworkAdapter`, `NetworkResult`
  (sealed), `LocalSyncStore`, `SyncScheduler`, `LogLevel`.
- `:sync-core` internals — `SyncStateMachine` (Mutex-guarded, guarded transitions), `SyncQueue`
  (thread-safe, coalesce-by-id), `SyncEngineImpl` (supervisorScope, single-flight, two-way sync with
  pull + conflict resolution + tombstone delete-confirmation).
- `:sync-storage-room` — `RoomSyncAdapter<T>` implementing `LocalSyncStore<T>`; adapter takes a plain
  host `@Dao` and a `rawQuery` function reference (no generic `@Dao` base class — Room/KSP restriction).
  Tombstone purge at the retention boundary. Robolectric JVM tests.
- `RoomSyncAdapter` constructor now accepts `idColumn`/`stateColumn`/`deletedColumn`/`modifiedColumn`
  (each defaulting to the `SyncableEntity` default names), for hosts whose entity renames a sync column
  with `@ColumnInfo`. Each is validated as a SQL identifier (SEC-05), same as `tableName`.
- `:sync-network-retrofit` — `RetrofitSyncAdapter<T>` implementing `SyncNetworkAdapter<T>`. Host passes
  suspend call refs returning `retrofit2.Response<...>`. All HTTP outcomes mapped to `NetworkResult`
  (never throws). MockWebServer JVM tests.
- `:sync-workmanager` — `WorkManagerSyncScheduler` implementing `SyncScheduler`; internal `SyncWorker`
  (`CoroutineWorker`) with exponential backoff + network constraint. WorkManager types hidden from the
  public API. `WorkManagerTestInitHelper` JVM tests.
- `:sync-ui-dashboard` — Compose-based debug dashboard (`SyncDashboardActivity`, `SyncDashboardRoute`,
  `SyncDashboardState`, `SyncDashboard` holder). Live state via `collectAsStateWithLifecycle`. Material3.
- Sample-app — `Note` entity end-to-end wiring with Room, an in-memory fake backend, three conflict
  resolvers (LastWriteWins / ServerWins / Merge), Compose UI, debug-only dashboard launcher. `Note` is
  id-only (ADL-022); sync state comes from a `notes_sync_meta` side table, added via an explicit
  `Migration(1, 2)` — a real example of adopting the new `SyncableEntity` contract on an existing schema.
- `consumer-rules.pro` on every publishable module — R8 keeps the full public API on release builds.
- Turbine-based `SyncEngineImplFlowTest` covering `StateFlow` emission ordering.

### Documentation
- `README.md` trimmed from 673 to ~320 lines. Content was already accurate post-refactor, so this is
  pure cutting: consolidated "How it works" (8 subsections) into one "The seams" section with a
  paragraph + minimal example each instead of full worked examples per topic; merged the 5-step Quick
  Start into a single ~35-line end-to-end example; dropped the Install section's Composite-build/Local-
  Maven options in favor of a pointer to `SETUP.md § 9` (already documents the latter in full); removed
  the "Implementation status" table (redundant with the rest of the doc describing the same features as
  working, and carried a stale "134/134 tests" figure).
- `SyncEngineConfig.maxRetries`, `SyncState.FAILED`, and `NetworkResult.NetworkError`'s KDoc all claimed
  the engine retries with exponential backoff. It doesn't — `:sync-core` has no delay/backoff primitive
  at all; a `FAILED` entity is retried on the very next `triggerSync()` call, and any cadence between
  runs is entirely the host `SyncScheduler`'s concern (`:sync-workmanager`'s own configurable backoff).
  Reworded to describe the actual behavior instead of a promise the engine doesn't keep.

### Fixed
- **`:sync-ui-dashboard` now exposes `:sync-core` on consumers' compile classpath.** It declared
  `implementation(project(":sync-core"))` on the stated grounds that no sync-core type appeared in its
  public API — but `SyncDashboardState` is public and its `syncState` property is typed
  `io.github.prathamesh2640.sync.core.model.SyncState`. The published Gradle metadata therefore listed
  sync-core under `runtimeElements` only (POM scope `runtime`), so a consumer taking this module without
  depending on sync-core itself could not compile against `SyncDashboardState` — the case that bites is
  a host isolating debug tooling in its own module, which is a normal way to wire a debug-only
  dashboard. Now `api(...)`. `androidx.compose.ui` moves to `api(...)` for the same reason:
  `androidx.compose.ui.Modifier` is a parameter of the public `SyncDashboardRoute`. Runtime behaviour is
  unchanged — both artifacts already resolved at runtime.
- **`RoomSyncAdapter` no longer exceeds SQLite's bind-variable limit.** `getByIds`, `getMetadataByIds`,
  `hardDelete` and `purgeExpiredTombstones` built one `?` placeholder per id in a single `IN (...)`
  clause. Those id lists are sized by the server's pull response and by the local tombstone count, so
  nothing in the library bounded them — and SQLite's `SQLITE_MAX_VARIABLE_NUMBER` is 999 on the SQLite
  bundled with Android below API 33 (this module's `minSdk` is 24). A pull returning more than ~999
  changed entities threw `SQLiteException: too many SQL variables`, which the engine reported as
  `SyncResult.Failure(StorageError)`; because the failure happened before the pull watermark advanced,
  every subsequent run re-requested the same oversized batch and failed identically — sync stayed
  wedged for the life of the install. Most likely to bite on a first sync, and on the full re-pull that
  follows every process restart. All four now split into statements of at most 900 bind arguments; the
  chunked delete still runs inside one transaction, so it remains atomic.
- **A throwing `LocalSyncStore` no longer crashes the host app at construction.** The best-effort
  initial `counts()` call ran in a root coroutine on the engine's scope, which has no
  `CoroutineExceptionHandler` — an escaping `SQLiteException` (missing metadata table, corrupt or
  locked database) therefore reached Android's default uncaught-exception handler and killed the
  process, typically during `Application.onCreate`. It is now caught: stats stay at zero and the next
  `triggerSync()` surfaces the real problem as a `SyncResult`.
- **`syncState` no longer sticks at `SYNCING` after a collaborator throws.** `triggerSync()` already
  converted an unexpected throwable into `SyncResult.Failure`, but left the state machine parked in
  `SYNCING`, so any UI bound to `syncState` (including `SyncDashboard`) spun until some later run
  happened to complete. The engine now settles the machine to `FAILED` on that path.
- `confirmDeletions` looked up each tombstone's metadata with a separate `getMetadata` call in a
  `filter` — an N+1 identical in shape to the one already fixed in the pull phase. It now uses one
  `getMetadataByIds` batch call instead.
- The pull phase's watermark no longer advances using a remote entity's raw `lastModified` unclamped.
  An entity with an implausibly far-future timestamp (attacker-tampered or clock-skewed server — the
  same threat `ConflictResolver`'s KDoc already warns implementors about) previously pinned `pullSince`
  past real time, permanently starving every later pull. The watermark is now capped at the engine's
  injected clock; the entity itself is still applied normally.
- The pull phase's watermark (`since` for the next pull) no longer advances past an entity that hit an
  unresolvable conflict or was skipped (a pending local deletion). Previously it advanced unconditionally
  for every remote entity regardless of outcome, so a stuck conflict — or anything at/after its
  timestamp — could be silently and permanently excluded from future pulls.
- `RoomSyncAdapter`'s raw-SQL writes (`markSyncState`, `markDeleted`, `hardDelete`,
  `purgeExpiredTombstones`) now call `InvalidationTracker.refreshVersionsAsync()` after their
  transaction commits. A bare `withTransaction` around raw `execSQL` does not by itself guarantee Room
  notices the write — this was a real documentation/behavior gap (verified empirically with a Robolectric
  `Flow` test), not just a stale doc: a host observing either table with a Room `Flow` now reliably sees
  engine writes. The sample app's `NoteDao.activeNotes()` is now `Flow`-backed and `NotesRepository` no
  longer manually re-queries after every local edit or sync.
- `RoomSyncAdapter.purgeExpiredTombstones` now closes its compiled `SupportSQLiteStatement`; it
  previously leaked one native SQLite statement handle per sync run.
- `SyncEngineConfig.batchSize`, `SyncNetworkAdapter.push` and the README described `batchSize` as
  entities per network round trip. The engine pushes each entity as its own request (so one item's
  failure is attributed to that item); the docs now say so.
- `WorkManagerSyncScheduler` no longer calls `WorkManager.getInstance()` at construction time — the
  lookup is deferred to the first `schedulePeriodicSync()`/`cancelSync()` call. A host that disables
  WorkManager's `androidx.startup` auto-init (standard practice with a Hilt `WorkerFactory`) no longer
  gets an `IllegalStateException` from merely constructing the scheduler.
- `SyncEngineConfig.maxConcurrentPushes` — the cap on simultaneous in-flight pushes within a batch was
  a hardcoded private `20`; it's now a configurable builder/DSL option (default unchanged).
- `WorkManagerSyncScheduler` — network requirement and retry backoff, previously hardcoded to
  `NetworkType.CONNECTED`/`BackoffPolicy.EXPONENTIAL` at `WorkRequest.MIN_BACKOFF_MILLIS`, are now
  optional constructor parameters (`networkRequirement`/`backoffPolicy`/`backoffDelayMillis`, defaults
  unchanged) via two new library-owned enums, `SyncNetworkRequirement` and `SyncBackoffPolicy` — no
  `androidx.work` type is added to the public API.
- `WorkManagerSyncScheduler` gains an `engineKey` parameter (default `"default"`) so more than one
  `SyncEngine` can be scheduled in the same process. `SyncEngineRegistry` is now keyed instead of a
  single-slot holder, and the WorkManager unique-work name is scoped per key
  (`SyncWorker.uniqueWorkName(engineKey)`), so two schedulers with different keys no longer collide.
  `SyncWorker.UNIQUE_WORK_NAME` is replaced by `UNIQUE_WORK_NAME_PREFIX` + `uniqueWorkName(key)`.
- `SyncDashboard.install`/`clear` gain an optional `key` parameter (default `"default"`) so more than
  one engine's dashboard can be installed in the same process. `SyncDashboardActivity` reads a matching
  optional `EXTRA_ENGINE_KEY` intent extra to pick which one to show.

### Removed
- **Breaking:** `LocalSyncStore.getById`/`getMetadata` — the single-item lookups had no caller in the
  engine; every read path already goes through the batched `getByIds`/`getMetadataByIds`. Same pattern
  as `getByState`'s removal below: an unused method on a published interface is a maintenance cost on
  every future `LocalSyncStore` implementer with no engine behavior behind it. `RoomSyncAdapter` drops
  the matching overrides.
- `SyncDatabase` — an abstract `RoomDatabase` subclass that added no schema and no behaviour. Host
  databases extend `RoomDatabase` directly; nothing about `RoomSyncAdapter` changes.
- Dead per-module `buildTypes { release { … } }` blocks and the empty `proguard-rules.pro` files they
  referenced. `isMinifyEnabled = false` is already the default for library modules, so the whole block
  was a no-op; `consumer-rules.pro` (the file that actually ships in the AAR) is untouched.
- Unused `androidx-appcompat` version-catalog entry.
- `LocalSyncStore.getByState` — the engine never called it; `RoomSyncAdapter`'s own `getPending()` now
  uses a private query instead of routing through the public interface method.

### Changed
- **Breaking:** `LocalSyncStore.getPending()` takes a `limit` — `getPending(limit: Int)`. It previously
  returned *every* pending row; the engine then drained only `batchSize` of them (50 by default) and
  held the remainder in its in-memory queue. A host that had been offline long enough to accumulate a
  large backlog therefore loaded the entire backlog into memory on every run just to send a batch of
  it — worst exactly in the scenario an offline-first library exists for. The engine now passes
  `SyncEngineConfig.batchSize`, so a run reads only what it can actually push. `RoomSyncAdapter`
  implements it as `ORDER BY <modifiedColumn> ASC LIMIT ?`; the ordering is new too, and is what makes
  the limit select the oldest slice of the backlog rather than an arbitrary one. Values `<= 0` return
  nothing, matching the engine queue's own drain semantics (and keeping a negative limit from becoming
  SQLite's unbounded `LIMIT -1`). Custom `LocalSyncStore` implementations must add the parameter and
  should honour it.
- The release version is declared in exactly one place — the root `build.gradle.kts`'s
  `allprojects { version }`. All 5 publishable modules derive their `coordinates(...)` version from it,
  instead of each restating the literal.
- Every publishable module now compiles under Kotlin's `explicitApi()` strict mode, so a declaration
  can never become part of the public API by omission. `SyncState` and `SyncableEntity` (which relied
  on the implicit default) now state `public` explicitly — no visibility actually changed.
- **Breaking:** `SyncableEntity.syncState`/`isDeleted` are removed — the interface now declares only
  `id`/`lastModified`. Sync lifecycle moves to a new library-owned `SyncMetadata` record (`syncState`,
  `isDeleted`), keyed by id, tracked through two new `LocalSyncStore` members: `getMetadata(id)` and
  `markDeleted(id)`. `markSyncState(id, state)` is now an upsert — it creates the metadata row if one
  doesn't exist — and is also the call a host makes after every local insert/update to enqueue that
  entity (there is no more column default doing so implicitly). See ADL-022 in `memory.md`. A host
  adopting SyncEngine on an existing table no longer needs to add sync-state columns to it, at the cost
  of an explicit `markSyncState`/`markDeleted` call at every local write site.
- **Breaking:** `RoomSyncAdapter` requires a new `metadataTable` constructor parameter (the sync-metadata
  side table's name) and drops `stateColumn`/`deletedColumn` (no longer meaningful — those columns don't
  live on the host table anymore). `idColumn`/`modifiedColumn` are unchanged. `getPending`/`getTombstones`
  now join the host table against `metadataTable`; `markSyncState` is an upsert; `hardDelete`/
  `purgeExpiredTombstones` remove rows from both tables. See README § Local storage for the metadata
  table's required shape and a migration example.

### Security
- `SyncEngineImpl` now enforces `SyncEngineConfig.maxRetries`: a per-entity push that fails more than
  `maxRetries` times in a row is left `FAILED` instead of being retried on every run forever.
- `SyncEngineImpl` caps concurrent in-flight pushes within a batch (20) independent of `batchSize`, and
  `SyncEngineConfig.batchSize` now has an upper bound (`MAX_BATCH_SIZE` = 1000) — an unbounded batch
  could previously open an unbounded number of simultaneous requests against the host's backend in one run.
- `SyncEngineConfig.logLevel` is now actually wired to diagnostic logging (state transitions, error
  codes/types, sync-job UUIDs only — never entity content, per SEC-06); previously the option was
  validated but silently did nothing.
- `ConflictResolver`'s KDoc documents the SEC-09 trust boundary (`remote` is network-sourced, untrusted
  input). The sample app's `LAST_WRITE_WINS` resolver now rejects an implausibly future-dated `remote`
  timestamp instead of trusting it outright, closing a clock-skew conflict-resolution bypass.
- Sample app's `backup_rules.xml` / `data_extraction_rules.xml` now exclude the Room database from
  Android backup/device-transfer, so a restored backup cannot resurrect tombstoned rows the user already
  deleted (SEC-10 / GDPR erasure).

### Documentation
- Security/conduct reports, the Apache-2.0 copyright line, and every module's POM `<developer>` block
  now name the maintainer (Prathamesh Sharma) and route to his address; `SECURITY.md` and
  `CODE_OF_CONDUCT.md` previously listed an unrelated fallback email.
- Corrected the stated Kotlin requirement: AGP 9.2.1's built-in Kotlin is 2.2.10 and consumers receive
  `kotlin-stdlib` 2.2.10 transitively, not the 2.1.0 the README and version catalog implied. Removed the
  dangling `kotlin` catalog entry, which no module referenced.
- Corrected the test counts (134), the SDK 36 platform name (Android 16), the AAR output list (was
  missing `:sync-workmanager`), the instrumented-test section (there are none — everything runs on the
  JVM), and replaced a Logcat filter table that documented four log tags the library never emitted.
- `RELEASE_CHECKLIST.md` no longer links `FEATURES.md` / `memory.md`, which are gitignored and therefore
  absent for anyone who clones the repository.
- Apache-2.0 `LICENSE` + `NOTICE` at repository root.
- README covering install, module structure, quick start, Java interop, ProGuard/R8, integration
  testing, FAQ, and versioning.
- SETUP.md with the developer environment requirements.

[Unreleased]: https://github.com/Prathamesh2640/Sync-Engine-Library/compare/v0.1.0...main
[0.1.0]: https://github.com/Prathamesh2640/Sync-Engine-Library/releases/tag/v0.1.0
