# NPC Desk Menus — Milestone & Issue Plan

Status: **ready-for-handoff**. Planning date: 2026-10-20. Base: `main` @
`054f92d` (post-0.5.0). Delivery: sequential commits on `main` (no PR/remote
side effects authorized; commits only).

## Contract

- **Goal:** Reimagine every non-dialog player↔NPC interaction as a vanilla
  chest-menu "desk" GUI — self-explaining item buttons with Romanian lore,
  hub-and-spoke navigation, in-GUI confirms — inspired by the `reclame.js`
  KubeJS surface (audit below), while keeping clickable chat as a fallback and
  all authority server-side.
- **System:** new `adapter/in/menu` package (vanilla `MenuType.GENERIC_9x*`
  `ChestMenu` subclass — no client assets, works on unmodded clients),
  rendering the existing pure surface model (`NpcPlayerSurface.OrderedSurface`
  / `ChatAction`) and dispatching through the existing `NpcRoles.performAction`
  action-id vocabulary. Optional second consumer: a chest-menu
  `NpcSurfaceProvider` over `NpcSurfaceSnapshot` for bound CustomNPCs.
- **Constraints:** `gameplay-decisions.md:489` leaves UI layout an
  implementation choice; gameplay invariants unchanged — every click still
  re-validates via `isStillValidForPlayer` + the use-case service. Hexagonal
  boundaries (`ArchitectureBoundaryTest`): menu view models stay MC-free;
  adapters named in the boundary test keep their port-only access. Refusals
  keep `refuse(reasonKey, remedyKey)` convention + lang parity. Dialog trees
  (CustomNPCs dialogs) are explicitly out of scope. Romanian UI text.
- **Evaluation:** `./gradlew compileJava` + `./gradlew test` green per issue;
  unit tests for the pure view-mapping (`*SurfaceTest` convention); a live
  server smoke (runServer + `/straja test` virtual players) per role issue;
  no regression in the chat fallback path.

## reclame.js audit — what to keep, what to fix

The reference KubeJS surface (`reclame.js`, ~700 lines) is the UX bar.
Audit findings, grouped by "preserve" vs. "do better" in the port:

### Preserve (why the GUI feels good)

1. **Self-explaining buttons** — every item carries name + lore covering what
   it does, price, and consequence (`adItem`, `adStatsLore`, `adPriceLore`).
2. **Hub-and-spoke navigation** — main → list → detail → ledger; a back
   button on every page; breadcrumbs in titles (`Jurnal anunț #3 (2/4)`).
3. **Zero typed commands for players** — everything is a GUI click or a chat
   click-button onto an internal command.
4. **Tiered clicks** — left/right/shift = 1/10/64 deposits; power users move
   fast, the hint is in the lore.
5. **Status-at-a-glance** — dye-icon + stats lore on every ad row; balance,
   reach, last-shown, per-day counts in the detail header.
6. **Diegetic I/O** — ad text written in a held book & quill; free guidebook
   teaches the mechanic; the agent is a real NPC in the world.
7. **Graceful economics** — deferred refunds while the inventory is captured
   by an open menu, delivered on next open/login; house-ad fallback slot;
   audience-tier cooldowns ("only upward").
8. **Admin parity** — chat dashboard with clickable approve/reject and config
   viewer; looking-at-entity NPC marking.

### Do better (QOL gaps the native port must close)

1. **Chat round-trip for every mutating click.** reclame.js must close the
   menu and ask `[DA]/[NU]` in chat because KubeJS captures the inventory.
   A native `AbstractContainerMenu` controls slots directly: confirms become
   an in-GUI two-item page (DA/NU), and coin deposits can use real input
   slots — no close/reopen cycle. **Design rule: chat is never required to
   finish a GUI action.**
2. **`[NU]` loses context** — `adAsk` always returns to the main menu. Our
   nav keeps a page stack: cancel returns to the page you came from.
