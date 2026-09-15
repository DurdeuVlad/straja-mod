# Straja Mod — Feature Parity Matrix

Status legend:

- **PASS** — implemented and verified by an automated test or a headless run
  (evidence column names the test/log).
- **PARTIAL** — implemented in code but only partially verified, or a subset
  of the reference behavior is missing.
- **BLOCKED** — not implemented, or cannot be verified yet.

Evidence types: `unit:` a JUnit test in `src/test`, `rcon:` exercised live on a
dedicated dev server over RCON, `code:` implementation exists, not yet
automatically verified.

This matrix distinguishes reference behavior from target UX. The target
player-facing flow is roleplay-first: normal players and officers use native
Straja NPCs, physical items, and clickable chat. `rcon:` coverage and the
command inventory below exercise the administrator/reference surface; they do
not mean that ordinary players should use typed commands. Manually typed
gameplay roots are permission-2 admin-gated. Typed setup, administration,
diagnostics, migration, and test surfaces are also permission-2 gated. The
public typed conveniences are limited to status/rules/help text. `/straja backup`
is an administrator-only command that writes a bounded durable SavedData snapshot;
`_corrupt_backup` remains a separate internal persistence recovery record.

Test suite: **597 unit tests** (`gradlew test`, all green) across
`ArchitectureBoundaryTest`, `GuardServiceTest`, `DutyEngineTest`,
`MissionServiceTest`, `CustodyServiceTest`, `PrisonServiceTest`,
`CivicServiceTest`, `MigrationServiceTest`, `PersistenceTest`,
`ItemCoinCurrencyProviderTest`, `StrajaPoliciesTest`,
`PlayerServiceTest`, `CommandSurfaceTest`, `NpcInteractionSurfaceTest`,
`PhysicalItemSurfaceTest`, `AdminToolSurfaceTest`, `AdminToolServiceTest`,
`FormSessionServiceTest`, `FormPayloadSurfaceTest`,
`EventSurfaceTest`, `DeliveryBoundaryTest`.

## Foundation

| Feature | Status | Evidence / notes |
|---|---|---|
| Clean hexagonal structure | PASS | `unit:ArchitectureBoundaryTest` — domain has no MC/app/adapter deps; application has no MC deps |
| Guard state model mirroring KubeJS schema | PASS | `unit:PersistenceTest` round-trip; field-for-field match enables direct migration |
| Role/rank model (CIVIL→LIEUTENANT) | PASS | `domain.model.Rank` |
| Permission matrix + capability checks | PASS | `unit:GuardServiceTest`, `unit:CivicServiceTest` |
| Player lifecycle (join/state bootstrap, restart recovery) | PASS | `unit:GuardServiceTest`; `rcon:` `closeDutyAfterRestart` ran on dev-server restart |
| Persistence via SavedData + JSON | PASS | `unit:PersistenceTest`; `rcon:` fines/missions/prison/custody/archive all survived server restarts |
| Corrupt-state backup + reset | PASS | `unit:PersistenceTest.corruptAggregateStoreIsBackedUp` |
| Audit log (bounded retention) | PASS | `unit:PersistenceTest`; every service calls `audit.record` |
| Commands usable from console/RCON | PASS | `rcon:` full M1–M6 flows driven from `tools/rcon.py`; administrator/reference surface, not ordinary-player UX |
| Administrator backup command | PASS | `code:StrajaCommands` + `code:StrajaDataProvider.createBackup` — permission-2 `/straja backup`, bounded durable SavedData snapshots |
| Typed gameplay command boundary | PASS | `unit:CommandSurfaceTest` — gameplay roots are permission-2 admin-only; public help does not advertise them; NPC clicks use the separate token boundary |
| Romanian in-game text + EN canonical commands + RO aliases | PASS | `code:StrajaCommands` (`recruit`/`recrute`, `resign`/`demisie`, `regulament`) |
| Test-mode command surface (gated) | PASS | `rcon:` `/straja test …` exercised continuously; gated by `testing.enableTestCommands` + permission 2; `debug.allowGrantRank` additionally gates `test set-rank` |

## Civil / recruitment lifecycle

