# Changelog

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
