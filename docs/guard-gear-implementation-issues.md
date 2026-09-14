# Guard Gear Ownership & Armory — Implementation Issues

Status date: 2026-09-14 (implemented — issues #56–#64, branch feature/guard-gear-armory)

Implementation queue for replacing the per-shift equipment lease with owned
gear granted at rank-up, a new Armorer NPC shopkeeper for resupply and
rank-gated upgrades, and a patrol rework: variable admin-defined checkpoint
counts, a shorter inter-checkpoint wait, and a hard real-time cap. Not in
production — free to change/remove machinery outright; no save-format
migration story is required, though legacy `GuardState`/`SetupData` fields
should drop cleanly on read.

## Original intent

Today every shift is a loan: `startDuty` issues `serviceEquipment` (marked
`StrajaService` items with serials) and `endDuty` reclaims it, charging
missing pieces against unpaid salary then `equipmentDebt`. Roleplay-wise this
is awkward — a guard who takes a mission mid-day has no gear until the next
shift starts.

New model:

- **Gear is owned.** No issue/reclaim on duty start/stop. The baton, cuffs
  and sword in `serviceEquipment` become permanent kit contents.
- **Rank-up grant.** Each promotion delivers the rank's loadout plus a
  monetary bonus — `promotionBonusHours` (default 5) × `salaryPerHour` of the
  *new* rank, credited to `unpaidSalary`. Better gear each rank; old gear is
  kept.
- **Armorer NPC.** A shopkeeper role that (a) sells rank-gated gear for
  physical coins and (b) hands out reserves — replacement gear paid from a
  spendable merit balance.
- **Two ledgers.** `serviceBlocks` stays the promotion counter (never spent);
  a new `requisitionPoints` balance accrues alongside it and is spent at the
  armory. Punishments can dock *both* — promote XP and the requisition
  budget.

## Current model → target model

| Today | Target |
|---|---|
| `serviceEquipment` leased per shift, reclaimed with serial matching, missing → `unpaidSalary`/`equipmentDebt` | No lease. All gear permanent, unmarked items |
| `kits[rank]` once per rank, *excluding* leased items (`giveKit` filters service ids) | `kits[rank]` is the full rank loadout; filter removed |
| `regear`/`approve-regear` inbox flow, `regearCost[rank]` from salary | Armorer "reserves" spend `requisitionPoints`; approval inbox flow retired |
| `serviceBlocks` = promotion gate only | `serviceBlocks` promotion gate + `requisitionPoints` spendable |
| No shopkeeper | `armorer` role sells `armoryStock` for coins via `CurrencyProvider.withdraw` |

## Design principles (carried over, plus two)

1. Commands stay canonical; NPC/token surfaces converge on the same service
   calls. `/straja kit`, `regear`, `approve-regear` change shape or retire,
   but every mutation remains service-side and re-checks authority.
2. State lives in `GuardState`/SavedData, never in item NBT. The
   `StrajaService` marker/serial scheme disappears with the lease — no item
   data is ever trusted.
3. Money movements are atomic: coin sales use `currency().withdraw`
   (exact-change, rollback-tested); failure must never take coins without
   delivering goods — preflight `canReceive`, refund via `deposit` on give
   failure.
4. **New:** gear ownership is permanent and cumulative — no code path may
   remove a lower rank's granted items on promotion.
5. **New:** `requisitionPoints` is a floor-0 balance; every deduction clamps
   and is audited. `serviceBlocks` is never decremented by spending — only
   by explicit punishment docking.
6. Fail closed on wrong rank, insufficient funds/points, full inventory.

## Dependency order

`EQ-001` (merit ledger) and `EQ-002` (rank-up grant) are independent
foundations. `EQ-003` (lease retirement) should land with or right after
EQ-002 — otherwise guards would have no gear source. `EQ-004` (armorer) is
the surface; `EQ-005` (reserves) needs EQ-001+EQ-004. `EQ-006` (punishment
docking) needs EQ-001. `EQ-007` is the release gate.

`PAT-001`/`PAT-002` (patrol rework) are independent of the EQ line but share
EQ-003's file surface (`DutyEngine`, `GuardService`, `SetupData`, wand
finish path) — sequence them after EQ-003 to avoid a merge tangle.

