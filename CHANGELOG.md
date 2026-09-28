# Changelog

## Unreleased

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