3. **Unlimited-sentinel stepping bug** — `maxPlayers=0` sits in the same
   preset list as numbers: "Plafon mai mare" from "fără limită" lands on 1,
   "mai mic" lands on `minPlayers`. Unbounded toggles get their own control
   (dedicated item), not a ± stepper.
4. **NBT string ceiling** — the whole state is one `putString` JSON blob;
   `StringTag` hits `writeUTF`'s ~64 KiB cap at world save, not at `adSave`,
   so busy ledgers silently stop persisting. (Non-issue here — Straja uses
   sharded `JsonBackedStore` SavedData — but it validates keeping menu state
   out of persistence entirely: menus are ephemeral views.)
5. **`AD.npcs` counter drift** — tagging counts, but a deleted/respawned
   entity never decrements, so `requireNpc` can pass with zero real agents.
   Menu availability must derive from the live entity/registration, never a
   counter.
6. **Invisible scheduling** — `AD.nextSlot` is never surfaced; players can't
   see "next slot in ~7 min". Time/state that exists should show up in lore.
7. **Silent caps** — `adMenuList` hard-caps at 9 with no paging; errors only
   reach chat, not the GUI. Every bounded list gets real pagination, every
   failure gets an in-GUI or actionbar signal.
8. Minor: priority cycles one direction only; `adStep` on a non-preset value
   jumps to index 0; `give` can spill at feet on full inventory.

### Non-obvious KubeJS-vs-native differences the port exploits

| reclame.js workaround | Native equivalent |
|---|---|
| close menu → chat `[DA]` | in-GUI confirm page (DA/NU items) |
| deposit via counted chat commands | real container input slots or direct inventory debit (services already charge server-side) |
| reopen menu per navigation | `container.setItem` + `broadcastChanges` — page turns without a reopen packet |
| `writable_book` held in hand for text | existing `StrajaFormScreen` (proven, localized, bounded) |
| tokened chat buttons | server-side menu instance; click packets carry only slot+button — forgery-free by construction |

## Architecture fit (facts, verified)

- Entry: `StrajaNpcEntity.mobInteract` → `NpcInteractionService.interact` →
  `NpcRoles.interact` → `NpcPlayerSurface.orderedFor(roleId, dynamicActions,
  faqContext)` → chat lines with 15 s one-use tokens → `/straja npc-action
  <token>` → `NpcRoles.performAction(actionId, player, level)`.
- `OrderedSurface` already yields a paged, phase-ordered action list —
  the chest renderer consumes it unchanged. `more:<role>`/`main:<role>`/
  `faq:*` ids already model navigation; the menu maps them to page turns and
  back buttons instead of chat links.
- `NpcRoles.performAction` handles plain + `op:recordId` ids and re-checks
  freshness for parameterized ops — the menu calls it directly (menu clicks
  are server-internal; no token needed inside an open menu).
- Secretary book-copy on interact (`trySecretaryBookCopy`) stays an
  interact-time special case before any menu opens.
- Bound CustomNPCs route through `NpcPresentationRuntime` providers
  (`NpcSurfaceSnapshot`, ≤64 actions, `NpcSurfaceActionService` 15 s–5 min
  tokens). A chest-menu provider can consume the same snapshots — separates
  cleanly into Issue 5.
- Dormant seam `PlayerGateway.openButtonGui(ButtonSpec)` exists; the new
  adapter implements the chest-menu equivalent under it or a sibling port so
  services/tests can drive menus headlessly (`VirtualPlayerGateway` degrades
  to a logged view — the `/straja test` path can exercise menus).
- Vanilla `MenuType.GENERIC_9x1..6` + `ChestMenu` render as the ordinary
  chest screen on any client — no `IMenuTypeExtension`, no client code, no
  new payload. Slot-sync is `broadcastChanges`; clicks arrive via the
  vanilla container-click packet at `menu.clicked`.
