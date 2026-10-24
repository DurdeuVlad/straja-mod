# Changelog

## 0.5.0 — 2026-10-18

The Straja career epic: the faction now matches the public recruitment
announcement — two part-time corps, a four-grade officer ladder, announced
hourly wages, weekly activity minimums, and officer postings — on top of the
FUN overhaul (delegated authority, work economy, paperwork, duty presence,
dispatch, prison escapes, prison economy, parole). All persisted stores
upgrade in place; no world migration is required over 0.4.0.

### Announced rank & pay parity (RPP-1..4)

- Military part-time: **Stagiar → Străjer → Sergent**; professional:
  **Zilier → Meseriaș → Maistru**; officers:
  **Locotenent → Căpitan → Maior → Colonel**. `CareerGrade.nextGrade()` is
  the single transition source; demotion returns officers to their origin
  track's top rung.
- Announced hourly wages at the 64:1 bronze ratio — part-time
  64/128/256, officers 512/1024/1536/2048 — via grade-keyed
  `salaryPerGrade` (legacy level-keyed map stays as fallback for non-V2
  records). Mission reward caps rescaled to the wage economy.
- **Trades work shifts** (`WORK` duty mode): professional members earn
  hourly like enlisted — same accrual engine, same anti-AFK gate, no
  routes or checkpoints, stoppable via `/straja stop`.
- **Weekly minimum activity**: `lastActiveAt` stamps on login + duty;
  dossiers flag `INACTIVE` past `weeklyInactivityDays` (default 7).
  `/straja absence <days> [reason]` files an audited Comandament notice
  (≤ `absenceMaxNoticeDays`, default 30); returning early clears it.
- **Officer postings**: `POSTING` appointments carry a Comandament-set
  title + station + jurisdiction — sections and specialist desks —
  audited and revocable via `/straja admin personnel post|revoke`.
  Organizational only; capabilities stay grade-uniform by design.

### Duty presence (RP-friendly AFK + disconnect handling)

- Anti-AFK grace 90s → **15min** and **chat counts as activity** —
  presence on an RP server is talking, not shuffling.
- Disconnect **pauses the shift**: the wage clock resets at rejoin so
  offline minutes never pay. Away longer than
  `dutyAutoEndOfflineMinutes` (default 5, 0 disables) auto-ends the
  shift with earned wages still claimable, audited.

### FUN overhaul (#264–#273)

- FUN-1 delegated authority — bounded standing orders + revocation for
  safe delegable actions, fail-closed everywhere else.
- FUN-3 work economy; FUN-4 paperwork; FUN-5 duty presence; FUN-6
  dispatch pipeline; FUN-7 prison breaks; FUN-8 transfer rides; FUN-9
  escape paths; FUN-10 prison economy; FUN-11 parole & appeal hearings.

### Rank remodel + petition flows

- **Persisted saves migrate on load**: legacy grade names (`SERGENT`,
  `STAGIAR_SPECIALIST`, `SPECIALIST`, `MAISTRU`, `INSPECTOR`) remap —
  persisted `MILITARY_SERGENT` still means Locotenent, as it always did.
  Unknown values fail closed into corrupt-backup; checkpoint `roleBans`
  keep matching migrated records via legacy role aliases.
- All officer grades share the full non-Comisar capability set;
  professional-origin officers keep the mobilization-gated patrol rule.
- **Join quiz simplified**: fixed-order 6-question bank, pass at ≥5/6,
  per-question feedback, cooldown + full retake on failure.
- **Promotion petitions at the Secretary**: "Cere avansarea" files an
  optional-reason petition straight to review. The Comisar reads each
  petition as a written book (with advisory service stats) and stamps
  Aprobă/Respinge in a form bound to the application version, so a stale
  review fails closed. Outcomes reach the applicant live or on next
  login.
- **Trades join petitions at the Receptionist**: choosing a profession
  opens a work-petition form; the Comisar reviews and approval enrolls a
  part-time Zilier. Sworn members, suspended and fired records cannot
  petition.
- Admin `promote` now means "approve the member's filed petition"; the
  legacy direct rank-bump path is removed.

### Custom desk GUI + the army loop

