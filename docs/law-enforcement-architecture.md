# LAW-000 — Law Enforcement Architecture: Prototype → Native Mapping

Discovery specification for the Law Enforcement milestone (#14). Maps every
behavior of the three legacy KubeJS server scripts onto native NeoForge
21.1.252 APIs and the existing `straja-mod` hexagonal architecture, before
M1–M8 implementation issues touch domain code.

- **Scope**: architecture review, API auditing, hook cataloging. No runtime
  game code changes land under this issue; native replacements for missing
  concepts are named as *target* types to be created by M1+.
- **Prototypes audited** (live clone, `kubejs/server_scripts/`):
  `port_checkpoint.js` (2430 lines), `straja_prison.js` (2509),
  `straja_storage.js` (1000 — the generalized successor of `straja_gold.js`).
- **Already native**: `straja_storage.js` is **done** — ported as
  `StorageService` + adapters in PR #221 (issue #220). This document records
  that mapping as precedent and leaves M8 to migrate legacy save keys.

All NeoForge class names below were verified against
`build/moddev/artifacts/neoforge-21.1.252.jar`.

---

## 1. Security boundary (non-negotiable)

The prototypes ran inside KubeJS with full server access; several "client"
touchpoints (CustomNPCs dialogues, quest commands, pressure-plate command
blocks) are **untrusted inputs** natively. Rules carried into every mapping:

1. **Server-authoritative state.** Crossing judgments, contraband scans,
   seizures, custody transitions, and debt ledgers live in `SavedData`-backed
   stores under `application/port/out` + `adapter/out/persistence`. A client
   or NPC-scripted action can *request* (dialogue action, command, block
   interaction) but never *decide*.
2. **No command-string authority.** Prototype `runCommandSilent` calls
   (`noppes faction`, `tag add`, `execute in … tp`, `gamemode`) become typed
   port calls: `NpcGuardGateway.setFactionPoints`, `PlayerGateway` gamemode,
   `ServerGateway.teleport`. External `strajastorage deposit`-style bridges
   survive only as permission-gated command roots feeding the same service.
3. **Optional dependency isolation.** CustomNPCs is a soft dep: all access
   goes through reflection in `adapter/out/npc/customnpcs/` behind ports
   (`NpcGuardGateway`, `FactionGateway`). With CNPC absent the ports return
   no-op/disabled and every feature degrades cleanly (precedent: storage
   aggro scan skips when `!guards.available()`).
4. **Offline-safe ledgers.** Everything keyed by player UUID persists in
   world `SavedData`; transient per-session state (pick modes, last
   positions) may be in-memory but must disarm on logout (precedent:
   `StorageService` picker cleanup on `PlayerLoggedOutEvent`).

---

## 2. Verified NeoForge 21.1.252 API catalog

| Prototype hook (KubeJS) | Native replacement (verified in jar) | Notes |
|---|---|---|
| `ServerEvents.tick` | `ServerTickEvent.Post` (or `LevelTickEvent.Post` per-level) | periodic scans: gate crossing, boarding, chest polling, rep enforcement, custody sweeps |
| `PlayerEvents.loggedIn` | `PlayerEvent.PlayerLoggedInEvent` | recover custody/prison/thief state, drop stale `prev` positions, re-issue hunt quests |
| `PlayerEvents.loggedOut` | `PlayerEvent.PlayerLoggedOutEvent` | disarm pickers, drop transient scan state |
| `PlayerEvents.respawned` | `PlayerEvent.PlayerRespawnEvent` | jail-on-respawn, camp dormitory respawn (AT7) |
| `EntityEvents.death` | `LivingDeathEvent` (filter `ServerPlayer`) | death-in-custody seizure, hunted-death bookkeeping |
| `BlockEvents.broken` | `BlockEvent.BreakEvent` | zone theft trigger (storage precedent) |
| `BlockEvents.placed` | `BlockEvent.EntityPlaceEvent` | repayment-by-placement (storage precedent) |
| `BlockEvents.rightClicked` | `PlayerInteractEvent.RightClickBlock` | pick-mode capture (gate corners, cells, chests); gate `MAIN_HAND` |
| `ItemEvents.pickedUp` | `ItemEntityPickupEvent.Post` | theft on pickup; delta = `getOriginalStack()−getCurrentStack()` |
| *entity mount (implicit in `p.getVehicle()` poll)* | `EntityMountEvent` | boarding-zone checks fire on mount instead of polling vehicle state |
| `player.getVehicle()` per tick | keep `PlayerTickEvent.Post`/`ServerTickEvent.Post` poll *or* `EntityMountEvent` + `ServerPlayer.getVehicle()`; recommend the mount event (cheaper, catches dismount-free zone exit is impossible — stamp logic already TTL-bound) | boarding checks |
| `server.runCommandSilent('execute in … tp …')` | `ServerPlayer.teleportTo(ServerLevel, x,y,z, yaw,pitch)` via `WorldGateway`/`ServerGateway` | pushback + jail teleport |
| `block.set('minecraft:iron_door', props)` | `ServerLevel.setBlock(pos, state.setValue(DoorBlock.OPEN,false), 3)` via `WorldGateway` | door closure |
| `player.persistentData` | `SavedData` stores (`adapter/out/persistence`, `JsonBackedStore`) keyed by UUID | all durable flags move to stores |
| `player.tell`, `cpTitle`, `cpSound` | `PlayerGateway.sendMessage`/`tellKey`, `title`, `playSound` | existing gateway methods |
| `stack.get(DataComponents.CONTAINER/BUNDLE_CONTENTS)` | same vanilla API on `ItemStack` inside `MinecraftInventoryView`/`ItemView` | deep container scan, §5 |
| ItemHandler capability | `Capabilities.ItemHandler.ITEM` | modded backpacks — see §5 |
| `player.inventory` iteration | `Player.getInventory()`, `player.getOffhandItem()`, armor slots via `InventoryView` | inspection snapshots |
| `Noppes` API / `noppes` commands | `NpcGuardGateway`, `FactionGateway` (reflection) | faction rep pin, quest start/finish, NPC target steering |
| Brigadier `ServerEvents.commandRegistry` | `RegisterCommandsEvent` → `StrajaCommands` subtrees | all admin/player commands |

Spatial math stays pure-domain: segment crossing (`cpSegCross`), side-of-line
(`cpGateSide`), AABB overlap (`cpGateNear`), radius checks — zero Minecraft
deps, unit-testable without a world.

---

## 3. `port_checkpoint.js` — checkpoint & border system

### 3.1 Behavioral audit

**Site model** (`portCheckpointCfg` JSON): named sites {anchor pos, gates[],
board zone, doors[], denyTarget, link, cb overrides, siteExempts} plus global
{contraband map, bans, exempts, jails}. Sites resolve "nearest" within
`CP_SITE_RADIUS` (24) for command context, but gate scanning tests *every*
site's gates (lanes can sit beyond the anchor radius).

**Two-stage crossing** (`cpInspect`, `cpArrest`/delegation): command-driven,
fired by pressure-plate command blocks or NPC dialogue at two physical
points. Stage 1 (`inspect`): banned → title+pushback to `denyTarget`;
fugitive/hunted → delegate arrest; contraband → warn title + itemized list +
log `warned`; clean → log `in`, optional confirmation. Stage 2 is the prison
service's arrest path (see §4). Legacy single-stage verbs `aggressive`
(mark wanted `strajaWantedUntil`), `info` (list only), `deny` (pushback only)
remain for custom gates.

**Gate lanes** (`cpGateScan`, every 5 ticks): per-player prev-position map;
movement segment vs each gate's directed segment `a→b`; `cpSegCross` strict
segment intersection + `cpGateNear` 3-block margin; `CP_GATE_MAX_STEP`=14
teleport guard; survival/adventure only. Wrong-way (`cpGateSide` sign
mismatch vs `from` marker) → `cpGateDeny` teleports back to the **origin
point** (never `from`, which would deliver exit-violators to their
destination) + close doors + log `denied`. Right-way at a **linked** site:
consumes a boarding stamp (`cpBoard`, 20 min wall-clock TTL) or runs the full
stage-2 pipeline at the line (`cpGateArrive`: banned→pushback,
custody/hunted→arrest, contraband→arrest, else log).

**Boarding zones** (`cpBoardScan`): player riding a `*boat*`-typed vehicle
inside a site's board rectangle (XZ box + |Δy|≤6) is scanned on the spot;
contraband/banned/custody → arrest; clean → stamp written. Prevents
jump-off-mid-route checkpoint skipping.

**Pushback & doors**: `cpPushBack` = tp to site `denyTarget` (yaw preserved)
+ `cpCloseDoors` iterating `site.doors[]` setting `iron_door.open=false` on
both door halves (dy 0..1).

**Deep contraband scan** (`cpScanContraband` → `cpSubStacks` +
`cpScanTagForContraband` + `cpDeepScan`, recursion depth ≤3): inventory +
armor + offhand + curios slots; per stack: `DataComponents.CONTAINER`
(shulker), `DataComponents.BUNDLE_CONTENTS` (bundles), NeoForge ItemHandler
capability (Backpacked/Sophisticated) — components first, capability only if
components yielded nothing (no double-count); else `stack.save(registryAccess)`
NBT fallback scanning `components` subtree for item IDs. Site-local `cb[id]`
overrides global contraband (`true`=extra ban, `false`=site exception).

**Inspection ledger**: `cpLogEvent` appends `{t,event,name,inv,detail}` to
`kubejs/checkpoint-log.json` (cap 2000); `inv` = `cpInvSummary` full
inventory snapshot `{id:{count,name}}` taken **before** any mutation.

### 3.2 Mapping matrix (target = new types unless noted)

| Prototype | Native target | API / mechanism |
|---|---|---|
| sites/gates/board/doors/cb/bans/exempts config | `CheckpointSite`, `GateLane`, `BoardingZone` domain records + `CheckpointSiteStore` (`SavedData`) | config primitives already proven by `StorageZone`/`StoragePoint` (#221) |
| `ServerEvents.tick` 5-tick scans | `CheckpointWatchService.tick()` from `StrajaEvents.onServerTick` | `ServerTickEvent.Post` |
| `cpGatePrev` per-player last pos | in-memory `Map<UUID,PrevPos>` in service; drop on login/logout | `PlayerLoggedIn/OutEvent` |
| `cpSegCross`/`cpGateSide`/`cpGateNear`/`cpGateDeny` math | `GateLane` domain: `crosses(prev,cur)`, `wrongWay(...)`, `backTo` | pure functions, unit-tested |
| `cpPushBack`, door close | `PlayerGateway.teleport` (existing), `WorldGateway.setDoorOpen(pos,false)` (new port method) | `ServerLevel.setBlock` `DoorBlock.OPEN` |
| boarding zone + boat check | `BoardingZone.contains(pos)` + mount trigger | `EntityMountEvent` (or keep tick poll on `ServerPlayer.getVehicle() instanceof Boat`) |
| `cpBoard` stamp TTL | `BoardingStamp` in `CheckpointSiteStore` (epoch ms, like storage hunt fields) | wall-clock TTL survives restarts (prototype rationale preserved) |
| `cpInspect`/`cpAggressive`/`cpInfo`/`cpDeny` stage verbs | `CheckpointService.inspect/deny/info/markWanted` invoked by `/straja checkpoint …` **and** an NPC surface action (`NpcSurfaceAction`) so dialogues/plates keep working | plates become interactable block bindings or stay command-block → `/straja` alias |
| `cpScanContraband` + deep scan | `ContrabandScanner` (application service) over `InventoryView` + new `DeepItemScanner` port | §5 |
| `cpLogEvent`/ledger | `InspectionLedger` domain + `ArrestRecordService`/`EvidenceService` snapshot reuse; new `CheckpointLogStore` | JSON `SavedData`, cap port of `CP_LOG_MAX` |
| `cpIsBanned`/exempts | fields on `CheckpointSite` + global `CheckpointPolicy` | — |
| `strajaWantedUntil` flag | `BoloService` active-BOLO check (storage already made this switch in #221) | wanted = BOLO record, not raw NBT |
| `cpInCustody` check | `CustodyService.isCuffed/isBound` + `PrisonService.activeSentence` | existing |
| `cpDelegateArrest` → `strajaprison arrest` | direct call `PrisonService.arrest(...)` with `ArrestRecord` reason/site | typed, no command string |
| pick modes `cpPick*` | session-scoped map in `CheckpointService` (storage precedent), `MAIN_HAND` right-click | — |
| `/strajacheckpoint …` command tree | `/straja checkpoint …` subtree, admin-gated via `CommandPermissions`; keep `strajacheckpoint` alias for live plates/NPCs (restricted like `strajastorage`) | `RegisterCommandsEvent` |

### 3.3 Prototype notes worth preserving

- **Wrong-way teleport target is the origin point**, not the `from` marker —
  using `from` for exit-direction violations would deliver violators to their
  destination.
- `CP_GATE_MAX_STEP`=14: larger inter-scan jumps are treated as
  teleports/flight and ignored — prevents phantom crossings on relog/elytra.
- Stale `cpGatePrev` is dropped on login; a relogged player's first tick
  must not phantom-cross a lane.
- Scan cadence is 5 ticks — with a 3-block margin that's still sub-walk-speed
  granularity; keep the same modulus to bound CPU.

---

## 4. `straja_prison.js` — custody, seizure & release

### 4.1 Behavioral audit

**Jail register** (`strajaPrisonJail` JSON; `portCheckpointJail` read as
legacy alias): `jailed[name]={t,reason,items,confiscated,status,site,arrests,
pchest,jcell}` + `fines[name]=total`. `status`: `jailed`|`fugitive`.

**Arrest pipeline** (`cpFinishArrest`): allocate personal-chest pair index
(`cpAllocPChest`) + cell index (`cpAllocJCell`) if first booking →
`cpScanContraband` + `cpInvSummary` snapshot **before** seizure → `cpSeizeAll`
(contraband → site `evidence[]` chest list with sequential overflow; personal
items → the allocated personal chest pair; curios slots included) → flags:
`cpJailWasMode` (first time only), `cpJailed=true`, `cpFugitive=false`, scoreboard
tag `cp_jailed` → clear hunt flags + restore faction rep (custody consumes the
hunt) → adventure mode → `cpTpJail` → `cpGiveReport` (written book intake
report) → register entry + log + jailer notify. Works for offline players
(entry written, physical steps deferred).

**Escape sweep** (`cpSweepJail`, 5-tick): for each jailed player —
stale-flag heal (released-while-offline → clear, restore mode+rep);
`!cpJailed` → set; non-adventure non-exempt → force adventure; distance to
cell `jt` > `SP_JAIL_RADIUS`(24) or wrong dimension → `status=fugitive` +
`strajaWantedUntil` + faction rep 0 + log `escaped`. Fugitive physically back
inside cell perimeter → recapture path.

**Death/respawn**: `EntityEvents.death` — jailed or hunted player dying gets
`cpSeizeAll` into personal chest + `cpJailOnRespawn` flag (hunted non-jailed
also get a register entry so they're booked); `PlayerEvents.respawned` —
`cpJailOnRespawn` → full `cpFinishArrest`; jailed → `cpRecapture` + adventure
+ `cpTpJail`.

**Release** (`cpRelease`, jailer-gated): optional fine accumulation → pour
personal chest pair back (`cpPourPChest`) → restore gamemode from
`cpJailWasMode` → clear `cpJailed`, tag → status out of register.

### 4.2 Mapping matrix

| Prototype | Native target | API / mechanism |
|---|---|---|
| jail register `strajaPrisonJail` | extend `PrisonStore`/`Sentence` + `ArrestRecordStore` (booking fields: items, confiscated, pchest, jcell, site, arrests) | `SavedData` |
| `cpJailed`/`cpFugitive`/`cpJailOnRespawn`/`cpJailWasMode` flags | `CustodyStore`/`PrisonStore` fields (CustodyState already carries restraint/jail status) | — |
| `cpFinishArrest` | `PrisonService.arrest(...)` extended with seizure pipeline hook | existing service + new `SeizureService` for physical movement |
| `cpSeizeAll` contraband→evidence / personal→locker | `SeizureService` using `WorldContainerGateway.insert` (merge-then-fill, `-1`=invalid item) + `ChestBlock.getContainer` canonical keys (double-chest precedent from #221) | physical chest writes are server-side only |
| `evidence[]` sequential overflow | `EvidenceChestPool` domain: ordered point list, fill-first-fit, mark changed | — |
| `cpAllocPChest`/`cpFreePChest` personal lockers | `LockerPool` domain + sign write (`WorldGateway.setSignText`, replaces `cpWriteSign` command) | — |
| `cpAllocJCell`/`cpJailSpot`/`cpTpJail` | existing `PrisonService` cells + `PlayerGateway.teleport` | `createCell`, `insideCell` already exist |
| `cpGiveReport` written book | `DocumentService`/written-book item creation (`DataComponents.WRITTEN_BOOK_CONTENT`) | reuse document pipeline |
| `cpSweepJail` escape/fugitive | `PrisonService.tick()` extension (already ticks) + `BoloService` for fugitive marking | `ServerTickEvent.Post` |
| rep suppress/restore (`cpSuppressRep`, faction id 12) | `NpcGuardGateway.setFactionPoints` with rep backup in `PrisonStore` (storage `ThiefRecord.repBackup` precedent) | CNPC reflection |
| death seizure/`cpJailOnRespawn`/respawn recapture | `StrajaEvents.onPlayerDeath` → `PrisonService` hook; `PlayerRespawnEvent` → recapture | storage `onPlayerDeath` already wired here |
| `cpRecapture` | `PrisonService.arrest` reuse with reason | — |
| `cpRelease`+fines+jailer gate | `PrisonService.release(actor,target,reason)` + `FineService` | both exist; add locker restore + mode restore |
| `/strajaprison …` | `/straja prison …` subtree + restricted `strajaprison` alias | — |

### 4.3 Prototype notes worth preserving

- **Snapshot before seizure** — `cpInvSummary` runs before `cpSeizeAll`
  empties slots; AT4's "snapshot at exact moment of arrest" depends on this
  ordering.
- **Custody consumes the hunt** — arrest clears thief/wanted flags and
  restores faction rep so guards don't kill a prisoner inside (mirrored
  natively: `StorageService.onArrested` releases the thief flag).
- **Offline arrest writes the register first**; physical steps are skipped
  for offline targets (also how `StorageService` handles offline cleanup).
- **`adventure_zone` datapack interference**: prototype comment notes an
  external datapack force-reverts adventure outside its zone — the sweep
  re-enforces adventure every 5 ticks. Native port should apply gamemode on
  transitions *and* keep the sweep enforcement (in `PrisonService.tick()`).

---

## 5. Deep container inspection contract

Shared by checkpoint scans (AT1/4) and seizure (§4). Design:

```
DeepItemScanner (application service)
  scan(InventoryView inv, ContrabandPolicy cb) -> Map<ItemId, Hit>
    per slot ItemView:
      1. id match? → hit
      2. DataComponents.CONTAINER   → recurse (depth ≤ 3)
      3. DataComponents.BUNDLE_CONTENTS → recurse
      4. Capabilities.ItemHandler.ITEM (only if 2&3 empty — no double count)
      5. NBT fallback: stack.save(registryAccess).getCompound("components") walk
```

- **Order matters**: components before capability — sophisticated containers
  expose both and would double-count.
- **Depth cap 3** (prototype parity) prevents pathological nesting cost.
- **Open decision (from issue)**: Curios/external backpack handlers beyond
  `DataComponents.CONTAINER`/`IItemHandler` — recommend *yes, query
  `Capabilities.ItemHandler.ITEM` on every non-empty stack* (covers Curios
  items carried as stacks in curio slots if a `CuriosInventoryView` adapter
  is added later; not required for M2).
- **Snapshot shape** for AT4: `{itemId: {count, displayName}}` over hotbar +
  main + armor + offhand + nested contents, serialized into the arrest record
  and ledger entry.

---

## 6. `straja_storage.js` → `StorageService` (DONE — #221)

**Audit findings for the merchant desk** (issue asks for these explicitly):

- **Merchant desk interaction hook** = the NPC's CustomNPCs *quest* invokes
  `strajastorage deposit <player> <item> <count>` on turn-in; the script only
  exposes that command — it has no dialogue listeners of its own.
- **Sequential chest filling & overflow** (`ssDepositItem`): merge into
  partial stacks of the same item first, then fill empty slots, then drop any
  leftover as an item entity at `dest.y+1`. `SS_PENDING_DEPOSITS` credits the
  inserted units so the chest poller never counts merchant writes as a
  theft/repayment.
- **Currency payout** is *not* in the script — the CNPC quest reward config
  pays the player. The ledger is therefore the quest system + chest audit
  trail; no payout math needed natively. AT5's 4-coin payout belongs to the
  quartermaster/trade system (LAW-005), which the storage prototype does not
  implement.

Precedent mapping, already native and merged:

| Prototype | Native | Where |
|---|---|---|
| `strajaStorageCfg`/`strajaGoldCfg` | `StorageWatchStore` via `SavedStores.Storage` | `adapter/out/persistence` |
| `SS_ZONE`/`SS_WATCHED_CHESTS`/`SS_DEST_CHEST` | `StorageZone`, `StoragePoint` list, `StorageSetup` | domain model |
| `SS_ITEM_VALUES` | `[storage] watchedItems` `item_id=units` | `StrajaServerConfig`/`StrajaPolicies` |
| `ssMarkThief`/`strajaThief`,`strajaOwed`,`strajaRepBackup` | `ThiefRecord` in store (thief flag, owed units, rep backup) | domain |
| `ssPollWatchedChests` | `StorageService.pollWatchedChests` — canonical container keys fix double-chest double-attribution; pending-deposit netting for merchant desk writes | `ServerTickEvent.Post` |
| `ssAggroScan` | `StorageService.scanGuardAggro` → `NpcGuardGateway` (reflection CNPC: faction filter, `stats.aggroRange`, target set/clear, LoS) | — |
| `ssEnforceRep` | `StorageService.enforceReputation` — pin 0 while flagged, restore on clear | — |
| `noppes quest start/finish` | `NpcGuardGateway.startQuestForTeam/finishQuestForTeam` | CNPC console cmd bridge |
| `strajaWantedUntil` read | `BoloService` active check | wanted = BOLO now |
| `ssDepositItem`/`ssCmdDeposit` | `StorageService.deposit` — sequential fill, overflow drop +1y, `strajastorage` restricted alias kept for live NPC quest commands | — |
| break/place/pickup triggers | `BlockEvent.BreakEvent`, `EntityPlaceEvent`, `ItemEntityPickupEvent.Post` (original−current count delta) | `StrajaEvents` |
| `ssPick`/`ssZoneCorner` | session pickers, logout disarm, `MAIN_HAND` | — |

Migration (M8): `MigrationService.migrateServer` gains a `strajaStorageCfg` →
`StorageWatchStore` importer + per-player thief-flag harvest.

---

## 7. Persistence & shared contract keys

| Legacy key | Owner | Native destination | Status |
|---|---|---|---|
| `portCheckpointCfg` | checkpoint | `CheckpointSiteStore` + `[checkpoint]` TOML defaults | M1/M2 |
| `portCheckpointJail` (legacy alias) | prison | `PrisonStore` fields | M8 migration read-only |
| `strajaPrisonCfg` / `strajaPrisonJail` | prison | `PrisonStore` + `[prison]` TOML | M1/M4 |
| `strajaStorageCfg` / `strajaGoldCfg` | storage | `StorageWatchStore` | M8 migration read-only |
| per-player `cpJailed`,`cpFugitive`,`cpJailOnRespawn`,`cpJailWasMode` | prison | `PrisonStore`/`CustodyStore` | M8 |
| per-player `strajaThief`,`strajaOwed`,`strajaRepBackup` | storage | `ThiefRecord` fields | M8 |
| per-player `strajaWantedUntil` | shared | **dropped** — `BoloService` is the wanted ledger | already replaced |
| per-player `cpBoard` | checkpoint | `BoardingStamp` record | M2 |
| `cpPick*`,`spPick`,`ssPick*` | all | **dropped** — session pickers, intentionally non-persistent | done pattern |
| `checkpoint-log.json`/`prison-log.json` files | both | `InspectionLedger`/`AuditRepository` | M2 |

Log files move into stores (no ad-hoc JSON files natively).

---

## 8. Acceptance Tests 1–8 → verification mapping

| AT | Scenario | Native surface | Verification |
|---|---|---|---|
| 1 | Border post: clean pass logged; contraband warned at stage 1 + drop-off; stage-2 carrier arrested; banned repelled | `CheckpointService.inspect` + `InspectionLedger` + `DeepItemScanner`; `PrisonService.arrest`; `GateLane` pushback | GameTest with mock players at staged positions; assert ledger entries + gamemode/teleport via `MinecraftWorldGateway` |
| 2 | Mine exit DENY gate: ore/salt local list, MINER role carry ban, sell-to-quartermaster exemption, prisoner always repelled | `GateLane.direction` + site-local `cb` map + role check (`PersonnelService`/`Rank`) + `PrisonService.activeSentence` | GameTest crossing simulation; quartermaster purchase toggles `cb` exemption flag |
| 3 | Port ARREST gate: contraband → instant custody | `CheckpointService` `ARREST` mode → `PrisonService.arrest` | GameTest |
| 4 | Ledger & snapshot integrity (incl. nested containers) | `InspectionLedger` + `DeepItemScanner` snapshot into `ArrestRecord` | Unit test on `DeepItemScanner` (shulker/bundle/handler cases) + GameTest arrest snapshot equality |
| 5 | Quartermaster trade: 4-coin payout (1:64 default, TOML tiers), optimal denomination breakdown, sequential chest fill, trade ledger | new `TradeService`/`EconomyService` (LAW-005): `[economy]` tiers Bronze/Brass/Silver/Gold + `WorldContainerGateway.insert` into desk chest list + `TradeLedger` store | Unit test denomination math incl. non-default ratios; GameTest chest fill order |
| 6 | Wanted on-sight: arrest at any arresting checkpoint; deny-gate repel + sighting log; mark cleared on arrest | `BoloService` check inside `inspect`/`gateArrive`; `PrisonService.arrest` clears BOLO | GameTest both modes |
| 7 | Labor camp loop: camp gate repels inmates; quartermaster credits penal account (no physical coins); freedom-price auto-release with locker restore; camp dormitory respawn keeps custody | `LaborCampService` (LAW-006): camp zone + `PenalAccountStore` + `[labor_camp]` TOML + respawn hook (`PlayerRespawnEvent` → camp spawn) | GameTest full loop; penal-account unit tests |
| 8 | Escort & cuff tether: cuff suspends hunt + blocks sprint/attack; tether keeps suspect adjacent; escorted pass through repel-gate; uncuff inside→custody, outside→fugitive | `CustodyService` cuffs (exists) + new `EscortTetherService`: per-tick distance enforce (`PlayerTickEvent.Post`), sprint/attack cancel (`EntityInteract`/input events), gate check honors cuffing officer adjacency | GameTest tether + gate-with-officer pass |

All 8 are GameTest-realizable on the headless server harness already in the
repo (`src/gameTest`, `runGameTestServer`, 24 tests green today); no external
scripting needed. The `TestPlayer`/`TestContainers`/`TestNpcGuards` fakes
(#221) cover the unit-test layer.

---

## 9. Open decisions

1. **Curios/backpack scope** (from issue): default to `DataComponents` +
   `Capabilities.ItemHandler.ITEM`; a dedicated curio-slot
   `InventoryView` adapter is deferred unless testers report bypasses.
2. **Gate scan cadence**: keep 5-tick prototype modulus vs every-tick —
   recommend keeping 5 (prototype-tuned, cheaper).
3. **Boarding detection**: `EntityMountEvent` preferred; fall back to tick
   poll if modded boats bypass the event.
4. **`strajacheckpoint`/`strajaprison` aliases**: keep as restricted roots
   (deposit/action bridge only) for live pressure plates & NPC quest data —
   `strajastorage` precedent.
5. **Economy denomination source**: Ady's Decorations coins by default
   (per #219), tier ratios in `[economy]` TOML — confirm item IDs at M5.
