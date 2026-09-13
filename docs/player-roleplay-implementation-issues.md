# Player/Roleplay Surface — Devin Implementation Issues

Status date: 2026-09-13 (implementation pass completed; statuses updated below)

This is the implementation queue for finishing the player-facing Straja
experience. Work through the issues in priority order, keeping each change
server-authoritative and verifying it before moving to the next issue.

## Original intent

The goal is not just to expose the old KubeJS feature list through a new set of
commands. The goal is to turn the Straja system into a native NeoForge 1.21.1
roleplay experience that feels like an institution inside the world: players
meet the right official, receive or use the right physical document/tool, make
decisions through an understandable interaction, and see the consequence in
the persisted Straja record.

The distinction that drove the implementation plan is explicit: admin work
belongs in commands, while player/roleplay work belongs in NPCs and items. For
example, taking a world backup is an operator action and should be `/straja
backup`, permission-gated and usable from console/RCON. A citizen paying a
fine, a recruit answering a quiz, an officer accepting a mission, or a guard
using cuffs should meet a receptionist, secretary, jailer, archivist, or
physical item in the world. These are different surfaces even when both
surfaces eventually call the same application service.

The implementation should preserve the useful behavior of the reference
system—Romanian in-game text, canonical English command names and Romanian
aliases, persistence, auditability, migration traceability, role/capability
rules, deadlines, budgets, item delivery, and recovery—while correcting the
reference system's console-first/player-confusing boundary.

## Design principles

1. **Roleplay is the player API.** A normal player should be able to discover
   and complete ordinary gameplay by visiting an appropriate NPC, carrying an
   appropriate item, or following a clickable prompt. A command may remain as
   an administrator/reference or console/RCON tool without being the intended
   player UX.
2. **Commands have an operational boundary.** Backup, setup, migration, NPC
   registry management, diagnostics, test harnesses, rank administration, and
   other operator actions remain permission-2 typed commands. `/straja backup`
   must not be disguised as a player interaction.
3. **Application services own rules.** NPCs, items, commands, payloads, and
   event hooks translate intent and context. They must not duplicate rank,
   capability, location, identity, status, budget, deadline, delivery, or
   persistence logic.
4. **The server is authoritative.** The client can request an action and send
   bounded text, but the server chooses the current question, resolves player
   identity, loads the record, checks state/location/capability, and performs
   the mutation on the server thread.
5. **Fail closed.** Missing records, malformed UUIDs, forged item metadata,
   stale buttons, expired forms/tokens, wrong-player submissions, wrong
   locations, and duplicate packets must produce no unauthorized mutation.
6. **Every mutation is durable and auditable.** State changes must use the
   existing SavedData repositories, preserve idempotency, release reservations
   when work is cancelled, and write an audit entry where the service contract
   requires it.
7. **Recovery is part of the feature.** Logout, death, restart, offline
   delivery, malformed legacy state, and full inventories must have explicit,
   deterministic behavior. A player must not lose a restraint, reward, notice,
   archive delivery, room assignment, or other entitlement silently.
8. **Native means native.** Prefer NeoForge registrations, native menus/forms,
   native items, and native entities. Do not reintroduce KubeJS-only flows,
   writable-book hacks, chat capture, or a dependency on CustomNPCs.
9. **Compatibility is deliberate.** Keep legacy IDs, migration readers, and
   Romanian aliases where they are part of the contract, but do not allow
   compatibility shortcuts to weaken UUID provenance or production security.
10. **Evidence beats assumption.** Unit tests prove pure rules and services;
    integration/GameTests prove NeoForge hooks and persistence; live client UAT
    proves rendering, clicks, screens, item use, and reconnect behavior. A
    missing live tool is a documented caveat, not an inferred pass.

## Non-goals and tradeoffs

- Do not make every administrator operation discoverable through an NPC. The
  operator command boundary is intentional and should stay small, explicit,
  permission-gated, and console-safe.
- Do not expose a general arbitrary-command runner through NPC clicks.
- Do not trust an item NBT/custom-data record ID without re-reading the
  persisted record and checking the holder's authority.
