# Instituția automată — Milestone & Issue Plan

Status: **ready-for-handoff**. Planning date: 2026-11-02. Base: `release/0.5.0`
fast-forwarded to `devin/rc4-ui-regressions` @ `63ea637` (the verified
institution line — see "Base decision" below). Delivery: sequential commits on
the working branch (no PR/remote side effects authorized; commits only), per
the `npc-desk-menus-implementation-issues.md` convention.

## Contract

- **Goal:** Straja becomes a self-running law-enforcement institution: it
  marks serious crime without anyone filing paperwork, answers with real
  force even when zero human officers are online, prices lawbreakers through
  bounties any player can collect, and lets people inspect each other's
  inventory by consent ("verificare corporală") — while the desk UI stops
  reading like a chest GUI and becomes an obvious, labeled menu.
- **System:** existing hexagonal stack. New behavior enters through the
  existing seams — `StrajaEvents` (damage/death/death-attribution),
  `WantedService` (the one wanted-truth), `BountyService` (post/payout/bail),
  `CustodyService`+`PrisonService` (restraint→sentence→cell), the
  `InspectionLedgerEntry`/`SnapshotItem` accountability model, and the
  `straja:desk` wire contract (`DeskWire.View` — slot-indexed, action-ids
  never cross). Client changes stay inside `StrajaDeskScreen` + shared
  layout constants.
- **Constraints:** `gameplay-decisions.md` §17 wins on gameplay rules; §6
  (core state never depends on a loaded NPC) still holds — enforcement NPC
  AI is *local force*, never the authority. All authority re-validates
  server-side per click/action (`refuse(reasonKey, remedyKey)` + lang
  parity). CustomNPCs stays optional/reflective — native path must not
  require it. Romanian UI text. No admin step may be required anywhere in
  the loop ("no admin intervention" is the explicit product rule). New
  behavior is config-gated (`StrajaServerConfig` → `StrajaPolicies`) so
  existing worlds opt in deliberately.
- **Evaluation:** `./gradlew compileJava` + `./gradlew test` green per
  issue; unit tests for every pure decision (page layout, consent state,
  mark idempotency, engage/hold targeting); the applicable RCON/GameTest
  surfaces where they already exist; per-issue receipts recorded below.

## Base decision (resolved)

`release/0.5.0` fast-forwards cleanly to `devin/rc4-ui-regressions`
(`release/0.5.0` is a strict ancestor; +13 commits). That line carries the
prior institution work this goal depends on — parole desk surface, custody
desk loop at the Jailer, clickable consents, commissioner setup command,
and two real-client UI regression fixes — verified at commit time (1939
unit tests, RCON scenarios incl. `custody_desk`). Building on it avoids
re-implementing and conflicting with the parole/custody desk surfaces.
Fast-forward performed as milestone step 0; rewind with
`git reset --hard f7eb1b7` if the maintainer rejects the base.

## Verified facts that shape the plan

- `StrajaNpcEntity` is deliberately `setNoAi(true)`, speed 0, invulnerable
  except the jailer — desks are pure interaction surfaces. The only armed
  enforcement today is `StorageService.aggroScan()` steering **CustomNPCs**
  guard entities (reflection-only; absent without that mod).
- Murder has no mark: `RpExpansionService.recordFinalDeath` records
  reputation only. Jailer assault auto-spawns mission+incident+reputation
  (`StrajaEvents.onNpcDamage`) — the auto-mark precedent already exists.
- Bounties (`BountyService`) are complete — post/cancel/list/surrender/
  capture-payout/bail — but only ever *officer-posted*; nothing posts one
  automatically. `MIN_POSTER_RANK` = Locotenent.
- `WantedService.isWanted` already composes BOLO + FUGITIVE register +
  legacy marks, and `dropAggroAround` semantics exist for escorted targets.
- A player-inventory search exists only inside lawful custody/checkpoint
  contexts (`SeizureService`, `LawCheckpointRecord` crossings with
  `SnapshotItem` + `InspectionLedgerEntry`). No consent-based civilian
  frisk exists.
- `StrajaDeskScreen` renders actions as a 9×5 grid of bare 24px icon
  tiles — the chest-GUI look being removed. The wire contract
  (`DeskWire.View`: title, rows, tiles[slot,itemId,name,lore]) is
  layout-agnostic — the client alone decides geometry.

## Milestones

### INST-1 — Desk UI: labeled action rows, not an icon grid
**Outcome:** every `straja:desk` page reads as a menu: full-width rows with
icon + visible action label + one-line hint, footer nav as labeled buttons,
confirm pages as an always-visible consequence body over two labeled DA/NU
buttons. Nothing item-grid-like remains. **Proof:** new pure layout helper
unit-tested (row geometry, page capacity, confirm placement); desk tests
green; `describePageButtons` wire view unchanged (slot contract intact).
**Scope:** `StrajaDeskScreen`, `DeskLayout`, `DeskPageSource`/`DeskLayout`
confirm geometry if needed. **Non-goals:** wire protocol changes, action-id
vocabulary, chat fallback, CustomNPCs `GuiCustom`, `StrajaFormScreen`.
**Depends on:** nothing.

