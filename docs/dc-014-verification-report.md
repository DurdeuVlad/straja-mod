# DC-014 integrated verification report

Date: 2026-09-14  
Branch under management: isolated issue worktrees  
Merged implementation through: DC-005 / `9656dda` (includes DC-015 and DC-016)

## Evidence status

This report distinguishes deterministic repository evidence from live
Minecraft evidence. A missing live tool is recorded as **unverified**, never as
a pass.

### Verified in the repository

| Area | Evidence | Result |
|---|---|---|
| Canonical condition/custody/transport/restraint state | `CustodyTransitionEngineTest`, `CustodyStateSerializationTest` | PASS |
| Absolute deadlines, pause/resume, abandonment, restart | `CustodyDeadlineEngineTest`, `CustodyServiceTest` | PASS |
| Lethal event ownership and second-hit death | `LethalEventResolverTest`, `CustodyServiceTest` | PASS |
| Carry, carrier death/logout, restart and dimension recovery | `CustodyServiceTest` | PASS |
| Resuscitation success, interruption and timeout | `CustodyServiceTest`, `CustodyDeadlineEngineTest` | PASS |
| Criminal rope, black sack, cutters and provenance | `CustodyServiceTest`, `CustodyTransitionEngineTest` | PASS |
| Police cuffs, universal key and jail delivery/revival | `CustodyServiceTest`, `PrisonServiceTest` | PASS |
| Confirmed give-up action and ownership guards | `GiveUpSurfaceTest`, `CustodyTransitionEngineTest` | PASS |
| Canonical ordinary jail entry/release and legacy projection reconciliation | `CustodyServiceTest`, `PrisonServiceTest`, `CustodyTransitionEngineTest` | PASS |
| Sparse action-bar status and 30/10/5/1 warnings | `CustodyServiceTest` | PASS |
| Optional-mod ownership safeguards | `OptionalModCompatibilityTest` | PASS |
| Architecture boundaries | `ArchitectureBoundaryTest`, `EventSurfaceTest` | PASS |
| Build artifact | `gradlew.bat clean build --no-daemon` | PASS |
| Remote CI | PRs #65, #66, #67, #68, #69, #70, #71, #76, #81, #82, #85, #87, #88 | PASS |

The complete Gradle test suite passed on the manager branch after DC-005:
585 tests with zero failures/errors. A clean build also passed before the
final documentation-only update.

## Live matrix

The `mct` command is not installed on this host, so no live server/client
profile can be claimed as verified.

| Profile | Server launch | Multiplayer tools | Client visuals | Messages | Status |
|---|---:|---:|---:|---:|---|
| Straja only | — | — | — | — | UNVERIFIED: MC Pilot unavailable |
| Straja + Vampirism 1.21-1.10.13 | — | — | — | — | UNVERIFIED: live MC Pilot unavailable; expected Vampirism ownership for vampire DBNO/stakes and Straja ownership for non-vampires |
| Straja + Incapacitated | — | — | — | — | UNVERIFIED; fail-safe disables Straja downed ownership |
| Straja + Piggyback | — | — | — | — | UNVERIFIED; fail-safe disables Straja crouch carry trigger |
| Straja + Incapacitated + Piggyback | — | — | — | — | UNVERIFIED |
| Full intended modpack | — | — | — | — | UNVERIFIED |

## Required live follow-up

When MC Pilot or an equivalent multiplayer test environment is available, run
the issue matrix from DC-014 against every profile above and capture commands,
server logs, client screenshots, and observed chat/action-bar output. Record the
exact target profile (currently Minecraft 1.21.1 / NeoForge 21.1.248 /
Vampirism 1.21-1.10.13) and confirm:

- no duplicate downed, revival, passenger, animation, or death resolution;
- carried players show the carry projection rather than faint;
- rope/cuffs remain visible after unconsciousness ends;
- black-sack vision clears only through the authorized release path;
- every unstable state eventually resolves and no player remains permanently
  locked or unconscious;
- live messages are understandable and threshold warnings do not repeat.