- Do not solve multiline input by intercepting arbitrary player chat or by
  putting reports/reasons in a command argument.
- Do not claim full parity merely because a service method exists. The relevant
  player surface, state transitions, persistence behavior, and evidence must
  all exist.

## Dependency order

`RP-001` is the shared foundation for all free-text actions. Recruitment and
training (`RP-002`), mission reports/failure (`RP-004`), complaint submission
and investigation (`RP-005`), and fine appeals/drafts (`RP-006`) should reuse
the same bounded form/session protocol. The NPC graph and item affordances can
then be expanded without inventing separate authorization paths. Recovery
(`RP-009`) and the security/regression matrix (`RP-010`) are release gates,
not optional cleanup. Documentation (`RP-011`) and final review (`RP-012`) must
describe observed behavior rather than planned behavior.

## Product boundary

The surface split is intentional:

- Administrator operations remain typed commands. This includes `/straja
  backup`, setup, migration, NPC registry administration, diagnostics, test
  tooling, rank administration, and other operator/reference commands.
- Ordinary player and roleplay interactions must be reachable through native
  Straja NPCs, physical Straja items, or clickable chat opened by those NPCs.
- A player-facing click may internally use `/straja npc-action <opaque-token>`.
  The token must be short-lived, one-use, bound to the clicking UUID, and
  validated against current persisted state. It must never expose a typed
  gameplay command or trust record IDs from the client.
- Do not make ordinary players type `/straja mission`, `/straja complaint`,
  `/straja fine`, `/straja start`, or equivalent gameplay roots. Those roots
  remain admin/reference commands even when they call the same application
  services.

## Current baseline

Shipped state after the implementation pass:

- `/straja backup` is registered as a permission-2 command. It writes a
  durable SavedData snapshot containing the Straja stores and keeps the newest
  ten snapshots. The implementation is in
  `src/main/java/com/dwurdy/straja/adapter/in/command/StrajaCommands.java` and
  `src/main/java/com/dwurdy/straja/adapter/out/persistence/StrajaDataProvider.java`.
- The typed command policy and public/admin help tests cover the backup
  boundary in `CommandSurfaceTest`.
- A native server-authoritative form/session layer exists
  (`FormSessionUseCase` + `FormSessionService`, `StrajaFormMenu`,
  `StrajaFormScreen`, `FormPayloads`, `FormSubmissionRouter`). Sessions are
  owner-bound, allowlisted, expiring, one-use, and purged on the server tick;
  payloads carry only the opaque session ID and bounded field values.
- Every ordinary player flow (recruitment quiz, duty self-service, missions,
  complaints, fines, custody, prison, rooms, archive) is reachable through
  native NPC clickable actions and physical items, routed through dedicated
  inbound ports (`*RoleplayUseCase`, `GuardDutyUseCase`,
  `GuardRecruitmentUseCase`, `PlayerQueryUseCase`). Player-facing adapters no
  longer call concrete services — `ArchitectureBoundaryTest` enforces it.
- `StrajaEvents` routes the full registered item set (`order_book`,
  `mission_carnet`, `archive_folder`, `archive_document`, `carbon_paper`,
  `archive_stamp`, `official_envelope`, `fine_book`, `fine_notice`, cuffs,
  rope, head sack, keys, markers) and runs login/logout/death/tick recovery
  for every persisted aggregate.
- `mct` is not installed in the current environment. All evidence is
  unit/build/source-level (`gradlew test --rerun-tasks`, `gradlew build`,
  `git diff --check`); live Minecraft client UAT remains an open caveat for
  RP-012, not an inferred pass.

## RP-001 — Add native server-authoritative forms

Priority: P0 — prerequisite for every text-bearing player flow.