### INST-2 — Verificare corporală (consented inventory check)
**Outcome:** any player asks a nearby player "pot să te verific?" — the
target gets a timed accept/decline (clickable chat consent, same convention
as custody/bribe/trade consents). On accept the requester receives a
read-only inventory snapshot (chat-page list reusing the `SnapshotItem`
model); the check lands in the inspection ledger + audit. Consent gates
everything outside existing lawful contexts; declining is a recorded
right, not a mark. **Proof:** unit tests for consent lifecycle
(request→accept/decline/timeout), snapshot read-only guarantee,
range/policy refusals; ledger+audit rows; RCON or command-surface test.
**Scope:** new `FriskService`, `/straja verificare <jucător>` (+`frisk`
alias) with `accept|decline`, policy keys (`friskEnabled`, `friskRange`,
`friskOfferSeconds`, `friskCooldownSeconds`), ledger entry variant.
**Non-goals:** forced searches on free citizens (custody/checkpoint rules
unchanged); seizing items (existing `SeizureService` stays the only
confiscation path, under its own rules); a dedicated GUI screen.
**Depends on:** nothing.

### INST-3 — The law marks criminals without a clerk
**Outcome:** killing a player outside lawful contexts writes the record
itself — incident + wanted mark (BOLO) naming the killer — and a wanted
or fugitive player nobody prices gets a state-posted bounty ("emisa de
Straja") after a configurable grace, so the hunt is priced for everyone.
Guard lawful kills stay exempt (§15a lawful-force rule). Everything
config-gated, idempotent (no double marks, no stacked bounties).
**Proof:** unit tests — murder→mark, lawful-kill exemption, mark
idempotency on repeat kills, grace-window bounty posting, policy-off
no-ops; tick-sweep test for the auto-bounty pass. **Scope:**
`RpExpansionService.recordFinalDeath` path (or a dedicated `MarkService`
if cohesion demands), `BountyService` system-issuer path, a periodic
sweep (existing tick funnel), new policy keys (`autoMarkKills`,
`autoMarkWitnessOnly`, `autoBountyEnabled`, `autoBountyAmount`,
`autoBountyAfterHours`). **Non-goals:** NPC/AI work; changing what counts
as wanted (`WantedService` stays the truth); new crime types beyond
kill/assault witness marks already precedented by jailer assault.
**Depends on:** nothing (INST-4 consumes its output).

### INST-4 — Patrulier: the institution's force
**Outcome:** a bound `patrulier` NPC at a declared patrol post enforces the
law physically, with zero humans on duty: it scans its post radius for
wanted players, engages the nearest until downed or surrendered, applies
restraint through `CustodyService`, and books the prisoner into the
nearest free station cell through `PrisonService` — an institutional
transport, not a player escort ride. Attacking an officer is assault on
the institution (jailer-assault precedent). Officers stand down on
escorted/just-arrested targets (`WantedService.dropAggroAround` truth),
return to post when the area is clean, and survive restart via the
existing binding/registry persistence. **Proof:** unit tests for
target selection + stand-down rules + booking pick (nearest free cell,
none-free→dispatch call + hold); a GameTest or live smoke for the
spawn→engage→cuff→book loop; `patrulier` spawn-point provisioning like
the other five roles. **Scope:** AI goal set + role on the native NPC
path (`StrajaNpcEntity` or sibling), `/straja npc` + setup-spawn wiring,
`PrisonService` booking entry reuse, policies (`patrolOfficerEnabled`,
`scanRange`, `engageCooldown`, `postRadius`). **Non-goals:** pathfinding
escort rides (TransferService untouched — institutional booking may
teleport the prisoner, justified as the wagon); patrol route-following
beyond post-radius wandering; army ops; CustomNPCs behavior changes.
**Depends on:** INST-3 (a solo criminal must carry a mark for the officer
to hunt — escapes/unpaid fines alone leave blind spots).

## Execution receipts

| Issue | Commit | Outcome |
|---|---|---|
| INST-1 | 0f13463 | done — rows not tiles; desk tests green; adversarial review findings remediated (driver geometry, header hover tooltip, dead-slot guard, adaptive row sizing). Live-client pass still open per §Verification. |
| INST-2 | cf5b626 | done — consent-gated frisk; 12 service tests + wiring pin; P0 (unregistered action ids) + P2 (accept-time custody) remediated. |
| INST-3 | pending | |
| INST-4 | pending | |

## Residual risks / decisions owned by the maintainer

- **Auto-mark severity.** `autoMarkKills` defaults matter socially: an
  always-on murder mark is the "institution" promise, but on PvP-heavy
  worlds every duel becomes a case. Default planned: on, with
  `autoMarkWitnessOnly` available to restrict marks to witnessed space
  (near NPCs/posts/checkpoints). Maintainer can flip defaults in config.
- **No NPC criminals exist.** A lone *officer* still has nobody to arrest —
  missions/dispatch loops cover their fun today; synthetic crimes are a
  future milestone, not hidden in this one.
- **Chunk reality.** Officer force only exists where the NPC is loaded —
  consistent with §6 (authority never depends on it: marks, bounties and
  sentences are system-level). Withering a patrol to escape justice is
  legal gameplay; the mark outlives the officer.
- **Delivery boundary.** Commits land on `release/0.5.0`; no push/PR —
  the maintainer merges when they accept the milestone.
