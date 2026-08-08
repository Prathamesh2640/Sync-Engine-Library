# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

SyncEngine — an offline-first data synchronisation library for Android (Kotlin, Coroutines/Flow, Room,
WorkManager, Retrofit). It is a set of publishable Gradle library modules, not an app. Read
`README.md` first — it is the canonical spec of the public API and is kept current; when in doubt about
intended behavior, trust it over guessing from code.

## Commands

```bash
./gradlew test                            # all unit tests, all modules (JVM only — what CI runs)
./gradlew :sync-core:test                 # single module
./gradlew :sync-core:testDebugUnitTest    # equivalent, explicit variant
./gradlew assembleDebug                   # debug build of libraries + sample-app
./gradlew assembleRelease                 # release AARs for all 5 library modules (also builds :sample-app's release APK)
./gradlew :sample-app:assembleRelease     # R8/consumer-rules.pro check (also run in CI)
./gradlew publishToMavenLocal             # install to local Maven cache for cross-repo testing
./gradlew lint
```

Run a single test class from the CLI with `--tests`, e.g.
`./gradlew :sync-core:test --tests "*.SyncStateTest"`.

There are **no instrumented tests** (`androidTest`) — by design. `:sync-storage-room`'s Room adapter and
`:sync-workmanager`'s scheduler are tested via **Robolectric** on the JVM, so the full suite (134 tests)
runs with no emulator, in `./gradlew test`. Don't add `androidTest` sources unless a future change
genuinely requires a real device/emulator.

CI (`.github/workflows/ci.yml`) runs, in order: `test` → `assembleDebug` →
`:sample-app:assembleRelease`. Match that locally before considering a change done.

## Module architecture

Six Gradle modules, strict one-way dependency graph — no circular deps, no sibling-module imports:

```
sample-app  ──depends on──>  all 5 library modules

sync-ui-dashboard      → sync-core
sync-workmanager       → sync-core
sync-network-retrofit  → sync-core
sync-storage-room      → sync-core
sync-core              → Kotlin stdlib + Coroutines only (no android.* imports — see below)
```

`:sync-core` importing no `android.*` classes is load-bearing, not incidental: it's what lets the
engine, state machine, and queue be unit-tested on plain JVM with no Robolectric/emulator. That's true
despite the module itself still applying the `com.android.library` plugin and building a real AAR
(ADL-002 in `memory.md`) — it isn't a plain Kotlin/JVM module. Never add an `android.*` import to
`:sync-core`.

Package root for all modules: `io.github.prathamesh2640.sync.*` (e.g.
`io.github.prathamesh2640.sync.core.engine`, `...core.internal`, `...core.model`). Within `:sync-core`,
`internal/` holds `SyncEngineImpl` and the state machine/queue — the rest (`engine/`, `model/`,
`adapter/`, `store/`, `result/`, `scheduler/`) is the public surface consumed by the other modules.

### The seams (what plugs into what)

- **`SyncableEntity`** (`:sync-core` model) — the contract every synced data class implements: `id`
  (client-generated UUID v4, immutable, the idempotency key), `lastModified`, `syncState`, `isDeleted`
  (soft-delete tombstone).
- **`SyncNetworkAdapter<T>`** — host-implemented, 3 suspend methods (`push`/`pull`/`delete`), never
  throws, returns `NetworkResult` (`Success`/`HttpError`/`NetworkError`/`UnknownError`).
  `:sync-network-retrofit`'s `RetrofitSyncAdapter` is a ready-made implementation that wraps host call
  references returning `retrofit2.Response` — it does not own a `Retrofit`/`OkHttpClient` instance.
- **`LocalSyncStore<T>`** — the engine's persistence seam (`:sync-core`, framework-free). Optional; it
  defaults to `null`, in which case the engine still queues and pushes entities via its internal
  in-memory `SyncQueue`, but the pull, conflict-resolution, and tombstone-purge phases are all skipped
  entirely — there is nowhere durable to pull into. `:sync-storage-room`'s `RoomSyncAdapter` is the
  Room-backed implementation. There is **no library-owned queue table** — the host's entity table with
  its `syncState` column *is* the queue (single-source storage, see ADL-011 in `memory.md`).
  `RoomSyncAdapter` talks to the host's DAO only through a `rawQuery` (`@RawQuery`) function reference
  and an `@Upsert` function reference passed in by the caller — never a generic base DAO. That's not
  because Room+KSP can't codegen generic DAOs (a plain non-generic `@Dao` failed identically; the real
  cause was Room 2.6.1's KSP2 backend, fixed by bumping to Room 2.7.1); the base DAO was dropped because
  a function reference is simpler for the host and needs zero library-side Room codegen (see ADL-013).
