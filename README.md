# SyncEngine

An offline-first data synchronisation library for Android.

SyncEngine handles the hard parts of offline-first development: queueing local writes when the network is unavailable, pushing them to the server when connectivity returns, detecting conflicts between local and remote versions, and keeping your local database consistent throughout. Your app code works against plain Kotlin data classes — the library does the rest.

---

## Is this library right for your app?

**Use SyncEngine if your app:**
- Stores user data locally and needs it synced to a backend
- Must work fully offline and sync automatically when back online
- Uses Room for local persistence
- Uses Retrofit or Ktor for network calls
- Needs configurable conflict resolution (last-write-wins, server-wins, or custom logic)

**Do not use SyncEngine if:**
- Your app is purely online with no local persistence requirement
- You only need one-way data download (no local writes to sync back)
- You require real-time sync (WebSockets/push). SyncEngine is pull/push on demand, not streaming.

---

## Requirements

| Requirement | Minimum |
|---|---|
| Android minSdk | 24 (Android 7.0) |
| Kotlin | 2.1.0 |
| Coroutines | 1.8.1 |
| Room | 2.6.1 (optional — only if using `:sync-storage-room`) |
| Retrofit | 2.x (optional — only if using `:sync-network-retrofit`) |

---

## Module structure

SyncEngine is split into focused, independently consumable modules. Import only what you need.

```
:sync-core               — interfaces, models, state machine (required)
:sync-storage-room       — Room-based local persistence adapter
:sync-network-retrofit   — Retrofit-based network adapter
:sync-ui-dashboard       — debug-only Compose dashboard (not for production)
:sample-app              — reference implementation (not published)
```

**Dependency graph — no circular dependencies, no sibling module imports:**
```
sample-app
    └── all modules

sync-ui-dashboard      → sync-core
sync-network-retrofit  → sync-core
sync-storage-room      → sync-core
sync-core              → Kotlin stdlib + Coroutines only
```

---

## How it works

### 1. The entity contract

Every data class you want to sync implements `SyncableEntity`:

```kotlin
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey
    override val id: String = UUID.randomUUID().toString(),
    val title: String,
    val body: String,
    override val lastModified: Long = System.currentTimeMillis(),
    override val syncState: SyncState = SyncState.PENDING,
    override val isDeleted: Boolean = false
) : SyncableEntity
```

Three things to know:
- `id` must be a **UUID v4** generated client-side at creation time. It never changes. It is the idempotency key for all network requests.
- `lastModified` must be updated to `System.currentTimeMillis()` every time any field changes. The default conflict resolver uses this to pick the winner.
- `isDeleted` enables **soft deletes**. Never remove a row from the database directly — set `isDeleted = true` and let SyncEngine push the tombstone to the server.

### 2. The sync lifecycle

Every entity moves through a defined state machine:

```
PENDING ──► SYNCING ──► SYNCED
                ├──► FAILED    (retried with backoff → PENDING)
                └──► CONFLICT  (resolved by ConflictResolver → PENDING)
```

`PENDING` is the starting state for any new or modified entity. The library manages all transitions — your app code never writes `syncState` directly.

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
    data class Success<out T>(val data: T)              // 2xx — carries the payload
    data class HttpError(val code: Int, val message: String)  // server reached, non-2xx
    data class NetworkError(val cause: Throwable)       // transport failure — retryable
    data class UnknownError(val cause: Throwable)       // e.g. a parse failure
}
```

### 4. Conflict resolution

`ConflictResolver<T : SyncableEntity>` is a single-method (`fun`) interface, so a strategy can be
a lambda or a class. It must be pure — no I/O, no mutation of its arguments.

```kotlin
// Last-Write-Wins: the copy with the newer timestamp survives.
val lastWriteWins = ConflictResolver<Note> { local, remote ->
    if (local.lastModified >= remote.lastModified) local else remote
}

// Server-Wins: remote always wins.
val serverWins = ConflictResolver<Note> { _, remote -> remote }

// Custom merge: keep remote's fields but the latest timestamp of the two.
val merge = ConflictResolver<Note> { local, remote ->
    remote.copy(lastModified = maxOf(local.lastModified, remote.lastModified))
}
```

These three strategies (last-write-wins, server-wins, custom merge) are demonstrated end-to-end in
the sample app.

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

The engine is configured with a type-safe DSL (all options have defaults, so `SyncEngineConfig {}`
is valid):

```kotlin
val config = SyncEngineConfig {
    batchSize = 100                 // default 50  — entities per network round trip
    maxRetries = 5                  // default 3   — backoff retries before giving up
    tombstoneRetentionDays = 30     // default 30  — how long deletes are kept locally
    logLevel = LogLevel.DEBUG       // default NONE — NONE < ERROR < WARN < INFO < DEBUG
}
```

Java callers use the equivalent builder: `new SyncEngineConfig.Builder().setBatchSize(100).build()`.

---

## Quick start

### Step 1 — Add dependencies

```kotlin
// settings.gradle.kts — if consuming from local Maven
includeBuild("../Sync-Engine-Library")

// app/build.gradle.kts
dependencies {
    implementation("io.github.prathamesh2640.sync:sync-core:1.0.0")
    implementation("io.github.prathamesh2640.sync:sync-storage-room:1.0.0")
    implementation("io.github.prathamesh2640.sync:sync-network-retrofit:1.0.0")

    // Debug builds only — never ship the dashboard in production
    debugImplementation("io.github.prathamesh2640.sync:sync-ui-dashboard:1.0.0")
}
```

### Step 2 — Make your entity implement `SyncableEntity`

See the example in **section 1** above.

### Step 3