- **Slotless `straja:desk` menus**: every native NPC desk and every
  `bind-desk` host renders through a purpose-built `MenuType` +
  `StrajaDeskScreen` — live titles, per-action icons, tooltips plus an
  instant info bar, «Mai multe…»/«Înapoi» pagination, and dedicated DA/NU
  confirm pages with spelled-out consequences. Clicks travel as
  `sessionId + sequence + slot` packets validated server-side; the old
  chest-item renderer is gone. `npcMenus.enabled=false` still degrades to
  the same actions as clickable chat rows, and CustomNPCs-bound surfaces
  keep the provider-native `GuiCustom`.
- **The Comandant**: a fifth physical official (spawned at the HQ anchor)
  fronts the garrison's army — Straja is now police *and* army.
- **Army units + operations** (`/straja unit` for admins): military-track
  members enlist at the Comandant; the station unit self-organizes on
  first enlistment and the first officer takes command (succession falls
  to the next officer on leave). Officers schedule drills, patrol sweeps
  and exercises; populated units without a scheduler auto-plan their next
  op on a cadence. The player loop is three clicks — sign up, report at
  muster (walk-ups count), done: the background sweep starts and
  completes operations, pays every reported participant
  `serviceBlocks` through the same progression wallet missions use,
  moves unit readiness by turnout, and keeps the last 20 results per
  unit for the history view. Leaving the unit is a confirmed action that
  also drops the player's operation sign-ups.
- Enlistment is gated server-side to active military-track members;
  scheduling to active officers of the unit, and a unit runs one open
  operation at a time. Every refusal follows the reason→remedy
  convention.
- Every desk-rendered icon now ships a real item model + texture —
  `seal_stamp`, `official_document`, `guard_uniform`, `identity_card`,
  `training_manual` and the rest no longer render as missing-texture
  placeholders.

## 0.6.0 — 2026-10-24

Polish + stability over 0.5.0: institution copy is consistent, and the
army treadmill no longer bleeds unattended units.

- **Idle operations resolve quietly**: auto-scheduled unit operations
  that nobody signed up for and nobody attended drop from the ledger
  instead of recording `-8` readiness failures every cycle — units stop
  sliding to 0 while the garrison sleeps. Committed no-shows and
  officer-planned operations still fail honestly.
- Terminology normalized: members are `străjer` (RO) / displayed rank
  names (EN) across refusal, custody and mission text; the player help
  intro, FAQ "Despre Strajă", manual oath and quickstarts now describe
  Straja as the garrison's police *and* army.
- Fixes a server crash when an idle auto-operation resolved while
  another operation was due in the same tick.
- Small copy corrections in the player/admin quickstarts and config
  comments.

## 0.4.0 — 2026-10-06

