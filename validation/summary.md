# Summary — SyncEngine 0.1.0 validation via Confidant

Full detail: `validation/results/HA1RHQ8Y.md` (scenario table), `validation/findings.md`
(root-caused bugs and their fixes), `validation/manual/scenarios.md` (manual-scenario
narrative), `validation/device-info/HA1RHQ8Y.md` (environment).

## Totals

- **35 scenarios attempted or explicitly scoped**, across every category A–L in
  INTEGRATION.md §2.3.
- **31 executed** (25 automated instrumented tests + 6 manual `adb`-driven checks), **all
  passed** after fixes were applied where needed.
- **3 marked N/A** (E27, G35, J43) — the app's own architecture doesn't exercise what
  those scenarios test (single entity type; dashboard module never integrated).
- **10 explicitly not run**: D19, D20, E26 (need a real backend over real network — this
  rig's mock backend rides `adb reverse`/USB and is immune to airplane mode, exactly the
  failure mode INTEGRATION.md itself warns about), F28, F31, F32 (F31 partially covered
  by a proxy — see below), H37, H39, H40, I42, L45 (scope/time cuts, not silently
  dropped — each has a one-line reason in `validation/results/HA1RHQ8Y.md`).

## Real bugs found and fixed (full detail: validation/findings.md)

1. **FINDING-001**: none of the four SyncEngine 0.1.0 AARs declare the `INTERNET`
   permission, contradicting INTEGRATION.md §1.3's explicit claim it's inherited via
   manifest merging. Every sync silently failed as `NetworkUnavailable` until added
   manually. This is the single highest-impact finding — a consuming app following
   §1.3 literally gets a library that appears completely non-functional.
2. **FINDING-002**: `notes_sync_meta` (created via a Room `Migration` per §1.5 Step 3)
   never gets created on a fresh install — Room only runs migrations on version
   upgrades, not on `onCreate`. First symptom is a `SQLiteException` on the very first
   cold start. Not a library bug, but INTEGRATION.md's own integration walkthrough
   doesn't warn about it and a reader following it exactly hits this.
3. **FINDING-003**: INTEGRATION.md §1.5 Step 4's own worked example for the clock-skew
   guard resolver clamps to `now` instead of `local.lastModified`, which means the
   documented "local wins" guarantee (scenario C15) never actually holds — reproduced,
   root-caused, and fixed in this app's resolver.

## Go/no-go

**Conditional go** — with the three findings above fixed at the integration-guide level
(not just worked around in this one app), SyncEngine 0.1.0's actual sync engine (state
machine, conflict resolution once correctly wired, retry/single-flight semantics, R8
compatibility, migration-based storage) held up cleanly across every scenario this rig
could exercise, including a full R8-minified release build with no reflection breakage.

What this run does **not** buy: new hardware-matrix coverage (this device duplicates the
vendor's own already-tested model+API exactly), coverage of the real-radio network
scenarios (D19/D20/E26), or a long-duration soak (L45). Those need either different
hardware or a longer/differently-resourced session, not a different verdict on the
library itself.

If I were shipping an app on this library today: yes, with INTEGRATION.md §1.3 and §1.5
Step 4 corrected first (both are one-line fixes on the documentation side), and with the
understanding that D19/D20/L45 remain unverified by any of the testing done to date
(neither the vendor's original pass nor this one covered real-radio airplane mode or a
long soak, per INTEGRATION.md's own description of what v0.1.0's existing coverage is).