- **`ConflictResolver<T>`** — a `fun` interface (SAM), so lambda-friendly; must be pure (no I/O, no
  mutating its args). Feeding it a network-sourced `remote.lastModified` without a plausibility/clock-skew
  check is a known trap — see the guarded Last-Write-Wins example in README § Conflict resolution before
  writing or reviewing a resolver.
- **`SyncScheduler`** — framework-free interface; `:sync-workmanager`'s `WorkManagerSyncScheduler` is the
  only implementation, keeping WorkManager entirely out of host code and out of `:sync-core`.

### Engine contract worth knowing before touching `SyncEngineImpl`

- State machine: `PENDING → SYNCING → SYNCED`, with `FAILED` (re-queued) and `CONFLICT` (resolved via
  `ConflictResolver` back to `PENDING`) branches. Host code never writes `syncState` directly.
- `triggerSync()` is **single-flight** — a concurrent call while a run is in progress is a no-op
  returning `Success(0, 0)`, not queued or rejected.
- Batch pushes are per-item (`push([one])` per entity), so one item's failure produces
  `SyncResult.PartialFailure` rather than aborting the batch.
- The engine never throws across its public API for ordinary failures — outcomes are `SyncResult`/
  `SyncError` sealed values. The one deliberate exception: `triggerSync()` rethrows
  `CancellationException` when the *caller's* own coroutine is cancelled (structured concurrency),
  rather than converting it to a `SyncResult`.
- `SyncEngine` is `Closeable`; `close()` cancels its internal coroutine scope. No `GlobalScope`, no
  `runBlocking`, anywhere in library code (see `CONTRIBUTING.md` § Project philosophy).
- Logging is opt-in only (`SyncEngineConfig.logLevel`, default `NONE`) and content-free by design — job
  ids, state names, error codes/types, entity **ids** only, never entity field values, response bodies,
  or auth material. Preserve that invariant in any new log line.

## Toolchain constraints that aren't obvious from the build files

These are hard-won (see `memory.md` ADL entries and `SETUP.md` § 13 Troubleshooting) — don't "fix" them
back to the more obvious-looking alternative:

- **AGP 9's built-in Kotlin only** — never re-add the standalone `org.jetbrains.kotlin.android` plugin
  (ADL-005). The Compose modules apply just the Compose compiler plugin
  (`org.jetbrains.kotlin.plugin.compose`) + `buildFeatures { compose = true }`.
- **Room must stay ≥ 2.7.1**, using the default KSP2 backend. Do not set `ksp.useKSP2=false` — that
  combination is broken on this AGP 9 + Kotlin 2.2.10 toolchain (AGP 9.2.1's actual built-in Kotlin
  compiler version — the `ksp = "2.1.0-1.0.29"` catalog entry is KSP's own version-string convention,
  not the project's Kotlin version; see the comment above it in `gradle/libs.versions.toml`).
- **`android.disallowKotlinSourceSets=false`** in `gradle.properties` is required for KSP to register
  generated sources under AGP 9's built-in Kotlin — don't remove it.
- Room codegen is wired as `ksp(...)` / `kspTest(...)`, never `annotationProcessor`.
- No Binary Compatibility Validator plugin — added once, reverted, unsupported on this toolchain
  (ADL-020).
- All dependency versions live in the version catalog (`gradle/libs.versions.toml`) — no inline version
  strings in module `build.gradle.kts` files.

## Conventions

- **`internal` by default.** Every public symbol is a permanent API commitment (semver: new public
  symbol = minor, changed signature = major). Ask whether the host app actually needs a given symbol to
  be public before adding it.
- **Sealed results, not exceptions**, across every public API — extend `SyncResult`/`SyncError`/
  `NetworkResult` rather than throwing.
- Coroutines + Flow only — no RxJava, no callbacks, `StateFlow` not `LiveData`.
- A public API addition needs: KDoc, a `consumer-rules.pro` keep in the owning module, a `CHANGELOG.md`
  entry under `[Unreleased]`, and a README update if it changes documented usage.
- Commit style: one-line Conventional Commits, `<type>(<scope>): <imperative summary>` — see
  `CONTRIBUTING.md` § Commit messages for the type/scope vocabulary used in this repo.
- `group`/`version` for every publishable module are set **once**, at the root `build.gradle.kts`
  (`allprojects {}`) — that's the single place to bump the release version, not per-module.

## Where to look for more

- `README.md` — full public API walkthrough, module-by-module, with usage examples; treat it as the spec.
- `CONTRIBUTING.md` — PR workflow, project philosophy, commit conventions.
- `SETUP.md` — environment setup and a troubleshooting table for the toolchain quirks above.
- `memory.md` — the project's architecture decision log (`ADL-NNN` entries) and feature history
  (`FEATURES.md` cross-references these as `F-NN`); consult it before reversing a decision that looks
  odd — it's very likely deliberate and explained there.
