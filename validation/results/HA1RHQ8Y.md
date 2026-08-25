# Results — HA1RHQ8Y (Lenovo TB-X306X, Android 10 / API 29)

Device/environment detail: `validation/device-info/HA1RHQ8Y.md`.
Full narrative for the manual/hardware scenarios (E,F,G,D,L): `validation/manual/scenarios.md`.
Root-cause detail for every Fail and every fix applied: `validation/findings.md`.

Scenario IDs match INTEGRATION.md §2.3 exactly. "Automated" = instrumented test in
`app/src/androidTest/java/com/project/confidant/sync/`; "Manual" = executed directly via
`adb` this session (not merely documented as a runbook for someone else).

| Scenario ID | Source | Expected | Actual | Pass/Fail | Notes |
|---|---|---|---|---|---|
| A1 | Automated | No crash/ANR on fresh launch | No crash | Pass | `CoreScenariosTest.a1_coldStartNoCrash` |
| A2 | Automated | INTERNET + ACCESS_NETWORK_STATE in merged manifest | Present (after app-level fix) | Pass | Originally Fail until fixed — see FINDING-001 |
| A3 | Automated | `stats` reads `INITIAL`-equivalent on fresh store | pending=0 failed=0 conflict=0 | Pass | |
| B4/B5 | Automated | `Success(1,0)`, entity SYNCED | `Success(1,0)` | Pass | |
| B6 | Automated | Edit flips back to PENDING, re-syncs | Confirmed | Pass | |
| B7 | Automated | Tombstone survives until confirmed delete, then hard-deleted | Confirmed | Pass | |
| B8 | Automated | `Success(0,0)` with zero pending | `Success(0,0)` | Pass | |
| B9 | Automated | Concurrent `triggerSync()` is single-flight | One real run + one no-op `Success(0,0)` | Pass | |
| B10 | Automated | 75 pending drains in 2 runs at batchSize=50 | 50 then 25, then 0 | Pass | |
| C11 | Automated | Local newer than remote: local wins | Local wins | Pass | |
| C12 | Automated | Remote newer than local: remote wins | Remote wins | Pass | |
| C13 | Automated | No resolver: `ConflictUnresolvable`, state CONFLICT | Confirmed | Pass | |
| C14 | Automated | Throwing resolver treated as unresolvable, no crash | Confirmed | Pass | |
| C15 | Automated | Clock-skewed future remote does not win | Local wins (after app-level resolver fix) | Pass | Originally Fail — see FINDING-003, a bug in INTEGRATION.md's own worked example |
| D16 | Automated | HTTP 500 -> `HttpError(500)`, FAILED | Confirmed | Pass | |
| D17 | Automated | Malformed JSON -> `StorageError`, no crash | Confirmed | Pass | Needed test isolation fix, see findings.md |
| D18 | Automated | Backend unreachable -> `NetworkUnavailable`, FAILED | Confirmed | Pass | |
| D19 | Manual | Real airplane mode -> `NetworkUnavailable`, graceful | — | **Not run** | Needs real backend over real network; this rig's backend rides adb reverse/USB, immune to airplane mode |
| D20 | Manual | Airplane mode mid-push -> graceful `NetworkUnavailable`, no stuck SYNCING | — | **Not run** | Same reason as D19 |
| D21 | Automated | Timeout -> `Failure`, single-flight lock releases | Confirmed (130s test, timeout then recovery) | Pass | |
| D22 | Automated | Retry counter resets after a success between failure streaks | Confirmed | Pass | |
| E23 | Manual | Periodic job registered | Present via `dumpsys jobscheduler` | Pass | |
| E24 | Manual | Background sync fires without foregrounding | Fired unattended during earlier smoke test | Pass (incidental) | Not a direct `jobscheduler run -f` execution |
| E25 | Manual | Registry re-resolves after force-stop + relaunch | Confirmed, backlog intact | Pass | Combined with F30 |
| E26 | Manual | Job waits offline, fires on reconnect (real airplane mode) | — | **Not run** | Same USB-bypass reason as D19/D20 |
| E27 | Manual | Two engines, independent jobs | — | **N/A** | App has one entity type; condition in INTEGRATION.md §2.3.E doesn't apply |
| F28 | Manual | Rotate mid-sync: no duplicate engine, sync completes | — | **Not run** | Time budget |
| F29 | Manual | Background mid-sync: sync still completes | Consistent with E24's incidental evidence | Pass (incidental) | |
| F30 | Manual | Force-stop mid-backlog: backlog survives, resumes | Confirmed, 4/4 notes survived, `pending=3` correct | Pass | Evidence: `validation/evidence-f30-*.png` |
| F31 | Manual | Kill mid-push: no torn/partial write | — | **Not run** | `kill -9` needs root (unavailable); `run-as` needs a debuggable build (release APK under test wasn't). F30's force-stop is the closest available proxy and passed, but is not the same guarantee |
| F32 | Manual | Low-memory trim: no crash | — | **Not run** | Time budget |
| G33 | Manual | R8-minified release: no crash | Confirmed, clean launch + first sync | Pass | R8 minification left ON deliberately in build config |
| G34 | Manual | Smoke subset of B + E on release build | Add+enqueue+sync confirmed on release build | Pass | E-subset (job force-run) not repeated on release specifically |
| G35 | Manual | sync-ui-dashboard absent from release | Never integrated at all | **N/A** | Deliberate scope cut, not tested by omission |
| H36 | Automated | 200 pending drains in exactly 4 runs at batchSize=50 | 4 runs | Pass | |
| H37 | — | batchSize=MAX_BATCH_SIZE (1000), no SQLite bind-limit error | — | **Not run** | Not automated in this pass — scope cut for time, see plan.md's task list |
| H38 | Automated | Out-of-range config throws `IllegalArgumentException` at `build()` | Confirmed for batchSize=0 and maxRetries=-1 | Pass | |
| H39 | — | `maxConcurrentPushes=1` serializes without deadlock | — | **Not run** | Scope cut for time |
| H40 | — | 10+ concurrent conflicts resolved independently | — | **Not run** | Scope cut for time |
| I41 | Automated | Zero entity content in `[SyncEngine]` logcat lines | Confirmed, no leak of unique test marker | Pass | |
| I42 | — | No HTTP bodies/auth headers in `[SyncEngine]` lines | — | **Not run** | Not separately automated; I41's grep would have caught obvious cases but this wasn't isolated as its own assertion |
| J43 | Automated | Two independent engines, no shared-state bleed | — | **N/A** | INTEGRATION.md §2.3.J is conditional on multiple entity types; this app has one (Note) |
| K44 | Automated | Full API surface constructible/usable from Java | Confirmed (with lambdas, not method references — see findings.md) | Pass | |
| L45 | Manual | 24h+ soak, no leak, periodic sync keeps firing | — | **Not run** | No unattended 24h window in this session |

**Totals:** 25 automated tests, all Pass. Manual: 6 Pass (E23, E25, F30, G33, G34, plus
E24/F29 as incidental-evidence Pass), 3 N/A (E27, G35, J43 — already counted once each
above; J43/E27 share the same root cause), 10 explicitly not run (D19, D20, E26, F28,
F31, F32, H37, H39, H40, I42, L45).
