# SyncEngine

[![Maven Central](https://img.shields.io/maven-central/v/io.github.prathamesh2640/sync-core.svg?label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.prathamesh2640)
[![CI](https://github.com/Prathamesh2640/Sync-Engine-Library/actions/workflows/ci.yml/badge.svg)](https://github.com/Prathamesh2640/Sync-Engine-Library/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![API docs](https://img.shields.io/badge/docs-Dokka-blue.svg)](https://prathamesh2640.github.io/Sync-Engine-Library/)

**An offline-first data synchronisation library for Android.**

SyncEngine handles the hard parts of offline-first development: queueing local writes when the network is unavailable, pushing them to the server when connectivity returns, detecting conflicts between local and remote versions, and keeping your local database consistent throughout. Your app code works against plain Kotlin data classes — the library does the rest.

Written in Kotlin. Built on Coroutines + Flow, Room, WorkManager, and Retrofit. Modular — you pick only the pieces you need.

---

## Table of contents

- [Is this library right for your app?](#is-this-library-right-for-your-app)
- [Supported versions](#supported-versions)
- [Install](#install)
- [Module structure](#module-structure)
- [The seams](#the-seams)
- [Quick start](#quick-start)
- [Java interop](#java-interop)
- [Testing your integration](#testing-your-integration)
- [FAQ](#faq)
- [Versioning & stability](#versioning--stability)
- [Contributing & community](#contributing--community)
- [License](#license)

---

## Is this library right for your app?

**Use SyncEngine if your app:**
- Stores user data locally and needs it synced to a backend.
- Must work fully offline and sync automatically when back online.
- Uses Room for local persistence and Retrofit (or any `suspend`-wrapped HTTP client) for network calls.
- Needs configurable conflict resolution (last-write-wins, server-wins, or custom logic).

**Do not use SyncEngine if:**
- Your app is purely online with no local persistence requirement.
- You only need one-way data download (no local writes to sync back).
- You need real-time streaming sync (WebSockets, push) — this is push/pull on demand, not streaming.

---

## Supported versions

| Requirement | Version | Notes |
|---|---|---|
| **Android minSdk** | **24** (Android 7.0) | Covers ~99% of active devices |
| **Android targetSdk / compileSdk** | 36 (Android 16) | No target-specific APIs used |
| **Kotlin** | 2.2.x | Via AGP 9's built-in Kotlin; `kotlin-stdlib` 2.2.10 comes transitively through `sync-core` |
| **Android Gradle Plugin** | 9.2.x (built-in Kotlin) | Works on AGP 8.x consumers too — the library ships pre-compiled AARs |
| **JDK (build only)** | 17 | Runtime is Android; JDK only affects your build machine |
| **Coroutines** | 1.8.1+ | Provided transitively |
| **Room** | 2.7.1+ | Only if you use `:sync-storage-room` |
| **Retrofit** | 2.11.0+ | Only if you use `:sync-network-retrofit` |
| **WorkManager** | 2.10.0+ | Only if you use `:sync-workmanager` |
| **Compose BOM** | 2024.10.00+ | Only if you use `:sync-ui-dashboard` |

Ships as standard Android AARs — nothing about your host app's toolchain matters beyond the versions above.

---

## Install

### Maven Central

```kotlin
dependencies {
    implementation("io.github.prathamesh2640:sync-core:0.1.0")               // required
    implementation("io.github.prathamesh2640:sync-storage-room:0.1.0")       // optional
    implementation("io.github.prathamesh2640:sync-network-retrofit:0.1.0")   // optional
    implementation("io.github.prathamesh2640:sync-workmanager:0.1.0")        // optional
    debugImplementation("io.github.prathamesh2640:sync-ui-dashboard:0.1.0")  // debug only — never ship in release
}
```

### JitPack (alternative — pulls directly from a git tag, no Central Portal review wait)

**`settings.gradle.kts`:** add `maven { url = uri("https://jitpack.io") }` to `repositories`.

```kotlin
dependencies {
    implementation("com.github.Prathamesh2640.Sync-Engine-Library:sync-core:<TAG>")
    // ...same optional modules as above, same group prefix
}
```

Replace `<TAG>` with a released git tag. JitPack builds the AARs on first request.

### Local development

Developing the library alongside a host app, or testing an unpublished change? Use a Gradle composite build (`includeBuild("../Sync-Engine-Library")` + `dependencySubstitution` in the host's `settings.gradle.kts`) or `./gradlew publishToMavenLocal` — see [SETUP.md § 9](SETUP.md) for the full local-Maven walkthrough.

### Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" /> <!-- optional: lets WorkManager wait for a real network -->
```

The library modules inherit these through manifest merging — they don't declare permissions themselves. `:sync-ui-dashboard` contributes a debug-only, non-exported `SyncDashboardActivity`.

---

## Module structure

```
:sync-core               — interfaces, models, state machine (required)
:sync-storage-room       — Room-based durable local queue
:sync-network-retrofit   — Retrofit-based network adapter
:sync-workmanager        — WorkManager-based background scheduler
:sync-ui-dashboard       — debug-only Compose dashboard (not for production)
:sample-app              — reference implementation (not published)
```

Strict one-way dependency graph, no circular deps, no sibling-module imports:

```
sample-app → all modules
sync-ui-dashboard / sync-workmanager / sync-network-retrofit / sync-storage-room → sync-core
sync-core → Kotlin stdlib + Coroutines only (no Android framework — pure JVM, unit-testable without Robolectric)
```

---

## The seams

What plugs into what — the contracts you implement or configure.

**Entity contract.** Every synced data class implements `SyncableEntity` — just `id` (a client-generated UUID v4, the idempotency key) and `lastModified`. Sync state and the soft-delete flag are **not** fields on your entity; they live in a library-owned `SyncMetadata` record, keyed by `id`, tracked by your `LocalSyncStore`. Adopting SyncEngine on an existing table needs no new columns on it:

```kotlin
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey override val id: String = UUID.randomUUID().toString(),
    val title: String,
    val body: String,
    override val lastModified: Long = System.currentTimeMillis(),
) : SyncableEntity
```

Creating or updating a row isn't enough by itself — call `store.enqueue(entity)` after every local insert/update (persists + marks `PENDING`; there's no column default doing this implicitly). For a delete, call `store.markDeleted(id)` instead: SyncEngine pushes the tombstone and hard-deletes the local row only after the server confirms it, so deletions are never lost while offline.

**Sync lifecycle.** Every entity moves through `PENDING → SYNCING → SYNCED`, with `FAILED` (re-queued next run) and `CONFLICT` (resolved by your `ConflictResolver` → `PENDING` → pushed back) branches. Beyond the single `syncState: StateFlow<SyncState>` enum, `engine.stats: StateFlow<SyncStats>` reports pending/failed/conflict counts (via `store.counts()`), the last run's timestamp, and its first error — updated once at the end of every `triggerSync()` run, not on every local write. This is what backs the debug dashboard with no host-side hand-counting.

**Network adapter.** Implement `SyncNetworkAdapter<T>` — three `suspend` methods (`push`/`pull`/`delete`) that never throw, mapping every outcome onto a `NetworkResult` (`Success`/`HttpError`/`NetworkError`/`UnknownError`). If your API is Retrofit-based, `:sync-network-retrofit`'s `RetrofitSyncAdapter` does this mapping for you — hand it three suspend call references returning `retrofit2.Response`:

```kotlin
val adapter = RetrofitSyncAdapter<Note>(pushCall = api::push, pullCall = api::pull, deleteCall = api::delete)
```

Auth headers, idempotency headers, and retry policy live on **your** `OkHttpClient` — the library is hands-off there.

**Conflict resolution.** `ConflictResolver<T>` is a single-method (`fun`) interface — a strategy can be a lambda. It must be pure (no I/O, no mutating its args). **Security note:** `remote` is network-sourced, untrusted input — a naive Last-Write-Wins that blindly trusts `remote.lastModified` lets a compromised or clock-skewed server win every future conflict. Guard against implausible future timestamps:

```kotlin
val maxClockSkewMillis = 5 * 60 * 1000L
val lastWriteWins = ConflictResolver<Note> { local, remote ->
    val plausible = remote.lastModified <= System.currentTimeMillis() + maxClockSkewMillis
    if (plausible && remote.lastModified > local.lastModified) remote else local
}
```

Pass a resolver to `SyncEngine.create(..., resolver = ...)` to enable two-way sync; without one, a conflict leaves the entity `CONFLICT` and reports `SyncError.ConflictUnresolvable`. Server-Wins and custom-merge variants are demonstrated in the sample app.

**Local storage.** By default the engine keeps its queue in memory — give it a `LocalSyncStore` for durability across process death:

```kotlin
interface LocalSyncStore<T : SyncableEntity> {
    suspend fun getPending(limit: Int): List<T>       // at most `limit`, oldest-first
    suspend fun getByIds(ids: List<String>): Map<String, T>
    suspend fun getMetadataByIds(ids: List<String>): Map<String, SyncMetadata>
    suspend fun counts(): SyncCounts                    // pending/failed/conflict — backs engine.stats
    suspend fun upsert(entities: List<T>)
    suspend fun getTombstones(): List<T>
    suspend fun markSyncState(id: String, state: SyncState)   // upsert — creates the row if absent
    suspend fun enqueue(entity: T)                             // default: upsert(listOf(entity)) + markSyncState(id, PENDING)
    suspend fun markDeleted(id: String)
    suspend fun hardDelete(ids: List<String>)
    suspend fun purgeExpiredTombstones(retentionDays: Int): Int
}
```

`:sync-storage-room`'s `RoomSyncAdapter` is the Room-backed implementation. Your DAO is a **plain `@Dao`** (no generic base — Room's KSP can't codegen one) with a `@RawQuery` read and an `@Upsert` write; `RoomSyncAdapter` joins your table against a small host-created `notes_sync_meta` side table (`id`/`syncState`/`isDeleted` — always that shape, created via an explicit `Migration`, never `fallbackToDestructiveMigration()`):

```kotlin
val store = RoomSyncAdapter<Note>(
    database = db, tableName = "notes", metadataTable = "notes_sync_meta",
    rawQuery = db.noteDao()::rawQuery, upsert = db.noteDao()::upsertAll,
)
```

Every raw-SQL write calls `InvalidationTracker.refreshVersionsAsync()` after its transaction, so a `Flow` query you observe over either table sees engine writes exactly as it would see your own DAO writes. `idColumn`/`modifiedColumn` are configurable if your entity renames either with `@ColumnInfo`; every table/column name is validated as a SQL identifier and every value is bound (no injection risk). `purgeExpiredTombstones` hard-deletes failed tombstones past `tombstoneRetentionDays` for GDPR erasure hygiene.

**Background sync.** `:sync-workmanager`'s `WorkManagerSyncScheduler` implements the framework-free `SyncScheduler` interface, keeping WorkManager out of your code entirely:

```kotlin
val scheduler = WorkManagerSyncScheduler(context, engineProvider = { engine })
scheduler.schedulePeriodicSync()   // every 15 min (WorkManager's floor) by default, when online
```

`intervalMinutes`, `networkRequirement` (`SyncNetworkRequirement`), and `backoffPolicy`/`backoffDelayMillis` (`SyncBackoffPolicy`) are all configurable — library-owned enums, no `androidx.work` type leaks into your code. More than one engine can run in the same process: give each scheduler its own `engineKey` and they schedule independently with no WorkManager unique-work-name collision.

**Debug dashboard.** `:sync-ui-dashboard` is a **debug-only** Compose screen (add via `debugImplementation`) showing live sync status. Build its state from `engine.syncState` + `engine.stats` — no hand-counting:

```kotlin
val dashboardState = combine(engine.syncState, engine.stats) { state, stats ->
    SyncDashboardState(
        syncState = state, lastSyncTimestamp = stats.lastSyncTimestamp,
        pendingCount = stats.pending, failedCount = stats.failed, conflictCount = stats.conflict,
        lastError = stats.lastError?.toString(),
    )
}.stateIn(scope, SharingStarted.Eagerly, SyncDashboardState())
SyncDashboard.install(state = dashboardState, onTriggerSync = { scope.launch { engine.triggerSync() } })
startActivity(Intent(context, SyncDashboardActivity::class.java))
```

Pass a distinct `key` to `SyncDashboard.install` per engine, and launch the activity with the matching `EXTRA_ENGINE_KEY` intent extra, to install more than one dashboard in the same process.

---

## Quick start

```kotlin
// 1. Entity — see "The seams" above for the full contract.
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey override val id: String = UUID.randomUUID().toString(),
    val title: String,
    override val lastModified: Long = System.currentTimeMillis(),
) : SyncableEntity

// 2. Engine — no Android Context required, cheap to construct, does no I/O until triggerSync():
val engine: SyncEngine = SyncEngine.create(
    adapter = RetrofitSyncAdapter(pushCall = api::push, pullCall = api::pull, deleteCall = api::delete),
    config = SyncEngineConfig { batchSize = 100 },
    store = RoomSyncAdapter(db, tableName = "notes", metadataTable = "notes_sync_meta",
        rawQuery = db.noteDao()::rawQuery, upsert = db.noteDao()::upsertAll),
    resolver = ConflictResolver { local, remote -> if (local.lastModified >= remote.lastModified) local else remote },
)

// 3. Observe + trigger:
engine.syncState.onEach { state -> render(state) }.launchIn(viewModelScope)
when (val result = engine.triggerSync()) {          // suspends, never throws, single-flight
    is SyncResult.Success        -> log("synced ${result.syncedCount}")
    is SyncResult.PartialFailure -> retryLater(result.errors)
    is SyncResult.Failure        -> show(result.error)
}

// 4. Local writes — enqueue after every insert/update, markDeleted on delete:
store.enqueue(note)
store.markDeleted(note.id)

// 5. Release when done — cancels the engine's coroutine scope:
engine.close()   // or: SyncEngine.create(adapter).use { it.triggerSync() }
```

---

## Java interop

Every public symbol is annotated so Java callers get idiomatic APIs — a `Builder` for `SyncEngineConfig`, static `SyncEngine.create(...)`. `suspend` functions need Kotlin coroutines interop (`kotlinx-coroutines-android`); most Java-first apps find it easiest to expose a thin Kotlin wrapper around `triggerSync()`. Nothing else to add for **ProGuard/R8** — every module ships a `consumer-rules.pro` that R8 merges automatically; your app's `proguard-rules.pro` needs no SyncEngine-specific keeps.

---

## Testing your integration

**161 automated tests** across all six modules, all JVM (no emulator required).

| Module | Tests | Where |
|---|---|---|
| `:sync-core` | 110 | JVM |
| `:sync-storage-room` | 24 (Robolectric + real Room in-memory) | JVM |
| `:sync-network-retrofit` | 7 (MockWebServer) | JVM |
| `:sync-workmanager` | 6 (`WorkManagerTestInitHelper` + Robolectric) | JVM |
| `:sync-ui-dashboard` | 6 | JVM |
| `:sample-app` | 8 | JVM |

Run everything: `./gradlew test`. For your own integration, the simplest smoke test: seed one row `PENDING`, call `engine.triggerSync()`, assert it's now `SYNCED`. The sample app does exactly this.

---

## FAQ

**Do I have to use Room / Retrofit?** No to both — `LocalSyncStore` and `SyncNetworkAdapter` are framework-free interfaces; `:sync-storage-room`/`:sync-network-retrofit` are convenience implementations for the common case.

**Can I have multiple engines?** Yes — one per entity type is a supported pattern, each with its own queue/state machine/adapter. Give each `WorkManagerSyncScheduler` a distinct `engineKey` so their schedules don't collide.

**Does the engine own a thread pool?** No — it borrows `Dispatchers.Default` by default (injectable via the internal constructor). One background coroutine per engine, cancelled by `close()`.

**How does the pull watermark work?** The engine tracks the highest `lastModified` it has successfully applied, in memory only (resets to 0 on process death, triggering a safe full re-pull — `upsert` is idempotent). An entity that hits an unresolved conflict or a pending local deletion does not advance the watermark, so it's requested again on the next pull rather than silently dropped.

**Is push atomic?** Per-item — each entity is a separate `push([one])` call, so one failure never blocks the others.

**How do I encrypt the local database?** Room supports SQLCipher via `SupportFactory`; SyncEngine talks to Room through the ordinary `RoomDatabase` handle, so encryption is transparent to it.

---

## Versioning & stability

Semantic Versioning: **major** = source-breaking public-API change, **minor** = additive public API, **patch** = bug fixes / internal changes. Anything `internal`, in an `internal` package, or in the sample app is not covered by the compatibility contract. Release history lives in [`CHANGELOG.md`](CHANGELOG.md).

---

## Contributing & community

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) (dev environment, PR workflow, commit style), [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md), and [`SECURITY.md`](SECURITY.md) (**do not** open a public issue for vulnerabilities) before opening a PR. Large redesigns are best discussed in an issue first — the public API is a forever promise. Maintainers cutting a release: [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md).

---

## License

Apache License, Version 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE) at the repository root. Copyright 2026 Prathamesh Sharma. You may use, modify, and redistribute this library — including in commercial software — as long as you preserve the copyright notice, state significant changes, and include a copy of the license with your distribution.
