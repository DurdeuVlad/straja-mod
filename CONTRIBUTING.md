# Contributing

## Setup

- Java 21, no other toolchain requirements — the Gradle wrapper handles the rest.
- `./gradlew build` compiles and runs the unit test suite.
- `./gradlew runServer` starts a headless dev server (RCON on :25575).
- Envelope resolves automatically from Modrinth Maven. For the coin economy in
  dev runs, drop `adys_decorations-*.jar` into `libs/` (not redistributed), or
  point `[economy]` coin IDs at items from any installed mod.

## Architecture rules

Hexagonal ports-and-adapters — `ArchitectureBoundaryTest` enforces it:

- `domain/` and `application/` never import Minecraft or NeoForge classes.
- Business rules live in `application/service/`; platform code in `adapter/`.
- Persist state before producing external effects (payments, deliveries).
- Economy operations must be idempotent — receipt-scoped deposits.

## Pull requests

- Keep changes focused; describe behavior changes and the "why".
- Add or update unit tests for behavior changes. `./gradlew test` must be green.
- For gameplay changes, run `tools/scenario.sh` against `runServer` and note
  the result in the PR.
- Every key in `straja-server.toml` must actually do something — don't add
  config knobs that aren't consumed.

## Releases

- Tag `vX.Y.Z-rc.N` to cut a release candidate: the RC workflow builds the
  JAR once, runs the blocking gates (GameTests, dedicated-server profiles,
  RCON scenarios, performance budgets), records the advisory client verdict,
  and publishes a beta + evidence prerelease.
- Tag `vX.Y.Z` on the same commit to promote the verified RC bytes to
  stable — promotion never rebuilds.
- `mod_version` in `gradle.properties` must equal `X.Y.Z` exactly.
- See `docs/testing.md` for the pipeline layers and `README.md` for the
  repository settings runbook.
