# Changelog

## Unreleased

- Evidence-gated release pipeline: `vX.Y.Z-rc.N` tags run the full gate set
  and publish a beta; matching `vX.Y.Z` tags promote the exact RC-tested
  bytes to stable across GitHub Releases, Modrinth, and CurseForge with
  Sigstore provenance and a publication ledger
- Blocking NeoForge GameTests, dedicated-server dependency profiles,
  declarative RCON scenario/recovery suite, and release-blocking server
  health budgets on real servers
- Advisory real-client UI suite (MC Pilot + Xvfb) covering forms, custody,
  physical items, and reconnect delivery
- Chunked NBT persistence for oversized JSON stores (fixes silent save
  failures past the 64 KiB string limit)

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
