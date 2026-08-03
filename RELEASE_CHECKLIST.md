# Release Checklist

Walk this top-to-bottom for every release, including the first one (`0.1.0`). One-time account/signing
setup lives in [`PUBLISHING.md`](PUBLISHING.md) — do that first if you haven't.

## Before the first release only — go-public switch

- [ ] `./gradlew test assembleDebug` is green, run fresh, on your machine.
- [ ] Central Portal namespace `io.github.prathamesh2640` shows **Verified** (`PUBLISHING.md` §1).
- [ ] The four signing/credential secrets are set in **Settings → Secrets and variables → Actions**
      (`PUBLISHING.md` §4).
- [ ] Flip the repo from Private to Public: **Settings → General → Danger Zone → Change visibility**.
      CI (`ci.yml`) and Docs (`docs.yml`) start running for real from this point on.
- [ ] Confirm the community-health checklist (GitHub shows this under **Insights → Community Standards**)
      is green — it auto-detects `LICENSE`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, `SECURITY.md`, the
      issue templates, and the PR template, all already in this repo.

## Every release

1. **Decide the version number.** Semantic Versioning per README's "Versioning & stability" section.
   Pre-1.0, minor bumps can still carry additive-only breaking risk — diff the public API against the
   last tag (`git diff v<last>..HEAD -- '*/src/main/**/*.kt'`) for anything added or changed.
2. **Update `CHANGELOG.md`.** Move everything under `[Unreleased]` into a new `## [X.Y.Z] - YYYY-MM-DD`
   section; leave `[Unreleased]` empty (or start listing what's landed since, for the next cycle).
3. **Bump the version in one place:** the root `build.gradle.kts`'s `allprojects { version = ... }`.
   All 5 publishable modules derive their `coordinates(...)` version from it, so they always release
   together on the same version — this library does not version its modules independently.
4. **Update the README** — the "Option A — Maven Central" code block's version numbers, and the Maven
   Central badge needs no manual edit (it resolves the latest version automatically once published).
5. **Run the full gate one last time:**
   ```bash
   ./gradlew test assembleDebug
   ./gradlew :sample-app:assembleRelease   # R8/ProGuard check, module-guide
   ```
6. **Commit** the version bump + CHANGELOG move as its own commit, e.g.
   `chore(release): prepare 0.1.0` — no source changes in this commit, just the version/changelog edits
   from steps 2–4.
7. **Tag and push:**
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```
   This triggers `.github/workflows/release.yml`, which runs the test suite and then
   `./gradlew publishToMavenCentral`.
8. **Review the deployment on the [Central Portal deployments page](https://central.sonatype.com/publishing/deployments).**
   Check the artifact list, POM metadata, and signatures for all 5 modules, then click **Publish**.
9. **Verify it's live** — `https://repo1.maven.org/maven2/io/github/prathamesh2640/sync-core/<version>/`
   should resolve within about 15–30 minutes of publishing (sync to the mirror isn't instant).
10. **Create a GitHub Release** from the pushed tag, pasting the matching `CHANGELOG.md` section as the
    release notes.
11. **Confirm the Docs workflow ran** (`.github/workflows/docs.yml`, on the push to `main` that included
    the version bump) and the Dokka site under GitHub Pages reflects the new version.

## After the first release only

- [ ] Consider switching `release.yml` from `publishToMavenCentral` to
      `publishAndReleaseToMavenCentral` once you've done the manual-review step above a couple of times
      and trust the pipeline (see the comment in `.github/workflows/release.yml`).
- [ ] No automated replacement exists yet for the removed Binary Compatibility Validator. Checked
      2026-08: upstream tracking issue [kotlin/binary-compatibility-validator#312](https://github.com/Kotlin/binary-compatibility-validator/issues/312)
      is open, unassigned, no fix timeline — BCV hooks into the standalone `kotlin-android` plugin's
      extension points, which AGP 9's built-in Kotlin doesn't expose. The only known workaround is a
      third-party single-maintainer bridge plugin published the same month this was checked, with no
      track record — deliberately not adopted (same reasoning as ADL-005/ADL-020: don't add fragile
      third-party tooling built on the standalone-Kotlin-plugin assumption). Until #312 resolves
      upstream, keep using step 1 above (manual API diff against the last tag) as the gate. A public API
      accidentally broken after `1.0.0` needs a major bump, so don't skip that manual diff.
