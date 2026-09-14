# Admin Tools Surface — Implementation Issues

Status date: 2026-09-14 (planning — no issues filed yet)

This is the implementation queue for the CustomNPCs-style admin tool
surface: physical items an authorized admin clicks the world with, instead
of typing coordinates and names into commands. Commands remain canonical;
every tool is a second inbound surface over the same application services.

## Original intent

Setup and world-authoring commands exist because an admin must communicate
positions, entity targets, and selections. A tool wins exactly where typing
loses: walk the patrol and click each waypoint, click the NPC to edit it,
click two corners to bound a cell. The interaction model is **one item per
verb** (CustomNPCs wand/cloner/pather style) — no mode rings, no omni-wand
that guesses intent from context.

## Surface split (the issue taxonomy)

Two columns, decided once, applied to every future item:

| Surface | Holder | Obtained | Examples |
|---|---|---|---|
| **Admin/op tools** (this queue) | `isCommissioner \|\| isOp` — checked in the service on every use | Creative inventory only; `/straja setup tools` gives the kit to an authorized holder. No recipes, never droppable by mobs, never in NPC menus | patrol wand, NPC wand, cloner, survey rod, boundary markers |
| **Day-to-day roleplay items** (already shipped) | Rank/capability/records, checked in services | Issued by NPCs, kit equipment, or fine/notice delivery | cuffs, rope, head_sack, baton, keys, order_book, carnet, fine_book, fine_notice, archive set, training_manual |

`room_marker` already sits in the admin column (Comisar-gated
`markerSelect`). `prison_marker` joins it as a functional tool in AT-005 —
today it is a hint-only stub by design.

## Design principles (carried over, unchanged)

1. Commands stay canonical. Every tool action must map to an existing
   application-service call; the tool adds no new capability a command
   lacks.
2. The item is a pointer, never a credential. Authority is re-checked in
   the service per use; a stolen or duplicated tool in a normal player's
   hands is inert and fails closed with a tell.
3. No trust in item NBT/custom data. Selection state (pending route,
   captured template, first corner) lives server-side keyed by holder UUID,
   same pattern as `RoomService.MarkerSelection`.
4. Admin tools are invisible to the player surface: no NPC menus, no
   `availableActions` projections, no roleplay paths emit them.
5. Fail closed on wrong holder, wrong target type, stale selection,
   crossing dimensions, and logout (pending tool state clears).

## Dependency order

`AT-001` is the shared foundation (item registration, holder gate,
per-player tool-state store, `setup tools` kit). AT-002 through AT-006 are
independent of each other once the foundation exists. `AT-007` is the
release gate. `AT-008` is deferred — mobile personnel actions are arguably
*worse* roleplay than the Secretary desk and need a deliberate decision.

## AT-001 — Admin tool foundation + authority model

Priority: P0 — prerequisite for every tool.

- Register the admin-tool item set (see AT-002..006) with consistent
  naming/texture placeholders; mark non-stackable.
- New inbound adapter (`PhysicalItemSurface`-style action for admin tools)
  routed in `StrajaEvents.onEntityInteract`/`onRightClickBlock`/
  `onRightClickItem`, parallel to the existing item routing.
- Holder gate helper: `isCommissioner || isOp`, deny-with-tell otherwise.
- Per-player tool-state store (selections, pending routes, captured NPC
  templates) in a SavedData repository, keyed by holder UUID; cleared on
  logout.
- `/straja setup tools` — adminOnly command giving the kit to an
  authorized holder.
- Tests: wrong-holder refusal, per-player state isolation, state cleared on
  logout, commands and tools converge on identical service calls.

## AT-002 — NPC Wand

Priority: P1. Maps to `npc assign / set-name / set-skin / remove / list`.

- Click a registered `StrajaNpcEntity` → chat menu of clickable token
  actions (existing `issueActionCommand` machinery): assign role (one
  button per known role), rename (form), set skin (form), remove
  (confirm), show registry record.
- Text entry routes through `FormSessionBridge`; mutations call
  `NpcAdminService` — no new rules.
- Clicking a non-Straja entity or a player: deny tell, no state change.
- Tests: token staleness, wrong holder, unregistered entity, remove
  confirm path.

## AT-003 — Patrol Wand (the Pather)

Priority: P1. Maps to `setup patrol` + `set-checkpoint <id>`.

- Click block = append waypoint to the holder's pending route (server-side
  list); click an already-recorded waypoint = remove it; sneak+click air =
  finish → writes the route through the existing checkpoint/setup path.
- The duty engine requires exactly four distinct checkpoints — the wand
  enforces that at finish time and reports progress ("2/4 recorded").
- Pending route clears on logout; dimension recorded per waypoint,
  cross-dimension finish is rejected.
- `set-mission-time` stays a command (per-checkpoint minutes are not a
  click concern).
- Tests: dedupe, order, exactly-four enforcement, logout clear, dimension
  mismatch.

## AT-004 — Survey Rod (location stamper)

Priority: P2. Maps to `set-location <name>` + `setup here`.

- Click block → chat menu with one token button per `SetupData` location
  key, missing ones flagged; picking a button stamps that location at the
  clicked position.
- Optional "stamp all missing here" button = the `setup here` behavior.
- Uses `SetupChecklist.missingLocations` so the checklist and the rod can
  never disagree.
- Tests: stamp, re-stamp overwrite, checklist convergence, wrong holder.

## AT-005 — Boundary Marker (cells)

Priority: P2. Maps to `cell` geometry commands.

- Promote `prison_marker` from hint-stub to functional: two clicks select
  cell corners (persisted per-holder selection, same pattern as
  `RoomService.MarkerSelection`), then a chat token confirms registration
  through the existing cell-creation service path.
- `room_marker` stays as-is; per model A, separate items for separate
  verbs. The event handler's current hint-only branch is replaced by real
  selection.
- Tests: two-click flow, corner ordering/normalization, dimension check,
  confirm staleness.

## AT-006 — NPC Cloner

Priority: P2. Maps to `npc spawn` + `assign` (+ `set-name`/`set-skin`).

- Click a `StrajaNpcEntity` = capture template (role/name/skin) into the
  holder's server-side slot; click a block face = spawn a registered copy
  there; click a different NPC = replace template; sneak+click air =
  clear.
- Spawn goes through the same registry path as `NpcCommands.spawn` so
  persisted entities re-adopt cleanly after restart.
- Tests: capture/spawn round-trip, empty-template click, restart
  re-adoption of spawned copies.

## AT-007 — Kit command, docs, and UAT gate

Priority: P1, last. 

- `/straja setup tools` finalized; `docs/parity-matrix.md` and player/admin
  docs updated to describe observed behavior.
- Live-server UAT: each tool exercised in `run/` (wand edit, route
  recording, cell bounding, location stamping, clone spawn), including
  wrong-holder and stolen-item cases.
- Release note: tools are non-craftable, non-droppable, admin-only.

## AT-008 — Personnel Baton (deferred)

Priority: P3 — needs a product decision, not just implementation.

Mobile dossier + promote/suspend/fire via confirm tokens on player click.
The Secretary desk already provides this surface and the desk visit is the
better roleplay beat; only build if remote personnel actions prove
necessary in play.