**Status: PASS (unit/build).** `FormSessionUseCase`/`FormSessionService`
implement owner-bound, allowlisted, 120-second, one-use sessions with bounded
fields (4 fields, 32-char IDs, 2000-char values, 128-char session IDs) and
atomic consumption. `StrajaFormMenu`/`StrajaFormScreen` provide the native
menu/screen with single-line `EditBox` and multiline editors; `FormPayloads`
registers serverbound payloads on the registrar's default MAIN thread and
rejects non-player senders; `FormSubmissionRouter` revalidates persisted state
after consumption. Evidence: `unit:FormSessionServiceTest` (owner, allowlist,
expiry, length, blank, cancel, one-use, token-collision),
`unit:FormPayloadSurfaceTest`, `unit:EventSurfaceTest`; `gradlew build` green.
Caveat: live client rendering/click UAT not run (`mct` unavailable).

Relevant areas:

- New `bootstrap/StrajaMenus.java`.
- New server-side form classes under `adapter/in/form/`.
- New client-only form screen under `adapter/out/client/`.
- Registration in `StrajaMod.java` and `adapter/out/client/StrajaClient.java`.
- New Minecraft-free session tests.

Implement a small custom menu/screen rather than writable books or chat
capture. The server opens a form with a title, prompt, bounded field length,
and an opaque session ID. The client sends only the session ID and text in a
serverbound payload; it must not send a player UUID, service authorization, or
record state.

Required session properties:

1. owner UUID;
2. allowlisted action ID, including a bounded record ID when required;
3. creation/expiry time (about two minutes is reasonable);
4. maximum text length;
5. cryptographically unpredictable or UUID-based opaque session ID;
6. atomic one-use consumption;
7. owner-checked cancel and cleanup on menu close;
8. rejection of null, blank, oversized, unknown, expired, or cross-player
   submissions.

The server must revalidate the referenced record and player identity after
consuming the session, then call the existing application service. Payload
handlers must enqueue work on the server thread and reject non-player senders.
Use a single-line `EditBox` for short answers and a multiline editor for
reports/reasons. Never log submitted text or put it in a command string.

Acceptance criteria:

- A native form can open, submit, cancel, expire, and close cleanly on both
  sides.
- A second submission, a submission by another UUID, and an expired session
  cannot invoke an application service.
- Unit tests cover owner binding, allowlist, expiry, max length, blank text,
  cancellation, and atomic one-use behavior.
- The common source set does not import client-only classes.
- A build succeeds with `./gradlew.bat build`.

## RP-002 — Complete recruitment and training through the receptionist

Priority: P0.

**Status: PASS (unit/build).** `GuardRecruitmentUseCase` exposes
`currentQuizPrompt`/`answerQuiz`/`recruit`; the persisted question ID rides as
the form's bound record ID so stale or foreign forms cannot advance the quiz.
Receptionist actions open the native form only for invited/eligible players —
fired, resigned, suspended, uninvited, cooldown, and completed states get
messages instead. Evidence: `unit:GuardServiceTest` quiz/recruitment cases
(bound answer advances once, stale question ID rejected, cooldown preserved,
training path), `unit:NpcInteractionSurfaceTest`.

Relevant areas: `GuardService.java`, `NpcRoles.java`,
`NpcPlayerSurface.java`, and the form router.

The receptionist must expose a state-aware recruitment action. When a player
has an invitation, the NPC opens the current initial or training quiz question
in the native form. The form answer is routed to the existing
`GuardService.quiz(...)`; the server chooses the question from persisted state
and does not trust a client-supplied question index or expected answer.

Required behavior:

- no invitation/fired player gets a clear receptionist message;
- cooldown and already-completed states are displayed without opening an
  invalid form;
- initial recruitment questions advance exactly once on a valid submission;
- failed answers preserve the service cooldown behavior;
- rank training questions use the same safe path;
- success, failure, and the next question are visible to the player at the
  receptionist.

Add a small read-only prompt API if necessary (for example, current question
  and max length) so the NPC can build the form without duplicating quiz rules.
Add tests for initial quiz, training quiz, cooldown, stale form, and wrong
player/session identity.

## RP-003 — Make duty and officer lifecycle roleplay-accessible

Priority: P0 for self-service; rank administration remains command-only.

