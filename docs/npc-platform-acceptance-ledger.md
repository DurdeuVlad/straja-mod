# NPC platform acceptance ledger

This ledger is the release gate for the CustomNPCs-first NPC platform. It
separates repeatable automated proof from the final real Minecraft server and
client acceptance. The real-server gate must not be run until every
non-deferred implementation issue is complete. Recruiter placement is
explicitly outside this milestone: Recruiter is the Instructor compatibility
alias, while appointment authority belongs to the separate Personnel domain.

## Product boundaries

- Straja owns profiles, assignments, dialogue/action meaning, quest state,
  authorization, persistence, rewards, and audit.
- CustomNPCs owns the normal player-facing GUI/dialogue/quest presentation and
  input transport only.
- The debug text provider is a diagnostic mirror. It is not a player-facing
  fallback and requires both the explicit NPC debug flag and the existing
  local/debug security gates.
- StoryNPC is deferred. It must implement the same provider contract later;
  no current runtime path may import or require it.
- Provider migration changes only the presentation binding. It must not delete
  or rewrite canonical Straja state.

## Automated evidence ledger

| Area | Required automated proof | Current evidence | Final status |
| --- | --- | --- | --- |
| Provider boundary | Architecture and provider contract suites; absent-provider fail-closed checks | `./gradlew test --no-daemon --console=plain` is green on the rollout stack | Pass |
| Binding lifecycle | Bind, publish, rebind, unbind, restart recovery, unknown-operation recovery, cold-start ownership probe, and durable unavailable-vs-unknown distinction | `NpcBindingLifecycleTest` and provider contract tests, including duplicate-bind prevention after cold restart | Pass |
| Admission | Receptionist/instructor projection, bounded quiz input, canonical action routing | `NpcAdmissionSurfaceTest`, profile tests, CI GameTests | Pass |
| Role workflows | Secretary, armorer, trainer/recruiter, civic, jailer, and archivist projections | Role surface tests and full automated suite | Pass; live evidence pending |
| In-game provisioning | Catalog, admin wand target flow, selector/reconfigure/unassign, persistence, recovery, audit | PR #154 automated suite; RC run [35749890223](https://github.com/DurdeuVlad/straja-mod/actions/runs/35749890223) CustomNPCs artifact reports `verdict: pass` for `customnpcs-admin-provisioning`, `customnpcs-catalog-profiles`, and `customnpcs-jailer-custody` | Pass for provisioning, replacement, action-dispatch, reconnect, unassign, and all six catalog profile selections |
| Provider rollout | Explicit mode, debug gating, capability checks, migration identity preservation, single- and multi-binding rollback | `NpcProviderMigrationServiceTest`, provider contract tests, PR #158 CI | Pass |
| Server regression | Package, GameTests, RCON scenarios, dedicated profiles, health soak | RC run [35743424227](https://github.com/DurdeuVlad/straja-mod/actions/runs/35743424227): all blocking jobs and `Server profiles gate` passed; generic real-client advisory failed but is explicitly non-blocking | Pass |
| Real CustomNPCs acceptance | Disposable server plus real CustomNPCs client: provision, interact, replace, unassign, relog, catalog selection | Final gate RC run [36296086276](https://github.com/DurdeuVlad/straja-mod/actions/runs/36296086276), head `aafb15b`, artifact `customnpcs-client-ui`: `customnpcs-admin-provisioning` 62/62 steps and `customnpcs-jailer-custody` 18/18 steps pass — wand select, native selector, profile choose/confirm/replace/reselect, binding persistence, canonical action dispatch, unassign. Companion run [36294338299](https://github.com/DurdeuVlad/straja-mod/actions/runs/36294338299) on the same head reports `verdict: pass` for all six real-client scenarios (`downed-custody`, `join-observe`, `physical-items`, `quiz-form`, `reconnect-delivery`, `report-form`), including live `BookViewScreen` open on the training manual | Pass — final acceptance executed on the exact integrated candidate |
| Independent review | Separate read-only adversarial review of security, migration, GUI boundaries, and rollback | Final read-only review of head `aafb15b` completed 2026-09-27: one-use player-bound action tokens (CAS claim, 15 s TTL, retained-outcome replay kill, bounded pools in `NpcSurfaceActionService`), owner-bound one-use form sessions with bounded payloads, write-ahead lifecycle intents with fail-closed recovery, transactional migration with reverse-order rollback and audit-on-failure, triple-gated test commands (flag + local-environment + SETUP), custody gates throttling tells on both branches. Two low-severity residuals recorded, neither release-blocking: `NpcInteractionService` token map lacks an explicit size cap (bounded by 15 s TTL purge) and complaint reward deposit precedes the store write (crash in the gap could double-pay once on restart) | Pass — no unresolved high- or medium-severity finding |

## Historical real-server run (not final acceptance)

Historical run executed on 2026-09-22 through RC workflow run
[35766766664](https://github.com/DurdeuVlad/straja-mod/actions/runs/35766766664),
head `cfba404`. It is evidence for that exact revision only and does not satisfy
the final real-server gate in issue #135.
The CustomNPCs client artifact reports `verdict: pass`; all three declarative
scenarios passed every step. The admin scenario proved the real NPC Wand,
native selector, jailer confirmation, canonical custody action, secretary
replacement, secretary action after reconnect, and explicit unassign flow. The
catalog scenario selected and confirmed archivist, armorer, instructor,
jailer, receptionist, and secretary through the real GUI; each profile then
opened its native player surface before the next profile was selected. The
direct jailer scenario proved native GUI action dispatch and rejoin
reconstruction. The artifact directory contains the transcript, server/client
logs, and screenshots. The CustomNPCs client job is a required release gate; a
failed or missing report cannot reach publication.

This run does not claim a live-client server-process restart, provider-loss
recovery, or debug-provider migration; those are covered by the automated
lifecycle/RCON evidence and remain outside the CustomNPCs client scenario.

## Final acceptance run (2026-09-27)

The final integrated acceptance ran as RC workflow run
[36296086276](https://github.com/DurdeuVlad/straja-mod/actions/runs/36296086276)
on head `aafb15b` with `final_mc_acceptance=true`, after the NPC-implementation
completeness gate verified issues #128–#134 and #149–#153 closed. Every job
passed: build and staged artifact (SHA-256-verified across profiles), NeoForge
GameTests, the combined RCON scenario suite, all ten dedicated server
profiles (required-only through full-modpack and production-config), the
server health soak, the foreign-NPC advisory, the CustomNPCs real-client
advisory (2/2 scenarios), and the real-client UI advisory (6/6 scenarios,
including live custody, NPC form/quiz/report flows, physical-item use,
reconnect reward delivery, and the book-screen fix). The `Publish beta` job
remains correctly skipped outside tag pushes.

Residual risks carried forward, all recorded and non-blocking: the two
low-severity review findings above, and the real-client suite's known MCT
WebSocket relaunch flake (recovery retried once; the reconnect scenario now
carries a 720 s budget and asserts authoritative `PENDING -> PAID` state).

Issue #135 acceptance criteria are satisfied by this run. The recruiter
placement boundary remains out of scope; StoryNPC stays deferred. The
procedure below remains the repeatable runbook for future acceptance
re-runs.

1. Start a disposable NeoForge 1.21.1 server with the pinned CustomNPCs
   artifact from `docs/customnpcs-compatibility-matrix.md`.
2. Create one clean world and one OP/admin account. Record the initial Straja
   binding store and audit store hashes.
3. Place or create a CustomNPCs NPC, hold the Straja NPC Wand, left-click it,
   and verify that the native selector opens without opening the native editor.
4. Select and confirm receptionist, secretary, trainer, armorer, jailer, and
   archivist profiles one at a time. Capture the provider instance id, selected
   profile, confirmation result, and audit record for each.
5. Reopen the selector, verify the current profile is selected, replace the
   profile, cancel once, then confirm a replacement. Verify the next player
   interaction exposes only the replacement surface.
6. Interact as a normal player and complete one representative action for each
   delivered workflow. Verify server-side state, quest/dialogue projection,
   duplicate-click behavior, and player-readable failure reasons.
7. Relog the admin and player, restart the server, and repeat one interaction.
   Verify the same provider instance and profile survive.
8. Unassign the NPC. Verify the Straja surface disappears and record the
   underlying CustomNPCs interaction result separately; reopening an admin or
   diagnostic surface is not evidence that ordinary native behavior remains
   available.
9. Enable the explicitly gated debug provider in a local disposable server,
   migrate the binding to `debug-text`, capture the text mirror, migrate back
   to `customnpcs`, and compare canonical state and audit continuity.
10. Interrupt or simulate a provider operation, run `/straja npc provider
    status` and `/straja npc provider recover`, and verify the binding remains
    fail-closed until reconciliation succeeds.
11. Save screenshots, server/client logs, command transcripts, binding data,
    audit data, and before/after hashes under one immutable evidence directory.

## Completion rule

The NPC platform is merge-ready only when every non-deferred row is `Pass`,
the final real-server evidence is attached to the release PR, the recruiter
placement boundary is explicit, and an independent read-only adversarial
review reports no unresolved high- or medium-severity finding. A green unit
suite alone does not satisfy this ledger.
