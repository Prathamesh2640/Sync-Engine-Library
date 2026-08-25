# Findings log

Running log of concrete, reproduced issues found while integrating and validating
SyncEngine 0.1.0, in the order they were hit. Each entry has enough detail to reproduce
independently (per INTEGRATION.md §3 — proof, not a summary to trust). Formal
scenario-by-scenario pass/fail tables live in `validation/results/` once the full
instrumented suite runs (plan.md Task 12); this file is for things worth flagging as
soon as they're found, categorized per INTEGRATION.md §2.4.

---

## FINDING-001 — `INTERNET` permission is not present in any 0.1.0 AAR manifest

**Category:** Wrong state/result vs. documentation (§2.4 "everything else" — a
documentation/integration-blocker bug, not a crash, but it makes the library
non-functional out of the box).

**Claim in INTEGRATION.md §1.3:**
> Permissions (inherited automatically via manifest merging — you don't need to add
> these yourself, but verify they land in your merged manifest):
> `<uses-permission android:name="android.permission.INTERNET" />`
> `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`

**Actual:** Neither permission is declared in any of the four resolved 0.1.0 AAR
manifests. Reproduced by unzipping each resolved AAR and inspecting
`AndroidManifest.xml` directly:

```
sync-core-0.1.0.aar/AndroidManifest.xml            -> no uses-permission entries
sync-network-retrofit-0.1.0.aar/AndroidManifest.xml -> no uses-permission entries
sync-storage-room-0.1.0.aar/AndroidManifest.xml     -> no uses-permission entries
sync-workmanager-0.1.0.aar/AndroidManifest.xml      -> no uses-permission entries
```

`adb shell dumpsys package com.project.confidant | grep -A10 "requested permissions"`
on the merged, installed APK (before this app added the permissions itself) showed
only `ACCESS_NETWORK_STATE` (contributed by WorkManager's own manifest, unrelated to
sync-workmanager) plus WorkManager's usual `WAKE_LOCK` / `RECEIVE_BOOT_COMPLETED` /
`FOREGROUND_SERVICE`. `INTERNET` was absent entirely.

**Effect:** every `triggerSync()` call fails with `SyncError.NetworkUnavailable` even
against a fully reachable backend, with no indication in the error that it's a missing
permission rather than an actual connectivity problem — logcat shows
`[SyncEngine] ERROR: sync error: NetworkUnavailable` and nothing more specific.
Confirmed via the mock backend: `curl` to the same URL from the host succeeded (200)
while the app, at the exact same time, still reported `NetworkUnavailable`.

**Fix applied in this app:** added both permissions explicitly to
`app/src/main/AndroidManifest.xml` (see the comment there citing this finding).
Confirmed this resolves it — same scenario, same mock backend, now
`[SyncEngine] INFO: sync finished: synced=1 conflicts=0`.

**This is directly what INTEGRATION.md §2.3 scenario A2 exists to catch** — it will be
recorded formally as a Fail there too once the automated suite runs (plan.md Task 6).

---

## FINDING-002 — `notes_sync_meta` never gets created on a *fresh install* (app-level bug, not a library bug)

**Category:** Wrong state/result — self-inflicted, but worth recording since
INTEGRATION.md's own integration walkthrough (§1.5 Step 3) doesn't warn about it, and a
reader following the doc literally would hit the same thing.