- Icon stock: `GuiTheme.iconItemFallback` role/action → Straja item map,
  `GuiItemIcons.mcItemStack`, 27 item PNGs; `ItemSpec.displayName` +
  `DataComponents.LORE` precedent in `MinecraftInventoryView.build`.

## Milestones

### M1 — Desk-menu framework + Receptionist pilot *(Issue 1)*
**Outcome:** right-clicking a Receptionist opens a chest GUI: header row
(role icon + title + guidance item), paged action grid with icon+lore
buttons, FAQ submenu, «Înapoi»/«Mai multe» navigation, in-GUI confirm page.
Config flag `npcMenus.enabled` (default true) with automatic chat fallback.
**Proof:** unit tests for OrderedSurface→MenuView mapping + session/click
invariants; compileJava+test green; live smoke via runServer or documented
manual checklist.

### M2 — Remaining role desks *(Issues 2–4)*
- Issue 2: Instructor + Jailer + Inspector desks (recruit/quiz/training/
  promote; custody requests paged; document inspect).
- Issue 3: Archivist + Armorer desks (folder/sheet paged lists → per-record
  action pages; offer list with cost lore + confirm page).
- Issue 4: Secretary + Comisar desk (duty desk, missions, complaints, fines,
  reports, audiences, petitions, dispatch, BOLO; Comisar member picker →
  dossier page with confirm-gated mutations).

### M3 — Unification + polish *(Issue 5)*
Chest-menu `NpcSurfaceProvider` for bound CustomNPCs (dialogs stay CNPC),
admin-tool chat menus (npc wand/survey/cell-confirm) → GUI, docs
(player-quickstart, README surface section), final live verification sweep.

## Issue 1 — Chest-menu framework and Receptionist desk

**Intent.** Players currently read a wall of chat links per NPC — functional
but not a *surface*; the reclame.js audit shows item+lore buttons explain
themselves. The pure `OrderedSurface` model makes this a presentation change,
not a behavior change.