**Status: PASS (unit/build).** `GuardDutyUseCase` covers dutyView, start,
checkpoint, stop, salary, coins, food, kit, regear, resignation
begin/confirm/cancel, rejoin, plus `recoverOnLogin`/`tickPlayerDuty`. The NPC
mapper (`NpcPlayerSurface.dutyActions`) emits state-aware actions from the
read-only projection; every mutator revalidates persisted state. Promote,
demote, suspend, fire, reinstate, and special-duty remain permission-2
commands. Evidence: `unit:GuardServiceTest`, `unit:DutyEngineTest`,
`unit:NpcInteractionSurfaceTest.dutyActions*`.

Relevant areas: `GuardService.java`, `NpcRoles.java`, `NpcPlayerSurface.java`,
`StrajaEvents.java`, and officer items/NPCs.

Provide NPC/item routes for a player to start duty, see the current checkpoint,
activate a checkpoint, stop duty, request normal officer supplies, and complete
the self-service resignation/rejoin flow. The application services remain the
source of truth for rank, duty state, configured location, suspension, fire,
cooldowns, and audit records.

Do not expose promote/demote/suspend/fire/reinstate as player actions; those
remain permission-2 admin commands. Special-duty assignment by a commissioner
may also remain an administrator command unless a deliberately designed NPC
workflow is added later.

Acceptance criteria:

- receptionist/secretary or a dedicated officer desk gives state-aware buttons
  for start/checkpoint/stop and self-service lifecycle actions;
- a player cannot start duty while suspended/fired, activate an unknown or
  wrong-location checkpoint, or stop another player's duty;
- salary, coins, food, kit, and regear are surfaced through an officer item or
  NPC action when the service allows them, with no duplicated business rules;
- all mutations write the existing audit/persistence records;
- service tests and at least one NPC action-surface test cover each rejected
  state.

## RP-004 — Finish the complete mission/order journey

Priority: P0.

**Status: PASS (unit/build).** `MissionRoleplayUseCase` covers the whole
journey: carnet, draft write/scope/status/sign/package, issue (player-target
item use), invite/join/decline, accept, report and voluntary-fail native
forms, issuer completion, reward claim and commissioner recovery. Stale
record actions re-check the fresh `availableActions` projection and fail
closed. `deliverPendingRewards` on login materializes durable claims,
reconciles interrupted payments against payout receipts, and retries pending
deliveries. Evidence: `unit:MissionServiceTest` (join/decline/report/fail/
complete/reward/login-recovery/stale/dup), `unit:NpcInteractionSurfaceTest.missionActions*`.

Relevant areas: `MissionService.java`, `NpcRoles.java`,
`NpcPlayerSurface.java`, `StrajaEvents.java`, and order-book item data.

Complete these player paths using the secretary, Order Book/Mission Carnet,
and native forms:

1. assigned player views an issued mission;
2. assigned player accepts or declines;
3. an invited secondary player joins or declines without changing the wrong
   participant;
4. an accepted player submits a multiline report;
5. an accepted player can voluntarily fail/withdraw with a bounded reason when
   the service allows it;
6. the issuer or authorized commissioner sees the submitted report and closes
   the mission;
7. reward claim/recovery is available after a successful completion, including
   pending delivery after logout/restart;
8. an authorized issuer can use the physical carnet/order book to inspect and
   complete the draft/write/scope/sign/package/issue flow.

Every clickable record action must carry only an opaque token or a bounded
record key and must re-read the mission before mutation. A stale button must
fail closed with a short player-facing message. Do not turn the report or fail
reason into a command argument.

Tests must cover invited join, one-player decline, report form routing,
voluntary failure, issuer completion, reward recovery, stale status, and
duplicate submissions.

## RP-005 — Complete complaint submission, investigation, and withdrawal

Priority: P0 for citizen/investigator flows.

**Status: PASS (unit/build).** `ComplaintRoleplayUseCase` exposes bounded
submission (accused/category/description), list, claim, join/leave, multiline
investigation report, complainant confirm, withdrawal-reason form, and
review — all through receptionist/secretary actions with persisted-state
revalidation and complaint-audit using the accused UUID. Pending
investigation rewards are re-delivered on login via
`claimPendingRewards`. Evidence: `unit:CivicServiceTest.complaint*`,
`unit:NpcInteractionSurfaceTest.complaintActions*`.

