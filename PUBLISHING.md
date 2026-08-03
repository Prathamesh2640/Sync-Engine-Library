# Publishing SyncEngine to Maven Central

This is the maintainer-facing guide for the one-time account/signing setup and the actual publish
mechanics. For the step-by-step release walkthrough (version bump, tag, verify), see
[`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md). This document is the setup **behind** that checklist.

The library publishes through the [vanniktech `gradle-maven-publish` plugin](https://github.com/vanniktech/gradle-maven-publish-plugin)
against Sonatype's **Central Portal** (the current, non-OSSRH publishing path). Every publishable
module (`sync-core`, `sync-storage-room`, `sync-network-retrofit`, `sync-workmanager`,
`sync-ui-dashboard`) is already wired with `mavenPublishing { ... }` in its `build.gradle.kts` — this
guide only covers the credentials and keys those blocks read at publish time. `:sample-app` is never
published (module-guide).

---

## 1. Central Portal account + namespace

1. Create an account at [central.sonatype.com](https://central.sonatype.com) if you don't have one.
2. Register the namespace `io.github.prathamesh2640`. Because it's a `io.github.<username>` namespace,
   Central verifies ownership automatically against the matching GitHub account — no DNS TXT record or
   support ticket needed. Follow the in-portal instructions (it asks you to create a short-lived public
   GitHub repo or gist to prove ownership).
3. Once the namespace shows **Verified**, generate a **User Token** (Account → Generate User Token). This
   gives you a token username/password pair — **not** your Central Portal login password. That pair is
   what `mavenCentralUsername` / `mavenCentralPassword` below actually are.

## 2. GPG signing key

Central requires every artifact (jar, sources jar, javadoc jar, POM) to be PGP-signed.

**If you already have a GPG key you're happy to use for this**, skip to step 3 and export it as
described there.

**If you need to generate one:**
```bash
gpg --full-generate-key
# Choose RSA and RSA, 4096 bits, key does not expire (or a long expiry — a
# revoked/expired signing key on old releases is awkward to deal with later).
# Use a real name + an email you control — this becomes public in the key's identity.

gpg --list-secret-keys --keyid-format LONG
# Note the key ID (the part after "rsa4096/") for the next step.

# Publish the public key so Central (and anyone verifying your artifacts) can find it:
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

## 3. Export the key for vanniktech's in-memory signing

The plugin's `signAllPublications()` (already called in every module) reads an **in-memory**
ASCII-armored key — not a keyring file path — from Gradle properties:

```bash
gpg --export-secret-keys --armor <KEY_ID> > signing-key.asc
```

Open `signing-key.asc` — that whole block (including the `-----BEGIN PGP PRIVATE KEY BLOCK-----` /
`-----END...-----` lines) is the value for `signingInMemoryKey` below. **Never commit this file** — it's
already covered by this repo's `.gitignore` (`*.asc`, `*.gpg`, `signing-key*`). Delete it once you've
copied the value out anyway — don't leave a plaintext private key on disk longer than necessary.

## 4. Required credentials, by where they're used

| Property (as read by the Gradle plugin) | Value | Local (`~/.gradle/gradle.properties`, never the repo's own) | CI (GitHub Actions secret name used in `release.yml`) |
|---|---|---|---|
| `mavenCentralUsername` | Central Portal **User Token** username (step 1) | `mavenCentralUsername=...` | `MAVEN_CENTRAL_USERNAME` |
| `mavenCentralPassword` | Central Portal **User Token** password (step 1) | `mavenCentralPassword=...` | `MAVEN_CENTRAL_PASSWORD` |
| `signingInMemoryKey` | Full contents of `signing-key.asc` (step 3) | `signingInMemoryKey=...` | `SIGNING_IN_MEMORY_KEY` |
| `signingInMemoryKeyPassword` | The GPG key's passphrase | `signingInMemoryKeyPassword=...` | `SIGNING_IN_MEMORY_KEY_PASSWORD` |

**Local publishing:** put the four `*.properties` lines above in your **global**
`~/.gradle/gradle.properties` (create it if it doesn't exist) — never in this repo's own
`gradle.properties`, which is committed. Gradle reads global properties automatically; no `ORG_GRADLE_PROJECT_`
prefix needed there.

**CI (`release.yml`):** add the four secret names in the right column under this repo's
**Settings → Secrets and variables → Actions**. The workflow already maps each one to the
`ORG_GRADLE_PROJECT_<property>` environment variable the plugin expects — that's the mechanism that
lets the same `mavenPublishing {}` config work identically locally and in CI without ever writing a key
to disk in the CI runner.

## 5. Test locally before ever touching Central

```bash
./gradlew publishToMavenLocal
```
Then in a throwaway consumer project, add `mavenLocal()` to `repositories` and depend on
`io.github.prathamesh2640:sync-core:0.1.0` (see README's "Option D — Local Maven"). Confirm the AAR,
sources jar, and javadoc jar all resolve and the POM metadata looks right before publishing for real.

## 6. Publishing for real

```bash
./gradlew publishToMavenCentral --no-configuration-cache
```
This uploads and validates all 5 modules' artifacts against Central's rules but stops short of the
final release — you get a **manual review window** on the [Central Portal deployments page](https://central.sonatype.com/publishing/deployments)
to double-check before clicking **Publish**. Once you trust the process (after the first release or
two), the release workflow can switch to `publishAndReleaseToMavenCentral` for a fully automatic release
on tag push — see the comment in `.github/workflows/release.yml`.

Normally you won't run this by hand — pushing a `vX.Y.Z` tag triggers `.github/workflows/release.yml`,
which runs this same command with the CI secrets from step 4.
