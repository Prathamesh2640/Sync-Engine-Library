# SyncEngine — Integration & Stability Validation Guide

This document has one purpose: get SyncEngine integrated into a **second, independent
app** on **hardware we haven't tested**, run it through a rigorous test protocol, and
get exact, reproducible results back. `v0.1.0` has 161 automated tests plus a 32-scenario
manual pass on one physical tablet (Lenovo TB-X306X, Android 10) — solid, but one device
is one data point. The fastest, most credible path to a "no known crashes" major release
is a second real integration on different hardware, different Android versions, and a
different backend, with results reported in a form we can verify line by line.

Three sections, in order:

1. **[Integration](#1-integration)** — what the library is, the exact version to use, every public API call, and how to wire it into your app.
2. **[Testing](#2-testing)** — the full test protocol: online/offline, every state transition, every error branch, run across your device matrix.
3. **[Expected results back](#3-expected-results-back)** — the exact report format we need, so results are directly verifiable, not a summary we have to trust.

---

## 1. Integration

### 1.1 What SyncEngine does

SyncEngine is an offline-first sync library for Android. Local writes are queued,
pushed to your backend when connectivity allows, pulled changes are reconciled against
local edits (with conflict resolution), and everything survives process death and app
restarts. Your data classes stay plain Kotlin — the library owns the sync state machine,
the queue, retries, and conflict handling.

### 1.2 Version and compatibility — use exactly this

| | |
|---|---|
| **Library version** | **`0.1.0`** (current, live on Maven Central — this is the only released version; do not use a SNAPSHOT or a JitPack commit build) |
| `minSdk` | 24 (Android 7.0) |
| `compileSdk` / `targetSdk` | 36 |
| AGP | 9.2.1 |
| Kotlin (AGP built-in) | 2.2.10 |
| Coroutines | 1.8.1 |
| Room (if using `sync-storage-room`) | 2.7.1 |
| Retrofit (if using `sync-network-retrofit`) | 2.11.0 |
| WorkManager (if using `sync-workmanager`) | 2.10.0 |
| Compose BOM (if using `sync-ui-dashboard`) | 2024.10.00 |

If your app already pins different versions of Room/Retrofit/WorkManager/Coroutines,
list the exact versions you tested with in your results report (§3) — a version skew
from the table above is itself a useful data point, not a disqualifier.

### 1.3 Install

```kotlin
dependencies {
    implementation("io.github.prathamesh2640:sync-core:0.1.0")               // required
    implementation("io.github.prathamesh2640:sync-storage-room:0.1.0")       // optional — durable offline queue
    implementation("io.github.prathamesh2640:sync-network-retrofit:0.1.0")   // optional — Retrofit adapter
    implementation("io.github.prathamesh2640:sync-workmanager:0.1.0")        // optional — background sync
    debugImplementation("io.github.prathamesh2640:sync-ui-dashboard:0.1.0")  // optional, debug builds only
}
```

Permissions (inherited automatically via manifest merging — you don't need to add these
yourself, but verify they land in your merged manifest):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### 1.4 Module picker

| Module | Use it if | Skip it if |
|---|---|---|
| `sync-core` | Always — everything else depends on it | never |
| `sync-storage-room` | You use Room and want the queue to survive process death / support pull + conflict resolution | You only need a push-only, in-memory queue (rare — most real apps want the store) |
| `sync-network-retrofit` | You use Retrofit for your backend | You use Ktor/OkHttp directly/gRPC — implement `SyncNetworkAdapter` yourself instead (§1.6) |
| `sync-workmanager` | You want automatic periodic background sync | You only trigger sync manually (e.g. pull-to-refresh) |
| `sync-ui-dashboard` | You want a debug-only live sync-state screen | Never ship this in a release build — `debugImplementation` only |

### 1.5 Integration walkthrough

**Step 1 — implement `SyncableEntity` on your data class.**

```kotlin
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey override val id: String = UUID.randomUUID().toString(), // must be a client-generated UUID v4
    val title: String,
    val body: String,
    override val lastModified: Long = System.currentTimeMillis(),
) : SyncableEntity
```

`id` must never change after creation — it's the idempotency key for every network call.
`lastModified` must be bumped on every local edit — the default conflict resolution
strategy compares it.

**Step 2 — implement `SyncNetworkAdapter<T>`** (or use `RetrofitSyncAdapter` if you're on
Retrofit — skip straight to the snippet below).

```kotlin
interface NoteApi {
    @POST("notes/push")    suspend fun push(@Body notes: List<Note>): Response<Unit>
    @GET("notes")          suspend fun pull(@Query("since") since: Long): Response<List<Note>>
    @POST("notes/delete")  suspend fun delete(@Body ids: List<String>): Response<Unit>
}

val api = retrofit.create(NoteApi::class.java)
val adapter = RetrofitSyncAdapter<Note>(pushCall = api::push, pullCall = api::pull, deleteCall = api::delete)
```

If you're not on Retrofit, implement `SyncNetworkAdapter<T>` directly — three suspend
functions (`push`, `pull`, `delete`). **The one hard rule: never throw.** Every outcome —
success, HTTP error, no connectivity, a serialization failure — must come back as a
`NetworkResult` value. An adapter that throws crashes your app with the library on the
stack; this is the single most important contract in the whole library, and it's exactly
what §2's error-path scenarios are designed to catch if violated.

**Step 3 (recommended) — wire `RoomSyncAdapter` for a durable, two-way queue.**

```kotlin
@Dao
interface NoteDao {
    @Upsert   suspend fun upsertAll(entities: List<Note>)
    @RawQuery suspend fun rawQuery(query: SupportSQLiteQuery): List<Note>
}
```

Create the metadata side table via a Room `Migration` (never
`fallbackToDestructiveMigration()`):

```sql
CREATE TABLE notes_sync_meta (
    id TEXT NOT NULL PRIMARY KEY,
    syncState TEXT NOT NULL,
    isDeleted INTEGER NOT NULL DEFAULT 0
)
```

```kotlin
val store = RoomSyncAdapter<Note>(
    database = db,
    tableName = "notes",
    metadataTable = "notes_sync_meta",
    rawQuery = db.noteDao()::rawQuery,
    upsert = db.noteDao()::upsertAll,
)
```

Without a store, the engine is push-only, in-memory, and skips pull/conflict/tombstone
handling entirely — fine for a quick smoke test, but **test with a store wired in**; that's
the configuration real apps ship and the one this validation effort needs covered.

**Step 4 (optional, needs a store) — a `ConflictResolver`.**

```kotlin
val resolver = ConflictResolver<Note> { local, remote ->
    val now = System.currentTimeMillis()
    val plausibleRemote = if (remote.lastModified > now + 5 * 60_000) now else remote.lastModified
    if (local.lastModified >= plausibleRemote) local else remote
}
```

Do not trust `remote.lastModified` blindly — it came over the network. Guard against a
clock-skewed or compromised server reporting a timestamp far in the future (see the
snippet above), and treat equal/missing timestamps as **local wins**, never remote.

**Step 5 — create the engine.**

```kotlin
val engine = SyncEngine.create(
    adapter = adapter,
    config = SyncEngineConfig { batchSize = 50; maxRetries = 3; logLevel = LogLevel.DEBUG },
    store = store,       // omit for push-only/in-memory
    resolver = resolver, // omit to leave unresolved conflicts as SyncState.CONFLICT
)
```

`SyncEngine` is `Closeable` — call `engine.close()` (or `use { }`) when you're done with
it; a closed engine must not be reused.

**Step 6 — enqueue local writes.** Creating/editing a row is not enough by itself — mark
it explicitly:

```kotlin
noteDao.upsert(note)
store.markSyncState(note.id, SyncState.PENDING)   // or: store.enqueue(note) to do both in one call
// on delete:
store.markDeleted(note.id)
```

**Step 7 — trigger sync and observe.**

```kotlin
engine.syncState.onEach { state -> updateUi(state) }.launchIn(scope)   // StateFlow<SyncState>
engine.stats.onEach { stats -> updateCounters(stats) }.launchIn(scope) // StateFlow<SyncStats>

when (val result = engine.triggerSync()) {
    is SyncResult.Success        -> { /* result.syncedCount, result.conflictCount */ }
    is SyncResult.PartialFailure -> { /* result.syncedCount, result.failedCount, result.errors */ }
    is SyncResult.Failure        -> { /* result.error */ }
}
```

**Step 8 (optional) — background sync via WorkManager.**

```kotlin
class App : Application() {
    lateinit var engine: SyncEngine
    override fun onCreate() {
        super.onCreate()
        engine = SyncEngine.create(adapter, store = store, resolver = resolver)
        WorkManagerSyncScheduler(this, engineProvider = { engine }).schedulePeriodicSync()
    }
}
```

**Step 9 (optional, debug builds only) — the dashboard.**

```kotlin
SyncDashboard.install(
    state = dashboardStateFlow,                          // StateFlow<SyncDashboardState>, built from engine.syncState/stats
    onTriggerSync = { scope.launch { engine.triggerSync() } },
)
```

Then launch `SyncDashboardActivity`, or embed `SyncDashboardRoute(state, onTriggerSync)`
directly in your own Compose screen.

**Java interop:** every public API is callable from Java — `SyncEngine.create(...)` is
`@JvmStatic @JvmOverloads`, `RetrofitSyncAdapter`/`RoomSyncAdapter`/`WorkManagerSyncScheduler`
constructors are `@JvmOverloads`, and `ConflictResolver`/`SyncNetworkAdapter` are SAM-friendly.

### 1.6 Complete public API reference

This is every public symbol in the library, module by module — nothing omitted. For full
KDoc prose on any of these, see the generated docs:
**https://prathamesh2640.github.io/Sync-Engine-Library/**

#### `sync-core`

| Symbol | Signature | Notes |
|---|---|---|
| `SyncEngine.create` | `fun <T : SyncableEntity> create(adapter: SyncNetworkAdapter<T>, config: SyncEngineConfig = SyncEngineConfig{}, store: LocalSyncStore<T>? = null, resolver: ConflictResolver<T>? = null): SyncEngine` | Companion factory; only way to obtain an engine |
| `SyncEngine.syncState` | `val syncState: StateFlow<SyncState>` | Hot, conflated |
| `SyncEngine.stats` | `val stats: StateFlow<SyncStats>` | Hot, conflated |
| `SyncEngine.triggerSync` | `suspend fun triggerSync(): SyncResult` | Never throws (except rethrown `CancellationException` on caller cancellation) |
| `SyncEngine.close` | `fun close()` | Idempotent; cancels internal scope |
| `SyncEngineConfig` | `batchSize: Int` (default 50, max 1000), `maxRetries: Int` (default 3), `tombstoneRetentionDays: Int` (default 30), `maxConcurrentPushes: Int` (default 20), `logLevel: LogLevel` (default `NONE`) | Built via `SyncEngineConfig { }` DSL or `SyncEngineConfig.Builder` (Java); `build()` throws `IllegalArgumentException` on out-of-range values |
| `LogLevel` | enum `NONE, ERROR, WARN, INFO, DEBUG` | |
| `ConflictResolver<T>` | `fun interface { fun resolve(local: T, remote: T): T }` | SAM; must be pure, no I/O |
| `SyncNetworkAdapter<T>` | `suspend fun push(payload: List<T>): NetworkResult<Unit>`; `suspend fun pull(since: Long): NetworkResult<List<T>>`; `suspend fun delete(ids: List<String>): NetworkResult<Unit>` | Must never throw |
| `NetworkResult<T>` | sealed: `Success(data: T)`, `HttpError(code: Int, message: String)`, `NetworkError(cause: Throwable)`, `UnknownError(cause: Throwable)` | |
| `SyncableEntity` | `val id: String`, `val lastModified: Long` | `id` = client-generated UUID v4, immutable |
| `SyncState` | enum `PENDING, SYNCING, SYNCED, FAILED, CONFLICT` | See §2.1 for the transition diagram |
| `SyncMetadata` | `syncState: SyncState`, `isDeleted: Boolean = false` | Library-owned, per-entity |
| `SyncCounts` | `pending: Int`, `failed: Int`, `conflict: Int` (+ `SyncCounts.ZERO`) | |
| `SyncStats` | `pending: Int`, `failed: Int`, `conflict: Int`, `lastSyncTimestamp: Long?`, `lastError: SyncError?` (+ `SyncStats.INITIAL`) | Updated once per `triggerSync` run, not per write |
| `SyncError` | sealed: `NetworkUnavailable`, `HttpError(code: Int)`, `ConflictUnresolvable(entityId: String)`, `StorageError(cause: Throwable)` | |
| `SyncResult` | sealed: `Success(syncedCount: Int, conflictCount: Int)`, `PartialFailure(syncedCount: Int, failedCount: Int, errors: List<SyncError>)`, `Failure(error: SyncError)` | |
| `SyncScheduler` | `fun schedulePeriodicSync()`, `fun cancelSync()` | Framework-free contract |
| `LocalSyncStore<T>` | `suspend fun getPending(limit: Int): List<T>`; `suspend fun getByIds(ids: List<String>): Map<String, T>`; `suspend fun getMetadataByIds(ids: List<String>): Map<String, SyncMetadata>`; `suspend fun counts(): SyncCounts`; `suspend fun getTombstones(): List<T>`; `suspend fun upsert(entities: List<T>)`; `suspend fun markSyncState(id: String, state: SyncState)`; `suspend fun enqueue(entity: T)` (default method = `upsert` + `markSyncState(PENDING)`); `suspend fun markDeleted(id: String)`; `suspend fun hardDelete(ids: List<String>)`; `suspend fun purgeExpiredTombstones(retentionDays: Int): Int` | Must never throw across the boundary |

#### `sync-network-retrofit`

| Symbol | Signature |
|---|---|
| `RetrofitSyncAdapter<T>` | `constructor(pushCall: suspend (List<T>) -> Response<Unit>, pullCall: suspend (Long) -> Response<List<T>>, deleteCall: suspend (List<String>) -> Response<Unit>, ioDispatcher: CoroutineDispatcher = Dispatchers.IO)` — implements `SyncNetworkAdapter<T>` |

#### `sync-storage-room`

| Symbol | Signature |
|---|---|
| `RoomSyncAdapter<T>` | `constructor(database: RoomDatabase, tableName: String, metadataTable: String, rawQuery: suspend (SupportSQLiteQuery) -> List<T>, upsert: suspend (List<T>) -> Unit, idColumn: String = "id", modifiedColumn: String = "lastModified", clock: () -> Long = System::currentTimeMillis, ioDispatcher: CoroutineDispatcher = Dispatchers.IO)` — implements `LocalSyncStore<T>`. `tableName`/`metadataTable`/`idColumn`/`modifiedColumn` are validated against `^[A-Za-z_][A-Za-z0-9_]*$`; invalid names throw `IllegalArgumentException` at construction |

#### `sync-workmanager`

| Symbol | Signature |
|---|---|
| `WorkManagerSyncScheduler` | `constructor(context: Context, engineProvider: () -> SyncEngine, intervalMinutes: Long = 15, engineKey: String = "default", networkRequirement: SyncNetworkRequirement = CONNECTED, backoffPolicy: SyncBackoffPolicy = EXPONENTIAL, backoffDelayMillis: Long = WorkRequest.MIN_BACKOFF_MILLIS)` — implements `SyncScheduler`. `schedulePeriodicSync()` is idempotent (replaces, doesn't stack); `intervalMinutes` below 15 is coerced up to WorkManager's hard minimum |
| `SyncNetworkRequirement` | enum `ANY, CONNECTED, UNMETERED, NOT_ROAMING` |
| `SyncBackoffPolicy` | enum `LINEAR, EXPONENTIAL` |

#### `sync-ui-dashboard` (debug-only)

| Symbol | Signature |
|---|---|
| `SyncDashboard.install` | `fun install(state: StateFlow<SyncDashboardState>, onTriggerSync: () -> Unit, key: String = DEFAULT_KEY)` |
| `SyncDashboard.clear` | `fun clear(key: String = DEFAULT_KEY)` |
| `SyncDashboardState` | `syncState: SyncState = PENDING`, `lastSyncTimestamp: Long? = null`, `pendingCount: Int = 0`, `failedCount: Int = 0`, `conflictCount: Int = 0`, `lastError: String? = null` |
| `SyncDashboardActivity` | Launch with intent extra `SyncDashboardActivity.EXTRA_ENGINE_KEY` (optional, defaults to `SyncDashboard.DEFAULT_KEY`) |
| `SyncDashboardRoute` | `@Composable fun SyncDashboardRoute(state: StateFlow<SyncDashboardState>, onTriggerSync: () -> Unit, modifier: Modifier = Modifier)` |

### 1.7 Troubleshooting — solutions procedures

| Symptom | Likely cause | Fix |
|---|---|---|
| App crashes on first `SyncEngine.create(...)` call | Passed an Android `Context`-dependent object into `:sync-core` types, or called `create` before Room/Retrofit finished initializing | `SyncEngine.create` takes no `Context` — construct it after `adapter`/`store` are fully built, not before |
| Entities never leave `PENDING` even after `triggerSync()` returns `Success` | Forgot `store.markSyncState(id, PENDING)` (or `enqueue`) after a local insert — a bare `upsert`/DAO write does not enqueue anything | Call `store.enqueue(entity)` (or `markSyncState`) on every local create/update; `markDeleted(id)` on delete |
| R8 release build crashes with `ClassNotFoundException`/`NoSuchMethodException` referencing `SyncWorker` or `SyncDashboardActivity` | Missing/broken consumer ProGuard rules | These modules ship `consumer-rules.pro` automatically via AAR — do **not** add `-dontobfuscate` workarounds; if it still breaks, that's exactly the bug we need reported (§3) |
| `RoomSyncAdapter` construction throws `IllegalArgumentException: Invalid identifier` | `tableName`/`metadataTable`/`idColumn`/`modifiedColumn` contains anything other than letters/digits/underscore, or starts with a digit | Rename the table/column, or pass the actual Room-generated name via `idColumn`/`modifiedColumn` if `@ColumnInfo` renamed it |
| `getPending`/`counts` returns nothing though rows exist | Metadata side table was never created (used `fallbackToDestructiveMigration()` instead of a real migration), or `markSyncState`/`enqueue` was never called | Verify the `notes_sync_meta`-shaped table exists (§1.5 Step 3) and every insert path calls `enqueue`/`markSyncState` |
| WorkManager periodic job never fires | Constraints unmet (default requires `CONNECTED`), or two `WorkManagerSyncScheduler` instances created with the same default `engineKey` in the same process | Check `adb shell dumpsys jobscheduler \| grep <your.package>`; give each independent engine its own `engineKey` |
| `triggerSync()` called twice in quick succession only runs once | This is by design — single-flight (SEC-11). A concurrent call while a run is in progress returns `SyncResult.Success(0, 0)` immediately, it is not queued | Not a bug; if you need "run again right after this one," call `triggerSync()` again after the first `await`s |
| A conflict resolver's clock-skew guard doesn't stop a compromised/misconfigured server from winning every conflict | Resolver trusts `remote.lastModified` unconditionally | See §1.5 Step 4 — clamp remote timestamps that are implausibly far in the future, and treat equal/missing timestamps as local-wins |
| Entity content (title/body/etc.) shows up in logcat | You added your own logging around the adapter/store — the library itself never logs entity field values, only ids/state names/error codes at any `LogLevel` | Audit your own call sites; report as a library bug (§2.4 category) only if a `[SyncEngine]`-prefixed line itself contains entity content |

---

## 2. Testing

### 2.1 Reference: the exact state machine and result semantics you're verifying

```
 ┌─────────┐     ┌─────────┐     ┌────────┐
 │ PENDING │────►│ SYNCING │────►│ SYNCED │
 └─────────┘     └────┬────┘     └────────┘
      ▲           ┌───┴────────────────┐
      │           │                    │
      │     ┌─────▼──────┐    ┌────────▼────┐
      └─────│   FAILED   │    │  CONFLICT   │
            └────────────┘    └─────────────┘
```

`triggerSync()`'s return value is **fully determined** by what happened during the run —
this is the exact logic to check your results against (not an approximation):

| Condition | `SyncResult` | `syncState` after |
|---|---|---|
| No pending work, no errors | `Success(syncedCount=0, conflictCount=0)` | `SYNCED` |
| Everything pushed/pulled cleanly | `Success(syncedCount=N, conflictCount=C)` | `SYNCED` |
| All errors are `ConflictUnresolvable` **and** `syncedCount > 0` | `PartialFailure(syncedCount, errorCount, errors)` | `CONFLICT` |
| All errors are `ConflictUnresolvable` **and** `syncedCount == 0` | `Failure(errors.first())` | `CONFLICT` |
| Any non-conflict error present, `syncedCount == 0` | `Failure(errors.first())` | `FAILED` |
| Any non-conflict error present, `syncedCount > 0` | `PartialFailure(syncedCount, errorCount, errors)` | `FAILED` |
| `triggerSync()` called while a run is already in progress | `Success(0, 0)` immediately (no-op, not queued) | unchanged |
| `triggerSync()` called after `close()` | `Failure(SyncError.StorageError(IllegalStateException))` | unchanged |

Error mapping (`NetworkResult` → `SyncError`, exact):

| Adapter returned | Engine reports |
|---|---|
| `NetworkResult.HttpError(code, msg)` | `SyncError.HttpError(code)` |
| `NetworkResult.NetworkError(cause)` | `SyncError.NetworkUnavailable` |
| `NetworkResult.UnknownError(cause)` | `SyncError.StorageError(cause)` (yes — `UnknownError` surfaces as `StorageError`; this is a known, documented API quirk, not a bug — don't flag it) |
| A pull-phase conflict with no resolver, or a resolver that throws | `SyncError.ConflictUnresolvable(entityId)` |

A push failure is retried up to `maxRetries` (default 3) **consecutive** failures on that
entity id before being left `FAILED` for good; a single success resets the counter. Retry
counts are in-memory only — they reset on process death, so a `FAILED` entity that
survives an app restart gets a fresh set of retries.

### 2.2 Environment matrix — the actual point of this exercise

Run the full scenario list in §2.3 against **every row you can cover**. More rows =
stronger proof; even 2–3 additional real devices beyond the one already tested materially
raises confidence for the major release.

| Axis | Cover as many as you have access to |
|---|---|
| Android version | Anything from API 24 up — prioritize versions *not* already tested (we've covered API 29). API 24/25 (minSdk floor), API 31–34 (Doze/background-restriction changes), API 35/36 (latest) are all high-value |
| OEM / skin | Samsung (aggressive battery/background killing), Xiaomi/MIUI (notoriously strict on background work), stock/Pixel, any tablet |
| Architecture | arm64-v8a (most common), armeabi-v7a if you have an older device, x86_64 if testing on an emulator |
| RAM tier | A low-RAM device (₹10k-class phone / 2–3GB RAM) is high-value — process death under memory pressure is one of the least-tested paths |
| Network | Real Wi-Fi, real mobile data, real airplane mode (not just `adb`-simulated) |

### 2.3 Test protocol

Each block below is a **template scenario** — run it once per row in your device matrix, so
5 template scenarios × 4 devices = 20 actual executions. There are ~48 template scenarios
across A–H, so a 4-device matrix already produces close to 200 individual test executions;
add network-condition variants (§2.3.D is inherently online/offline paired) and it
comfortably clears several hundred. Record every execution separately in your report (§3)
— don't collapse multiple devices into one row.

Every scenario states the **exact expected output** — state values, `SyncResult` shape,
counts — per §2.1's tables. "Pass" means the actual output matches exactly, not
"seemed to work."

#### A — Install & cold start
1. Fresh install, launch. Expect: no crash in logcat, no ANR.
2. Inspect merged manifest permissions (`INTERNET`, `ACCESS_NETWORK_STATE`, and — if using `sync-workmanager` — background-work-related entries). Expect: no runtime permission prompt (all are normal-protection).
3. `SyncEngine.create(...)` called at app startup with a fresh (empty) store. Expect: `engine.stats.value` reads `SyncStats.INITIAL` (or the best-effort post-init counts if pre-existing local data exists) within a few hundred ms, no crash even if the metadata table doesn't exist yet on a truly first run.

#### B — Core CRUD / state machine (per entity type your app has)
4. Create entity → `store.enqueue(entity)` → confirm `PENDING` in your UI/DB.
5. `triggerSync()` with backend reachable and accepting. Expect: `SyncResult.Success(syncedCount=1, conflictCount=0)`; entity `SYNCED`; `engine.syncState` StateFlow emits `PENDING → SYNCING → SYNCED` in order (no skipped/reordered emissions).
6. Edit a `SYNCED` entity → confirm it flips back to `PENDING` (you must call `markSyncState`/`enqueue` again — this does not happen automatically). Sync → `SYNCED` again.
7. Delete (tombstone) via `markDeleted(id)` → `triggerSync()` → confirm the row is gone from your business-logic queries immediately, but confirm via direct DB inspection that the row (and metadata) survive locally until `SyncNetworkAdapter.delete` is confirmed by the server, then are hard-deleted. Expect zero orphaned tombstones after a clean run.
8. Run once with **zero** pending work. Expect exactly `SyncResult.Success(syncedCount=0, conflictCount=0)`.
9. Rapid double-`triggerSync()` (fire both without awaiting the first). Expect: one real run, one `Success(0, 0)` no-op from the second call — confirm via logcat timestamps that only one run's `[SyncEngine]` INFO "sync finished" line appears, not two.
10. Batch-create 60–100 entities in one pass, `batchSize` left at default (50). Expect: first `triggerSync()` drains 50, leaves the rest `PENDING`; a second run drains the remainder. Confirm `engine.stats.value.pending` reflects the correct remaining count after each run.

#### C — Conflict resolution (requires a store + resolver)
11. Create the same entity id both locally (`PENDING`) and have your test backend report a remote change for it in the next `pull`. With a resolver installed, `local.lastModified >= remote.lastModified`: expect the resolver picks local, entity ends `PENDING → SYNCED` after being pushed back, and the run's `SyncResult.Success.conflictCount` counts it once.
12. Same setup, `remote.lastModified` newer: expect remote wins, applied and pushed back the same way.
13. Same setup, **no resolver installed**: expect `SyncState.CONFLICT`, `SyncError.ConflictUnresolvable(entityId)`, and per §2.1's table — `Failure` if that was the only pending item, `PartialFailure` if other items succeeded in the same run.
14. A resolver that throws an exception: expect the engine treats it identically to "no resolver" (`ConflictUnresolvable`) — must not crash the run or propagate the exception.
15. A malicious/clock-skewed remote timestamp far in the future, with the clock-skew-guarded resolver from §1.5 Step 4: expect local wins (or the plausibility clamp kicks in) — confirm the far-future timestamp does *not* win by default and does not corrupt the pull watermark for subsequent runs (i.e., a later normal pull isn't starved).

#### D — Network conditions (online/offline pairs — run every one of these twice: once with your real backend, once with airplane mode / real connectivity loss, not simulated)
16. Backend returns HTTP 500 on push. Expect: `SyncError.HttpError(500)`, entity `FAILED`, retried on next `triggerSync()` (up to `maxRetries`).
17. Backend returns malformed/unparseable JSON on pull. Expect: `SyncError.StorageError` (per §2.1's `UnknownError→StorageError` mapping), no crash, entity states unaffected by the parse failure.
18. Backend unreachable (server down / DNS failure). Expect: `SyncError.NetworkUnavailable`, entity `FAILED`.
19. **Real airplane mode** enabled device-wide (not `adb`-simulated — note that `adb reverse`-based test traffic can bypass airplane mode since it rides the USB/adb transport, not the radio; use your app's real backend URL over real Wi-Fi/cellular for this one). Expect: `NetworkUnavailable`, graceful `FAILED`, no crash.
20. Airplane mode toggled ON mid-push (kill connectivity while a request is in flight). Expect: graceful `NetworkError`→`NetworkUnavailable`, no crash, no corrupted local state, no stuck `SYNCING`.
21. Artificial slow backend (delay past your HTTP client's timeout). Expect: timeout surfaces as `NetworkError`; confirm the single-flight lock releases cleanly — a subsequent `triggerSync()` is not permanently stuck.
22. Recover connectivity after a `FAILED` batch, `triggerSync()` again. Expect: `FAILED → PENDING → SYNCING → SYNCED` (or another retry attempt if still failing), and confirm the retry counter actually resets on a success (push the same entity to `FAILED` once, then succeed, then fail again — confirm it gets a fresh `maxRetries` count, not the depleted one from the first failure streak).

#### E — Background sync (`sync-workmanager`, if integrated)
23. `dumpsys jobscheduler | grep <your.package>` after `schedulePeriodicSync()`. Expect: the unique periodic job is registered with the constraints you configured.
24. Force-run the job (`adb shell cmd jobscheduler run -f <uid> <jobId>`) with pending work and the app **not foregrounded**. Expect: sync happens without a manual app-open.
25. `adb shell am force-stop <package>` with entities still `PENDING`, relaunch. Expect: the engine/registry re-registers on your `Application.onCreate` and the next periodic run resolves a live engine (no `Result.failure()` from a missing registry entry).
26. Real airplane mode toggled while a `CONNECTED`-constrained job is pending. Expect: job sits unsatisfied while offline, fires once reconnected — no manual intervention needed.
27. Two independent engines in the same process, each with its own `engineKey`. Expect: each gets its own periodic job (confirm both appear separately in `dumpsys jobscheduler`), triggering one never affects the other's schedule or state.

#### F — Lifecycle / process robustness
28. Rotate screen mid-sync. Expect: no duplicate engine instances, in-flight sync completes normally.
29. Home-button background the app mid-sync. Expect: sync still completes (engine runs in an app-scoped coroutine scope, not Activity-bound) — no ANR, no cancelled run.
30. `adb shell am force-stop` mid-backlog (with a store configured), relaunch. Expect: the backlog survived (real on-disk persistence, not an in-memory queue), sync resumes cleanly on the next `triggerSync()`.
31. Kill the app process during an in-flight push (not a graceful background — an actual process kill). Expect on relaunch: no corrupted local row, the entity is either still `PENDING`/`FAILED` (safe to retry) or `SYNCED` if the push had actually completed server-side before the kill — never a torn/partial write.
32. Low-memory conditions (if your test devices can simulate this, e.g. `adb shell am send-trim-memory`). Expect: no crash from the engine's coroutine scope or store during trim.

#### G — Release / R8-minified build
33. `assembleRelease`, install, launch. Expect: no crash from R8 shrink/obfuscate (this is the highest-risk category — `SyncWorker` and `SyncDashboardActivity` are instantiated reflectively).
34. Repeat a smoke subset of B (create/sync/edit/delete) and E (force the periodic job) on the release build specifically.
35. If you use `sync-ui-dashboard`, confirm it is **absent** from your release build (debug-only) and nothing crashes from a missing reference to it.

#### H — Scale / stress (push the boundaries the automated suite already covers on the JVM — worth re-confirming on real SQLite/real hardware)
36. 200+ pending entities in one backlog, default `batchSize=50`. Expect: 4 clean `triggerSync()` runs drain it fully, oldest-created entities drained first.
37. `batchSize` set to `SyncEngineConfig.MAX_BATCH_SIZE` (1000) with a backlog that size. Expect: no `SQLiteException: too many SQL variables` (the Room adapter internally chunks `IN (...)` queries below SQLite's 999-arg bind limit — this is what you're confirming holds on real on-device SQLite, not just Robolectric's).
38. `SyncEngineConfig.Builder` with an out-of-range value (`batchSize = 0`, `batchSize = 1001`, negative `maxRetries`, etc.). Expect: `IllegalArgumentException` at `.build()` time, not a silent clamp, not a crash later during a sync run.
39. `maxConcurrentPushes` set very low (e.g. 1) with a large batch. Expect: pushes are serialized, all still complete, just slower — no deadlock.
40. Many entities (10+) conflicting in the same pull batch, resolver installed. Expect: every conflict resolved independently, no cross-entity interference, `conflictCount` in the result matches exactly.

#### I — Logging invariant (SEC-06 — run alongside every scenario above, not standalone)
41. Capture `adb logcat | grep "\[SyncEngine\]"` for your entire session. Grep the captured output for every entity title/body/field value you used in testing. Expect: **zero matches** — the library must never log entity content, only ids, state names, and error codes, at any `LogLevel` including `DEBUG`.
42. Confirm no HTTP response bodies or auth headers/tokens appear in `[SyncEngine]`-prefixed log lines either.

#### J — Multi-entity-type (if your app syncs more than one entity type)
43. Two independent `SyncEngine` instances (one per entity type) running concurrently, each syncing on its own schedule. Expect: no shared-state bleed between them (each has its own queue/state machine/stats).

#### K — Java-only host (if any part of your app or a teammate's module is Java, not Kotlin)
44. Call `SyncEngine.create(...)`, `SyncEngineConfig.Builder`, `RetrofitSyncAdapter`, and implement `SyncNetworkAdapter`/`ConflictResolver` as SAM lambdas from Java. Expect: compiles and runs identically to the Kotlin path — this is a real, supported use case, not an afterthought.

#### L — Long-soak (optional but high-value if you can leave a device running)
45. Leave the app running with periodic background sync active for 24+ hours with realistic intermittent connectivity (real Wi-Fi that occasionally drops, e.g. moving in/out of range). Expect: no memory growth indicating a coroutine/scope leak, no accumulation of stuck `SYNCING` entities, periodic sync keeps firing on schedule throughout.

### 2.4 If something fails — categorize before reporting

- **Crash** (highest priority): app process dies, uncaught exception with the library on the stack. Always a library bug if the failure originates inside a `sync-*` package — the "never throw" contract makes this unambiguous.
- **Wrong state/result**: actual `SyncState`/`SyncResult`/counts don't match §2.1's table for the condition you triggered.
- **Stuck state**: `SYNCING` (or any state) never resolves after a reasonable wait with no further `triggerSync()` calls.
- **Data loss/corruption**: an entity or its sync metadata is missing, duplicated, or has fields from the wrong version after a sync.
- **Logging leak**: entity content, response bodies, or auth material in `[SyncEngine]` log lines (§2.3.I).
- **Everything else**: performance, confusing API, missing convenience — still worth reporting, just not release-blocking the same way.

---

## 3. Expected results back

We need results in a form we can verify directly — not a paragraph saying "mostly
worked." For each executed scenario, send back:

### 3.1 Device/environment info (once per device)

| Field | Example |
|---|---|
| Device model | Samsung Galaxy A14 |
| Android version / API level | Android 13 / API 33 |
| OEM skin | One UI 5.1 |
| Architecture | arm64-v8a |
| RAM | 4 GB |
| App build type tested | debug / release (R8) |
| SyncEngine version | 0.1.0 |
| Room / Retrofit / WorkManager / Coroutines versions actually used | (list, especially if different from §1.2) |
| Backend used for testing | your own test API / the mock server pattern from §1.5 / other |

### 3.2 Per-scenario result table

One row per scenario **per device** (so a 40-scenario run on 3 devices = ~120 rows).

| Scenario ID | Device | Expected (from §2) | Actual | Pass/Fail | Logcat excerpt / notes |
|---|---|---|---|---|---|
| B5 | Galaxy A14 | `Success(1,0)`, PENDING→SYNCING→SYNCED | `Success(1,0)`, same sequence | Pass | — |
| D18 | Galaxy A14 | `NetworkUnavailable`, FAILED | `NetworkUnavailable`, FAILED | Pass | — |
| … | | | | | |

For any **Fail**: include the exact `SyncResult`/`SyncState` values observed, the
`[SyncEngine]`-prefixed logcat lines around the failure (with `logLevel = DEBUG` for the
run), and — for a crash — the full stack trace (`adb logcat -b crash` or the crash dialog's
"Copy" output) plus, if possible, `adb bugreport` captured immediately after.

Zero entity content in any submitted log excerpt, please — strip titles/bodies/PII before
sending; only ids, states, and error codes should appear (this should already be true per
§2.3.I, but double-check before sharing logs externally).

### 3.3 Crash reports (if any occurred)

For each crash: full stack trace, the scenario that triggered it, device/OS from §3.1,
whether it reproduces consistently or intermittently, and — if you can pinpoint it — the
`sync-*` package/class in the stack trace closest to the top.

### 3.4 Summary

- Total scenarios executed / passed / failed, per device and overall.
- Any scenario you couldn't run and why (missing hardware, backend limitation, etc.) —
  gaps are fine to report, just be explicit rather than silently skipping them.
- Your own go/no-go read: would you ship an app built on this library today, based on
  what you saw?

### 3.5 How to send it back

A filled copy of this document (§3.2's table as a copy-pasted Markdown/CSV table is fine),
plus a zip of raw logcat captures and any crash dumps, sent back through whatever channel
we agreed on. Partial results are still useful — send what you have as you finish each
device rather than waiting to batch everything at the end.

---

**What this buys us:** every scenario above has a precise expected output grounded
directly in the engine's actual state-machine and result-mapping logic (§2.1) — not
guesswork. A clean pass across a real second integration, on hardware we haven't already
tested, is exactly the evidence needed to cut a "no known crashes" major version with
confidence. Thank you for helping get there.