Relevant areas: `ComplaintService.java`, `NpcRoles.java`,
`NpcPlayerSurface.java`, and the form protocol.

At the receptionist, provide a native complaint form with bounded fields for
accused player, category, and description. The server must resolve/validate the
accused identity, enforce the configured receptionist location, and call
`ComplaintService.submit(...)`; do not accept arbitrary client-side target
UUIDs or bypass location/capability rules.

At the secretary/reviewer NPC, provide state-aware actions for an authorized
investigator to list, claim/accept, and submit a multiline investigation report.
Route the report through `ComplaintService.report(...)` after revalidating lead
identity and status.

At the receptionist, the complainant must be able to confirm a report or open a
reason form to withdraw it. The withdrawal reason must be bounded, persisted,
and passed to `complainantDecision(...)`; no dead-end “ask for a form” message
is acceptable once RP-001 lands.

Add tests for invalid target, invalid category/description, wrong location,
unauthorized investigator, claim race, stale withdrawal, report submission,
confirm, withdrawal reason, and duplicate/stale form submission.

## RP-006 — Finish fines, appeals, payment refusal, and officer tasks

Priority: P1, with payment/refusal required for a complete citizen flow.

**Status: PASS (unit/build).** `FineRoleplayUseCase` covers draft write via
`fine_book`, target issue on player interact, notice-driven list/pay/refuse,
bounded appeal + review reasons (240-char cap), task
list/accept/complete/arrest, receipt-scoped `claimTaskReward`, and hearing
warrants. `fine_notice` data is never trusted for authorization. Login
recovery retries interrupted arrest bounties (`recoverOnLogin`);
`PAYMENT_REVIEW` stays commissioner-only. Evidence:
`unit:CivicServiceTest` fine/appeal/task/reward cases,
`unit:NpcInteractionSurfaceTest.fineActions*`.

Relevant areas: `FineService.java`, `StrajaEvents.java`, `NpcRoles.java`,
`NpcPlayerSurface.java`, and fine item data.

Keep `fine_book` as a physical officer register and `fine_notice` as the
citizen-facing physical notice. Add the missing native paths:

- officer writes a fine draft with bounded target/amount/law/description fields;
- officer issues the validated draft to the target;
- citizen reads the notice, pays, or refuses through a state-aware NPC/item
  action;
- citizen submits an appeal with a native reason form;
- authorized reviewer lists and decides appeals with bounded reason/tariff
  data;
- active officers see task-board actions to list, accept, complete, refuse,
  arrest, or issue a hearing warrant where the existing service allows it;
- failed payment/reward delivery can be recovered after reconnect.

Do not let a notice's custom data authorize payment by itself. The service must
re-read the persisted fine, target UUID, current status, location, and actor
capability. Add tests for each status transition and for forged/stale item
metadata.

## RP-007 — Close custody, prison, head-sack, and room gaps

Priority: P1.

**Status: PASS (unit/build).** `CustodyRoleplayUseCase`,
`PrisonRoleplayUseCase`, and `RoomRoleplayUseCase` cover
cuff/surrender/rope/head-sack/key/cutter/crowbar flows, downed state,
sentences, cell protection, room markers/assignment/waitlist, and block
protection (break/place/explosion) without touching unrelated blocks.
`recoverOnLogin`/`recoverOnLogout`/`recoverAfterDeath` revalidate issuer
provenance, drop malformed records with audit, and stay idempotent under
repeated recovery. Admin cell geometry remains typed setup. Evidence:
`unit:CustodyServiceTest`, `unit:PrisonServiceTest`, `unit:CivicServiceTest`
room cases, `unit:EventSurfaceTest` (source-level hook coverage; no
GameTest runtime available).

Relevant areas: `CustodyService.java`, `PrisonService.java`, `RoomService.java`,
`StrajaEvents.java`, `StrajaItems.java`, and the jailer NPC.

The current custody token path is a foundation, not the complete player UX.
Finish the following through jailer NPC actions and physical tools:

