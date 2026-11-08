# Item-first gameplay — mită cu monede, soldați plătitori — implementation issues

**Goal:** gameplay happens through in-game items, not typed commands —
admins use commands to set up, players act with items. The bribe
mechanic moves to coins-in-hand, and Straja soldiers pay their own
bounty: killing an officer drops ~2 brass and experience, so farming
them is a real criminal income.

## Design

- **ITEM-1 coin bribe.** Holding any coin-tier item
  (`economy.coinItems` denominations) and right-clicking an officer is
  the bribe:
  - **Human on-duty guard** — the click creates the bounded consent
    offer (`offers` map, `[Acceptă]`/`[Refuză]` ActionLinks already
    wired) targeting *that* guard, not the nearest one. Same accept
    path as today: withdraw → `bribeUnlock` → `corrupt_guard` +
    `kind=bribe` trail → witnessed mark.
  - **NPC officer** (`patrulier`, `jailer` — "certain officers") —
    answers instantly by a deterministic trait: the entity uuid hashes
    against `escape.npcBribeAcceptPercent` (default 40), so each
    spawned officer is stably honest or corrupt — find out which by
    offering. Corrupt: withdraw `bribePostPrice`, door opens, money
    trail records `target=npcUuid`. Honest: the offer is declined and
    reported, same as a human refusal.
  - **Discipline on sight.** A *witnessed* NPC bribe means a second
    on-duty guard saw the coins move — the institution relieves the
    corrupt officer immediately (the entity is discarded by the
    adapter, after a dispatch notice fires). Unwitnessed, the trail
    stays in the ledger.
  - **Citizen enforcement on NPCs.** `denunt` resolving to a
    *registered* NPC officer with a proven trail disciplines it the
    same way (dismissal by the institution) instead of issuing a BOLO
    no patrol can serve. Human suspects keep the warrant pipeline.
  - Prisoner must be `IN_CELL` with an unlockable cell — identical
    requirement to the existing flow.
- **DROP-1 soldier loot.** `StrajaNpcEntity` death on officer roles
  (`patrulier`, `jailer` — the damageable ones) drops brass coins
  (the `coinItemIds` brass tier) — `npc.soldierCoinsMin`…`Max` default
  1–3 (mean 2 "around 2 brass") — plus `npc.soldierXp` (default 8)
  experience via the vanilla `getExperienceReward` path. Desk roles
  are invulnerable and drop nothing.
- Commands stay as the admin/headless path; the item gesture is the
  player-facing surface.

## Issues

| # | Issue | Status | Commit | Notes |
|---|-------|--------|--------|-------|
| ITEM-1 | Coin-click bribe on human + NPC officers, corruptibility trait, witnessed discipline, NPC denunt | done | `91b8f5c` | Adversarial review remediated: idempotent dismissal, player-identity warrant gate, provider-bound officers included, hand-gated gestures |
| DROP-1 | Officer-role NPCs drop ~2 brass + XP on death | done | `91b8f5c` | Player-attributed kills only (recentlyHit); brass resolves by coinTierRatio, never ordinal |

## Execution receipt

| Issue | Commit | State |
|---|---|---|
| ITEM-1 | `91b8f5c` | verified — 2037 unit tests green, 51/51 live GameTests incl. `denuntDismissesRegisteredNpc` |
| DROP-1 | `91b8f5c` | verified — `soldierDropsCoinsAndXp` passes on the live GameTest server |