## EQ-001 — Requisition ledger (spendable merit)

Priority: P0 — foundation for reserves and docking.

- New `GuardState.requisitionPoints` (long, default 0).
- Accrual inside `DutyEngine.accrue` on the same `serviceBlockMinutes`
  cadence as serviceBlocks — `requisitionPointsPerBlock` policy (default 1).
  Optional hook: mission completion awards points
  (`missionRequisitionReward`), if cheap to wire.
- `PlayerService`/`GuardService` deduction helpers clamped at 0, with audit
  records. Deduction ≠ spending: spending happens only in EQ-005.
- `StrajaServerConfig` entries for the new policies.
- Tests: accrual cadence (points tick per block), persistence round-trip,
  deduction clamps at 0, audit written.

## EQ-002 — Rank-up grant replaces the lease

Priority: P0 — the core behavior change.

- Merge `serviceEquipment` item ids into `kits` defaults (sword/baton/cuffs
  become permanent per-rank contents); remove the `giveKit` service-id
  filter — it exists only to skip leased items.
- On every effective rank-up — quiz pass → Stagiar, trainer-confirmed
  promotion, admin `promote` — deliver the rank kit (`giveVerified`,
  per-item persisted progress like the old lease's `delivered` flag if
  partial) and credit `promotionBonusHours × salaryPerHour(newRank)` to
  `unpaidSalary`; tell + `rank_grant` audit record.
- Never remove lower-rank items. Re-promotion after demote grants the kit
  only if that rank was never claimed (`kitClaimedRank` already tracks);
  the bonus firing per promotion event vs. first attainment only is an open
  question (see below) — default to per-event unless it farms obviously.
- Tests: each rank's grant contents + bonus amount (80/120/180/320 at
  defaults), kit idempotence per rank, partial-inventory path, bonus
  credited to `unpaidSalary` not minted as coins.

## EQ-003 — Retire the service-equipment lease

Priority: P0 — cleanup that makes EQ-002 coherent.

- Remove `issueServiceEquipment` calls from both `startDuty` paths (patrol
  and free duty) and `reclaimServiceEquipment` from every `endDuty` caller:
  voluntary stop, patrol complete, demote/suspend/fire, restart recovery,
  equipment-issue-failure rollback.
- Remove `GuardState.ServiceEquipment` lease record, `equipmentDebt`,
  `settleEquipmentDebt`, `StrajaService`/`StrajaServiceItem`/`StrajaServiceRank`
  markers and serials, `regearPending`, `kitClaimedRank` reset-on-regear
  (kit reset now belongs to re-promotion logic only).
- Retire `/straja regear` + `approve-regear`, the REGEAR inbox flow,
  `regearCost`, `serviceLeaseMinutes`, `serviceEquipment` policy/config
  entries; drop the regear button from `dutyActions` and `DutyView`'s
  `canRegear`; update help text.
- Sweep for stragglers: `isServiceStack`/`MARKER*` readers, `TestCommands`
  entries, `EventSurfaceTest`/`GuardServiceTest` equipment cases.
- Capability check confirmed safe: `CustodyService.enforcementGuard` is
  rank-based and duty-independent — permanent cuffs/baton change nothing
  about who may use them.
- Tests: `startDuty`/`stopDuty` never touch inventory; duty no longer
  blocked by gear delivery; all removed-surface references gone (compile +
  surface tests).

## EQ-004 — Armorer NPC + coin shop

Priority: P1 — the new roleplay surface.

