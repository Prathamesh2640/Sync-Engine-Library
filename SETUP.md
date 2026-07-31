# SyncEngine — Developer Setup Guide

Everything you need to get the project running from a fresh clone.

---

## Prerequisites

| Tool | Minimum Version | Notes |
|---|---|---|
| Android Studio | Meerkat (2024.3.1) or later | Hedgehog+ works too |
| JDK | 17 | Android Studio bundles JDK 17 — use that |
| Android SDK | API 24 (minSdk) + API 36 (targetSdk) | Install via SDK Manager |
| Android Emulator | API 24+ system image | For running sample-app |
| Git | 2.x | Any recent version |

> **Do not use JDK 21 unless Android Studio ships it.** The Gradle wrapper is configured for JDK 17.
> Using a mismatched JDK causes subtle build failures.

---

## 1. Clone and open

```bash
git clone <your-repo-url> Sync-Engine-Library
```

Then in Android Studio:

```
File → Open → select the Sync-Engine-Library folder → OK
```

Android Studio will detect the Gradle project automatically.

---

## 2. First Gradle sync

When the project opens, Android Studio will prompt you to sync Gradle. Click **Sync Now**.

If it does not prompt automatically:
```
View → Tool Windows → Gradle → click the Sync button (elephant icon with refresh arrows)
```

A successful sync shows no red errors in the **Build** tab and the module tree in the **Project** panel shows all modules: `sync-core`, `sync-storage-room`, `sync-network-retrofit`, `sync-workmanager`, `sync-ui-dashboard`, `sample-app`.

**Common sync issues:**

| Error | Fix |
|---|---|
| `SDK location not found` | Create `local.properties` at project root with `sdk.dir=C:\\Users\\YourName\\AppData\\Local\\Android\\Sdk` |
| `Unsupported class file major version` | Go to **File → Settings → Build → Gradle** and set Gradle JDK to the bundled JDK 17 |
| `Could not resolve ...` | Check your internet connection; corporate proxies sometimes block Maven Central |

---

## 3. Install missing SDK components

Open **SDK Manager** (`Tools → SDK Manager`):

- **SDK Platforms tab:** Install API 24 and API 36 (tick "Show Package Details" if needed)
- **SDK Tools tab:** Ensure `Android Emulator`, `Android SDK Build-Tools`, and `Android SDK Platform-Tools` are installed

---

## 4. Create an emulator (for GUI testing)

Open **Device Manager** (`Tools → Device Manager → + Create Virtual Device`):

1. Choose **Phone → Pixel 6** (or any phone profile)
2. System Image: **API 33 or 34** (x86_64 recommended for speed)
3. AVD Name: `SyncEngine_Test`
4. Finish → the device appears in Device Manager

Start it: click the **play ▶** button next to the AVD name.

---

## 5. Run the sample-app

The sample app is a full Compose notes app wired to the whole library stack (Room store, in-memory
fake backend, WorkManager scheduling, and — in debug builds — the sync dashboard).

1. In Android Studio, select `sample-app` in the run configuration dropdown (top toolbar)
2. Select your emulator from the device dropdown
3. Click **Run ▶** (or `Shift+F10`)
4. The app installs and launches on the emulator

**Walkthrough:** add notes with **+** (they show `PENDING`) → tap **Sync** (they move to `SYNCED`) →
toggle **Online** off, edit a note (`PENDING`), tap **Sync** (it goes `FAILED`) → toggle back on and
sync → tap **Conflict** on a note, pick a resolver, and **Sync** to see it resolved. The **Dashboard**
button (debug builds only) opens the live counters.

---

## 6. Run unit tests

### Run all tests across all modules
```bash
# In the Android Studio Terminal (View → Tool Windows → Terminal)
./gradlew test
```

Look for `BUILD SUCCESSFUL` and check `*/build/reports/tests/test/index.html` for detailed results.

### Run tests for a specific module
```bash
./gradlew :sync-core:test
./gradlew :sync-storage-room:test   # includes the Room adapter tests (Robolectric, no device)
./gradlew :sync-network-retrofit:test
./gradlew :sync-workmanager:test    # WorkManager tests via WorkManagerTestInitHelper (Robolectric)
```

> `:sync-storage-room`'s `RoomSyncAdapter` tests spin up a real in-memory Room
> database on the JVM via **Robolectric**, so they run under `test` /
> `testDebugUnitTest` with **no emulator or connected device** — the same way CI
> runs them. You do not need Section 7 for this module.

### Run a single test class from Android Studio
1. Open the test file (e.g., `sync-core/src/test/.../SyncStateTest.kt`)
2. Click the green **▶** gutter icon next to the class declaration
3. Select **Run 'SyncStateTest'**

The **Run** panel shows green ticks for passing tests and red X for failures with stack traces.

---

## 7. Instrumented tests

There are **none**, by design. Every test in the project runs on the JVM — the Room adapter and the
WorkManager scheduler use Robolectric (Section 6), so `./gradlew test` covers the whole suite with no
emulator or connected device. This is what CI runs too.

If you add an `androidTest` source set later, run it with `./gradlew connectedAndroidTest` against a
started emulator.

---

## 8. Build an AAR (for local consumption)

To build release AARs for all library modules:

```bash
./gradlew assembleRelease
```

Output AARs are at:
```
sync-core/build/outputs/aar/sync-core-release.aar
sync-storage-room/build/outputs/aar/sync-storage-room-release.aar
sync-network-retrofit/build/outputs/aar/sync-network-retrofit-release.aar
sync-workmanager/build/outputs/aar/sync-workmanager-release.aar
sync-ui-dashboard/build/outputs/aar/sync-ui-dashboard-release.aar
```