| Feature | Status | Evidence / notes |
|---|---|---|
| Civil → application at Recepție (§5) | PASS | `unit:GuardServiceTest.applicationFlowAuthorizesThroughRecruiterQuiz`, `invitedApplicantSkipsTheApplicationStep`, `firedAndAuthorizedCannotApply`; `rcon:` `apply` → APPLIED → quiz → AUTHORIZED/Stagiar |
| Admission exam at Instructor/Recrutor (§6) | PASS | `unit:NpcInteractionSurfaceTest.applicationAndAdmissionExamAreAtReceptionistThenInstructor`; legacy recruiter role aliases to the Instructor |
| Invitations (invite/accept/decline) | PASS | `unit:GuardServiceTest` |
| Progressive quiz (shuffled order, cooldown) | PASS | `unit:GuardServiceTest` answers dynamic `quizOrder` |
| Rank progression (promote/demote rules) | PASS | `unit:GuardServiceTest`; `rcon:` `set-rank` |
| Suspend / reinstate / fire | PASS | `unit:GuardServiceTest` |
| Resignation (notice 15 min, 7-day cooldown, rejoin) | PASS | `unit:DutyEngineTest`, `unit:GuardServiceTest` incl. `resignationFiresStatusChangeHooks` (room release + mission cancel on sign) |

## Duty, patrols, salary

| Feature | Status | Evidence / notes |
|---|---|---|
| Duty start/stop at the Secretary (§7) | PASS | `unit:GuardServiceTest.normalDutyStartsAndStopsOnlyAtTheSecretary`, `freeDutyStopsAtWillAwayFromSecretary`; `rcon:` far start/stop refused, near allowed; `/straja stop` player-facing, gated inside the use case |
| Faction capture/restore on duty (§7) | PASS | `unit:GuardServiceTest.dutyCapturesAndRestoresScoreboardFaction`, `factionlessGuardReturnsToNoTeam`, `dissolvedFactionFailsSafelyOnRestore`; `rcon:` Vladicani → Straja → Vladicani observed via `team list` |
| Patrol route loops in rounds (§8) | PASS | `unit:DutyEngineTest.finalCheckpointLoopsIntoNextRound`; `rcon:` round 1 → checkpoint_1 reactivated in round 2 |
| Duty start/stop, deadline from mission minutes | PASS | `unit:DutyEngineTest`; `rcon:` start/stop-duty |
| Patrol routes, duplicate/short-route rejection | PASS | `unit:DutyEngineTest.startDutyRejectsDuplicateOrShortRoutes` |
| Checkpoint unlock/wait/activate/deadline | PASS | `unit:DutyEngineTest`; `rcon:` `checkpoint_timeout` end observed on live server; `checkpointUnlockMinutes`/`checkpointDeadlineMinutes` honored (`configuredTimersOverrideDefaults`) |
| Hourly-wage accrual (§10: 16/24/36/64/128 Bronze/h) + paid-minutes cap | PASS | `unit:DutyEngineTest.hourlyWageAccruesProportionally`, `subCoinFractionsCarryAcrossTicks`, `salaryCapSuppressesPayAboveDailyLimit`; granularity/service-block timers honored |
| Anti-AFK movement-based accrual | PASS | `unit:DutyEngineTest`; `rcon:` accrual paused until movement observed |
| Special Duty mode | PARTIAL | `code:DutyEngine` — implemented, thinner coverage |
| Salary payment in real Ady's coins | PASS | `rcon:` salary paid as 6× `adys_decorations:brass_coin` (60 base units); `unit:GuardServiceTest.salaryInProgress*` — crashed attempts lock for review / recover via receipt, never double-pay |
| Food cooldown (30 min) | PASS | `unit:GuardServiceTest` |
| Weekly activity reports via Secretary (§11) | PASS | `unit:ReportServiceTest` — submit/idempotent refile, return-with-note → resubmit revision, call-in, self-anchoring interval, overdue duty gate opt-in; `rcon:` R1 filed → returned → rev2 → accepted; overdue refused duty, filing cleared |
| Audience requests to the Comisar (§12) | PASS | `unit:AudienceServiceTest` — one open request/member (re-request updates reason), coalesced online Comisar notify, resolve/dismiss, outcome on next visit/login; `rcon:` A1 filed → dismissed with note → requester told; A2 survived restart in `straja_audiences.dat` |
| Emergency system (§25): urgency call + sustained mode | PASS | `unit:EmergencyServiceTest` — member-only broadcast, late-login delivery, TTL lapse, clamps, hazard-pay accrual, required-rounds patrol completion; `rcon:` alert reached on/off-duty members not civilians, urgency survived restart in `straja_emergency.dat`, patrol ended after 2 laps with `patrol_complete`, accrual tell showed ×2 hazard pay |
| Comisar admin surface via Secretary (§14) | PASS | `unit:AdminServiceTest` — gate, roster/dossier, authorize/promote/demote/suspend/fire/reinstate, stale + forged + offline refusal, policy/emergency delegation; `unit:NpcInteractionSurfaceTest` — admin action ids, malformed ids dropped, no typed commands; `rcon:` commissioner saw 153 actions vs member 0, full mutation chain live, audit rows `personnel_authorize/suspend/reinstate/fire`, record persisted with `applicationState:AUTHORIZED` + `lastKnownName` |