**Root cause:** `notes_sync_meta` is created inside a Room `Migration(1, 2)` object, per
§1.5 Step 3's instruction. Room only *runs* `Migration` objects when opening an
*existing* database at a lower version than the code declares. A brand-new install has
no existing database, so Room takes the `onCreate` path instead (building the schema
straight from the `@Entity` list) and the migration never executes — `notes_sync_meta`
simply doesn't exist. First symptom: `[SyncEngine] WARN: initial counts unavailable:
SQLiteException` on the very first cold start after a fresh install.

**Fix applied:** added a `RoomDatabase.Callback.onCreate` that runs the same
`CREATE TABLE IF NOT EXISTS notes_sync_meta (...)` statement, so both the fresh-install
path and the upgrade-migration path create it. See `app/src/main/java/com/project/confidant/data/AppDatabase.kt`.

**Recommendation for INTEGRATION.md:** §1.5 Step 3 should say explicitly that the same
`CREATE TABLE IF NOT EXISTS` needs to run from a `RoomDatabase.Callback.onCreate` too,
not just the `Migration` — otherwise every fresh install of every app that follows the
guide as written hits this on first launch.

---

## FINDING-003 — INTEGRATION.md 1.5 Step 4's own clock-skew clamp example does not make local win

**Category:** Wrong state/result vs. documentation.

**Claim (INTEGRATION.md 1.5 Step 4):**
```kotlin
val resolver = ConflictResolver<Note> { local, remote ->
    val now = System.currentTimeMillis()
    val plausibleRemote = if (remote.lastModified > now + 5 * 60_000) now else remote.lastModified
    if (local.lastModified >= plausibleRemote) local else remote
}
```
with the stated expectation (2.3 scenario C15): "a far-future timestamp does *not* win by
default... confirm the far-future timestamp does not win".

**Actual:** transcribed verbatim into `SyncModule.resolver` and exercised via
`ConflictScenariosTest.c15_clockSkewedFutureRemoteDoesNotWin`, remote won every time.
Root cause: `plausibleRemote` clamps to `now` (the resolver's own wall-clock instant at
invocation time). `local.lastModified` was always set at least a few milliseconds
*earlier* (the note was created, then enqueued, then a sync round-trip happened before
the resolver runs) — so `local.lastModified >= plausibleRemote` is `local.lastModified >=
now`, which is false for any entity that already exists. The clamp doesn't fail to guard
against the far-future value; it substitutes a *different* value (`now`) that still beats
every possible `local.lastModified`, so remote wins regardless of the clamp.

**Fix applied:** clamp to `local.lastModified` instead of `now` — see
`app/src/main/java/com/project/confidant/sync/SyncModule.kt`. This makes the `>=`
comparison an equality, so local wins as C15 expects, while still preventing the
clock-skewed value from being applied. Re-ran `c15_clockSkewedFutureRemoteDoesNotWin`
after the fix: passes.

**Recommendation for INTEGRATION.md:** fix the worked example in 1.5 Step 4 — clamp to
`local.lastModified`, not `now`.

---

## Test-isolation note (inconclusive — not asserted as a library bug)

Running the full instrumented suite with every test sharing `ConfidantApp`'s single
long-lived `SyncEngine` instance (`app.engine`/`SyncModule.store`), one test
(`d17_malformedJsonOnPull`, which expects *zero* pending work) intermittently saw
`syncedCount=1` on a run where nothing in that test enqueued anything — after a prior
test (`d16_http500OnPush`) had left one entity `FAILED`, and after `@Before` cleared both
`notes` and `notes_sync_meta` via raw SQL. Whether this is the engine retaining
in-memory queue state across calls that isn't invalidated by an external raw-SQL wipe, or
something else, wasn't root-caused — clearing a live engine's backing tables via raw SQL
bypassing its own store API is not something a real app does, so this may not reflect
real-world behavior at all. Worked around by giving `d17` its own freshly-constructed
engine/store instead of chasing the internals further (see the test's own comment). Flagged
here rather than silently dropped, per INTEGRATION.md 3.4's "gaps are fine, be explicit."

---

## Build/tooling friction (not library bugs — recorded because they cost real time and are worth knowing about for anyone repeating this on AGP 9.x)

- AGP 9.3.1's built-in Kotlin support conflicts with the classic
  `org.jetbrains.kotlin.android` plugin (`Cannot add extension with name 'kotlin'`) —
  don't apply both.
- KSP's source-set registration needs `android.disallowKotlinSourceSets=false` in
  `gradle.properties` under AGP 9's built-in Kotlin, or `processDebugResources`-adjacent
  tasks fail with "Using kotlin.sourceSets DSL... is not allowed".
- Kotlin's compile classpath under built-in Kotlin did not resolve
  `androidx.activity.EdgeToEdge` even though the same class compiled fine from Java in
  the same module and is confirmed present on `debugCompileClasspath`. Worked around by
  dropping the (cosmetic, unrelated to this task) `EdgeToEdge.enable()` call rather than
  chasing a preview-AGP-feature classpath bug further.
