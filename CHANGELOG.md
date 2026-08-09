# Changelog

All notable changes to **SyncEngine** are documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Pre-0.1.0 development. The first published artefact will be `0.1.0`.

### Added
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

### Fixed
- `RoomSyncAdapter`'s raw-SQL writes (`markSyncState`, `hardDelete`, `purgeExpiredTombstones`) now run
  inside a Room transaction, so Room refreshes its `InvalidationTracker` on commit. Previously a host
  observing its own table with a Room `Flow` was never notified of engine writes and its UI went stale.
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
- `SyncDatabase` — an abstract `RoomDatabase` subclass that added no schema and no behaviour. Host
  databases extend `RoomDatabase` directly; nothing about `RoomSyncAdapter` changes.
- Dead per-module `buildTypes { release { … } }` blocks and the empty `proguard-rules.pro` files they
  referenced. `isMinifyEnabled = false` is already the default for library modules, so the whole block
  was a no-op; `consumer-rules.pro` (the file that actually ships in the AAR) is untouched.
- Unused `androidx-appcompat` version-catalog entry.
- `LocalSyncStore.getByState` — the engine never called it; `RoomSyncAdapter`'s own `getPending()` now
  uses a private query instead of routing through the public interface method.

### Changed
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

[Unreleased]: https://github.com/Prathamesh2640/Sync-Engine-Library/commits/main
