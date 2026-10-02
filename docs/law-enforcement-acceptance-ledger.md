# LAW-008 — Law Enforcement Acceptance Ledger

Release-gate ledger for the LAW milestone (issues #211–#219). It records the
end-to-end acceptance evidence for the eight LAW scenarios, the KubeJS legacy
migration proof, the TOML economy proof, optional-dependency status, and the
retirement state of the legacy prototype scripts.

Automated proof runs on the real NeoForge GameTest dedicated server — real
`ServerPlayer` entities, real inventories, real chest block entities, real
SavedData persistence, and the live 5-tick checkpoint scan cadence — not on
synthetic fakes.

## Environment

| Property | Value |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.252 |
| Java | 21 |
| Harness | `./gradlew runGameTestServer` (GameTest dedicated server) |
| Unit suite | `./gradlew test` |
| Latest result | **38/38 required GameTests passed**, `BUILD SUCCESSFUL`, clean server shutdown |
| Companion suite | `src/gameTest/.../StrajaGameTests.java` (pre-existing tests, incl. `cellProtection`, `laborCampCustody`, `wantedLifecycle`) |

## Acceptance tests 1–8

| # | Scenario | GameTest | Proven live | Status |
| --- | --- | --- | --- | --- |
| AT1 | Border post | `borderPostCrossingLedger` | Clean carrier logs a PASS crossing with a full inventory snapshot; banned player is repelled at stage 1; doors stay closed for banned players | Pass |
| AT2 | Mine exit | `mineGateCarryBanAndRoleBan` | `DENY` checkpoint + outgoing direction + local illegal list (ore/salt) + `MINER` role carry-ban: miner with ore repelled with quartermaster instruction; passes after dropping; prisoner repelled on empty pockets | Pass |
| AT3 | Port gate | `portGateArrestMode` | `ARREST` checkpoint arrests contraband carrier on stage-2 entry; custody transfer lands | Pass |
| AT4 | Inspection ledger + snapshot integrity | `borderPostCrossingLedger` (snapshot asserts), plus ledger assertions across AT1–AT3/AT6–AT8 | Every crossing decision writes a ledger entry with a full `SnapshotItem` capture — hotbar, main, armor, offhand, nested-container (`>`-slot) contents | Pass |
| AT5 | Quartermaster trade + four-coin TOML economy | `quartermasterFourCoinEconomy` | 129 ore at price 1 pays **2 brass + 1 bronze** (optimal breakdown, no truncation) on a custom ladder; sequential chest fill overflows chest A's single free slot into chest B; trade recorded in the desk ledger | Pass |
| AT6 | Wanted on sight + hunt termination | `wantedOnSightArrestAndDenyRepel` | Seeded active BOLO → arrest on first contact at an `ARREST` gate regardless of inventory; wanted mark resolved at arrest; same wanted player at a `DENY` gate is repelled, not arrested; sighting logged | Pass |
| AT7 | Labor camp exit + penal accounts | `laborCampExitConfiscation` | IN_CAMP prisoner repelled at camp exit gate; carried ore confiscated into the site's physical evidence chest; custody stays active | Pass (camp lifecycle — intake, dormitory respawn, labor buy-out — additionally proven by `laborCampCustody`) |
| AT8 | Escort + handcuff tether | `escortBypassAndUncuffOutcome` | Officer cuffs prisoner; suspect cannot sprint while cuffed; escorted suspect passes a `PRISONER`-ban `DENY` gate beside the officer with no repel; uncuff inside the assigned cell restores `IN_CELL`; re-cuff + uncuff outside custody marks `FUGITIVE` | Pass |

### Test-isolation notes (why a green run is trustworthy)

- Every acceptance fixture uses unique ids (`at1_*` … `at8mig_*`), cleans its own
  sites/camps/sentences/register rows/custody state at both start and finish,
  and runs inside a dedicated **y-band** (`anchor.y + 12…84`) so no foreign
  test's checkpoint box overlaps — GameTest structures pack at 6-block spacing,
  so earlier `x±20/±40` staging lanes were reachable by neighbor tests.
- Checkpoint scans run on the real 5-tick cadence; staged crossings wait a full
  cadence before asserting.
- Production fix landed under this milestone: `PrisonService.recoverOnLogin`
  adoption now re-keys **only** legacy `legacyUuid(name)`-anchored register
  rows — a live uuid-keyed record can no longer be adopted by a same-named
  joiner.

## TOML economy proof

| Requirement | Evidence | Status |
| --- | --- | --- |
| Default ladder 1 : 64 | `tomlCoinEconomyDefaults` reads the **live-loaded** `StrajaServerConfig.COIN_TIER_RATIO` (= 64) and resolved `policies.coinItemIds` on the running server | Pass |
| Default denominations | Asserts `adys_decorations:bronze_coin` @1, `brass_coin` @64, `silver_coin` @4096, `gold_coin` @262144 | Pass |
| Custom ratio without truncation | `quartermasterFourCoinEconomy` installs a vanilla-item ladder (`iron_nugget` @1, `gold_nugget` @64, `emerald` @4096, `diamond` @262144) and proves 129 units pay exactly 2×64 + 1 — then restores the TOML baseline from `StrajaServerConfig.toPolicies()` | Pass |

## KubeJS legacy migration proof

| Requirement | Evidence | Status |
| --- | --- | --- |
| Live ingestion into SavedData stores | `kubeJsMigrationIngestsLiveStores`: synthetic `portCheckpointCfg` blob → real `law_checkpoints` store (site + `denyTarget`→pushback + evidence chest + `cb`→local ban); `strajaPrisonJail` blob → `prisoner_register` (IN_CELL under `legacyUuid`, fine joined on the prisoner name) | Pass |
| Idempotent re-run | Same blobs re-migrated — checkpoint count unchanged, `report.ok()` fail-closed | Pass |
| Corrupt-entry tolerance, offline `.dat` players, storage/gold aliases (`strajaStorageCfg`, `strajaGoldCfg`), `portCheckpointJail` | Unit suite: `MigrationServiceTest`, `LawMigrationTest` — all green under `./gradlew test` | Pass |
| Non-destructive | Migration merges into stores; it never deletes legacy keys — the source blobs are inputs, not mutated state | Pass |

## Optional dependencies

| Dependency | Status in this run | Tester note |
| --- | --- | --- |
| CustomNPCs | **Absent** — `CustomNPCs API unavailable: ClassNotFoundException` in the GameTest classpath; provider degrades fail-closed as designed | Install CustomNPCs on the tester server for officer/trade-NPC surfaces; the mod does not hard-require it. See `docs/customnpcs-compatibility-matrix.md` |
| Adys Decorations | Absent in the bare GameTest env — coin payout asserts therefore run on a vanilla-item ladder; TOML *resolution* of the adys ids is proven separately | Testers with Adys Decorations get the real four-coin payouts at the configured denominations |

## Legacy script retirement

| Script (deployed clone `kubejs/server_scripts/`) | Disposition |
| --- | --- |
| `port_checkpoint.js` (2430 lines) | Retired → `port_checkpoint.js.disabled` (KubeJS skips non-`.js`) |
| `straja_prison.js` (2509 lines) | Retired → `straja_prison.js.disabled` |
| `straja_storage.js` (1000 lines — the generalized successor of `straja_gold.js`; `straja_gold.js` never existed as a separate file — the `strajaGoldCfg` key lives here) | Retired → `straja_storage.js.disabled` |

- Retirement is **reversible**: rename `.js.disabled` back to `.js` and reload.
- Migration support is retained permanently: `/straja migrate <worldPath>`
  reads the legacy persistent-data keys on any not-yet-migrated world.
- Cross-script coupling audit: no other KubeJS file imports the retired
  scripts' functions; `coffer.js` reads only the persisted `portCheckpointCfg`
  data key, which remains in place.
- Tester-server procedure: run `/straja migrate <worldPath>` once per world,
  verify the checkpoint/prisoner/economy stores via the inspection commands,
  then retire the three files.

## Residual risks and honest caveats

- **Persisted-world hygiene.** The GameTest world persists SavedData between
  runs; a leaked fixture in `cellProtection` accumulated `celula_*` cells until
  `prisonMaxCells` (32) refused new cells. Fixed: the test now removes the cell
  it creates; the accumulated store was surgically purged (all `celula_*`
  cells/assignments/sentences removed, `cell_gametest` preserved).
- **Concurrent-mock flakiness.** Mock players share the name
  `test-mock-player`; all name-keyed lookups now either use unique fixture
  names or are protected by the uuid-first `identityMatches` and the tightened
  adoption guard. One intermittent AT8 outcome (`FUGITIVE` instead of
  `IN_CELL` on inside uncuff) was observed once and not reproduced after
  fixture isolation; the assertion now reports sentence/cell/position/camp
  diagnostics on failure for root-cause capture.
- **Live-client acceptance.** This ledger certifies the dedicated-server
  GameTest surface. The final real-CustomNPCs client gate follows the same
  convention as `docs/npc-platform-acceptance-ledger.md` and is run on the
  packaged RC, not under this issue.