Until that evidence exists, DC-014 is not a complete release acceptance even
though the repository and CI gates pass.

## Addendum — 2026-09-27: hosted live-matrix results

The 2026-09-14 claim that "no live server/client profile can be claimed as
verified" is superseded. The release-candidate pipeline now runs
`tools/ci/server_harness.py` against real disposable NeoForge servers with the
pinned artifacts from `tools/ci/server_manifest.json`, and a real client job
runs the declarative scenarios under Xvfb.

Authoritative runs: RC
[36281326361](https://github.com/DurdeuVlad/straja-mod/actions/runs/36281326361)
on `6b11dbb`, RC
[36283147759](https://github.com/DurdeuVlad/straja-mod/actions/runs/36283147759)
on `e8d48a9`, RC
[36289723554](https://github.com/DurdeuVlad/straja-mod/actions/runs/36289723554)
on `221dd6f`.

Pinned artifacts: Minecraft 1.21.1, NeoForge 21.1.248, Vampirism
1.21-1.10.13, Incapacitated 2.0.1, Piggyback 1.1.1 (+ `faewulf_lib` 1.4.0),
CustomNPCs-Unofficial 1.21.1.20251230, Envelope `MmLbWZuv`, Ady's Decorations
1.0.7.2.

### Live profile matrix — verified

| Issue profile | Manifest profile | Result |
|---|---|---|
| Straja only | `required-only` | PASS — boots, native downed/carry ownership detected, custody legs run |
| Straja + Vampirism | `vampirism` | PASS — Vampirism owns vampire DBNO/stakes; Straja owns non-vampires |
| Straja + Incapacitated | `incapacitated` | PASS — fail-safe yields Straja downed ownership to Incapacitated |
| Straja + Piggyback | `piggyback` | PASS — fail-safe yields Straja carry trigger to Piggyback |
| Straja + Incapacitated + Piggyback | `incapacitated-piggyback` | PASS |
| Full intended modpack | `full-modpack` | PASS — all seven optional deps load, ownership stays fail-closed |

Each test-enabled profile also runs the RCON custody legs: `baton-strike`
(knockout, not death), `rope` → `UNCONSCIOUS_CUSTODY`, `head-sack`,
`custody-dump` membership assertions, `advance-time` deadline resolution,
`sack-remove`. Profile checks additionally assert expected-mod loading,
provider ownership markers, deployment gates, salary/coin behavior, and the
test surface.

### Real-client custody scenario — verified

`downed-custody` on a real NeoForge client under Xvfb (RC 36289723554 on
`221dd6f`, 30/30 steps): baton knockout → downed state and give-up surface →
rope restraint transitions canonical state to `UNCONSCIOUS_CUSTODY` →
black-sack visual applied (screenshot captured) → `advance-time 150` resolves
the 120 s unconscious-custody deadline → wake announce `Ai recăpătat
controlul` → conscious-restrained state persists → `sack-remove` restores
vision → bolt cutters → `cuffs-release` releases the rope → final
`custody-dump` asserts `bound=[] cuffed=[] sacks=[] downed=[]`. A second real
client (`ci_straja_2`) joins the same server mid-scenario and reports healthy
(`second-client-join`, `second-client-health`).

### Still unverified at the live layer

- Straja-native carry visual and resuscitation-interrupt legs: no `straja
  test` command exists for carry pickup or resuscitation; covered by
  `CustodyServiceTest` / `CustodyDeadlineEngineTest` only.
- Universal-key live tool leg: no test command; unit-tested.
- Vampire DBNO/stake live chain under Vampirism: ownership hand-off is
  asserted, the vampire kill chain is not driven live.
- Dimension-change and missing-jail live legs: teleport/rejoin are exercised;
  cross-dimension transport is unit-tested only.
- Player-facing spam thresholds and first/third-person visual comparison:
  messages are observed live; threshold cadence and camera-pair screenshots
  are not asserted.

These rows carry deterministic test evidence; per this report's rule they are
recorded as live-unverified, not passes.
