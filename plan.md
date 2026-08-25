# Plan — outstanding validation scenarios

Tracks the scenarios from the 0.1.0 external validation pass (`validation/`) that
were never executed. Source of truth: `validation/summary.md` ("10 explicitly not
run" — the list below has 11 IDs; F31 is partially covered by a proxy scenario, not
a clean miss, which is likely why the summary's own count reads 10) and
`validation/results/HA1RHQ8Y.md` (full scenario table, all IDs match
`INTEGRATION.md` §2.3 — note `INTEGRATION.md` itself was reverted from the repo,
so these IDs currently only resolve against the validation folder and this file).

None of these blocked the 0.1.1 patch release — they're coverage gaps, not known
bugs. `validation/summary.md`'s go/no-go verdict already scopes them as needing
different hardware or a longer/differently-resourced session, not a different
verdict on the library.

## Needs a real backend over a real network

The validation rig's mock backend rode `adb reverse`/USB, which airplane mode
doesn't touch — these three need genuine radio-off testing, not adb tricks.

- [ ] **D19** — Real airplane mode mid-idle → `NetworkUnavailable`, graceful (no crash)
- [ ] **D20** — Airplane mode mid-push → graceful `NetworkUnavailable`, no entity stuck in `SYNCING`
- [ ] **E26** — WorkManager job waits while offline, fires on reconnect (real airplane mode)

## Needs root or a debuggable build

- [ ] **F31** — Kill mid-push (`kill -9`) → no torn/partial write. Blocked last time: `kill -9`
      needs root (unavailable on the test device), `run-as` needs a debuggable build (the
      release APK under test wasn't). F30 (force-stop) passed as the closest available
      proxy but is not the same guarantee — a force-stop is a graceful signal, `kill -9` isn't.

## Scope/time cuts — just need a session, no new capability

- [ ] **F28** — Rotate mid-sync: no duplicate engine instance, sync still completes
- [ ] **F32** — Low-memory trim (`adb shell am send-trim-memory`): no crash
- [ ] **H37** — `batchSize = MAX_BATCH_SIZE` (1000): no SQLite bind-variable-limit error
- [ ] **H39** — `maxConcurrentPushes = 1`: serializes pushes, no deadlock
- [ ] **H40** — 10+ concurrent conflicts, each resolved independently (no cross-talk)
- [ ] **I42** — No HTTP request bodies or auth headers leak into `[SyncEngine]` log lines
      (I41 already confirmed no *entity content* leaks — this is the same check, narrowed
      to request bodies/headers specifically, never isolated as its own assertion)
- [ ] **L45** — 24h+ soak: no leak, periodic sync keeps firing across the full window

## When to actually run these

Per `GO_LIVE_GUIDE.md` Part 2.B: not every release needs a full device pass. Bundle
these into whichever future release next touches `:sync-workmanager`,
`:sync-storage-room`, or a `consumer-rules.pro` file — that's already the trigger
condition for a device smoke pass, so ride it rather than scheduling a standalone
session. Update this checklist in the same PR that closes any of these out, citing
the new evidence the same way `validation/findings.md` does.
