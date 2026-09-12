# Straja Mod

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
  port/out            outbound SPI: PlayerGateway, ServerGateway, CurrencyProvider,
                      DeliveryProvider, repositories, WorldGateway, Clock, IdGenerator
  service             use-case services: PlayerService, GuardService, EquipmentService,
                      MissionService, CustodyService, PrisonService, FineService,
                      ComplaintService, RoomService, ArchiveService, NpcAdminService,
                      MigrationService, AuditService
  StrajaContext       the assembled ports record injected into every service
adapter/
  in/command          Brigadier command trees (Straja/Test/Debug/Npc commands)
  in/event            NeoForge events (tick, login recovery, damage hooks, cell guard)
  in/npc              StrajaNpcEntity + role routing
  in/test             virtual players (test mode only)
  out/minecraft       ServerPlayer-backed gateways (inventory, position, effects)
  out/persistence     JsonBackedStore + NbtStore SavedData adapters
  out/delivery        EnvelopeDeliveryProvider (real MailService)
  out/economy         ItemCoinCurrencyProvider (configurable coin items)
  out/migration       KubeJsNbtReader (pure-Java gzipped NBT reader)
bootstrap/            StrajaRuntime composition root, StrajaServerConfig → StrajaPolicies
```

Rules: adapters never contain business rules; domain/application never import
Minecraft or NeoForge classes (`ArchitectureBoundaryTest` enforces it).

## Commands

All player-facing flows are also reachable from the dedicated server console or
RCON. Highlights:

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

## Economy

Salaries, fines and rewards pay out as physical coin items. The four
denominations (values 1, 10, 100, 1000) are item IDs configured under
`[economy]` — defaults are Ady's Decorations coins, but any mod's items work
(vanilla emeralds, custom coins, …). If the configured items aren't in the
registry the provider reports unavailable and all coin operations fail closed.

## Building & releasing

```bash
./gradlew build         # produces build/libs/straja-<version>.jar
```

Contributing to dev runs: Envelope resolves automatically from Modrinth Maven.
Ady's Decorations is not redistributed — drop its jar into `libs/` to have the
default coins in `runServer`.

Releases publish to Modrinth + CurseForge via `./gradlew modrinth curseforge`
once `modrinth_project_id` / `curseforge_project_id` are set in
`gradle.properties` and `MODRINTH_TOKEN` / `CURSEFORGE_API_KEY` are exported.

## Testing

```bash
./gradlew test          # unit tests — pure domain, in-memory fakes
./gradlew runServer     # headless dev server (RCON on :25575, password in run/server.properties)
```

Test mode (`config/straja-server.toml → testing.enableTestCommands = true`,
local environment, permission 2) exposes `/straja test …` — virtual players
with real inventories/positions so the entire gameplay surface is
console-drivable:

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

See `docs/parity-matrix.md` for the full PASS/PARTIAL/BLOCKED matrix. No custom
client GUIs — interactions are command/text-driven by design.