- New `armorer` role: `NpcAdminService.KNOWN_ROLES`/`ROLE_ORDER` (setup
  checklist, `setup npcs`, and the admin-tool wand menu pick it up free),
  `RoleRoute.ARMORER`, `RoleSurface` with actions:
  - "Catalog" — read-only list of stock with prices + rank requirements;
  - parameterized `armory-buy:<stockKey>` token actions;
  - "Rezerve" — EQ-005 entry point;
  - "Ridică echipamentul de rang" — kit claim, if it moves here (see open
    questions; the Secretary can keep it too — but one gear surface is
    cleaner).
- `armoryStock` policy: list of `{key, itemId, count, priceBronze, minRank}`;
  config parse/format + `policy set` support like the other maps.
- Buy path in a service (extend `EquipmentService` or new `ArmoryService`):
  active-guard + `minRank` check → `canReceive` preflight →
  `currency().withdraw` (atomic) → `giveVerified`; on give failure, refund
  via `deposit`; audit `armory_buy`.
- `PARAMETERIZED_ACTIONS` + `isStillValidForPlayer` entries so stale buy
  buttons refuse cleanly.
- Tests: rank gate, insufficient funds fail closed (zero coin loss),
  full-inventory atomicity (refund path), unknown stock key refused.

## EQ-005 — Reserves (requisition resupply)

Priority: P1 — needs EQ-001 + EQ-004.

- `armoryReserves` policy: per-item point prices for rank-legal replacement
  gear (e.g. baton 5 pts, cuffs 5 pts) — or a flat "top up my rank loadout"
  bundle; pick one, configurable either way.
- Reserve action: active guard → points ≥ cost → deduct (clamped) →
  `giveVerified` → audit `armory_reserve`. Default: usable on or off duty —
  gear is owned now.
- Tests: deduction exact, insufficient points refused with balance shown,
  reserve limited to items the rank may legally hold, on-duty use per the
  chosen rule.

## EQ-006 — Punishment docking

Priority: P2 — needs EQ-001.

- `demote` docks `demotionServiceBlockCost` serviceBlocks;
  `suspend` docks `suspensionRequisitionCost` requisition points (defaults
  configurable, floor 0), each with audit + tell naming the docked amount.
- Canonical admin command `/straja merit dock <player> <points>` (adminOnly)
  for discretionary deductions of the requisition balance.