- request/accept/refuse cuffs with correct downed state and issuer identity;
- item-backed cuff, rope, key, cutters, crowbar, and head-sack behavior with
  explicit state validation;
- safe head-sack removal/recovery on death, logout, malformed issuer data, and
  restart;
- sentence status, arrest, release, and prison marker/cell interactions;
- room marker placement, assigned-room status, release, waitlist, and restart
  recovery;
- protection of managed room/prison blocks against break, placement, and
  explosions without blocking unrelated world blocks.

Admin cell geometry/setup commands may remain commands. Player use of a marker,
cuffs, key, or NPC must be sufficient for the normal roleplay path. Add event
integration tests or GameTests where mocks cannot prove the real NeoForge hook.

## RP-008 — Complete archive and paper-item workflows

Priority: P1.

**Status: PASS (unit/build).** `ArchiveRoleplayUseCase` drives the archivist
NPC: folder create/read/issue, sheet new/read/edit/recipients/submit,
stamp-gated sign (`straja:archive_stamp` required), carbon-paper numbered
copies, official-envelope packing, formal document issue, and revoke — all
through a fresh `availableActions` projection with stale-action rejection.
Persisted readers/recipients/DELIVERED copies grant read authority; item
metadata is only a bounded hint and never authorizes. `deliverPending`
recovers offline deliveries on login, discards malformed records with audit,
and never delivers revoked sheets. Evidence: `unit:CivicServiceTest.archive*`,
`unit:NpcInteractionSurfaceTest.archiveActions*`,
`unit:PhysicalItemSurfaceTest`.

Relevant areas: `ArchiveService.java`, `StrajaItems.java`, `StrajaEvents.java`,
and archivist NPC behavior.

Ensure the archivist and physical archive items support the full authorized
workflow rather than only listing folders:

- authorized folder/document read;
- issuing a folder/document with validated metadata;
- carbon-paper copying and archive-stamp/seal behavior;
- official-envelope delivery where the application service supports it;
- unauthorized, missing, forged, and stale metadata fail closed;
- offline/pending archive delivery is recovered on login.

Keep archive data access in `ArchiveService`; item events should only translate
the held item and context into service calls. Add item-routing tests plus
service tests for permission and pending-delivery cases.

## RP-009 — Finish restart/logout/recovery coverage

Priority: P1 and required before final sign-off.

**Status: PASS (unit/build).** The login hook now runs every persisted
aggregate through inbound ports: stale-duty close + boot-id stamp
(`guardDuty().recoverOnLogin`), custody incl. hidden items
(`recoverOnLogin/Logout/AfterDeath`), prison sentences/cells/waitlist,
complaint pending rewards, mission reward delivery
(`deliverPendingRewards` — materializes claims, reconciles receipts, retries
interrupted payments, leaves `PAYMENT_REVIEW` for the commissioner), arrest
bounty retry (`fineRoleplay().recoverOnLogin`), archive pending deliveries,
and room auto-assign + waitlist. Form sessions and NPC action tokens purge
on the 20-tick cadence. All recovery paths are receipt/state-gated and
idempotent, and audit every mutation/discard. Evidence:
`unit:MissionServiceTest` login-recovery cases, `unit:CivicServiceTest`
arrest-reward recovery, `unit:GuardServiceTest.recoverOnLogin*`,
`unit:EventSurfaceTest.loginRecoveryCoversEveryPersistedAggregateThroughPorts`,
`volatileTokensAndSessionsArePurgedOnTick`. No GameTest runtime available —
source-level hook evidence stands in for live UAT.

Audit `StrajaEvents` login, logout, tick, death, and server lifecycle hooks
against every persisted state:

- active custody and hidden items;
- open missions and reward deliveries;
- fine notices, task rewards, and pending payments;
- complaint state and rewards;
- archive deliveries;
- room assignment/waitlist;
- duty/membership state and cooldowns.

Every recovery must be idempotent, fail closed on missing/malformed UUIDs, and
write an audit event when it changes or discards state. Add restart-oriented
tests for each state and at least one real server/GameTest path when possible.

## RP-010 — Security and regression matrix

Priority: P0 verification gate.