**Expectation.** With `npcMenus.enabled=true` (default), right-clicking a
native Receptionist opens a 6-row chest GUI titled `Recepție`:
- a header/info item carrying role icon + guidance text as lore;
- up to 45 action slots from the ordered surface (primary then overflow),
  each an icon item with Romanian name + 1–3 lore lines (what it does, what
  happens next — e.g. „Deschide formularul", „Costă: 0", „Aprobare: instant");
- page footer: «Pagina x/y» arrows, «Înapoi» where a stack exists;
- clicking an action: `main:`/`more:`/`faq:` ids navigate inside the menu;
  `Parameterized` ids re-validate via `isStillValidForPlayer` then dispatch
  `performAction`; immediate actions refresh the menu in place via
  `broadcastChanges`; form actions close the chest and open the existing
  native form (unchanged path); destructive/mutating actions pass through an
  in-GUI confirm page first;
- closing is always safe (Esc works, no stuck state, no item movement);
- FAQ renders as menu pages (root topics → questions → answer page with the
  answer in lore + «Înapoi»).
- With the flag off or `openMenu` failure, the exact current chat surface
  renders — zero regression.

**Acceptance criteria.**
- `StrajaChestMenu` (or equivalent) uses vanilla `MenuType.GENERIC_9x*`;
  `clicked` never mutates slot contents; `quickMoveStack` returns EMPTY;
  `stillValid` gates on session liveness; `removed` cleans up.
- Pure view model (`MenuView`/`MenuButton` records or `ButtonSpec`-style)
  is MC-free and unit-tested: ordering preserved, paging math, lore bounds,
  icon fallback on unknown item id (never throws).
- Every rendered action reaches `performAction` with its exact actionId —
  one-use tokens are *not* minted for in-menu clicks; a menu session dies
  with the container (no replay after close).
- Receptionist primary+overflow actions all reachable; trades list, fines,
  complaints, join-review buttons page correctly at >45 and ≤45 items.
- `trySecretaryBookCopy` still intercepts book-in-hand interact before menus.
- Refusals from `performAction` reach the player (chat tell is acceptable
  for service-level refusals; validation refusals show in-menu or actionbar).
- `ArchitectureBoundaryTest` and `RefusalConventionTest` stay green.

**Context code cannot infer.** Players are Romanian-speaking RP server
members on possibly vanilla clients — the reason vanilla chest types are
mandatory (no `IMenuTypeExtension`, no client mod requirement). Labels/lore
come from the existing hardcoded Romanian strings (`NpcPlayerSurface` style),
NOT new lang keys (chat surface precedent). Menu state must never persist —
menus are views over live service state.

**Scope.** New `adapter/in/menu` package; `NpcRoles.interact` gains the menu
branch behind the config flag; `StrajaServerConfig`/`StrajaPolicies` gain
`npcMenus.enabled`; GuiTheme icon reuse. Type: enhancement.

**Non-goals.** No bound-CustomNPC provider (Issue 5); no changes to
`performAction` semantics, token service, or form screens; no other role
desks; no custom textures/models; no chat-surface removal.

**Dependencies.** None (foundation). Blocks Issues 2–5.

**Verification.** `./gradlew compileJava`, `./gradlew test`; new
`NpcDeskMenu*Test` (view mapping, paging, confirm flow, session lifecycle);
manual/RCON smoke: `/straja setup npcs` on runServer → right-click
receptionist → navigate pages → submit path opens form.

## Issue 2 — Instructor, Jailer and Inspector desks

**Intent.** Same surface quality for the remaining small roles.

**Expectation.** Instructor: `training-progress` (progress → summary item
lore), `recruit`, `quiz-answer` (→ form), `training-manual`, `training-promote`,
`v2-promotion-status`. Jailer: custody list pages (one row per request/target
with name lore), `arrest-handoff` → form, `cuffs-item`, statuses. Inspector:
`inspect-documents` + FAQ. All: header, FAQ, back nav, confirm pages where
destructive (`custody-release:<id>`).

**Acceptance criteria.** Every role's static+dynamic actions reachable in-GUI;
paged lists >45; forms open native screen; statuses render as item lore or
chat (acceptable) with menu refresh on return; boundary+refusal tests green.

**Context.** `inspect-documents` resolves nearest inspector within 8 blocks —
the menu action must preserve that entity-context resolution, not assume the
clicked NPC (verify in `NpcRoles:278-300`).

**Scope.** `adapter/in/menu` role renderers + dispatch wiring. Type:
enhancement. **Non-goals:** behavior changes; CNPC provider. **Depends on:**
Issue 1. **Verification:** role unit tests + live smoke per desk.

## Issue 3 — Archivist and Armorer desks

**Intent.** The two record-heavy desks: archive folders/sheets and rank-gated
armory offers.

**Expectation.** Archivist: folder list → folder page (read/issue/new-sheet)
→ sheet page (read/edit/recipients/submit/sign/copy/envelope/issue/revoke);
evidence list → per-evidence actions; `archive-list`/`evidence-list` text
becomes item rows (name + key facts in lore) instead of chat dumps where
each row has follow-up actions — pure-text status can stay chat. Armorer:
offer rows show `count× item — cost` lore; buy/reserve → confirm page
(DA/NU) → `performAction` (economy charges server-side as today);
`armory-status` summary item.

**Acceptance criteria.** Per-record paging correct at 0/1/many; confirm page
shows exact cost/consequence in lore; cancel returns to the originating page
(fixes reclame.js gap 2); all form actions still open native forms; `give`
results (books, copies) unchanged.

**Context.** Archive per-sheet actions are ~7 deep — sheet detail pages are
mandatory (not flat lists). Armorer offers are config-sized and rank-filtered
server-side already — render what `stateAwareActions` yields, nothing more.

**Scope.** `adapter/in/menu` renderers for the two roles. Type: enhancement.
**Non-goals:** archive/fine/economy rule changes. **Depends on:** Issue 1.
**Verification:** unit tests for record→menu mapping; live smoke with seeded
folder/sheet via `/straja` admin commands.

## Issue 4 — Secretary and Comisar desk

**Intent.** The densest surface (~15 static + 8 dynamic action families +
Comisar admin). This is where the desk metaphor pays off most: duty desk,
registry, and the Comisar's personnel drawer.

**Expectation.** Secretary menu sections (in-menu subpages, not one flat
grid): **Duty** (duty-start/checkpoint/stop/salary/coins/food/kit/resignation
with current-status header lore — live `DutyView` state incl. next deadline),
**Missions** (carnet give, draft ops, per-mission detail page with the ≤7
parameterized ops), **Records** (complaints/fines/reports/audiences/petitions
lists → detail pages → form or confirm), **Board** (dispatch calls,
incident accept/join/resolve, BOLO list+cancel+create-form), **Comisar**
(visible only with authority: personnel roster paged → member dossier page →
promote/demote/suspend/fire/reinstate as confirm-gated items; policies and
emergency entries → forms). Duty-start must still only work at the Secretary
(invariant: patrol stop restriction stays service-side, untouched).

**Acceptance criteria.** All `stateAwareActions(SECRETARY)` families render;
member picker pages correctly at large rosters (≥45 → pages); confirm pages
carry member name + action consequence in lore; every mutation audited as
before (service path unchanged); no Comisar rows render for non-Comisar
(re-validated server-side anyway); duty/status lore reflects live state.

**Context.** `delegation-*` actions are currently only on the CNPC provider
surface — surface them on the Comisar page too (parity win, allowed by the
same `PARAMETERIZED_ACTIONS` prefix rule). The §11 weekly-report and §12
audience flows open forms — unchanged.

**Scope.** `adapter/in/menu` secretary/comisar renderers. Type: enhancement.
**Non-goals:** delegation rule changes, new admin powers, CNPC provider.
**Depends on:** Issues 1–3 (framework settled). **Verification:** unit tests
for section routing + dossier paging; live smoke covering duty-start→stop and
a confirm-gated promote.

## Issue 5 — Bound-NPC parity, admin-tool menus, docs

**Intent.** "All mod interactions" — bound CustomNPCs and the admin-tool chat
menus join the desk system; docs catch up.

**Expectation.** A `ChestMenuNpcSurfaceProvider` implementing
`NpcSurfaceProvider` renders `NpcSurfaceSnapshot`s as desk menus (actions ≤64
→ paged grid, input-field actions → native form bridge); registered in
`NpcPresentationRuntime` and selectable as the surface for bound NPCs
(config/precedence decided at implementation — must not break the existing
CNPC GuiCustom path when CustomNPCs is present; default keeps CNPC path,
chest provider serves native registry bindings). Admin-tool menus (npc wand
role assign, survey stamp, cell confirm) render as desk menus when a real
player opens them. Docs: README player-experience section + player-quickstart
updated; this plan's evidence section filled.

**Acceptance criteria.** Provider passes the same publish/capability/reconcile
contract; snapshot actions dispatch through `NpcSurfaceActionService` tokens
(5-min TTL path, not the 15 s chat tokens); wand menus work for ops in-GUI;
`CustomNPCs absent → chest provider` degrades cleanly; `./gradlew test` green.

**Context.** `NpcSurfaceSnapshot` caps 64 actions / 64 dialogue / 64 quests —
dialogue rows stay CNPC dialogs (explicitly out of scope); only the action
surface is rendered. Provider must fail closed on bridge errors (existing
convention).

**Scope.** `adapter/out/npc/chestmenu` (or `adapter/in/menu` provider shim —
choose per boundary test), admin-tool surfaces, docs. Type: enhancement.
**Non-goals:** dialog-tree changes; removing the CNPC provider. **Depends
on:** Issues 1–4. **Verification:** provider contract tests (mirror
DebugText provider tests if present); live bound-NPC smoke when CustomNPCs
installed; RCON headless path for natives.

## Execution receipts

| Issue | Commit | Outcome |
|---|---|---|
| NPCDM-1 | `58fb548` | Chest-menu framework shipped: pure view model (`DeskIcon`/`DeskButton`/`DeskPage`/`DeskLayout`/`DeskNavigation`/`DeskActionStyle`), `DeskMenu`+`DeskMenuSession`+`DeskMenuService`, Receptionist desk live; direct `performAction` dispatch — no chat tokens inside an open menu. |
| NPCDM-2 | `eeb0dcd` | Instructor, Jailer and Inspector role desks on the same framework, paged at 45 with the shared confirm gates. |
| NPCDM-3 | `d2e7500` | Archivist and Armorer desks shipped; fine/armory mutations ride the in-GUI DA/NU page. |
| NPCDM-4 | `37aa8ea` | Secretary desk + Comisar admin surface: section drawers (`DeskSections`), duty-start/stop verbs pinned, member-scoped clicks revalidated. |
| NPCDM-5 | (this commit) | Bound-NPC parity pinned (`StrajaEvents` → `NpcRoles.interact` for every registry-bound host); admin-tool menus GUI-first via `openToolMenu`/`openList` with the chat fallback intact; `tool-npc-remove` rides the in-GUI DA/NU page; `ChestMenuNpcSurfaceProvider` (`desk-menu`) registered — binds via `bind-desk`/`provider migrate desk-menu`, submits clicks through `NpcSurfaceActionService` (5-min-capable token path); docs updated. |

### Known limitations at close

- **Title is fixed at open.** The container title is set once when the menu
  opens; later page turns update only the grid contents (the baked-in page
  suffix is deliberately stripped for the same reason).
- **Flat grids vs drill-down.** Most desks render the action list as a flat
  paged grid; only the Secretary/Comisar desk classifies rows into section
  drawers. Deep hierarchies remain a future QOL pass.
- **Live smoke pending.** `compileJava` + `test` are green (1841 tests);
  the provider contract is covered headlessly. A live client run — bound
  villager through `bind-desk`, wand menus, CustomNPCs present/absent
  precedence — is still outstanding.
- **Chat-bound actions documented.** `sendToolPrompt` stays chat for every
  non-`tool-*` id (trades enrollment, dispatch accept/decline, V2 mission
  offers, prison-cell confirm): those flows are role-surface decisions with
  richer surrounding text, not admin-tool menus. Surface actions carrying
  input fields also leave the grid and open the native form screen — a
  chest menu cannot collect text.
- **Dialogue stays provider-native.** A `desk-menu` binding renders only
  `NpcSurfaceSnapshot.actions()`; dialogue/quest rows are never rendered —
  CustomNPCs keeps `GuiCustom` for those.

## Handoff notes

- Sequencing: 1 → (2 ∥ 3 disjoint, but run sequentially per pr-flow) → 4 → 5.
- Subagent execution: each issue goes to a `subagent_general` with this doc as
  the contract; orchestrator reviews the diff adversarially, runs
  compileJava+test, then commits to `main` with the repo's commit style +
  Devin trailer. No pushes, no PRs.
- Every commit message names the issue id (NPCDM-1..5).
- Residual risks: (a) `broadcastChanges` page-turn must re-render before
  click settles — verify live; (b) `performAction` writes some output as
  chat — acceptable hybrid (menu for navigation, chat for long text), but
  refusals mid-flow must not strand the player — each Issue's verification
  covers it; (c) 45-item pages: `NpcSurfaceSnapshot` caps 64 so two pages
  max per grid; `stateAwareActions` is unbounded for Comisar rosters —
  general pagination is mandatory, not per-role.
