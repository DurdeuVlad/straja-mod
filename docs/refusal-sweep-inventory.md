# Refusal → Remedy Sweep Inventory (Issue #204)

Every player-facing denial reachable through NPC surfaces, public commands,
and forms now renders as **reason + concrete next step** via the
`PlayerGateway.refuse(reasonKey, remedyKey, args...)` convention. Romanian is
canonical (`ro_ro.json`); `en_us.json` holds parity translations (778 keys
each, key-for-key). Gameplay decision logic is unchanged — only the message
path.

## Convention

- `PlayerGateway.refuse(reasonKey, remedyKey, reasonArgs...)` — the gateway
  contract; `VirtualPlayerGateway` and `Fakes.TestPlayer` resolve the keys
  through `VirtualLang` (bundled `ro_ro.json`) so headless tests see the real
  rendered text.
- `StrajaText.refusal(reasonKey, remedyKey, ...)` — the `Component` variant for
  adapter sites that talk to `Player`/`CommandSourceStack` directly
  (`sendSystemMessage`, `sendFailure`).
- Rendered format (`straja.refusal.format`): `<reason> → <remedy>`.
- Remedy vocabulary lives under `straja.remedy.*` — NPC pointers
  (`reception`, `instructor`, `secretary`, `jailer`, `archivist`, `armorer`,
  `recruiter`), escalations (`ask_comisar`), self-service
  (`fix_retry`, `retry`, `wait`, `duty`, `status`), and `faq`
  (`/straja help`).

## Coverage by file

Sites counted as literal `refuse(`/`refusal(` calls in source
(769 total across 33 files; `GuardService.refuseCore` additionally routes every
`DutyEngine` error code — rank/duty/suspension/resignation/checkpoint
rejections — through `straja.duty.err.*` keys with per-code remedies).

Computed denial producers are routed through the same convention instead of
returning raw text: `PrisonService.validateCellGeometry` returns
`straja.prison.cell_*` keys, `MissionService.parseStart` returns
`straja.mission.start_*` keys inside `StartParse.errorKey()/errorArgs()`, and
`MissionService.templateProblem` returns a `Denied(key, args)` record — the
callers invoke `refuse()` with the produced key and args. Form payload denials
(`straja.form.invalid`, `straja.give_up.stale`) are wrapped in
`StrajaText.refusal` rather than bare `Component.translatable`.

| File | Sites | Notes |
|---|---|---|
| `application/service/GuardService.java` | 117 + core table | duty engine, quiz, promotion, resignation, setup |
| `application/service/MissionService.java` | 111 | mission issue/accept/decline/reward gates, `parseStart`/`templateProblem` helpers |
| `application/service/FineService.java` | 89 | issuance, payment, appeals, refusal-to-arrest paths |
| `application/service/CustodyService.java` | 86 | cuff/surrender/carry/resuscitate gates incl. `policyDeny` |
| `application/service/ArchiveService.java` | 54 | document/instrument access checks |
| `adapter/in/npc/NpcRoles.java` | 41 | stale action tokens, role gates, trade refusals |
| `application/service/ComplaintService.java` | 40 | filing/claim/verify/withdrawal checks |
| `adapter/in/command/StrajaCommands.java` | 32 | public + admin validations, room assign/release, redemption |
| `application/service/AdminToolService.java` | 19 | wand/cloner/cell tool gates |
| `application/service/EvidenceService.java` | 18 | warrant, search, transfer gates |
| `application/service/IdentityCardService.java` | 18 | card issue/renew/read gates |
| `application/service/IncidentService.java` | 16 | incident report gates |
| `application/service/RoomService.java` | 15 | selection/geometry/assignment gates |
| `adapter/in/form/FormSubmissionRouter.java` | 13 | invalid form submissions |
| `adapter/in/command/NpcCommands.java` | 13 | role/NPC lookup, provider errors, migration gates |
| `application/service/PrisonService.java` | 12 | arrest/release/cell gates incl. `validateCellGeometry` keys |
| `application/service/ReportService.java` | 11 | activity report gates |
| `application/service/AdminService.java` | 9 | admin-desk authorization |
| `application/service/ArmoryService.java` | 9 | funds/requisition gates, refund-failure ternary |
| `application/service/AudienceService.java` | 9 | audience request gates |
| `application/service/PolicyService.java` | 7 | config view/modify/validation gates |
| `adapter/in/event/StrajaEvents.java` | 6 | capability gates, client messages |
| `adapter/in/command/AdminCommandHelp.java` | 4 | OP-gated help |
| `adapter/in/command/DebugCommands.java` | 3 | debug reveal gates |
| `adapter/in/command/ConsolePlayerGateway.java` | 2 | console gateway refusal rendering |
| `adapter/in/form/FormPayloads.java` | 1 | stale form submission |
| `application/service/ArrestRecordService.java` | 2 | jailer handover gates |
| `application/service/EquipmentService.java` | 2 | kit delivery gates |
| `application/service/ReputationService.java` | 2 | commissioner-only reputation ops |
| `application/service/*` (Bolo/Emergency/Secretary/etc.) | ~30 | remaining services |

The codemod that performed the sweep is auditable under `tools/`:
`refusal-map.py` (the RO text → key + remedy + EN table),
`refusal-apply.py` (mechanical rewrite), `refusal-census.py` and
`refusal-remaining.py`/`refusal-literals.py` (literal inventory),
`refusal-lang-merge.py` (lang regeneration + parity),
`refusal-map-additions.py` (round-2 vocabulary additions).

## Intentionally left literal

Post-event notifications and status output are *not* denials of a player
action and stay as `tell(...)`: confirmations ("Ai primit…", "Cererea a fost
trimisă"), state notifications ("Ești încătușat de %s", "Resuscitarea a
început"), timed expiry notices ("Termenul … a expirat"), empty-state list
results ("Nu ai acte V2 emise."), informational output (reputation, arrest
banners, role descriptions), partial-failure reports ("livrat parțial"), and
the `/straja help` / setup-checklist text. The `noBareDenialText` test keeps an
explicit whitelist of these so new denials cannot ship bare.

## Verification

- `RefusalConventionTest` (source scan): every refusal call's second argument
  is a `straja.remedy.*` key — including computed-key paths like
  `refuseCore`/`coreRemedy`; every referenced key exists in `ro_ro.json` **and**
  `en_us.json`; `%s` placeholder counts match call args; the two lang files
  hold identical key sets; and no bare denial-shaped text reaches a player
  outside the convention (explicit whitelist for notifications). The scan
  covers every message channel (`tell`, `tellKey`, `sendFailure`,
  `sendSystemMessage`, `sendSuccess`, `displayClientMessage`,
  `sendToolPrompt`/`sendToolMenu`, `notify`, form/button helpers) with the
  widened denial vocabulary, plus `noComputedErrorBypass` which rejects
  `tell(errorValue)`/`Component.literal(result.reason())`-style pass-throughs.
- `RefusalConventionTest.refusalRendersReasonAndRemedy` (spot): a fired
  applicant hears the reason ("îndepărtat") **and** the remedy ("Comisarul").
- `StrajaGameTests` (real headless NeoForge server): a refusal path produces a
  `straja.refusal.format` component containing the reason key and a
  `straja.remedy.*` key.
- `./gradlew test` — 916 tests green; 24 GameTests pass on the real server;
  `./gradlew build` produces `build/libs/straja-0.2.0.jar`.