- `fire`/`resign` already end membership — decide whether they also zero
  the requisition balance (recommended: yes, it's a service entitlement).
- Tests: clamp at 0, demote/suspend apply configured amounts, audit
  entries, dock on a civilian refused.

## EQ-007 — Docs, checklist, release gate

Priority: P1, last.

- `docs/parity-matrix.md` rows for the new mechanics; README equipment
  section rewritten (lease → ownership + armory); `gameplay-decisions.md`
  §10/§11 notes updated.
- Verify `setup` checklist counts the armorer (`missingNpcRoles` via
  ROLE_ORDER) and the survey-rod/wand surfaces still converge.
- RCON `test` commands for armory buy/reserve/dock; live-server UAT
  including insufficient-funds and wrong-rank cases.
- Release note: gear is permanent from rank-up; armory sells rank-gated
  stock; reserves cost requisition points; punishments dock both ledgers.

## PAT-001 — Variable-length patrol routes

Priority: P0 — the "N checkpoints generated by admins" half of the ask.

Today `setup.checkpoints` is a **fixed four-slot list** (`checkpoint_1`…
`checkpoint_4`): `startDuty` requires ≥4 placed, `route_invalid` says
"exactly patru checkpoint-uri", `setupCheckpoints` lays a 4-point square,
and the patrol wand's finish path enforces exactly-N slots.

- `SetupData.checkpoints` becomes a growable list. Admins add/remove via a
  canonical command (`/straja checkpoint add|remove <id>` — converged on by
  the patrol wand: wand clicks append a new slot instead of requiring an
  existing one; a remove mode or `checkpoint remove` deletes).
- `startDuty` validation: `≥ patrolMinCheckpoints` placed (policy, default
  e.g. 2–4) rather than exactly 4; route = placed checkpoints in definition
  order. `route_invalid` message updated to name the real minimum.
- `missionMinutes` map keys follow dynamic ids — already keyed by id, just
  needs seeding for new slots.
- One pass, not a loop: normal patrols get `requiredRounds = 1` so the last
  checkpoint ends the shift via the existing `patrol_complete` path (the
  emergency-snapshot machinery already proves this works). Looping forever
  is the alternative — see open questions.
- Update `setupCheckpoints` quick-square (still seeds a starting layout, N
  configurable), the "exactly patru" strings, the quiz question about
  checkpoint timing, `duty`/`regulament` help text, checklist counts.
- Tests: 2-checkpoint and 6-checkpoint routes start; fewer than minimum
  refused; wand finish appends slots; single pass ends duty
  `patrol_complete`; remove-a-checkpoint mid-roster doesn't corrupt a
  running route (running route is a copied list — verify).

## PAT-002 — Patrol timing: 5-minute wait, 1-hour cap

Priority: P0 — the second half of the ask.

- `checkpointUnlockMinutes` default 10 → **5**. Two messages hardcode
  "10 minute" (`checkpoint_activated` tell and `checkpoint_not_active`
  refusal) — switch them to the policy value, which fixes the existing
  drift too.
- New `patrolMaxMinutes` policy, default **60** real minutes: a NORMAL-duty
  tick at `dutyStartedAt + cap` ends the shift with
  `duty_ended(reason=patrol_time_cap)` — graceful end, salary accrued
  normally. Per-checkpoint `missionMinutes` deadlines still apply *within*
  the cap; SPECIAL/FREE duty unaffected (Sergent+ at-will shifts keep no
  cap unless separately desired).
- Interplay to spell out in code comments: cap vs. last-checkpoint wait —
  a guard in WAITING at the cap boundary ends cleanly rather than timing
  out mid-pause.
- Tests: cap ends a long patrol with `patrol_time_cap`; unlock fires at 5
  min; checkpoint deadline still fires inside the cap; FREE duty has no
  cap; restart recovery preserves `dutyStartedAt` so the cap survives
  reboots.

## Open questions

1. **Kit claim surface**: does "Ridică echipamentul" move from the
   Secretary to the Armorer, or is the rank grant fully automatic on
   promotion with no claim step? Automatic-on-promotion matches "you get
   weapons every time you rank up" most literally; a claim step survives
   gracefully when inventory is full.
2. **Bonus semantics**: per promotion event vs. first attainment of each
   rank. Per-event is the literal ask; first-attainment blocks a
   demote/promote bonus farm. Leaning: bonus per event, gear once per rank.
3. **Reserve pricing model**: per-item point prices vs. a loadout bundle.
4. **Which punishments dock which ledger**: proposal above (demote →
   serviceBlocks, suspend → requisition) is a default, not a decision.
5. **Stock contents**: what "better gear" means concretely per rank —
   enchanted/diamond items, utility items, or faction cosmetics; the
   `armoryStock` config supports anything, needs a curated default list.
6. **Patrol end semantics**: does hitting the last of N checkpoints end the
   shift (`patrol_complete`, one pass) or does the route keep looping until
   the 1-hour cap? One pass is the doc default — a patrol that "finishes"
   matches the N-checkpoint framing better than a loop with a ceiling.
7. **Cap behavior at 60 min**: graceful `duty_ended` (proposed) vs. a
   `checkpoint_timeout`-style mark. Also whether the cap applies only to
   NORMAL patrols or should bound SPECIAL/FREE shifts too.
8. **"5 minutes"**: read as the inter-checkpoint unlock wait (10 → 5). If
   it was meant as the per-checkpoint *deadline* instead, that's a one-line
   policy default change (`checkpointDeadlineMinutes`/`missionMinutes`)
   rather than an engine change — confirm.
