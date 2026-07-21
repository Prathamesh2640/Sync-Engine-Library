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

### Documentation
- Apache-2.0 `LICENSE` + `NOTICE` at repository root.
- README covering install, module structure, quick start, Java interop, ProGuard/R8, integration
  testing, FAQ, and versioning.
- SETUP.md with the developer environment requirements.

[Unreleased]: https://github.com/Prathamesh2640/Sync-Engine-Library/commits/main