## Economy & equipment

| Feature | Status | Evidence / notes |
|---|---|---|
| Configurable item currency provider | PASS | `code:ItemCoinCurrencyProvider` resolves `[economy]` item IDs (defaults `adys_decorations:*_coin`); `unit:ItemCoinCurrencyProviderTest` — atomic withdraw (plan → preflight → execute → rollback); `rcon:` real withdrawal during fine payment |
| Permanent rank kits at rank-up (+salary bonus) | PASS | `unit:GuardServiceTest.kitIsGrantedAtRankUpAndClaimsOncePerRank`, `kitStaysClaimableWhenInventoryIsFullAtRankUp`; kit contents configurable via `kits` policy |
| Duty start/stop never leases or reclaims gear | PASS | `unit:GuardServiceTest.startDutyBeginsPatrolWithoutIssuingGear`, `stopDutyKeepsAllPermanentGear`, `dutyStartNeverIssuesOrRequiresGear` |
| Patrol real-time cap (`patrolMaxMinutes`, default 60) ends duty gracefully | PASS | `unit:GuardServiceTest.patrolTimeCapEndsDutyGracefully`, `patrolTimeCapDoesNotEndEarly`; `unit:DutyEngineTest.patrolTimeCapEndsNormalDuty`, `patrolTimeCapIgnoresFreeDuty` |
| Armorer NPC shop (coins + requisition reserves) | PASS | `unit:ArmoryServiceTest` — rank-gated offers, atomic coin withdraw with delivery-failure refund, requisition spends restore on failure |
| Requisition ledger separate from promotion blocks | PASS | `unit:DutyEngineTest.requisitionAccruesPerServiceBlock`; `unit:GuardServiceTest.suspensionDocksRequisitionPoints`, `demotionDocksServiceBlocks`, `meritDockClampsAtZeroAndAudits` |
| Coin denomination breakdown | PASS | `unit:DutyEngineTest` |
| Economy idempotency / receipts | PASS | `unit:` receipt-scoped deposits in `MissionService`/`ComplaintService`; payment boundary review state in `FineService` |

## Native NPCs

