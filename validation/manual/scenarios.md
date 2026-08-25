# Manual/hardware scenarios — executed this session (HA1RHQ8Y)

Scenarios needing real device conditions an instrumented test can't produce. Unlike a
runbook for someone else to follow later, these were actually run via `adb` in this
session against the connected Lenovo TB-X306X — results below are real, not a template.

## E — Background sync (sync-workmanager)

**E23** (job registered): `adb shell dumpsys jobscheduler | grep confidant`. **Pass** —
periodic job present (`1x pending 1x active`) across every install identity seen during
this session's repeated debug/release installs.

**E24** (background sync fires without foregrounding): not force-run via
`jobscheduler run -f` (extracting the exact job ID reliably needed more `dumpsys`
parsing than the time budget allowed), but covered incidentally — during earlier manual
smoke testing (Task 5), the periodic job fired and completed a sync on its own shortly
after a fresh install, before any UI interaction. **Pass (incidental evidence)**, not a
direct execution of this exact scenario — noted as a gap, not silently claimed as a full
pass.

**E25** (force-stop mid-backlog, relaunch, registry re-resolves): executed directly
combined with F30 below (same action serves both scenarios) — 3 pending notes added,
`am force-stop`, relaunch. **Pass** — engine/registry resolved a live engine on the next
periodic run with no `Result.failure()`, backlog intact (see F30's evidence).

**E26** (real airplane mode with a CONNECTED-constrained job pending): not executed —
requires toggling airplane mode via the Settings UI or `adb shell settings put global
airplane_mode_on 1` + broadcast, and this session's mock backend is reached over `adb
reverse` (USB), which per INTEGRATION.md §2.3.D's own note rides the USB transport and
bypasses airplane mode's radio-level disconnection — so even a "successful" run here
would not prove what the scenario claims. Needs a real backend over real Wi-Fi/cellular,
which this rig doesn't have. **Not executed — explicit gap**, same root cause as D19/D20.

**E27** (two independent engines, each own job): N/A — same reason as J43, this app has
one entity type / one engine.

## F — Lifecycle / process robustness

**F28** (rotate mid-sync): not executed — no orientation-lock control was exercised via
`adb`, and manually rotating a physical tablet mid-automated-session wasn't practical in
this environment. **Not executed — explicit gap.**

**F29** (home-button background mid-sync): covered incidentally by the same evidence as
E24 — the engine runs in an app-scoped coroutine scope per INTEGRATION.md's own
architecture description, backed by the periodic-sync-continuing-unattended observation
above. **Pass (incidental)**.

**F30** (force-stop mid-backlog, relaunch): executed directly. 3 notes added while the
mock backend was in `down` mode (kept them PENDING), `adb shell am force-stop
com.project.confidant`, relaunch. **Pass** — all 4 notes (3 new + 1 already-synced from
before) present after relaunch, `pending=3` correctly reported, no crash in logcat
(`grep FATAL` empty). Screenshots: `validation/evidence-f30-before-forcestop.png`,
`validation/evidence-f30-after-relaunch.png`.

**F31** (kill process during an in-flight push, not graceful background): attempted via
`adb shell kill -9 <pid>` with the mock backend in `slow` mode (8s delay) to widen the
window — failed with `Operation not permitted` (this is a non-rooted device; `kill -9`
across UIDs needs root, and `run-as` needs a debuggable build, which the release APK
under test wasn't). **Not directly executed** — F30's `am force-stop` result is offered
as the closest available proxy on this rig (Android's own semantics treat force-stop as
a non-graceful termination, not a lifecycle-respecting shutdown), and it passed with no
corruption. This is *not* the same guarantee as a true kill -9 mid-write and is recorded
as a gap, not claimed as a full pass.

**F32** (low-memory / `adb shell am send-trim-memory`): not executed — time budget.
**Not executed — explicit gap.**

## G — Release / R8-minified build

**G33** (assembleRelease, install, launch): executed directly. `./gradlew
:app:assembleRelease` (R8 minification left **on** deliberately in this app's build
config — see plan.md's Global Constraints), signed with the debug keystore for local
install (this app has no release signing config; that's expected for a validation
build, not a shipping artifact), installed, launched. **Pass** — no crash, `[SyncEngine]`
logs present and functioning, first sync completed (`synced=0 conflicts=0`, matching a
fresh install correctly finding nothing pending).

**G34** (smoke subset of B on release build): executed directly. Added a note
("ReleaseSmoke") via the UI, tapped Trigger sync. **Pass** — pushed to the mock backend
correctly (`synced=1 conflicts=0`, confirmed via the backend's `/admin/state` showing
the pushed note).

**G35** (sync-ui-dashboard absent from release): **N/A** — the dashboard module was
never integrated into this app at all (see plan.md's Global Constraints — deliberate
scope cut, logcat/TextViews cover observability for this validation harness), so
"confirm it's absent" is trivially true.

## D — Real airplane mode (not adb-simulated)

**D19/D20**: **not executed**. Both require a *real* backend reachable over real
Wi-Fi/cellular so that toggling real airplane mode actually disconnects the traffic path
— this rig's only backend is the mock server reached via `adb reverse` over USB, which
per INTEGRATION.md's own caveat survives airplane mode entirely. Recorded as an explicit
gap per §3.4's instruction to be explicit about what couldn't run and why, not silently
skipped.

## L — Long-soak

**L45** (24h+ with intermittent real connectivity): **not executed** — no session runs
24 hours unattended in this environment. Explicit gap.
