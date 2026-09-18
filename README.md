# Straja Mod

![Straja Mod banner](docs/media/straja-banner.png)

Native NeoForge 1.21.1 implementation of the Straja police system, replacing the
legacy KubeJS runtime (`Politie Rustic Craft`). Romanian in-game text, English
canonical commands with Romanian aliases.

- **Minecraft** 1.21.1 · **NeoForge** 21.1.248 · **Java** 21
- **Mod ID** `straja` · **Package** `com.dwurdy.straja` · **License** LGPL-3.0
- **Required:** NeoForge, [Envelope](https://modrinth.com/mod/envelope) 0.6.2+
- **Optional:** any coin items for the physical economy — defaults are
  Ady's Decorations coins; configure other item IDs under `[economy]` in
  `config/straja-server.toml`.
  No KubeJS, no CustomNPCs, no scripting runtime.

## Architecture (hexagonal)

```
domain/model          pure value objects & aggregates (no MC imports — enforced by test)
application/
  port/in             inbound ports: *RoleplayUseCase, GuardDutyUseCase,
                      GuardRecruitmentUseCase, PlayerQueryUseCase,
                      NpcRegistryUseCase, FormSessionUseCase,
                      RoleplayExpansionUseCase
  port/out            outbound SPI: PlayerGateway, ServerGateway, CurrencyProvider,
                      DeliveryProvider, repositories, WorldGateway, Clock, IdGenerator
  service             use-case services: PlayerService, GuardService, EquipmentService,
                      MissionService, CustodyService, PrisonService, FineService,
                      ComplaintService, RoomService, ArchiveService, NpcAdminService,
                      MigrationService, AuditService, FormSessionService,
                      IncidentService, BoloService, EvidenceService,
                      ArrestRecordService, ReputationService, RpExpansionService
  StrajaContext       the assembled ports record injected into every service
adapter/
  in/command          Brigadier command trees (Straja/Test/Debug/Npc commands)
  in/event            NeoForge events (tick, login recovery, damage hooks, cell guard)
  in/npc              StrajaNpcEntity + role routing
  in/form             StrajaFormMenu + FormPayloads + FormSubmissionRouter
  in/item             physical Straja items → inbound port calls
  in/test             virtual players (test mode only)
  out/minecraft       ServerPlayer-backed gateways (inventory, position, effects)
  out/client          StrajaFormScreen (native EditBox/MultiLineEditBox UI)
  out/persistence     JsonBackedStore + NbtStore SavedData adapters
  out/delivery        EnvelopeDeliveryProvider (real MailService)
  out/economy         ItemCoinCurrencyProvider (configurable coin items)
  out/migration       KubeJsNbtReader (pure-Java gzipped NBT reader)
bootstrap/            StrajaRuntime composition root, StrajaServerConfig → StrajaPolicies
```

Rules: adapters never contain business rules; domain/application never import
Minecraft or NeoForge classes (`ArchitectureBoundaryTest` enforces it).

## Player experience and commands

Straja is roleplay-first. Normal players and officers use native Straja NPCs,
physical items, clickable chat prompts, and native form screens in the world.
The NPC roles are:

- **Receptionist** — records the Straja application ("Depune cererea"),
  rules, status, complaint submission/confirmation/withdrawal, fine
  payment/refusal/appeals, room status/release, native-faction declaration.
- **Trainer / Recruiter (Instructorul)** — handles the admission exam for
  applicants and invited recruits, then owns training modules, service-block
  points and progress, self-service rank-ups for configured ranks, and the
  physical training manual (`straja:training_manual`) — right-click it to read
  the rules.
- **Secretary** — copies a book recognized by `#minecraft:bookshelf_books`
  held in the main hand without consuming the original; duty self-service
  (start/checkpoint/stop, salary, coins,
  food, kit, resignation/rejoin), mission browsing/accept/report/fail, order
  carnet, complaint investigation reports, archive catalog, and **weekly
  activity reports** (§11): every member files a report per interval
  (`reports.intervalDays`, default 7) through a native form — activity,
  missions, incidents, notes for the Comisar. The Comisar reviews them at the
  same surface: accept, return-with-note (reopens submission), or call the
  author in. An overdue report can refuse duty start when
  `reports.blockDutyWhenOverdue` is enabled. Members can also **request an
  audience with the Comisar** (§12) — one open request each, re-requesting
  updates the reason; the online Comisar gets a coalesced notification and
  resolves/dismisses with an optional note, delivered to the requester on
  their next visit or login. Patrol shifts **must** start and
  end here — `/straja stop` away from the Secretary is refused for normal
  duty (free-duty ranks keep at-will stop). On duty start the guard's
  scoreboard team is captured and the `Straja` team is applied; the captured
  team is restored at shift end. Patrol routes loop — after the last
  checkpoint the next round returns to checkpoint 1 and duty ends only on
  stop or a missed deadline.
  Mission issuing is template-driven (§13): the Secretary lists enabled
  **mission templates** with the budget computed live from the wage table —
  `reward = hourlyWage(minRank) × estimatedHours × risk` Bronze per
  participant, `× maxPaidParticipants` for the draft's maximum budget. One
  click turns a template into a work order draft; a native form adjusts
  hours/risk (recomputed) or an explicit reward — anything beyond
  `mission.rewardOverrideMargin` (default +25% over the calculated value)
  requires a reason and lands in the audit log. Templates never store coin
  amounts, so wage changes re-derive every preview and new draft. The
  Comisar administers templates in-game via `/straja mission template`
  (create/set/duplicate/enable/disable); issued missions keep their stamped
  reward, persist the template id, and can mark patrol-substituting work.
- **Armorer** — rank-gated equipment, coin purchases and requisition-reserve
  offers in the second-floor changing room.

Every role surface also exposes a contextual **Am o întrebare** FAQ. The FAQ
is read-only and filters explanations by lifecycle state, rank and Comisar
authority; it never replaces the server-side use-case checks. The Secretary
also copies a book held in the player's main hand on right-click, preserving
  the original and all book components.

- **Emergency system** (§25) — the Comisar (or op/console/RCON) can raise a
  TTL-bound **urgency call** (`/straja emergency alert <mesaj>`, default
  `emergency.urgencyTtlMinutes=60`): every online member — on- or off-duty —
  hears it once, members logging in while it is live still receive it, and the
  message points at the `hq` location when configured. `clear` dismisses early;
  expiry lapses silently. A sustained **emergency mode**
  (`/straja emergency start [multiplier] [runde] [motiv]`) multiplies every
  hourly wage accrual by the hazard-pay factor (`emergency.payMultiplier`,
  clamped by `emergency.maxPayMultiplier`; the salary tell notes the bonus)
  and turns new patrol shifts into finite runs of `emergency.patrolRounds`
  full route laps — the shift snapshots its requirement at start, so ending
  the emergency mid-shift never strands a patrol. `end` restores the base
  rate; accrued salary is untouched. State persists across restarts.
- **Comisar admin surface** (§14) — the Secretary gives the Comisar a
  personnel desk: the full roster and the on-duty roster, per-member
  dossiers, direct authorization at rank (skips application/exam for
  experienced hires), promote/demote/suspend/fire/reinstate, plus live
  YAML policy editing and the emergency controls. Member-scoped clicks
  carry the member's UUID and are revalidated against current state
  before dispatch — a stale or forged token cannot mutate a record.
  Every mutation is audited; non-Comisar, non-op actors see nothing.
- **Jailer** — custody, downed-state and sentence status, officer task
  accept/complete/arrest, and issues the cuffs item when permitted.
- **Archivist** — folder read/issue, sheet authoring/edit/submit/sign,
  numbered copies via carbon paper, official envelopes, document issue.

NPC actions are rendered as clickable chat. Each click uses a short-lived,
one-use token bound to the clicking player; it is not a typed gameplay command
and cannot be reused by another player. Text-bearing decisions (quiz answers,
mission reports and failure reasons, complaint submissions and withdrawal
reasons, investigation reports, appeals, archive sheet edits, activity
reports and their review decisions) open a native
form screen — a server-authoritative session that is owner-bound, allowlisted,
expiring, and one-use; the payload carries only an opaque session ID and
bounded field values.

Physical items complete the loop: the Order Carnet and mission draft on
`straja:order_book`, archive folder/document/carbon paper/stamp/envelope
items, `fine_book`/`fine_notice`, the `training_manual`, cuffs, rope, head
sack, baton, whip, keys, cutters, crowbar, and room markers all route through the same
inbound ports — item metadata is only a display hint and never grants
authority.

The Service Whip is available from the Armorer for active Străjeri (rank 2+),
for the same coin and requisition prices as the service baton. It follows the
same custody rules: damage is capped above zero health, a lethal hit creates a
downed state and surrender request, and cuffed, bound or already-downed players
cannot be struck again. A successful hit plays the eight-frame whip flick,
holding the two impact poses slightly longer alongside the normal attack swing
and strong-hit sound. It applies 1.75 knockback strength for its low base
damage and +2 attack speed for faster follow-up swings.

The RP expansion adds a closed incident loop: citizens report incidents at the
Receptionist, on-duty guards can raise a cooldown-bound whistle call, the
Secretary dispatches one lead and bounded support, and the call resolves into
an auditable outcome. BOLOs are persistent notices, not arrest authority;
authority comes from a valid warrant/task and custody validation. A valid
search produces a read-only inventory snapshot, exact-stack confiscation and a
chain-of-custody record. Arrest paperwork links the sentence, task, fine,
incident, evidence, BOLO, complaint and reputation history. Reputation is a
separate bounded civic score: it affects recruitment and rehabilitation, but
never grants omniscient detection or the right to arrest.

## First-time setup

One command drives installation: `/straja setup` prints a checklist —
commissioner identity, the 10 administrative locations, the 4 patrol
checkpoints, and the 4 NPC officials — and always ends with the single next
step. The commissioner is also nudged at login while anything is missing.

The fast path from a fresh world:

1. Stand where the service desks should be → `/straja setup here` stamps all
   10 locations (receptionist, trainer, secretary, armorer, infirmary, …) at
   your spot; refine the four NPC locations individually for the building
   layout.
2. Stand in the patrol area → `/straja setup patrol` lays a 16×16 checkpoint
   square around you with default mission times.
3. `/straja setup npcs` spawns every missing official at its configured
   location (idempotent — roles already registered are skipped, so it only
   fills gaps).

Done. Refine individual points later with `/straja set-location <nume>`,
`set-checkpoint <id>`, `set-mission-time <id> <min>`, and `/straja npc …`.
Everything persists in SavedData, so setup can be interrupted and resumed
safely — the checklist always shows what remains. Prison cells and guard rooms
stay world-built (real geometry: enclosed shell, one door) via
`/straja prison cell` and `/straja room discover`.

### Admin tools

`/straja setup tools` (Comisar or op) hands out the physical tool kit —
CustomNPCs-style pointers that converge on the same service calls as the
commands above. Every use re-checks authority server-side, so a stolen or
duplicated item is inert in a normal player's hands; pending routes, corners,
and templates live in a per-holder SavedData store and are dropped on logout.
The items are non-craftable, non-stackable, and have no mob drops.

- **NPC Wand** — click a registered Straja NPC for a clickable menu: assign
  role, rename, set skin (native form), remove (behind a confirm click), or
  print the registry record. Clicking an *unregistered* entity (e.g. an
  existing CustomNPCs NPC) offers the same role list and **binds the role in
  place** — the entity keeps its own model, skin and name; Straja just owns
  the right-click. On a bound foreign NPC the menu hides rename/skin and
  removal *detaches* the role record without deleting the entity.
- **Patrol Wand** — click blocks to record the patrol route (re-click removes
  a point), sneak + click air to finish. Writes a variable-length route
  through the same path as `checkpoint add`/`set-checkpoint`; routes cannot
  cross dimensions.
- **Survey Rod** — click a block to choose which administrative location to
  stamp there, or stamp every missing one at once (`setup here` behavior).
- **Prison Marker** — click two opposite corners, then confirm the chat prompt
  to register the cell through `prison cell` validation.
- **NPC Cloner** — click a registered NPC to capture its role/name/skin, click
  a block face to spawn a registered copy, sneak + click air to clear the
  template. With a template captured, clicking an *unregistered* entity
  applies just the role — clone the logic onto an existing NPC, keep its
  appearance.
- **Room Marker** — unchanged room corner selection for `room discover`.

Bound foreign NPCs keep their host mod's rules: name/skin options in the
registry only reflect onto native `StrajaNpcEntity`, and the jailer assault
lifecycle (damage → arrest mission) requires the bound jailer to be
damageable in its own mod. Bindings key on entity UUID — if a host mod
respawns an NPC under a fresh UUID, detach the stale record and rebind.

Commands remain useful as an administrator/reference surface and for console or
RCON operation. Manually typed gameplay roots are OP 3 admin-gated, while setup,
policy, migration, NPC, debug and test surfaces require OP 4, so
ordinary players should not need to type them. The inventory below is therefore
not the target player UX:

- `/straja invite|recruit|quiz|resign|rejoin` — lifecycle
- `/straja start|checkpoint <id>|stop|special …` — duty & patrols
- `/straja salary|coins|food|kit` — economy
- `/straja mission create|draft|issue|accept|decline|report|fail|list` — missions
- `/straja cuffs request|accept|refuse|release|key|emergency` — restraints
- `/straja prison cell|arrest|release|status` — prison
- `/straja fine book|write|draft|issue|pay|appeal|appeals|review|recover|list|
  tasks|accept|complete|refuse|arrest|warrant|cancel` — fines & recovery
- `/straja complaint submit|list|claim|mobilize|join|leave|report|decide|
  review` — complaints
- `/straja room mark|discover|assign|release|status|waitlist` — rooms
- `/straja archive folder|sheet|edit|recipients|submit|sign|revoke|copy|
  envelope|catalog` — archive
- `/straja npc list|spawn|assign|set-name|set-skin|remove` — native NPCs
- `/straja setup tools` — hand out the physical admin-tool kit (Comisar/op)
- `/straja emergency alert|clear|start|end|status` — §25 urgency calls and the
  sustained emergency mode (Comisar or op/console; `status` is public)
- `/straja migrate <worldPath>` — import legacy `kubejs_persistent_data.nbt`
  + `playerdata/*.dat` (OP 4, idempotent, audit-logged)
- `/straja backup` — create a bounded SavedData snapshot (OP 3)

Typed setup, administration, diagnostics, migration, backup, and test surfaces remain
permission-gated: OP 3 for normal administration and OP 4 for setup/operating tools.
`/straja help` is also OP 3-gated and supports `/straja <comandă> help` on every
command-tree node. `/straja status`, `/straja rules`, and `/straja regulament`
are public text conveniences; `/straja npc-action <token>` is an internal
clickable-chat boundary and is intentionally omitted from help. `/straja backup`
creates an OP 3, durable SavedData snapshot of Straja's stores; corrupt-state
preservation remains a separate internal persistence recovery behavior. See
[`docs/admin-command-help.md`](docs/admin-command-help.md) for the full operator
surface.

## Economy

Salaries, fines and rewards pay out as physical coin items. The four
denominations follow a 64:1 ladder — Bronze (1), Brass (64), Silver (4096),
Gold (262144) Bronze-equivalents — and are item IDs configured under
`[economy]`; defaults are Ady's Decorations coins, but any mod's items work
(vanilla emeralds, custom coins, …). If the configured items aren't in the
registry the provider reports unavailable and all coin operations fail closed.

## Configuration

Every tunable rule lives in `config/straja-server.toml`, editable by hand or
through the in-game config screen (mod list → Straja → Config). Changes apply
on the next server start. Sections include `identity`, `timers`, `mission`,
`incidents`, `bolo`, `evidence`, `reputation`, `cuffs`, `restraints`, `downed`, `arrestRewards`, `economy`, `salary`,
`promotion`, `quiz`, `jailer`, `equipment`, `trainer`, `security`, `envelope`, `archive`,
`rooms`, `fines`, `prison`, `audit`, `complaints`, `ranks`, `reports`,
`audiences`, `emergency`, `debug`, and `testing`.

The `ranks` section also controls the §4 display prefix: every authorized
member (including off-duty) shows `[Rank] Name` in chat and the TAB list,
independently of their scoreboard team — the nameplate surface is opt-in via
`prefixNameplate`. Rank names and the Comisar title are configurable, and
renaming a rank never touches the persisted numeric rank.

Structured values use simple list encodings shown in the file's comments:

- integer tables: `"rank=coins"`, `"amount=days"`, `"severity=coins"`
- kits: `"rank=itemId,count"`
- service equipment: `"rank=key|itemId|count|replacementCost|label"`
- quiz questions: `"id|minRank|question|answer1;answer2"`

Empty or fully malformed structured lists fall back to the built-in defaults,
which mirror the reference KubeJS configuration. World-dependent state
(checkpoints, officer locations, cells, rooms, NPCs) is configured in-game by
the Commissioner and lives in SavedData, not in this file.

### Runtime overrides — `straja-policies.yaml`

Day-to-day tuning doesn't need a restart: the Commissioner (or an op/console)
edits ~120 curated keys in-game and they apply to the live services
immediately:

```
/straja policy list                          # all overridable keys by section
/straja policy get salary.perHour           # effective value + source
/straja policy set timers.quizCooldownMinutes 25
/straja policy set fines.allowedAmounts 10;25;50;100;250;500
/straja policy reset salary.perHour         # back to the TOML default
```

Overrides persist in `config/straja-policies.yaml` (flat `key: "value"` YAML,
hand-editable) and re-apply on boot — a malformed line is skipped and logged,
never fatal. Structured values use `;`-separated entries in the same encodings
as the TOML. Identity, security gates and debug/test flags are deliberately
**not** overridable — they stay TOML-pinned.

## Building & releasing

```bash
./gradlew build         # produces build/libs/straja-<version>.jar
```

Contributing to dev runs: Envelope resolves automatically from Modrinth Maven.
Ady's Decorations is not redistributed — drop its jar into `libs/` to have the
default coins in `runServer`.

Releases are evidence-gated. Push `vX.Y.Z-rc.N` to run
[`.github/workflows/rc.yml`](.github/workflows/rc.yml): the JAR is built once,
attested with Sigstore provenance, verified by digest through GameTests,
dedicated-server profiles, the RCON scenario suite, and the performance
budgets, then published as a **beta** to Modrinth and CurseForge and
finalized as a GitHub **prerelease** carrying the release manifest, SBOM,
checksums, and publication ledger. The advisory real-client UI job reports
its verdict in the manifest but never blocks publication.

Push the matching stable tag `vX.Y.Z` on the **same commit** to run
[`.github/workflows/release.yml`](.github/workflows/release.yml): it locates
the highest green RC for that version+commit, downloads the exact RC bytes,
re-verifies the digest and attestation, promotes the Modrinth version to a
release (retaining the file hash), uploads the identical bytes to CurseForge
as a release file, and creates the stable GitHub Release last. No compile or
JAR task runs during promotion. `workflow_dispatch` supports `dry_run` for a
no-mutation validation pass.

Repository setup (names only — values stay in GitHub settings):

- **Secrets:** `MODRINTH_TOKEN`, `CURSEFORGE_API_KEY`
- **Variables:** `MODRINTH_PROJECT_ID`, `CURSEFORGE_PROJECT_ID`,
  optionally `CURSEFORGE_GAME_VERSIONS` (numeric IDs; names are resolved
  through the CurseForge API when unset)
- **Environment:** `release`, restricted to release workflows/tags
- **Tags:** restrict `v*` creation to maintainers; protect `main` with the
  PR checks from `ci.yml`

All external mutations are query-before-create: a matching remote
version/hash is an idempotent no-op, while the same version carrying
different bytes fails closed. The workflow never deletes remote releases.

## Testing

```bash
./gradlew test          # unit tests — pure domain, in-memory fakes
./gradlew runGameTestServer # upstream NeoForge GameTests (8 required tests)
./gradlew runRpExpansionGameTestServer # RP expansion GameTests (7 required tests)
./gradlew runServer     # headless dev server (RCON on :25575, password in run/server.properties)
```

Test mode (`config/straja-server.toml → testing.enableTestCommands = true`,
local environment, OP 4) exposes `/straja test …` — virtual players
with real inventories/positions for deterministic service and integration
checks. This is an administrator/test surface, not the player UX:

```bash
python tools/rcon.py "straja test create-player civ1"
python tools/rcon.py "straja test fine-write guard1 civ1 50 huliganism descriere"
python tools/rcon.py "straja test fine-dump"
python tools/rcon.py "straja test advance-time 600"
python tools/rcon.py "straja test assert civ1 coins 60"
```

Virtual players are never visible when test mode is off.

The coherent expansion smoke flow is available as
`/straja test rp-expansion-flow` when test commands are enabled. It exercises
incident reporting/dispatch, callsign allocation, BOLO separation, custody,
exact-stack evidence and case linking in one server-authoritative path.

The full test matrix, GameTest batch and live-client/restart UAT checklist are
in [docs/testing.md](docs/testing.md).

## Persistence & migration

State lives in SavedData (`data/straja_*.dat`) via `JsonBackedStore` — corrupt
payloads are preserved as `_corrupt_backup` and reset, matching the reference.
Per-player state is a player SavedData section keyed by UUID.

`/straja migrate <legacyWorldPath>` imports the KubeJS world:
`kubejs_persistent_data.nbt` (fines, prisons, rooms, complaints, archive,
missions, custody, setup, audit) plus every `playerdata/*.dat`'s
`KubeJSPersistentData` (`straja_state`, fine drafts, archivist flag). Merging is
idempotent (dedup by record id) and each run is audit-logged.

## Invariants

Server is authoritative; rank text never grants authority; UUIDs take
precedence over names; every economy op is idempotent (receipt-scoped
deposits); failed delivery/payment persists a review/retry state instead of
false success; sentence time only ticks while the prisoner is online and active;
the baton and whip are structurally non-lethal.

## Known limitations

See `docs/parity-matrix.md` for the full PASS/PARTIAL/BLOCKED matrix. The
player flow is native NPCs, physical items, clickable chat, and native
server-authoritative form screens. The test profile now starts with the built
mod loaded successfully. Client live UAT is still needed to verify rendered
NPC interactions, form screens, item use, and the end-to-end client
experience.