| Feature | Status | Evidence / notes |
|---|---|---|
| Native Straja NPC entity (humanoid, skinned) | PASS | `code:StrajaNpcEntity` + client renderer + generated 64×64 role skins |
| Persistent UUID + explicit role ID | PASS | `code:NpcRegistry` + `EntityJoinLevelEvent` resync; `rcon:` registry survives restart |
| Roleplay-first NPC player surface | PASS | `unit:NpcInteractionSurfaceTest` + `code:NpcRoles` — native NPC interaction presents clickable chat actions for the full player journey; client live UAT still needed for rendered behavior |
| Native server-authoritative forms | PASS | `unit:FormSessionServiceTest` + `unit:FormPayloadSurfaceTest` + `code:StrajaFormMenu`/`StrajaFormScreen` — owner-bound, allowlisted, expiring, one-use sessions with bounded fields; quiz, mission report/fail, complaint submit/report/withdraw, appeal/review, archive sheet edit flows |
| Contextual FAQ dialog trees | PASS | `unit:NpcInteractionSurfaceTest.everyVisibleFaqQuestionHasAnAllowlistedAnswer` — 36 generated questions have matching answers, rank/lifecycle gates and read-only dispatch; `docs/dialog-trees.md` is the directed-graph audit |
| Four-NPC building layout and guided spawn | PASS | `unit:NpcInteractionSurfaceTest.physicalNpcRolesResolveToTheirConfiguredBuildingLocations`; `code:NpcCommands` — receptionist, trainer/recruiter, secretary and armorer spawn only at configured locations and refuse partial setup |
| Secretary book-copy interaction | PASS | `unit:SecretaryServiceTest`, `unit:NpcInteractionSurfaceTest.secretaryGuidesImplicitBookCopyInteraction`; `code:MinecraftPlayerGateway` — tagged main-hand book copied with components preserved, original retained, full inventory fails closed |
| Roles: receptionist/trainer-recruiter/secretary/armorer (+ optional custody/archive roles) | PASS | `code:NpcRoles` — receptionist: application intake, rules, status, fines, rooms, native faction, complaints and contextual FAQ; Instructor/Recrutor: admission exam → Stagiar plus training modules, service-block progress, self-service rank-ups and manual; secretary: book copy, duty self-service, missions, Order Carnet, investigation reports and contextual FAQ; armorer: rank-gated coin/reserve equipment; jailer/archivist remain optional role surfaces |
| NPC admin commands | PASS | `rcon:` `npc list/spawn/assign/set-name/set-skin/remove` — registry-targeted, console-safe |
| NPC interaction → application services | PASS | `code:NpcInteractionService` → `NpcRoles` → inbound `*RoleplayUseCase` ports only; state-aware actions use short-lived, one-use, TTL-expiring, player-bound tokens; text actions open native form sessions. `unit:ArchitectureBoundaryTest` bars concrete services/persistence from player-facing adapters |
| Jailer damage → assault mission | PASS | `code:` jailer takes real damage (`StrajaNpcEntity.isInvulnerable`/`hurt` → `jailerMayTakeDamage` → `LivingDamageEvent.Post`/`LivingDeathEvent` → `createJailerAssaultMission`); `unit:CivicServiceTest.jailerAssaultCreatesUrgentMissionAndUpgradesSeverity`; `unit:StrajaPoliciesTest.jailerDamageAllowed*` (guard-immunity truth table) |

## Physical admin tools (AT-001..006)

CustomNPCs-style pointer items for the Comisar/operator: the item is never a
credential — every use re-checks authority server-side, pending state lives in
the per-holder `straja_admin_tools.dat` SavedData store (never in item data)
and is dropped on logout. All mutations converge on the same application
services the canonical commands use. Items are non-stackable, non-craftable
(no recipes), and have no mob drops.