**Status: PASS (unit/build).** `gradlew test --rerun-tasks` passes
(331 tests). `CommandSurfaceTest` covers permission-2 gameplay roots,
backup/help policy, hidden `npc-action`, token owner binding, one-use
consumption, action-shape validation, allowlisted actions, and the TTL seam
(`npcActionTokenExpiresAfterTtl` + tick purge). `ArchitectureBoundaryTest`
enforces domain purity plus no concrete-service or persistence imports in
`adapter/in` player-facing code. `EventSurfaceTest` verifies events route
through ports only. `PhysicalItemSurfaceTest` proves metadata is never
authorization. `PersistenceTest` covers the backup source-store manifest
(backup store itself excluded, `mission_recipients` included) and
`_corrupt_backup`. `FormPayloadSurfaceTest` pins MAIN-thread registration,
no player data off the wire, and non-player rejection.
`DeliveryBoundaryTest` covers payment/delivery boundaries. Duplicate-click,
reconnect, and kill-duplicate-token behavior is covered across service
tests. `gradlew build` and `git diff --check` clean. Live `mct` UAT
unavailable.

Before declaring completion, add or update tests for:

- every typed gameplay root being permission-2 gated;
- `/straja backup` being permission-2 gated and present only in admin help;
- public help not advertising gameplay roots or internal `npc-action`;
- token owner binding, one-use, TTL, malformed action IDs, and record-state
  revalidation;
- native form session owner binding, one-use, expiry, max length, and payload
  server-thread handling;
- item metadata being treated as hints, never authorization;
- service-level capability/location/status checks remaining authoritative;
- persistence snapshots retaining all source stores without recursively
  snapshotting the backup store;
- duplicate packets/clicks and logout/restart races.

Run, at minimum:

```text
./gradlew.bat test --rerun-tasks
./gradlew.bat build
git diff --check
```

If `mct` becomes available, run the canonical live case and verify rendered NPC
clicks, native forms, item use, stale buttons, reconnect recovery, and the
backup command through console/RCON. Record exact failures instead of marking
live UAT green by inference.

## RP-011 — Keep documentation aligned with the shipped surface

Priority: P1.

**Status: PASS.** This document now carries per-issue statuses with evidence;
`README.md` and `docs/parity-matrix.md` document the admin-only backup,
`_corrupt_backup` semantics, NPC roles, physical items, clickable actions,
and the native form layer, with stale "no custom GUI"/"form still needed"
wording removed and the absent-`mct` live-UAT caveat stated explicitly.

Update `README.md` and `docs/parity-matrix.md` after implementation:

- document `/straja backup` as admin-only and explain that corrupt-state
  `_corrupt_backup` records are a separate recovery mechanism;
- document NPC roles, physical items, clickable actions, and native forms;
- remove stale “no custom GUI”/“form still needed” wording once forms work;
- mark each issue above PASS/PARTIAL/BLOCKED with test/code evidence;
- state explicitly if client live UAT remains blocked because `mct` is absent.

Preserve the pre-existing user changes to `.github/workflows/release.yml`,
`README.md`, and `docs/media/`; do not reset or discard unrelated work.

## RP-012 — Final review and handoff quality

Priority: P0.

**Status: PASS WITH CAVEATS.** Full-diff review confirmed scope discipline,
port-only player-facing adapters (enforced by `ArchitectureBoundaryTest`),
server authority, persistence/recovery coverage, and preserved user changes
(`release.yml`, `docs/media/`). Caveats: no live client UAT (`mct`
unavailable — unit/build/source-level evidence only); admin archive commands
intentionally use the concrete `ArchiveService`; `TestCommands` archive
signing requires the stamp item. No commit/push/deploy performed.

After implementation, inspect the complete diff for scope discipline and ask a
fresh reviewer to return exactly one verdict: `PASS`, `PASS WITH CAVEATS`, or
`FAIL`. The reviewer must check requirement coverage, command/NPC/item
separation, server authority, persistence/recovery, tests, build, docs, and
unverified live-runtime assumptions. Resolve every FAIL and document every
caveat with its impact.
