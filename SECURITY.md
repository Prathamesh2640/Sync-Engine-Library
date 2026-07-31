# Security Policy

SyncEngine is an Android library that runs inside host apps and handles their local data plus network
calls to their backends. A vulnerability in this library is a vulnerability in every app that uses it,
so we take security reports seriously and respond promptly.

---

## Supported versions

| Version | Supported |
|---------|-----------|
| `0.x`   | ✅ security fixes for the latest minor |
| pre-`0.1.0` (unreleased) | ❌ not published |

Once `1.0.0` ships, this table will list the two most recent minor versions as supported.

---

## Reporting a vulnerability

**Please do not open a public GitHub Issue for a security report.**

Choose either channel — GitHub Security Advisories is preferred because it gives us a private
coordinated-disclosure workspace.

### Preferred — GitHub Security Advisories

1. Go to the **Security** tab of this repository.
2. Click **"Report a vulnerability"**.
3. Fill in the private form. Only the maintainers can see the report until the advisory is published.

### Fallback — email

Email **prathameshsharma1694@gmail.com** with the subject line prefixed `[syncengine-security]`.

Include, if possible:

- A clear description of the issue and its impact.
- The affected version(s) of SyncEngine.
- Reproduction steps or a proof-of-concept (attach as text; do not include host-app credentials).
- Whether you have already disclosed the issue elsewhere.
- Your preferred name for the eventual advisory credit (or "anonymous").

---

## What to expect

| Stage | Target |
|-------|--------|
| Acknowledgement of receipt | within **72 hours** |
| Initial triage + severity (CVSS 4.0) | within **7 days** |
| Fix + private patch build | within **30 days** for High/Critical, **90 days** for Medium/Low |
| Coordinated public disclosure | after the fix is released, typically within 14 days of the patch |

Reporters are credited in the published advisory unless they request anonymity.

---

## Out of scope

The following are generally **not** treated as SyncEngine vulnerabilities:

- Host-app misconfiguration (leaving debug logging on in production, storing tokens in `SharedPreferences`
  without encryption, exposing the debug `SyncDashboardActivity` in a release build).
- Third-party dependency vulnerabilities that surface only through the *host app's* transitive tree
  (report those upstream; SyncEngine will bump the transitive when patched upstream).
- Denial-of-service caused by the host app enqueueing an unbounded number of entities without a queue
  limit — this is a documented consumer responsibility.
- Attacks that require a compromised device (root, malicious system app, physical access with unlocked
  bootloader).

If you are unsure whether an issue is in scope, please still report it — we will triage.

---

## Cryptographic material

SyncEngine ships neither cryptographic keys nor secrets. All signing keys for the published Maven
artefacts are held by the maintainer and never appear in this repository. If you find a suspected key
or secret in the repository history, please report it through the channels above so we can rotate.
