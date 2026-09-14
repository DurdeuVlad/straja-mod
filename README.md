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
                      NpcRegistryUseCase, FormSessionUseCase
  port/out            outbound SPI: PlayerGateway, ServerGateway, CurrencyProvider,
                      DeliveryProvider, repositories, WorldGateway, Clock, IdGenerator
  service             use-case services: PlayerService, GuardService, EquipmentService,
                      MissionService, CustodyService, PrisonService, FineService,
                      ComplaintService, RoomService, ArchiveService, NpcAdminService,
                      MigrationService, AuditService, FormSessionService
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
- **Recruiter (Recrutor)** — the admission exam: only applicants recorded at
  Recepție (or commissioner-invited recruits) may answer; passing authorizes
  the applicant as Stagiar.
- **Trainer (Instructor)** — post-admission progression only: training
  modules, service-block points and progress, self-service rank-ups for
  configured ranks, and the physical training manual
  (`straja:training_manual`) — right-click it to read the rules.
- **Secretary** — duty self-service (start/checkpoint/stop, salary, coins,
  food, kit, resignation/rejoin), mission browsing/accept/report/fail, order
  carnet, complaint investigation reports, archive catalog, and **weekly
  activity reports** (§11): every member files a report per interval
  (`reports.intervalDays`, default 7) through a native form — activity,
  missions, incidents, notes for the Comisar. The Comisar reviews them at the
  same surface: accept, return-with-note (reopens submission), or call the
  author in. An overdue report can refuse duty start when
  `reports.blockDutyWhenOverdue` is enabled. Patrol shifts **must** start and
  end here — `/straja stop` away from the Secretary is refused for normal
  duty (free-duty ranks keep at-will stop). On duty start the guard's
  scoreboard team is captured and the `Straja` team is applied; the captured
  team is restored at shift end. Patrol routes loop — after the last
  checkpoint the next round returns to checkpoint 1 and duty ends only on
  stop or a missed deadline.
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
sack, keys, cutters, crowbar, and room markers all route through the same
inbound ports — item metadata is only a display hint and never grants
authority.

## First-time setup

One command drives installation: `/straja setup` prints a checklist —
commissioner identity, the 8 administrative locations, the 4 patrol
checkpoints, and the 5 NPC officials — and always ends with the single next
step. The commissioner is also nudged at login while anything is missing.

The fast path from a fresh world:

1. Stand where the service desks should be → `/straja setup here` stamps all
   8 locations (receptionist, trainer, secretary, infirmary, …) at your spot.
2. Stand in the patrol area → `/straja setup patrol` lays a 16×16 checkpoint
   square around you with default mission times.
3. `/straja setup npcs` spawns every missing official in a row (idempotent —
   roles already registered are skipped, so it only fills gaps).

Done. Refine individual points later with `/straja set-location <nume>`,
`set-checkpoint <id>`, `set-mission-time <id> <min>`, and `/straja npc …`.
Everything persists in SavedData, so setup can be interrupted and resumed
safely — the checklist always shows what remains. Prison cells and guard rooms
stay world-built (real geometry: enclosed shell, one door) via
`/straja prison cell` and `/straja room discover`.

Commands remain useful as an administrator/reference surface and for console or
RCON operation. Manually typed gameplay roots are permission-2 admin-gated, so
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
- `/straja migrate <worldPath>` — import legacy `kubejs_persistent_data.nbt`
  + `playerdata/*.dat` (op-only, idempotent, audit-logged)
- `/straja backup` — create a bounded SavedData snapshot (op-only)

Typed setup, administration, diagnostics, migration, backup, and test surfaces remain
permission-2 gated. `/straja status`, `/straja rules`, and `/straja regulament`
are public text conveniences; `/straja npc-action <token>` is an internal
clickable-chat boundary and is intentionally omitted from help. `/straja backup`
creates a permission-2, durable SavedData snapshot of Straja's stores; corrupt-state
preservation remains a separate internal persistence recovery behavior.

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
`cuffs`, `restraints`, `downed`, `arrestRewards`, `economy`, `salary`,
`promotion`, `quiz`, `jailer`, `equipment`, `trainer`, `security`, `envelope`, `archive`,
`rooms`, `fines`, `prison`, `audit`, `complaints`, `ranks`, `reports`,
`debug`, and `testing`.

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

Releases publish to Modrinth and CurseForge through
[`.github/workflows/release.yml`](.github/workflows/release.yml) when a `v*` tag
is pushed. Configure these repository-level GitHub Actions values first:

- **Secrets:** `MODRINTH_TOKEN`, `CURSEFORGE_API_KEY`
- **Variables:** `MODRINTH_PROJECT_ID`, `CURSEFORGE_PROJECT_ID`

The workflow builds and tests the mod, publishes the matching NeoForge 1.21.1
file to both platforms, and creates a GitHub Release with the jar attached.

## Testing

```bash
./gradlew test          # unit tests — pure domain, in-memory fakes
./gradlew runServer     # headless dev server (RCON on :25575, password in run/server.properties)
```

Test mode (`config/straja-server.toml → testing.enableTestCommands = true`,
local environment, permission 2) exposes `/straja test …` — virtual players
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
the baton is structurally non-lethal.

## Known limitations

See `docs/parity-matrix.md` for the full PASS/PARTIAL/BLOCKED matrix. The
player flow is native NPCs, physical items, clickable chat, and native
server-authoritative form screens. Client live UAT is still needed to verify
rendered NPC interactions, form screens, item use, and end-to-end client
experience — all current evidence is unit/build/source-level.
