# Refusal → Remedy Sweep Inventory (Issue #204)

Every player-facing denial reachable through NPC surfaces, public commands,
and forms now renders as **reason + concrete next step** via the
`PlayerGateway.refuse(reasonKey, remedyKey, args...)` convention. Romanian is
canonical (`ro_ro.json`); `en_us.json` holds parity translations (517 keys each,
key-for-key). Gameplay decision logic is unchanged — only the message path.

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
(481 total; `GuardService.refuseCore` additionally routes every
`DutyEngine` error code — rank/duty/suspension/resignation/checkpoint
rejections — through `straja.duty.err.*` keys with per-code remedies):

| File | Sites | Notes |
|---|---|---|
| `application/service/MissionService.java` | 82 | mission issue/accept/decline/reward gates |
| `application/service/GuardService.java` | 71 + core table | duty engine, quiz, promotion, resignation, setup |
| `application/service/FineService.java` | 65 | issuance, payment, refusal-to-arrest paths |
| `application/service/CustodyService.java` | 58 | cuff/surrender/carry/resuscitate gates incl. `policyDeny` |
| `application/service/ArchiveService.java` | 46 | document/instrument access checks |
| `adapter/in/npc/NpcRoles.java` | 26 | stale action tokens, role gates, unregistered NPCs |
| `application/service/ComplaintService.java` | 21 | complaint filing/withdrawal checks |
| `application/service/EvidenceService.java` | 16 | warrant and search gates |
| `application/service/IdentityCardService.java` | 11 | card issue/renew/read gates |
| `application/service/IncidentService.java` | 9 | incident report gates |
| `application/service/ReportService.java` | 9 | activity report gates |
| `application/service/PrisonService.java` | 8 | arrest/release gates |
| `application/service/AdminService.java` | 7 | admin-desk authorization |
| `application/service/ArmoryService.java` | 7 | gear order gates |
| `application/service/AudienceService.java` | 7 | audience request gates |
| `application/service/RoomService.java` | 6 | room access gates |
| `application/service/*` (Bolo/Emergency/Secretary/etc.) | 18 | remaining services |
| `adapter/in/command/StrajaCommands.java` | 4 | public `npc-action`, inbox, runtime guards |
| `adapter/in/form/FormSubmissionRouter.java` | 4 | validation failures on consumed forms |
| `adapter/in/event/StrajaEvents.java` | — | item-use denials via gateway path |

## Intentionally left literal

Post-event notifications and status output are *not* denials of a player
action and stay as `tell(...)`: confirmations ("Ai primit…", "Cererea a fost
trimisă"), state notifications ("Ești încătușat de %s", "Resuscitarea a
început"), timed expiry notices ("Termenul … a expirat"), informational output
(reputation, arrest banners, role descriptions), and the `/straja help` /
setup-checklist text.

## Verification

- `RefusalConventionTest` (source scan): every refusal call's second argument
  is a `straja.remedy.*` key; every referenced key exists in `ro_ro.json` **and**
  `en_us.json`; the two lang files hold identical key sets.
- `RefusalConventionTest.refusalRendersReasonAndRemedy` (spot): a fired
  applicant hears the reason ("îndepărtat") **and** the remedy ("Comisarul").
- `./gradlew test` — 908 tests green; `./gradlew build` produces
  `build/libs/straja-0.2.0.jar`.