| Feature | Status | Evidence / notes |
|---|---|---|
| Tool foundation: items, holder gate, per-holder state store | PASS | `unit:AdminToolServiceTest.wrongHolderIsRefusedEverywhereWithATell`, `opHolderPassesTheGate`, `pendingStateIsPerHolderAndClearsOnLogout`; `code:StrajaItems` (stacksTo(1), no recipes); `unit:AdminToolSurfaceTest` |
| `/straja setup tools` kit | PASS | `unit:AdminToolServiceTest.kitGivesEveryAdminTool` — six items through the permission-2 `setup` root, gate re-checked in the service; `rcon:` `test tool-kit` gave all six, wrong-holder denied |
| NPC Wand: registry menu, assign/rename/skin/remove/record | PASS | `unit:AdminToolServiceTest.wandMenu*`/`wandMutations*`; tokens reuse the player-bound one-use boundary; rename/skin via native forms (`tool-npc-name`/`tool-npc-skin`); remove behind a second confirm token; `rcon:` `test tool-wand*` — menu, assign/rename/skin/record, remove + post-remove refusal live |
| Foreign NPC binding: wand bind/rebind/detach, no appearance change | PASS | `unit:AdminToolServiceTest.wandBind*`/`wandAssignBinds*`/`wandMenuHides*`/`detachDropsOnly*` — unregistered entity gets role menu, assign creates the record keyed by entity UUID, rename/skin hidden for foreign targets, detach drops the record only; `code:StrajaEvents.onEntityInteract` routes bound targets by registry role and owns the click |
| Cloner apply-mode onto foreign entities | PASS | `unit:AdminToolServiceTest.clonerApplies*`/`clonerWithoutTemplate*` — captured template role binds onto an unregistered entity in place; no spawn, no appearance writes |
| Bound jailer damage parity | PASS | `code:StrajaEvents` — `LivingDamageEvent.Post`/`LivingDeathEvent` resolve role registry-first (foreign jailers produce the same assault mission, deduped by incidentKey); `LivingIncomingDamageEvent` cancels on-duty-guard hits on bound foreign jailers when `jailerGuardImmunity` is on; host-mod damageability is a documented requirement |
| Patrol Wand: waypoint record/remove/finish | PASS | `unit:AdminToolServiceTest.patrol*` — click toggles, exactly the checkpoint-slot count enforced, cross-dimension finish refused, writes through `setCheckpointAt`; `rcon:` `test tool-patrol*` — record/toggle/4-of-4/finish wrote checkpoint_1..4 in order |
| Survey Rod: location stamping menu | PASS | `unit:AdminToolServiceTest.survey*` — pending target per holder, `SetupChecklist.missingLocations` drives labels + stamp-all, writes through `stampLocation`; `rcon:` `test tool-survey*` — stamp + stamp-all-missing took checklist to 10/10 |
| Prison Marker: two-click cell bounding | PASS | `unit:AdminToolServiceTest.cell*` — corner normalization, dimension reset, stale-confirm refusal, registration through `createCell`; `rcon:` `test tool-cell*` — real-shell cell registered as `celula_2`, unloaded-area confirm failed closed |
| NPC Cloner: capture/spawn/clear | PASS | `unit:AdminToolServiceTest.cloner*` — registry-round-trip via `NpcAdminService.register`, empty-slot refusal; `rcon:` `test tool-clone*` — two secretary copies spawned + registered, records survived a server restart |
| Tool event routing (main-hand, per-hand packet dedupe) | PASS | `code:StrajaEvents` — entity/block/air clicks claim the main-hand packet only; `toolClickHandledAt` also dedupes the item-use packet that trails a non-consuming block/entity click, so the air gesture (route finish, template clear) cannot double-fire |
| Tool service flow live on dev server | PASS | `rcon:` `/straja test tool-*` drives the same `AdminToolsUseCase` the items reach via interact events — kit, wand edit, patrol finish, survey stamp, cell confirm, clone spawn, wrong-holder and logout-clear all exercised; `straja_admin_tools.dat` persisted |
| Live client UAT of the tools | BLOCKED | needs a running client: rendered wand menus, click packets, item in hand — the service layer is `rcon:`-verified |

## Missions, orders, packages

| Feature | Status | Evidence / notes |
|---|---|---|
| Mission create/issue/accept/refuse/complete/fail | PASS | `unit:MissionServiceTest`; `rcon:` issue→persist across restart; NPC actions + native forms cover accept/decline/report/fail/complete end-to-end; `deliverPendingRewards` on login reconciles interrupted payments and retries pending reward deliveries |
| Order books (`straja:order_book`) | PASS | `code:` registered item; `rcon:` give + mission draft flow |
| Sealed packages via Envelope | PASS | `code:EnvelopeDeliveryProvider.sendPackage` (real `MailService`); used by mission issue |
| Mission templates + calculated budget (§13) | PASS | `code:MissionTemplate`/`MissionTemplateStore`/`MissionBudget` + `MissionTemplateRepository`; `unit:MissionServiceTest.strajerTemplateTwoHoursRisk15*` (72 B/participant, 144 B budget), `templateRewardsRederiveFromCurrentWageTable`, `overMarginOverride*` (reason-gated + audited), `templatesPersist*`; `rcon:` `template-list`/`draft-template`/`draft-adjust`; forms: `mission-budget-adjust` |
| Deadlines + reward splitting | PASS | `unit:MissionServiceTest` |
| Max-active / copies / budget pool limits | PASS | `unit:MissionServiceTest` incl. fail-closed budget counter fix |
| Open missions cancelled on status change | PASS | `unit:MissionServiceTest.statusChangeHooksAllFireAndCancelMissions` (multi-listener wiring) + `resignationCancelsOpenMissions` + `suspendCancelsOpenMissions` |
| Mission store retention (`mission.retentionLimit`) | PASS | `unit:MissionServiceTest.retentionLimitPrunesOldestClosedMissions` — open and unsettled-reward missions are never pruned |