---

## 9. Publish to local Maven (for testing in another app)

```bash
./gradlew publishToMavenLocal
```

Then in your consuming app's `settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

And in `app/build.gradle.kts`:
```kotlin
dependencies {
    implementation("io.github.prathamesh2640:sync-core:0.1.0")
}
```

---

## 10. Useful Gradle commands reference

| Command | What it does |
|---|---|
| `./gradlew test` | Run all unit tests |
| `./gradlew assembleDebug` | Build debug APK (sample-app) |
| `./gradlew assembleRelease` | Build release AARs (all library modules) |
| `./gradlew :sync-core:test` | Test a single module |
| `./gradlew clean` | Wipe all build output |
| `./gradlew dependencies` | Print full dependency tree |
| `./gradlew publishToMavenLocal` | Install to local Maven cache |
| `./gradlew lint` | Run Android lint on all modules |

---

## 11. Seeing engine activity in Logcat

The engine is **silent by default** — a library should not write to a host app's log unless asked.
Opt in by raising the log level when you build the config:

```kotlin
SyncEngineConfig { logLevel = LogLevel.DEBUG }   // NONE < ERROR < WARN < INFO < DEBUG
```

`:sync-core` is framework-free (no `android.util.Log`), so it writes to stdout. On Android that
surfaces under the **`System.out`** tag, with every line prefixed `[SyncEngine]`:

```
System.out  [SyncEngine] INFO: sync finished: synced=3 conflicts=0
```

In Android Studio Logcat, filter on `SyncEngine` (a plain text match on the message catches the
prefix). Lines carry only state names, error codes/types, and entity ids — never entity content,
response bodies, or auth material. The other modules do no logging of their own; WorkManager's own
job lifecycle shows up under the `WM-` tags it emits.

---

## 12. Publishing to Maven Central

Publishing itself (Central Portal account, GPG signing key, credentials, and the actual release
mechanics) is documented separately in [`PUBLISHING.md`](PUBLISHING.md) and
[`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md) — it's maintainer-only and not needed to build or
contribute to the project day-to-day. All 5 publishable modules already apply the
`com.vanniktech.maven.publish` and `org.jetbrains.dokka` plugins (`build.gradle.kts`); `./gradlew
publishToMavenLocal` (Section 9 above) works today without any of the Central-specific setup.

## 13. Troubleshooting

**Build fails with `Could not find com.android.tools.build:gradle`**
→ Ensure you have a working internet connection on first sync. The Gradle wrapper downloads dependencies automatically.

**`./gradlew` permission denied (macOS/Linux)**
```bash
chmod +x gradlew
```

**Emulator is slow**
→ Enable Hardware Acceleration: `SDK Manager → SDK Tools → Intel HAXM` (Intel) or use ARM system images on Apple Silicon.

**Tests fail with `No tests found`**
→ Ensure the test class is in `src/test/java/...` (unit tests) not `src/androidTest/java/...` (instrumented tests) and the class is not abstract.

**`Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin`**
→ AGP 9's built-in Kotlin blocks third-party plugins (KSP) from registering generated sources via
`kotlin.sourceSets`. Fixed once, project-wide, by `android.disallowKotlinSourceSets=false` in
`gradle.properties` — the documented workaround for KSP + AGP 9 built-in Kotlin. Already set in this repo.

**KSP fails with `unexpected jvm signature V`, or `Cannot query … testedVariantArtifacts`**
→ On this toolchain (AGP 9 built-in Kotlin + Kotlin 2.1) Room 2.6.x cannot build: the KSP2 backend throws
`unexpected jvm signature V`, and pinning KSP1 (`ksp.useKSP2=false`) then fails against AGP 9's built-in
Kotlin with `testedVariantArtifacts … no value`. Resolved by using **Room 2.7.1** with the default KSP2
backend (Room 2.7 supports KSP2). Keep Room ≥ 2.7 here; do not add `ksp.useKSP2=false`.

**Room compile error `Cannot find symbol @Dao`**
→ `:sync-storage-room` uses KSP for Room codegen (migrated in Feature F-09). The `com.google.devtools.ksp` plugin (version `2.1.0-1.0.29`, tracking Kotlin 2.1.0) is declared apply-false at the root and applied via `alias(libs.plugins.ksp)` in the module; Room codegen is wired as `ksp(libs.androidx.room.compiler)` for `main` and `kspTest(...)` for the Robolectric JVM tests — never `annotationProcessor`. KSP needs no separate SDK install; Gradle resolves it automatically. If codegen seems stale, run `./gradlew clean`.

**Compose build error about the Compose compiler / Kotlin version mismatch**
→ The Compose modules (`:sync-ui-dashboard`, `:sample-app`) apply `org.jetbrains.kotlin.plugin.compose`
(`libs.plugins.compose.compiler`, currently pinned to `2.1.0`). AGP 9.2.1's built-in Kotlin is **2.2.10**
— the pin is older and builds green today, but that is the first thing to check if Compose codegen
breaks after an AGP bump: raise `composeCompiler` in `gradle/libs.versions.toml` to match the built-in
Kotlin version. Do **not** re-add the standalone Kotlin Android plugin (ADL-005) — only the Compose
compiler plugin plus `buildFeatures { compose = true }` are needed.

> To check which Kotlin AGP is actually using: `./gradlew :sync-core:generatePomFileForMavenPublication`
> and read the `kotlin-stdlib` version in `sync-core/build/publications/maven/pom-default.xml` — that is
> exactly what consumers get transitively.
