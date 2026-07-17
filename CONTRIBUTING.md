# Contributing to SyncEngine

Thanks for your interest in improving SyncEngine. This document covers how to file bugs, propose
changes, and get a pull request merged.

By participating, you agree to abide by the [Code of Conduct](CODE_OF_CONDUCT.md).

---

## Ways to help

1. **Report a bug** — open a GitHub Issue using the *Bug report* template.
2. **Request a feature** — open a GitHub Issue using the *Feature request* template. Prefer a small,
   focused proposal over a large redesign; the library's public API is a forever promise.
3. **Send a fix** — small, direct PRs are the easiest to review. See "Pull request workflow" below.
4. **Improve documentation** — README, KDoc, sample-app usage. Corrections are always welcome.
5. **Report a security issue** — do **not** open a public issue. Follow [SECURITY.md](SECURITY.md).

---

## Development environment

- JDK **17** (Temurin recommended)
- Android Studio **Ladybug** (or newer) — matches AGP 9.x
- Android SDK **36** installed
- Kotlin **2.1.x** (bundled with AGP 9)

Clone and open the project in Android Studio. First `Gradle Sync` fetches dependencies from Google
Maven + Maven Central. No secrets are needed to build.

```bash
./gradlew assembleDebug           # full build
./gradlew testDebugUnitTest       # all module unit tests (JVM, Robolectric where needed)
./gradlew :sync-core:testDebugUnitTest   # just one module
```

The sample-app requires a running emulator (or a physical device) for the end-to-end flow.

---

## Project philosophy

Please read [`README.md`](README.md) and skim the module structure first. The library is opinionated:

- **Simple beats clever.** A fix that simplifies is better than one that adds complexity.
- **`internal` by default.** Every public API addition is a forever promise. Prefer `internal`.
- **Sealed types over exceptions.** Return `SyncResult` / `SyncError`, never throw across the public API.
- **Coroutines + Flow only.** No RxJava, no callbacks. StateFlow, not LiveData.
- **No `GlobalScope`, no `runBlocking`** in library code.
- **Version catalog is the source of truth.** No inline version strings in module `build.gradle.kts`.

---

## Pull request workflow

1. **Fork** the repo and create a branch off `main`. Name it something descriptive
   (`fix/tombstone-purge-off-by-one`, `feat/ktor-adapter`).
2. **One logical change per PR.** Do not bundle two features or fold refactors into a bug-fix PR.
3. **Add or update tests.** Every behaviour change needs a test that would have caught the bug or that
   exercises the new API.
4. **Update `README.md` / KDoc** for any public-API change. Reviewers will not merge undocumented
   public API additions.
5. **Update `CHANGELOG.md`** under `[Unreleased]`. Use the same section headings as prior entries
   (`Added` / `Changed` / `Fixed` / `Removed` / `Security` / `Documentation`).
6. **Run the local checks** before pushing:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```
7. **Open the PR** against `main`. Fill in the PR template. Reference the Issue number if applicable
   (`Fixes #123`).

CI will run the full build + tests. Address review comments in-place — do not squash your own history
before review; the maintainer will squash on merge.

---

## Commit messages

This repo uses **one-line [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/)**.
Each commit is atomic (one logical change, one line). No feature numbers in the message text.

```
<type>(<scope>): <imperative summary>
```

Common types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`.
Common scopes: `core`, `storage-room`, `network-retrofit`, `workmanager`, `dashboard`, `sample`,
`readme`, `ci`, `build`, or no scope for repo-wide changes.

Examples from this repo's history:

```
feat(core): add two-way sync with pull, conflict resolution and delete confirmation
fix(storage-room): purge tombstones at the retention boundary; run Room tests on JVM
docs: document two-way sync, background scheduling and the dashboard
```

Every commit **must compile** and **must not break any passing test**. If a change requires multiple
files across modules, that is fine — the atomic unit is the *logical* change, not the file.

---

## Public-API changes

The library's public API is a promise. Adding a public class, function, or property is a semver
`MINOR` bump; changing the signature of an existing one is a `MAJOR` bump.

Before opening a PR that touches the public API:

- Prefer `internal`. Ask yourself: does the host app *actually* need this?
- Add KDoc to every public symbol.
- Update the module's `consumer-rules.pro` so R8 keeps the new type in release builds.
- Note the change in `CHANGELOG.md` under `Added` / `Changed`.

---

## Licensing of contributions

By submitting a pull request, you agree that your contribution is licensed under the
[Apache License, Version 2.0](LICENSE), as stated in Section 5 of the license
("Submission of Contributions"). No separate CLA is required.

---

## Questions?

Open a GitHub Discussion (once enabled) or a low-priority Issue tagged `question`. For anything
security-related, use the channel in [SECURITY.md](SECURITY.md).