## Enforcement

| Feature | Status | Evidence / notes |
|---|---|---|
| Cuffs request/accept/refuse + keys | PASS | `unit:CustodyServiceTest`; `rcon:` full flow |
| Crowbar / bolt cutters release | PASS | `rcon:` cutter release live |
| Rope binding + head sack | PASS | `rcon:` applied live |
| Distance expiry | PASS | `unit:CustodyServiceTest` |
| Baton, non-lethal damage, downed state | PASS | `unit:CustodyServiceTest`; `rcon:` knockout; damage capped so Straja never kills |
| Whip, low damage + strong knockback, downed state | PASS | `unit:CustodyServiceTest.whipUsesTheSameNonLethalCustodyFlow`; `gameTest:whipDamageRouting`; rank-gated armory item |
| Surrender / refusal / knockout flow | PASS | `rcon:` surrender live |
| Prison cells, sentence timers, release points | PASS | `unit:PrisonServiceTest`; `rcon:` arrest→cell→sentence countdown (5s/tick cap) |
| Sentence store retention (`prison.retentionLimit`) | PASS | `unit:PrisonServiceTest.retentionLimitPrunesOldestClosedSentences` — active/waiting sentences never pruned |
| Offline-safe / AFK-safe sentence time | PASS | `unit:PrisonServiceTest.offlineTimeNeverCounts` |
| Cuffs/downed survive restart | PASS | `rcon:` active sentence + custody persisted across restart |

## Civic & archive

| Feature | Status | Evidence / notes |
|---|---|---|
| Fines: fixed amounts, book, draft, issue + notice | PASS | `unit:CivicServiceTest.fine*`; `rcon:` write/issue live |
| Fine payment at reception (exact coins) | PASS | `rcon:` `fine-pay` withdrew 50 bronze → `PAID` |
| Appeals (file, freeze escalation, review uphold/reduce/void, 5-day auto-waive, abuse block) | PASS | `unit:CivicServiceTest.appeal*` |
| Debt escalation (14 online days → recovery mission) | PASS | `unit:CivicServiceTest.fineEscalates*`; `rcon:` `fine-set-grace` + tick → `ESCALATED` + `FM-F*` task |
| Recovery task present → explicit refusal → arrest → sentence | PASS | `unit:CivicServiceTest.recoveryTask*`; `rcon:` full chain live |
| Arrest blocked before explicit refusal | PASS | `unit:CivicServiceTest.arrestBlockedBeforeExplicitRefusal` |
| Complaints: submit/claim/mobilize/join/report/confirm/withdraw/review/reward | PASS | `unit:CivicServiceTest.complaint*`; `rcon:` submit→claim→report→review→reward `PAID` |
| Complaint reward budgets + pending delivery | PASS | `unit:CivicServiceTest.complaintLifecycleToReward`; `claimPendingRewards` on login |
| Rooms: marker selection, bounded discovery, interior/wall/door validation | PASS | `unit:CivicServiceTest.roomDiscovery*`/`roomRejectsOpenWall`; `rcon:` real-block room discovered + sign placed |
| Room assignments, FIFO waitlist, offline-first reservation, release | PASS | `unit:CivicServiceTest.roomWaitlistFifoReservesForOffline`; `rcon:` release→auto-assign live |
| Waitlist retention pruning (`rooms.waitlistRetentionDays`) | PASS | `unit:CivicServiceTest.waitlistEntriesExpireAfterRetentionDays` |
| Jailer assault missions (wounded/killed, severity upgrade) | PASS | `unit:CivicServiceTest.jailerAssault*` |
| Arrest rewards (alive/death multiplier, minor divider, min/max clamp, daily cap) | PASS | `unit:CivicServiceTest.jailerAssaultArrestPaysAliveBounty`, `suspectKilledPaysReducedBountyAndClosesTask`, `fineRefusalArrestPaysMinorDividerBounty`, `arrestRewardHonorsDailyCap`, `arrestRewardIsIdempotentPerTaskAndOfficer`; `code:FineService.suspectKilled` wired to `LivingDeathEvent` |
| Hearing warrants | PASS | `unit:CivicServiceTest.hearingWarrantRequiresAuthorityAndDedupes` |
| Archive: folders, sheets, revisions, signatures, recipients | PASS | `unit:CivicServiceTest.archiveFolderSheetSignFlow`; `rcon:` full chain live |
| Archive: numbered copies, carbon consumption, abort | PASS | `unit:CivicServiceTest.archiveCopy*` |
| Official envelopes + pending delivery recovery | PASS | `unit:CivicServiceTest.archiveEnvelope*`; `deliverPending` on login |
| Failed delivery never reports success | PASS | payment/delivery boundaries persist `PAYMENT_REVIEW`/`PENDING`/`DELIVERY_FAILED` states; `unit:MissionServiceTest.givePackageFailurePersistsFailedMissionAndReleasesBudget` + `giveChecksBudgetBeforeSendingPackage` (no orphan Envelope packages) |

