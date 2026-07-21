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
- `:sync-network-retrofit` — `RetrofitSyncAdapter<T>` implementing `SyncNetworkAdapter<T>`. Host passes
  suspend call refs returning `retrofit2.Response<...>`. All HTTP outcomes mapped to `NetworkResult`
  (never throws). MockWebServer JVM tests.
- `:sync-workmanager` — `WorkManagerSyncScheduler` implementing `SyncScheduler`; internal `SyncWorker`
  (`CoroutineWorker`) with exponential backoff + network constraint. WorkManager types hidden from the
  public API. `WorkManagerTestInitHelper` JVM tests.
- `:sync-ui-dashboard` — Compose-based debug dashboard (`SyncDashboardActivity`, `SyncDashboardRoute`,
  `SyncDashboardState`, `SyncDashboard` holder). Live state via `collectAsStateWithLifecycle`. Material3.
- Sample-app — `Note` entity end-to-end wiring with Room, an in-memory fake backend, three conflict
  resolvers (LastWriteWins / ServerWins / Merge), Compose UI, debug-only dashboard launcher.
- `consumer-rules.pro` on every publishable module — R8 keeps the full public API on release builds.
- Turbine-based `SyncEngineImplFlowTest` covering `StateFlow` emission ordering.

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
- Apache-2.0 `LICENSE` + `NOTICE` at repository root.
- README covering install, module structure, quick start, Java interop, ProGuard/R8, integration
  testing, FAQ, and versioning.
- SETUP.md with the developer environment requirements.

[Unreleased]: https://github.com/Prathamesh2640/Sync-Engine-Library/commits/main
