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

A successful sync shows no red errors in the **Build** tab and the module tree in the **Project** panel shows all modules: `sync-core`, `sync-storage-room`, `sync-network-retrofit`, `sync-ui-dashboard`, `sample-app`.

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

> **Note:** sample-app is implemented in Feature F-14. Before that feature is done, running sample-app will show an empty stub activity.

1. In Android Studio, select `sample-app` in the run configuration dropdown (top toolbar)
2. Select your emulator from the device dropdown
3. Click **Run ▶** (or `Shift+F10`)
4. The app installs and launches on the emulator

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
./gradlew :sync-storage-room:test
./gradlew :sync-network-retrofit:test
```

### Run a single test class from Android Studio
1. Open the test file (e.g., `sync-core/src/test/.../SyncStateTest.kt`)
2. Click the green **▶** gutter icon next to the class declaration
3. Select **Run 'SyncStateTest'**

The **Run** panel shows green ticks for passing tests and red X for failures with stack traces.

---

## 7. Run instrumented tests (on emulator)

Instrumented tests need a running emulator or connected device:

```bash
./gradlew :sync-storage-room:connectedAndroidTest
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
    implementation("io.github.prathamesh2640.sync:sync-core:1.0.0")
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
| `./gradlew apiCheck` | Verify no accidental public API changes (available after F-15) |
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

## 12. Troubleshooting

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

**Room compile error `Cannot find symbol @Dao`**
→ After migrating to KSP (Feature F-09), ensure `id("com.google.devtools.ksp")` plugin is applied in the module's `build.gradle.kts` and the dependency is `ksp(libs.androidx.room.compiler)` not `annotationProcessor`.
