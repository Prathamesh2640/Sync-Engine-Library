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

## 7. Run instrumented tests (on emulator)

The library modules have **no** instrumented (`androidTest`) tests — the Room
adapter tests run on the JVM under Robolectric (Section 6). Instrumented tests
apply only to `:sample-app` and need a running emulator or connected device:

```bash
./gradlew :sample-app:connectedAndroidTest
```

Or from Android Studio: right-click the `androidTest` source set → **Run Tests**.

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

## 11. Logcat filters (for GUI testing)

When running sample-app, filter Logcat to see sync activity:

| Filter | Shows |
|---|---|
| `tag:SyncEngine` | All engine lifecycle events |
| `tag:SyncWorker` | WorkManager job execution |
| `tag:SyncRoom` | Room query activity |
| `tag:SyncNetwork` | Network adapter calls |

In Android Studio Logcat: click the filter dropdown → **Edit Filter Configuration** → set **Tag** to `SyncEngine`.

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
(`libs.plugins.compose.compiler`, version `2.1.0`). That version must match the Kotlin version AGP 9's
built-in Kotlin uses. Do **not** re-add the standalone Kotlin Android plugin (ADL-005) — only the Compose
compiler plugin plus `buildFeatures { compose = true }` are needed. If the versions drift, align
`composeCompiler` in `gradle/libs.versions.toml` with the built-in Kotlin version.