The Papers-Please forgery epic (#245): a server-authoritative artifact
registry, physical black-market forging, and a two-layer detection and
enforcement pipeline. All persisted stores upgrade in place; no world
migration is required over 0.3.1.

### Central artifact registry + licensing (M1, #246)

- `ArtifactRegistryStore` — schema-versioned `SavedData` covered by
  `/straja backup`; records carry serial `RC-<n>`, marking `#RC-<n>`,
  itemId, classified kind, holder, issuing inspector, `pendingUntil`,
  status
- 24h pending maturation (`artifactRegistry.pendingMaturationHours`):
  a pending mark is not yet legal — legality costs a day
- `/straja inspector register [player]` binds the held item and stamps
  `ArtifactSerial`/`ArtifactMark`; already-marked items refuse, and a
  failed mark burns the serial as REVOKED so no unbound serial exists
- INSPECTOR and TRANSPORTER licenses — authority grant/revoke (OP 3),
  audited, self-view via `/straja license`, `inspector status`,
  `transporter status`
- Sealed military crates: `sealed_military_crate` + `seal_stamp`;
  `/straja transporter seal|unseal` writes `SealBy`/`SealId`/`SealAt`,
  transporters unseal only their own
- `/straja artifact check|revoke <mark>` admin surface,
  `[artifactRegistry]` config section, `artifactRegistry.*`
  `/straja policy` overrides

### Forging engine (M2, #247)

- Physical forging verbs only — anvil strikes and smithing copies, no
  commands, no menus: a forgery kit is materials + a station
- Locked 5-tier roll N1=3 / N2=7 / N3=15 / N4=30 / N5=45 via
  `forgery.tierWeights`; malformed values fall back to the pyramid
- Anvil: `seal_stamp` + regulated item — licensed inspectors strike
  authentic pending registrations, unlicensed strikes roll on the take
- Smithing: `straja:exemplar` template ingredient (only data-carrying
  documents match — can't fake a doc class never held) + blank stock +
  `carbon_paper`; exemplar returned, only carbon consumed
- The roll happens only at the take — UI previews show unmarked copies,
  no reroll-on-refresh vector
- Forged items carry the claimed serial, the physical malformed mark,
  and the `ArtifactForgery` tier; the registry keeps `FRG-n` FORGED
  shadow records that never mature legal
- One `artifact_forge` audit record per attempt
- `/straja identity forge <player> [N1..N5]` stages forged identity
  cards for scenario testing

### Mark detection + enforcement pipeline (M3, #248)

- Gate scanners read physical marks only — a barcode scanner, not an
  archivist: `UNREGISTERED`, `FLAGGED` (malformed), `CRUDE` (absurd) →
  arrest lane; a plausible `#RC-<digits>` walks through by design
- Trained eyes add the registry cross-check: JUNIOR catches
  known-forged + far-fetched claims, VETERAN adds near-miss serials,
  EXPERT adds holder/item conflicts + retired marks
- `/straja inspect <player>` officer parity — comisar→EXPERT,
  quiz-trained/sergent+→VETERAN, on-duty→JUNIOR; duty-gated,
  proximity-bound (~8m hands-on)
- One enforcement path everywhere: targeted seizure to evidence
  (craft grid, cursor, and Curios via `DeepScanGateway.seizeAt`),
  `document_forgery`/`artifact_forgery` offense, `forgery_detected`
  audit row, idempotent system BOLO appending `falsificare`
- Every control surface scans: stage-1 inspect, stage-2 arrest,
  deny-stage, board checks, linked-gate arrivals, wrong-way repels,
  escorted camp exits

### Release pipeline

- Retry release-asset uploads over the create/upload race — follow-up
  to the 0.3.1 prerelease-rollback fix

## 0.3.1 — 2026-10-05

Patch release: tester-protocol onboarding and release-pipeline fixes over
0.3.0.

### Tester protocol onboarding

- `/straja protocol` no longer requires a `serverconfig` edit: operators
  (or the singleplayer host with cheats on) and commissioners can run it
  in-game; `protocol.enabled` remains only as the opt-in for non-op
  testers on dedicated servers
- `protocol start` auto-provisions missing LAW fixtures around the tester
  as pure store records — a DENY walk-through gate (`protocol_gate`), an
  ARREST intake lane (`protocol_intake`), a cell (`protocol_cell`), and a
  labor camp (`protocol_camp`). Provisioned worlds are left untouched
- Chapter books resolve `{gate}`/`{intake}` to the live site name and
  coordinates so the invisible bounds stay findable

### Release pipeline

- GitHub releases are created before assets upload — inline asset upload
  on a fresh release raced `uploads.github.com` and rolled the release
  back
- CurseForge game-version resolution is constrained to file-applicable
  type categories (minecraft/modloader/environment/java), fixing the
  invalid-dependency upload failure

## 0.3.0 — 2026-10-05

Targets Minecraft 1.21.1 on NeoForge 21.1.x (built against 21.1.252).
All persisted stores upgrade in place; no world migration is required
over 0.2.0.

### Law enforcement series (LAW-000 → LAW-008)

- Full native port of the law-enforcement stack off the legacy KubeJS
  layer into hexagonal application services with Gson-backed
  `SavedStores` persistence
- M2 checkpoint core: gate crossing ledger, contraband detection,
  repel/deny flows, drop-off bookkeeping
- M3 rules of the gate and M4 custody & escort: arrest, escort tethers,
  cell assignment, locker seizure
- M5 trade NPCs, M6 labor camps, M7 guard integration, M8 hardening and
  migration tooling (`/straja migrate`)

### Prisoner debt and bail

- Fine ledger with `paidAmount` + pooled `contributions[]`; anyone may
  chip in any amount, capped at the remainder, final
- Locker levy: at release, camp transfer, or bail-set, coins are
  extracted live inventory → locker chests → pending reservations,
  oldest-first, with exact-change breaking and spill-never-void
- Release gate: debt over `releaseBlockThreshold` blocks release
  (`onBlocked = CAMP|CELL`); jailer-gated, commissioner/op bypass
- `/straja debt`, `/straja debt pay`, `/straja bail`
- Written-book notices at every key moment — levy receipt,
  release-denial, transfer order, credit notice — queued for offline
  players

### State-issued bounty hunting

- Inspector+/Comisar post bounties (`/straja bounty post|list|cancel`);
  posting auto-issues a wanted-on-sight BOLO
- Civilian capture: rope binds only a downed or surrendered
  (`/straja surrender`) target; escort tether to checkpoint delivery
- Hunter payout by the state; prisoner owes 2× bail payable by anyone;
  unpaid after 24h → automatic labor-camp transfer

### Guided tester protocol

- `/straja protocol` — a nine-chapter acceptance dossier delivered as
  written books, one step at a time, covering every player and admin
  surface
- `/straja protocol actor` summons "Suspectul", a joined fake player
  (real player-list entry) that can be cuffed, arrested, roped,
  escorted, and bountied — no second human needed
- Per-tester progress persists across relog/restart; `next`/`back`/
  `skip`/`reset` recovery controls; `[protocol]` config gate (off by
  default — enable in test worlds only)

### Hardening and docs

- `GameTestChunkWatchdog`: works around a vanilla chunk-ticket race that
  could stall the GameTest batch on cold worlds
- Player and administrator quick-start guides (RO), refusal→remedy
  denial sweep, audit/command surface tightening
- Gradle 9.8.0; CI action bumps

## 0.2.0 — 2026-09-28

Targets Minecraft 1.21.1 on NeoForge 21.1.x (built against 21.1.252;
`neoforge` dependency range `[21.1.0,)`, `minecraft` `[1.21.1, 1.22)`).
No world migration is required over 0.1.0; all persisted stores upgrade
in place.

### NPC provider platform

- Provider-neutral NPC surface boundary: authored profiles project to both
  the native Straja surface and CustomNPCs through one canonical model,
  with tokenized actions bound to player, binding, action, and proximity
- CustomNPCs presentation foundation plus a persistent binding lifecycle:
  assignment revisions, durable audit intents, and recovery after
  interrupted bind, unbind, reproject, or cross-provider switch
- In-game provisioning: admin wand gated at the early-attack boundary,
  in-game NPC profile selector, and spawn/remove commands with
  provisioning audit and recovery
- Provider rollout and migration controls with per-binding audit, plan
  previews, and rollback on failure
- Role bindings onto existing foreign NPCs, plus hardened dialog trees

### CustomNPCs workflows

- Admission vertical slice: receptionist application flow feeding the
  instructor quiz, with persisted application status projection
- Armorer orders, recruiter career surface, secretary and receptionist
  civic workflows (complaints, fines, audiences)
- Jailer custody and prison workflows; archivist archive workflows
  (folders, sheets, recipients, signatures, envelopes, document issue)
- Native and CustomNPCs surfaces both render bounded forms through the
  server-authoritative form-session port

### Custody and downed domain

- Persisted downed state and custody deadlines across restarts, with
  canonical state serialization and idempotent recovery
- Single-owner lethal-event resolver; terminal second-hit damage;
  Vampirism provider boundary with its own DBNO path
- Server-owned carry transport, interruptible resuscitation, criminal
  rope and black sack visuals, bolt-cutters release
- Cuffed arrests resolve into canonical jail custody with delivery and
  revival deadlines evaluated by a dedicated deadline engine
- Confirmed give-up action for eligible downed players; bound and cuffed
  players are told why their actions are blocked
- Bici whip custody item; event-driven custody messaging; optional-mod
  safeguards for the custody visuals

### Personnel, careers, and economy

- Rank ladder realignment (Stagiar → Străjer → Sergent → Inspector +
  specializations) with rank display in chat, TAB, and nameplates
- Straja V2 personnel foundation checkpoint: career grades, promotion
  applications, evidence, and Comisar-approved reconciliation
- Self-service Meseriași trades path: civilians enroll as Ziler (part-time,
  per-trade label) at the Receptionist
- Maistru grade and full-time trades advancement: promotion requests
  submitted by the player, approved by the Comisar, employment upgraded
  to full-time on approval
- 64:1 coin ladder (Bronze/Brass/Silver/Gold), hourly salary accrual,
  secretary-gated shifts, faction capture/restore, looping patrols
- Mission templates with calculated budgets

### Items, documents, and roleplay systems

- Physical admin tools (AT-001..AT-007), permanent guard gear, armorer
  shop, and variable-length patrols
- UUID-bound identity cards plus a controlled forgery route;
  bookshelf-compatible archive papers
- §25 emergency system and Comisar admin surface behind the Secretary
- Runtime-editable YAML policy store with live apply; native roleplay
  baseline (trainer, guided setup, config GUI, free duty, factions)

### GUI and visual revamp

- Visual design contract with annotated mockups driving a four-milestone
  surface revamp: themed headers with item icons, a generated icon pack,
  panel backgrounds, and a texture pipeline
- Surface-by-surface GuiTheme revamp for CustomNPCs and a restyled
  StrajaFormScreen with an adaptive field layout budget

### Forms and wire protocol

- Unified form field bound: `MAX_FIELDS` now lives once on the form-session
  port (5 fields) instead of three divergent copies; oversize requests
  fail at construction on the server, never at client decode
- BOLO_CREATE form renders correctly (was silently rejected / crash-prone
  at five fields against a four-field cap)

### CI, release engineering, and reliability

- Evidence-gated release pipeline: `vX.Y.Z-rc.N` tags run the full gate
  set and publish a beta; matching `vX.Y.Z` tags promote the exact
  RC-tested bytes to stable across GitHub Releases, Modrinth, and
  CurseForge with Sigstore provenance and a publication ledger
- Blocking NeoForge GameTests (27 tests across two suites), dedicated-
  server dependency profiles, a declarative RCON scenario/recovery suite,
  and release-blocking server health budgets on real servers
- Advisory real-client UI suite (MC Pilot + Xvfb) covering forms, custody,
  physical items, and reconnect delivery; CustomNPCs client UI suite
- Chunked NBT persistence for oversized JSON stores (fixes silent save
  failures past the 64 KiB string limit)
- Dozens of CI hardening fixes: scenario click-path alignment, client
  relaunch retries, log capture, token/chat-wait races, sequential
  GameTest servers on constrained runners

### Fixes and hardening

- CustomNPCs interaction listener registration and native GUI client
  compatibility fixes
- V2 GameTests run through the real commissioner bootstrap
- CustomNPCs surfaces no longer overlap labels, tooltips, or submit areas
- Training manual no longer opens incorrectly on real clients
- Gradle wrapper 9.7.1, ModDev 2.0.147, Minotaur 2.10.0, setup-node 7.0.0,
  NeoForge 21.1.248 → 21.1.252

## 0.1.0 — Initial release

Server-side NeoForge implementation of the Straja police system, replacing the
legacy KubeJS runtime:

- Recruitment lifecycle (invite, quiz, ranks, resignation, rejoin)
- Duty engine: patrols, checkpoints, configurable timers, service leases,
  equipment issue/reclaim with serials
- Physical coin economy with receipt-scoped idempotent payouts; coin item IDs
  configurable under `[economy]` (defaults: Ady's Decorations)
- Missions with drafts, rewards, retention pruning
- Fines, appeals, recovery tasks and arrest bounties (alive/death multipliers,
  daily caps)
- Cuffs, restraints, downed state, custody and prison sentences
- Rooms, waitlists, complaints, archive documents
- Envelope-based mail delivery, native NPCs, KubeJS world migration
- 162 unit tests plus RCON-driven live-server scenario (`tools/scenario.sh`)
