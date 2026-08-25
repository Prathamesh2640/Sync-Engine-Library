# Device info — HA1RHQ8Y

| Field | Value |
|---|---|
| Device model | Lenovo TB-X306X |
| Android version / API level | Android 10 / API 29 |
| OEM skin | stock Lenovo tablet UI |
| Architecture | arm64-v8a |
| RAM | 3.7 GB (MemTotal 3829092 kB) |
| App build types tested | debug and release (R8-minified) |
| SyncEngine version | 0.1.0 |
| Room / Retrofit / WorkManager / Coroutines versions actually used | Room 2.7.1, Retrofit 2.11.0, WorkManager 2.10.0, Coroutines 1.8.1 (matches INTEGRATION.md §1.2 exactly) |
| AGP / Kotlin actually used | AGP 9.3.1 (doc lists 9.2.1), Kotlin 2.2.0 via AGP's built-in Kotlin support, not the classic `org.jetbrains.kotlin.android` plugin (see validation/findings.md build-friction notes) |
| Backend used for testing | `validation/mock-backend/server.py` — local, stdlib-only, fault-injectable (HTTP 500 / malformed JSON / dropped connection / slow response), reached over `adb reverse tcp:8080 tcp:8080` |

**Hardware-matrix caveat:** this is the exact same device model and API level
(Lenovo TB-X306X, API 29) INTEGRATION.md states was already used for the vendor's own
`v0.1.0` testing ("161 automated tests plus a 32-scenario manual pass on one physical
tablet (Lenovo TB-X306X, Android 10)"). This run adds **zero new hardware-matrix
coverage**. Its value is a second, independent app + backend integration exercising the
same library, which is still meaningful (different code paths, different Retrofit/Gson
wiring, a from-scratch Room schema) — just not new device/OS coverage. No other physical
device or emulator was available in this environment.
