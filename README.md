# SyncEngine

[![Maven Central](https://img.shields.io/maven-central/v/io.github.prathamesh2640/sync-core.svg?label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.prathamesh2640)
[![CI](https://github.com/Prathamesh2640/Sync-Engine-Library/actions/workflows/ci.yml/badge.svg)](https://github.com/Prathamesh2640/Sync-Engine-Library/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**An offline-first data synchronisation library for Android.**

SyncEngine handles the hard parts of offline-first development: queueing local writes when the network is unavailable, pushing them to the server when connectivity returns, detecting conflicts between local and remote versions, and keeping your local database consistent throughout. Your app code works against plain Kotlin data classes — the library does the rest.

Written in Kotlin. Built on Coroutines + Flow, Room, WorkManager, and Retrofit. Modular — you pick only the pieces you need.

---

## Table of contents

- [Is this library right for your app?](#is-this-library-right-for-your-app)
- [Supported versions](#supported-versions)
- [Install](#install)
- [Permissions & manifest](#permissions--manifest)
- [Module structure](#module-structure)
- [How it works](#how-it-works)
- [Quick start (5 steps)](#quick-start-5-steps)
- [Java interop](#java-interop)
- [ProGuard / R8](#proguard--r8)
- [Testing your integration](#testing-your-integration)
- [FAQ](#faq)
- [Implementation status](#implementation-status)
- [Versioning & stability](#versioning--stability)
- [Contributing & community](#contributing--community)
- [License](#license)

---

## Is this library right for your app?

**Use SyncEngine if your app:**
- Stores user data locally and needs it synced to a backend.
- Must work fully offline and sync automatically when back online.
- Uses Room for local persistence.
- Uses Retrofit (or any HTTP client wrapped behind a `suspend` function) for network calls.
- Needs configurable conflict resolution (last-write-wins, server-wins, or custom logic).

**Do not use SyncEngine if:**
- Your app is purely online with no local persistence requirement.
- You only need one-way data download (no local writes to sync back).
- You need real-time streaming sync (WebSockets, push). SyncEngine is push/pull on demand, not streaming.

---

## Supported versions

SyncEngine targets modern Android but keeps its floor low so it works in most production apps.

| Requirement | Version | Notes |
|---|---|---|
| **Android minSdk** | **24** (Android 7.0 Nougat) | Covers ~99% of active devices |
| **Android targetSdk / compileSdk** | 36 (Android 16) | Newer is fine — no target-specific APIs used |
| **Kotlin** | 2.2.x | Built with AGP 9's built-in Kotlin (2.2.10); `kotlin-stdlib` 2.2.10 comes transitively through `sync-core`. Older host compilers warn about the newer stdlib on the classpath |
| **Android Gradle Plugin** | 9.2.x (built-in Kotlin) | Works on AGP 8.x consumers too — the library ships pre-compiled AARs |
| **JDK (build only)** | 17 | Runtime is Android; JDK only affects your build machine |
| **Coroutines** | 1.8.1+ | Provided transitively through `sync-core` |
| **Room** | 2.7.1+ | Only if you use `:sync-storage-room` |
| **Retrofit** | 2.11.0+ | Only if you use `:sync-network-retrofit` |
| **WorkManager** | 2.10.0+ | Only if you use `:sync-workmanager` |
| **Jetpack Compose BOM** | 2024.10.00+ | Only if you use `:sync-ui-dashboard` |

The library ships as **standard Android AARs** — nothing about your host app's toolchain matters as long as it can consume AARs and the versions above are met.

---

## Install

### Option A — Maven Central (recommended, once `0.1.0` is released)

No extra repository needed — `mavenCentral()` is already in every Android project's default repositories.

**`app/build.gradle.kts`:**
```kotlin
dependencies {
    // Required — the core engine
    implementation("io.github.prathamesh2640:sync-core:0.1.0")

    // Optional — pick what you need
    implementation("io.github.prathamesh2640:sync-storage-room:0.1.0")
    implementation("io.github.prathamesh2640:sync-network-retrofit:0.1.0")
    implementation("io.github.prathamesh2640:sync-workmanager:0.1.0")

    // Debug builds only — never ship the dashboard in production
    debugImplementation("io.github.prathamesh2640:sync-ui-dashboard:0.1.0")
}
```

Until the first release completes (see [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md) / [`PUBLISHING.md`](PUBLISHING.md) for maintainers), use one of the options below.

### Option B — JitPack (works today, before the first Central release)

[JitPack](https://jitpack.io) builds the library on demand from a GitHub tag. No publishing infrastructure needed.

**`settings.gradle.kts`:**
```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

**`app/build.gradle.kts`:**
```kotlin
dependencies {
    // Required — the core engine
    implementation("com.github.Prathamesh2640.Sync-Engine-Library:sync-core:<TAG>")

    // Optional — pick what you need
    implementation("com.github.Prathamesh2640.Sync-Engine-Library:sync-storage-room:<TAG>")
    implementation("com.github.Prathamesh2640.Sync-Engine-Library:sync-network-retrofit:<TAG>")
    implementation("com.github.Prathamesh2640.Sync-Engine-Library:sync-workmanager:<TAG>")

    // Debug builds only — never ship the dashboard in production
    debugImplementation("com.github.Prathamesh2640.Sync-Engine-Library:sync-ui-dashboard:<TAG>")
}
```

Replace `<TAG>` with a released git tag (e.g. `1.0.0`). Once the first tag is pushed, JitPack builds the AARs automatically on first request.

### Option C — Composite build (recommended for local development)

If you want to develop the library and your host app side by side, use Gradle's composite build. No publishing.

**`settings.gradle.kts` of your host app:**
```kotlin
includeBuild("../Sync-Engine-Library") {
    dependencySubstitution {
        substitute(module("io.github.prathamesh2640:sync-core"))
            .using(project(":sync-core"))
        substitute(module("io.github.prathamesh2640:sync-storage-room"))
            .using(project(":sync-storage-room"))
        substitute(module("io.github.prathamesh2640:sync-network-retrofit"))
            .using(project(":sync-network-retrofit"))
        substitute(module("io.github.prathamesh2640:sync-workmanager"))
            .using(project(":sync-workmanager"))
        substitute(module("io.github.prathamesh2640:sync-ui-dashboard"))
            .using(project(":sync-ui-dashboard"))
    }
}
```

Then declare the same `implementation("io.github.prathamesh2640:sync-core:...")` coordinates in your app — Gradle transparently substitutes the local source.

### Option D — Local Maven

Publish once from the library, then consume like any Maven artifact.

**In the library:** `./gradlew publishToMavenLocal`

**In your host app's `settings.gradle.kts`:**
```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

Coordinates are the same as Option A, under the `io.github.prathamesh2640` group.

---

## Permissions & manifest

Add these to your host app's `AndroidManifest.xml`:

```xml
<!-- Required by any Retrofit / HTTP call the library makes on your behalf -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Optional: lets WorkManager wait for a real network before running -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

The library modules do not declare permissions themselves — they inherit yours through manifest merging. `:sync-ui-dashboard` contributes a debug-only `SyncDashboardActivity` (not exported) when included via `debugImplementation`.

---

## Module structure

SyncEngine is split into focused, independently consumable modules. Import only what you need.

```
:sync-core               — interfaces, models, state machine (required)
:sync-storage-room       — Room-based durable local queue
:sync-network-retrofit   — Retrofit-based network adapter
:sync-workmanager        — WorkManager-based background scheduler
:sync-ui-dashboard       — debug-only Compose dashboard (not for production)
:sample-app              — reference implementation (not published)
```

**Dependency graph — no circular dependencies, no sibling module imports:**
```
sample-app
    └── all modules

sync-ui-dashboard      → sync-core
sync-workmanager       → sync-core
sync-network-retrofit  → sync-core
sync-storage-room      → sync-core
sync-core              → Kotlin stdlib + Coroutines only  (no Android framework — pure JVM)
```

`:sync-core` is framework-free by design: you can unit-test it on the JVM without Robolectric or an emulator.

---

## How it works

### 1. The entity contract

Every data class you want to sync implements `SyncableEntity` — just `id` and `lastModified`. Sync state and the soft-delete flag are **not** fields on your entity; they live in a library-owned `SyncMetadata` record, keyed by `id`, that your `LocalSyncStore` tracks separately (see § Local storage). This means adopting SyncEngine on an existing table needs no new columns on it:

```kotlin
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey
    override val id: String = UUID.randomUUID().toString(),
    val title: String,
    val body: String,
    override val lastModified: Long = System.currentTimeMillis(),
) : SyncableEntity
```

Two things to know:
- `id` must be a **UUID v4** generated client-side at creation time. It never changes. It is the idempotency key for all network requests, so a retried push cannot create duplicates on the server.
- `lastModified` must be updated to `System.currentTimeMillis()` every time any field changes. The default conflict resolver uses this to pick the winner.

Creating or updating a row isn't enough by itself to sync it — there's no column default doing that anymore. After every local insert/update, call `store.markSyncState(id, SyncState.PENDING)` (an upsert: it creates the metadata row if one doesn't exist). For a local delete, call `store.markDeleted(id)` instead of setting a field — soft deletes (tombstones) work the same as before, just through the store instead of a field: SyncEngine pushes the tombstone to the server and hard-deletes the local row only after the server confirms it, so deletions are never lost while offline.

### 2. The sync lifecycle

Every entity moves through a defined state machine, tracked in its `SyncMetadata`:

```
PENDING ──► SYNCING ──► SYNCED
                ├──► FAILED    (re-queued next run)
                └──► CONFLICT  (resolved by ConflictResolver → PENDING → pushed back)
```

`PENDING` is the starting state you set when enqueueing a new or modified entity (`store.markSyncState(id, SyncState.PENDING)`). The library manages every transition after that — your app code never writes `syncState` directly beyond that initial enqueue call.

### 3. Network adapter

You implement one interface to connect SyncEngine to your API:

```kotlin
class MyApiAdapter(private val api: MyRetrofitService) : SyncNetworkAdapter<Note> {
    override suspend fun push(payload: List<Note>): NetworkResult<Unit> { ... }
    override suspend fun pull(since: Long): NetworkResult<List<Note>> { ... }
    override suspend fun delete(ids: List<String>): NetworkResult<Unit> { ... }
}
```

An adapter **never throws** — it maps every outcome onto a `NetworkResult` branch:

```kotlin
sealed class NetworkResult<out T> {
    data class Success<out T>(val data: T)                    // 2xx — carries the payload
    data class HttpError(val code: Int, val message: String)  // server reached, non-2xx
    data class NetworkError(val cause: Throwable)             // transport failure — retryable
    data class UnknownError(val cause: Throwable)             // e.g. a parse failure
}
```

If your API is Retrofit-based, you do not have to write that mapping yourself — `:sync-network-retrofit` provides `RetrofitSyncAdapter`. You keep your own plain Retrofit service (the library never sees your `Retrofit`/`OkHttpClient` and does not force a serialization library on you); you hand the adapter three suspend call references returning `retrofit2.Response`, and it does the `Response`/exception → `NetworkResult` mapping (including re-throwing cancellation):

```kotlin
interface NoteApi {
    @POST("notes/push")   suspend fun push(@Body notes: List<Note>): Response<Unit>
    @GET("notes")         suspend fun pull(@Query("since") since: Long): Response<List<Note>>
    @POST("notes/delete") suspend fun delete(@Body ids: List<String>): Response<Unit>
}

val api = retrofit.create(NoteApi::class.java)
val adapter = RetrofitSyncAdapter<Note>(
    pushCall = api::push,
    pullCall = api::pull,
    deleteCall = api::delete,
)
```

Mapping: `2xx` → `Success` (pull yields the body, or an empty list on `204`); non-`2xx` → `HttpError`; `IOException` → `NetworkError`; anything else (e.g. a converter failure) → `UnknownError`. Auth headers, idempotency headers and retry policy live on **your** `OkHttpClient` — the library is intentionally hands-off there.

### 4. Conflict resolution

`ConflictResolver<T : SyncableEntity>` is a single-method (`fun`) interface, so a strategy can be a lambda or a class. It must be pure — no I/O, no mutation of its arguments.

**Security note:** `remote` is network-sourced, untrusted input — a compromised or clock-skewed server can send an arbitrary timestamp. A naive Last-Write-Wins that blindly trusts `remote.lastModified` lets such a server win every future conflict by reporting a timestamp far in the future. Guard against this by rejecting implausible future timestamps rather than trusting them outright:

```kotlin
// Last-Write-Wins: the newer timestamp survives, but only if remote's
// timestamp is plausible (not further ahead of "now" than clock skew allows).
val maxClockSkewMillis = 5 * 60 * 1000L // 5 minutes
val lastWriteWins = ConflictResolver<Note> { local, remote ->
    val remoteIsPlausible = remote.lastModified <= System.currentTimeMillis() + maxClockSkewMillis
    if (remoteIsPlausible && remote.lastModified > local.lastModified) remote else local
}

// Server-Wins: remote always wins.
val serverWins = ConflictResolver<Note> { _, remote -> remote }

// Custom merge: keep remote's fields but the latest timestamp of the two.
val merge = ConflictResolver<Note> { local, remote ->
    remote.copy(lastModified = maxOf(local.lastModified, remote.lastModified))
}
```

Pass a resolver to `SyncEngine.create(..., resolver = ...)` to enable two-way sync. During a run the engine pulls remote changes and, when a locally-pending entity also changed on the server, invokes the resolver; the winner is persisted `PENDING` and pushed back so both sides converge. A conflict with **no** resolver configured leaves the entity in `SyncState.CONFLICT` and is reported as `SyncError.ConflictUnresolvable`. These three strategies (with the clock-skew guard) are demonstrated end-to-end in the sample app.

### 5. Engine results and configuration

`SyncEngine.triggerSync()` returns a `SyncResult` — the engine never throws; failures are values:

```kotlin
sealed class SyncResult {
    data class Success(val syncedCount: Int, val conflictCount: Int)
    data class PartialFailure(val syncedCount: Int, val failedCount: Int, val errors: List<SyncError>)
    data class Failure(val error: SyncError)
}

sealed class SyncError {
    data object NetworkUnavailable
    data class HttpError(val code: Int)
    data class ConflictUnresolvable(val entityId: String)
    data class StorageError(val cause: Throwable)
}
```

The engine is configured with a type-safe DSL (all options have defaults, so `SyncEngineConfig {}` is valid):

```kotlin
val config = SyncEngineConfig {
    batchSize = 100                 // default 50, max 1000 — entities drained per run; each is pushed as its own request
    maxRetries = 5                  // default 3    — consecutive per-entity failures before it's left FAILED for good
    tombstoneRetentionDays = 30     // default 30   — how long failed deletes are kept locally
    maxConcurrentPushes = 20        // default 20   — pushes in flight at once within a batch
    logLevel = LogLevel.DEBUG       // default NONE — NONE < ERROR < WARN < INFO < DEBUG; job ids/state/error codes only, never entity content
}
```

Java callers use the equivalent builder — see [Java interop](#java-interop).

### 6. Local storage (offline durability)

By default the engine keeps its queue in memory. To make pending work survive process death, give it a `LocalSyncStore` — the durable, offline-first queue. `LocalSyncStore` lives in `:sync-core` and is framework-free; `:sync-storage-room` provides the Room-backed implementation, `RoomSyncAdapter`.

```kotlin
// LocalSyncStore is the engine's view of persistence (in :sync-core):
interface LocalSyncStore<T : SyncableEntity> {
    suspend fun getPending(): List<T>
    suspend fun getById(id: String): T?
    suspend fun getMetadata(id: String): SyncMetadata?
    suspend fun upsert(entities: List<T>)
    suspend fun getTombstones(): List<T>
    suspend fun markSyncState(id: String, state: SyncState)   // upsert — also how you enqueue a new/edited entity
    suspend fun markDeleted(id: String)                        // soft-delete: call instead of setting a field
    suspend fun hardDelete(ids: List<String>)
    suspend fun purgeExpiredTombstones(retentionDays: Int): Int
}
```

Your Room DAO is a **plain `@Dao`** with a `@RawQuery` read and an `@Upsert` write; `RoomSyncAdapter` reads state-scoped slices of your entity table through them and writes engine outcomes back. There is **no separate queue table** and no generic base DAO to implement — your entity's `syncState` column is the single source of truth, and a plain `@Dao` sidesteps Room's KSP limitation with generic DAOs.

```kotlin
@Dao
interface NoteDao {
    @Upsert   suspend fun upsertAll(entities: List<Note>)
    @RawQuery suspend fun rawQuery(query: SupportSQLiteQuery): List<Note>
}

@Database(entities = [Note::class], version = 1)
abstract class AppDatabase : RoomDatabase() {   // your own database — the library adds no schema
    abstract fun noteDao(): NoteDao
}

val db = Room.databaseBuilder(context, AppDatabase::class.java, "app.db").build()
val store = RoomSyncAdapter<Note>(
    database = db,
    tableName = "notes",
    rawQuery = db.noteDao()::rawQuery,
    upsert = db.noteDao()::upsertAll,
)
```

The store keeps the four `SyncableEntity` columns under their default names (`id`, `syncState`, `isDeleted`, `lastModified`) unless you tell it otherwise. If your entity renames any of them with `@ColumnInfo`, pass the real names via `idColumn`/`stateColumn`/`deletedColumn`/`modifiedColumn`. `RoomSyncAdapter` validates the table name *and* every column name as a SQL identifier and binds every query value (no injection risk), and `purgeExpiredTombstones` hard-deletes failed tombstones past `tombstoneRetentionDays` for GDPR erasure hygiene.

The library owns no table of its own — it reads and writes yours. Engine writes run inside a Room transaction, so a `Flow` query you observe over the same table is invalidated by them exactly as it would be by your own DAO writes.

> **Never call `fallbackToDestructiveMigration()`** on a database holding syncable data — it drops every table on a version bump, silently destroying unsynced local changes and tombstones. Ship explicit, additive migrations.

### 7. Background sync (WorkManager)

`:sync-workmanager` runs sync automatically in the background via `WorkManagerSyncScheduler`, an implementation of the framework-free `SyncScheduler` interface. Create it once (typically in your `Application`) with a provider for your engine, and WorkManager is kept entirely out of your code:

```kotlin
interface SyncScheduler {
    fun schedulePeriodicSync()   // requires network; retries with exponential backoff
    fun cancelSync()
}

val scheduler = WorkManagerSyncScheduler(context, engineProvider = { engine })
scheduler.schedulePeriodicSync()   // every 15 min (WorkManager's minimum), when online
```

The worker carries no payload — only WorkManager's own job id, never tokens or entity data. The default cadence is 15 minutes (WorkManager's minimum for periodic work); pass `intervalMinutes` to change it (values below 15 are coerced up).

The network requirement and retry backoff are also configurable, without any `androidx.work` type leaking into your code — `SyncNetworkRequirement`/`SyncBackoffPolicy` are library-owned enums:

```kotlin
val scheduler = WorkManagerSyncScheduler(
    context,
    engineProvider = { engine },
    networkRequirement = SyncNetworkRequirement.UNMETERED, // default CONNECTED
    backoffPolicy = SyncBackoffPolicy.LINEAR,               // default EXPONENTIAL
    backoffDelayMillis = 30_000L,                            // default WorkManager's minimum
)
```

More than one engine can run in the same process — give each scheduler its own `engineKey` and they schedule independently, with no shared state or WorkManager unique-work-name collision:

```kotlin
WorkManagerSyncScheduler(context, engineProvider = { notesEngine }, engineKey = "notes").schedulePeriodicSync()
WorkManagerSyncScheduler(context, engineProvider = { remindersEngine }, engineKey = "reminders").schedulePeriodicSync()
```

### 8. Debug dashboard (Compose)

`:sync-ui-dashboard` is a **debug-only** Jetpack Compose screen showing live sync status: current state, last-sync time, pending / failed / conflict counts, last error, and a "Sync now" button. Add it with `debugImplementation` so it never ships in release builds.

Embed `SyncDashboardRoute` in your own screen, or launch the ready-made `SyncDashboardActivity` from a debug menu. The activity reads a state source you install once:

```kotlin
// Debug build only — build a StateFlow<SyncDashboardState> from your engine + store:
SyncDashboard.install(
    state = dashboardState,
    onTriggerSync = { scope.launch { engine.triggerSync() } },
)
// ...then launch it:
startActivity(Intent(context, SyncDashboardActivity::class.java))
```

The dashboard depends only on `:sync-core` — it observes state through the public interfaces and never touches Room or WorkManager directly.

More than one engine's dashboard can be installed in the same process — pass a distinct `key` per engine and launch the activity with the matching `EXTRA_ENGINE_KEY` intent extra:

```kotlin
SyncDashboard.install(state = notesState, onTriggerSync = { ... }, key = "notes")
startActivity(Intent(context, SyncDashboardActivity::class.java).putExtra(SyncDashboardActivity.EXTRA_ENGINE_KEY, "notes"))
```

---

## Quick start (5 steps)

### Step 1 — Add dependencies

See [Install](#install). Minimum for a working setup: `:sync-core` + `:sync-storage-room` + `:sync-network-retrofit`. Add `:sync-workmanager` for background sync and `debugImplementation` of `:sync-ui-dashboard` for a diagnostic screen.

### Step 2 — Make your entity implement `SyncableEntity`

See the example in [How it works § 1](#1-the-entity-contract).

### Step 3 — Implement your network adapter

See the `SyncNetworkAdapter` example in [How it works § 3](#3-network-adapter). If you use Retrofit, add `:sync-network-retrofit` and wrap your service with `RetrofitSyncAdapter` instead of writing the mapping by hand.

### Step 4 — Create the engine and sync

The engine is created from its collaborators — **no Android `Context` is required**, which keeps `:sync-core` framework-free and unit-testable. Construction is cheap and does no I/O; nothing syncs until you call `triggerSync()`.

```kotlin
val engine: SyncEngine = SyncEngine.create(
    adapter = MyApiAdapter(api),
    config = SyncEngineConfig { batchSize = 100 },
    store = RoomSyncAdapter(                                  // durable, offline-first queue
        db, tableName = "notes",
        rawQuery = db.noteDao()::rawQuery, upsert = db.noteDao()::upsertAll,
    ),
    resolver = ConflictResolver { local, remote ->            // enable two-way sync + conflicts
        if (local.lastModified >= remote.lastModified) local else remote
    },
)

// Observe the engine's state reactively (e.g. from a ViewModel):
engine.syncState
    .onEach { state -> render(state) }   // PENDING / SYNCING / SYNCED / FAILED / CONFLICT
    .launchIn(viewModelScope)

// Trigger a sync — suspends until the run completes, never throws:
when (val result = engine.triggerSync()) {
    is SyncResult.Success        -> log("synced ${result.syncedCount}")
    is SyncResult.PartialFailure -> retryLater(result.errors)
    is SyncResult.Failure        -> show(result.error)
}
```

`triggerSync()` is safe to call from any dispatcher and is **single-flight**: if a run is already in progress, a concurrent call is a no-op returning `Success(0, 0)` rather than starting a second run. Each item in a batch is pushed independently, so one item's failure never aborts the others — the run reports `PartialFailure` instead.

### Step 5 — Release the engine

`SyncEngine` is `Closeable`. Call `close()` (or use `use { }`) when you are done to cancel its coroutine scope — this prevents leaked coroutines in the host process. A closed engine returns `SyncResult.Failure` from any further `triggerSync()` call.

```kotlin
engine.close()
// or, scoped:
SyncEngine.create(adapter).use { engine ->
    engine.triggerSync()
}
```

---

## Java interop

Every public symbol has been annotated so Java callers get idiomatic APIs.

**Building a config:**
```java
SyncEngineConfig config = new SyncEngineConfig.Builder()
    .setBatchSize(100)
    .setMaxRetries(5)
    .setTombstoneRetentionDays(30)
    .setLogLevel(LogLevel.DEBUG)
    .build();
```

**Creating the engine:**
```java
SyncEngine engine = SyncEngine.create(myAdapter, config, myStore, myResolver);
```

**Calling `suspend` functions** requires Kotlin coroutines interop (`kotlinx-coroutines-android`); wrap the call with `BuildersKt.runBlocking` or launch it from a `CoroutineScope` obtained via `CoroutineScopeKt.CoroutineScope`. Most Java-first apps will find it easier to expose a thin Kotlin wrapper around `triggerSync()`.

---

## ProGuard / R8

Nothing to add. Every library module ships a `consumer-rules.pro` inside its AAR that R8 automatically merges into your app's shrinker configuration. It keeps every public symbol the engine relies on reflectively (Room-generated `_Impl`, Retrofit reflection, WorkManager's worker constructor, sealed/enum names). Your host-app `proguard-rules.pro` does not need any SyncEngine-specific keeps.

---

## Testing your integration

The library is validated by **134 automated tests** across all six modules — all pass on the JVM (no emulator required for library modules).

| Module | Tests | Where |
|---|---|---|
| `:sync-core` | 102 (state machine, queue, engine, pull/push/flow) | JVM |
| `:sync-storage-room` | 10 (Robolectric + real Room in-memory) | JVM |
| `:sync-network-retrofit` | 7 (MockWebServer) | JVM |
| `:sync-workmanager` | 5 (`WorkManagerTestInitHelper` + Robolectric) | JVM |
| `:sync-ui-dashboard` | 2 (state) | JVM |
| `:sample-app` | 8 (resolvers, fake API) | JVM |

Run everything: `./gradlew test`. See [SETUP.md](SETUP.md) for the full developer setup.

For your own integration, the simplest smoke test is: seed one row locally in `PENDING`, call `engine.triggerSync()`, and assert the row is now `SYNCED` (or that your adapter's push was called with the expected body). The sample app does exactly this.

---

## FAQ

**Do I have to use Room?**
No — `:sync-storage-room` is optional. `LocalSyncStore` is framework-free; you can back it with SQLDelight, DataStore, a JSON file, or a pure in-memory map. Only the four sync columns (`id`, `lastModified`, `syncState`, `isDeleted`) need to be persistable somewhere.

**Do I have to use Retrofit?**
No — `:sync-network-retrofit` is optional. `SyncNetworkAdapter` has three `suspend` methods; implement them against Ktor, OkHttp, gRPC, whatever. The Retrofit module is just a convenience for the most common case.

**What if my app never runs offline?**
Then you probably do not need this library — a plain Retrofit service is enough. SyncEngine's value is durability under intermittent connectivity.

**Does the engine own a thread pool?**
No — it borrows one. By default it runs on `Dispatchers.Default`; you can inject any `CoroutineDispatcher` through the internal constructor if you need a specific pool. There is one background coroutine per engine and it is cancelled by `close()`.

**Can I have multiple engines?**
Yes — one engine per entity type is a supported pattern. Each engine has its own queue, state machine, and adapter. If you schedule them with `WorkManagerSyncScheduler`, give each a distinct `engineKey` — it scopes both the `SyncEngineRegistry` entry and the WorkManager unique-work name so the two schedules don't collide.

**Does `pull` need a real timestamp watermark?**
The engine passes the `lastModified` of the newest entity it has seen so far. In-memory only — after process death it resets to 0, which triggers a full re-pull. That is safe because `upsert` is idempotent (keyed on `id`).

**How do conflicts get to my resolver?**
When `pull` returns an entity whose local copy is *not* `SYNCED` (i.e. the user made local changes that have not been accepted by the server), the engine hands `(local, remote)` to your `ConflictResolver` and persists the winner `PENDING`. The next push sends the winner back. If no resolver is configured, the local row is marked `CONFLICT` and reported.

**Is push atomic?**
Per-item, yes — each item is a separate `push([one])` call so one item's failure never blocks the others. If you want batched pushes with all-or-nothing semantics, batch them yourself and treat the whole batch as a single `SyncableEntity`.

**How do I encrypt the local database?**
Room supports SQLCipher via `SupportFactory`; wire it in when you build the `RoomDatabase`. SyncEngine talks to Room through the ordinary `RoomDatabase` handle, so encryption is transparent to it.

---

## Implementation status

| Capability | Status |
|---|---|
| Public API contracts (entity, results, adapter, config, engine) | Available |
| `SyncEngine.create()` + `triggerSync()` + `close()` | Available |
| Guarded state machine + thread-safe queue + single-flight, isolated batch push | Available |
| Room-backed durable storage (`LocalSyncStore` / `RoomSyncAdapter`) | Available |
| Retrofit network adapter (`RetrofitSyncAdapter` in `:sync-network-retrofit`) | Available |
| Two-way pull + conflict resolution + tombstone delete-confirmation during a run | Available |
| Background scheduling (`WorkManagerSyncScheduler` in `:sync-workmanager`) | Available |
| Debug dashboard (`SyncDashboardActivity` in `:sync-ui-dashboard`) | Available |

**Build status:** all 5 library modules produce debug + release AARs; sample app produces a runnable APK. 134/134 unit tests pass.

---

## Versioning & stability

The library follows **Semantic Versioning**:
- **Major** (`X.0.0`) — source-breaking public-API changes (e.g. new branch on a sealed class).
- **Minor** (`0.X.0`) — additive public API (new methods, new modules, new optional parameters).
- **Patch** (`0.0.X`) — bug fixes, internal changes, no public-API impact.

Anything under `internal` visibility, in an `internal` package, or in the sample app is **not** covered by the compatibility contract. Once `1.0.0` is tagged, breaking changes across the public surface require a major bump.

Release history and per-version details live in [`CHANGELOG.md`](CHANGELOG.md).

---

## Contributing & community

Contributions are welcome. Before opening a PR, please read:

- [`CONTRIBUTING.md`](CONTRIBUTING.md) — development environment, PR workflow, one-line conventional-commit style.
- [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — Contributor Covenant 2.1.
- [`SECURITY.md`](SECURITY.md) — how to report a vulnerability (**do not** open a public issue).

Bug reports and small fixes are the easiest to review; large redesigns are best discussed in an issue first, since the public API is a forever promise.

Maintainers cutting a release should follow [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md); the Maven Central Portal account/namespace setup itself is documented in [`PUBLISHING.md`](PUBLISHING.md).

---

## License

SyncEngine is licensed under the **Apache License, Version 2.0** — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE) at the repository root.

```
Copyright 2026 Prathamesh Sharma

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

You may use, modify, and redistribute this library — including in commercial software — as long as you preserve the copyright notice, state significant changes, and include a copy of the license with your distribution. The license also grants you a patent license from every contributor.
