# CI/CD pipeline

Straja's pipeline is layered by cost: every layer tests the same packaged
JAR, verified by SHA-256, and expensive layers only run on release-candidate
tags.

## Fast CI — `.github/workflows/ci.yml`

Runs on every push and PR. Secretless, single job:

- `./gradlew build` (compile + unit tests + JAR)
- Python harness suites under `tools/ci/` (`unittest discover`)
- Release contract: `mod_version` ⇄ tag equality rules, artifact selection,
  forbidden-content scan of the distributable JAR

## RC pipeline — `.github/workflows/rc.yml`

Triggers on `vX.Y.Z-rc.N` pushes (and `workflow_dispatch` for a no-publish
validation run — `publish-beta` is tag-gated).

| Job | Blocking | Purpose |
|-----|----------|---------|
| `package` | yes | Build once, contract-validate, SHA-256/512 digests, Sigstore attestation, SBOM, staged artifact |
| `game-tests` | yes | Blocking NeoForge GameTests on the packaged JAR |
| `server-profiles` | yes | Disposable dedicated NeoForge servers, focused dependency profiles (`required-only`, `combined`, `vampirism`, `production-config`, …), RCON smoke |
| `foreign-npc-advisory` | no | Advisory profile — report must exist with an explicit status |
| `rc-scenarios` | yes | Declarative JSON RCON suite: gameplay flows, restart/persistence, KubeJS migration + dedup, corrupt-store recovery, idempotency, Vampirism ownership |
| `rc-performance` | yes | Warm-up + deterministic workload + health sampling; avg/p95 MSPT, heap, startup, workload-error and log-scan budgets |
| `client-ui-advisory` | no | Real NeoForge client via MC Pilot under Xvfb; declarative UI scenarios (forms, custody, physical items, reconnect delivery); pass/fail/blocked verdict |
| `profiles-gate` | yes | Explicit aggregate — fails on any failed/cancelled/missing blocking job or missing advisory report |
| `publish-beta` | — | Tag-only. Publishes the staged JAR as beta on Modrinth + CurseForge, then finalizes the GitHub **prerelease** with JAR, digests, `release-manifest.json`, `sbom.json`, `publication-ledger.json` |

The prerelease is the long-lived evidence ledger (workflow artifacts expire
in days; release assets do not).

## Stable promotion — `.github/workflows/release.yml`

Triggers on stable `vX.Y.Z` pushes; `workflow_dispatch` offers `tag` +
`dry_run` (default on). **No Gradle task runs.**

1. Resolve the highest green `vX.Y.Z-rc.N` prerelease pinned to the tag's
   commit (`resolve-rc` reads each candidate's `release-manifest.json`).
2. Download the RC assets, verify SHA-256 against the manifest, and run
   `gh attestation verify`.
3. Modrinth: PATCH the RC version's metadata to `version_type: release`
   with the stable version number — the published file (and hash) is
   retained byte-identical.
4. CurseForge: upload the exact same bytes as `releaseType: release` (its
   API has no metadata promotion). Existing files with matching md5/sha1
   are a no-op.
5. GitHub stable release is created/marked latest **last**, after external
   targets are verified.

Every platform mutation is query-before-create: matching version + hash is
an idempotent no-op; same version with different bytes fails closed. The
pipeline never deletes remote releases. Tokens come from environment
secrets and are never written to ledgers or logs.

## Local reproduction

```bash
# dedicated-server profile (requires ~4 GB free in the work root)
python tools/ci/server_harness.py --manifest tools/ci/server_manifest.json \
  --profile combined --jar build/libs/straja-<v>.jar \
  --work-root /tmp/straja --artifacts-dir /tmp/straja-out

# RCON scenario suite against a running profile
python tools/ci/scenario_runner.py --scenarios-dir tools/ci/scenarios ...

# performance soak
python tools/ci/performance.py ...

# client UI (Linux + Xvfb + Node 20 only; classified 'blocked' elsewhere)
python tools/ci/client_ui.py run --manifest tools/ci/client_manifest.json ...
```