## Migration & ops

| Feature | Status | Evidence / notes |
|---|---|---|
| KubeJS persistentData → native migration | PASS | `unit:MigrationServiceTest` + `/straja migrate <world>` reads `kubejs_persistent_data.nbt` + `playerdata/*.dat` |
| Migration fixtures + idempotency test | PASS | `unit:MigrationServiceTest` — real fixture files from the reference world in `src/test/resources/fixtures/`; double-run dedupe verified |
| Real KubeJS world migration | PASS | `rcon:` migrated the reference `local-server/world` — setup/audit/player state imported |
| Headless server boot with real deps | PASS | `rcon:` dev server boots with straja + envelope + adys_decorations; configs generated |
| Restart persistence (server) | PASS | `rcon:` sentences, fines, missions, rooms, archive all persisted across restarts |
| Debug/test gating | PASS | `code:` `testing.enableTestCommands` + permission 2; virtual players never visible in production |
| Clean build | PASS | `gradlew clean build` from scratch |

## Known limitations / intentional deviations

| Item | Status | Notes |
|---|---|---|
| Client GUI screens | PASS | Native server-authoritative form screens shipped (`StrajaFormMenu` + `StrajaFormScreen` using EditBox/MultiLineEditBox); sessions are owner-bound, allowlisted, expiring, one-use. The player flow uses native NPCs, physical items, clickable chat, and these forms |
| Parameterized player actions | PASS | All text-bearing player decisions run through native forms: quiz answers, mission reports/failure reasons, complaint submissions/withdrawal reasons, investigation reports, appeals/reviews, archive sheet edits; parameterized clicks cover the rest |
| Client live UAT | BLOCKED | Server-side/unit coverage exists for role routing and token provenance, but a live client still needs to verify rendering, hover/click behavior, item interactions, and the end-to-end player experience |
| Guided UAT harness from reference | BLOCKED (intentional) | KubeJS-only test scaffolding; `/straja test …` is an administrator/test surface and does not replace client live UAT |
| CustomNPCs compatibility | BLOCKED (intentional) | Replaced by native `StrajaNpcEntity` per requirements |
| Special-duty coverage | PARTIAL | Implemented in `DutyEngine`; less test coverage than normal patrols |
| Envelope delivery to virtual test players | PARTIAL | Real `MailService` call made; virtual players can't hold mailbox items — pending-delivery path exercised instead |
