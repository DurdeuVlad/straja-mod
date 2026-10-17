# Fun-First Overhaul — Implementation Issues

Status: `ready-for-handoff` (owner-approved 2026-10-17; FUN-1 first, branch + PR per issue)

This is the implementation queue for turning Straja from a protocol simulator
into a game. Work through the issues in dependency order, keep every change
server-authoritative, and verify before moving on.

## Original intent

The current mod asks players to do the institution's paperwork: applications
wait on the Comisar, duty is checkpoint walking against a deadline, weekly
reports are homework, and promotion means writing petitions into a queue.
The bureaucracy is real, but it is pointed at the player instead of working
for the player.

The redesign target: **the institution carries the protocol, players carry
the moments.**

- Routine protocol (intake, triage, drafting, logging, routing) is executed by
  NPCs and automated policy behind the scenes. Players see results, not forms.
- Authority stays human where it matters, through a mixed model chosen per
  action type: standing orders, timeout auto-approve, or one-click digest.
  No NPC silently satisfies a human authority requirement (existing contract
  preserved), but no routine decision waits on one human being online either.
- The fun engine is **real player crime**: kills, escapes, prison breaks,
  bounties, and manhunts generate the work list for on-duty members. Straja
  reacts to player chaos instead of simulating its own errands.
- Options scale with rank: enlisted get calls and chases, officers get
  transports, hunts, and operations, the Comisar keeps command authority.
- **The prisoner is a player too.** Custody is a game state with its own
  mechanics — escape paths, transfer rides, prison economy, parole — not a
  timeout in a box.

Owner decisions already made (2026-10-17):

- Automate all three protocol taxes (human waits, paperwork chores,
  checkpoint walking) in parallel — none is the single priority.
- Fun generation: player-vs-player crime, not NPC-scripted incidents.
- Authority: mixed model per action type, not one global rule.
- Prisoner fun: all of escape mechanics, transfer rides, prison economy/jobs,
  and parole/lawyer RP are in scope.
- **Career model (confirmed and sharpened):** exactly two corps.
  **Part-time enrolled corps** — two automatic-entry paths: the
  police/soldier path (Stagiar → Străjer) and the specialist path
  (Zilier → Meșter). Non-combatant work is a first-class part-time path
  because outside players already carry the projects. **Officer corps** —
  full-time faction members only: Locotenent → Căpitan → Colonel, plus the
  Comisar appointment. Each grade carries its own responsibilities,
  authority, rank, and salary; we do not need to specify everything — rank,
  salary, and the rule that a higher rank can grant extra permissions beyond
  a member's base.
- **Entry rules:** exams exist **only** on the part-time corps' automatic
  entries — and at each step, not just the first. Each part-time track has
  **three ranks**; every step up comes with an exam drawn from new info,
  more responsibility, and more money. For everything else (officer corps),
  an authorized user can invite the player directly, or the candidate
  applies and a human decides. Promotion on officer tracks is petition →
  the Comisar gets a book via the Secretary → approve/deny.
- **Quiz contract:** exams on the part-time tracks are fixed-question and
  learnable by retaking — soldier entry starts at 5-of-6; later steps use
  the ~10-question format. Third rank names and per-step exam sizes are open
  decisions in FUN-2 (proposals inside).
- **Players are the officers; Custom NPCs are the soldiers.** The enlisted
  fantasy is volunteering; the permanent fantasy is the officer corps doing
  "the challenging mental part" while NPC soldiers absorb the visible
  standing-around work of the institution.
- **Dumb-proof everything.** Players will not read laws or manuals. No flow
  may require reading to complete; the quiz stays simple and learnable on
  the spot; promotion is petition → book → approve/deny. Nothing more.

## Design principles

All principles in `player-roleplay-implementation-issues.md` still apply
(roleplay is the player API, server is authoritative, fail closed, durable and
auditable, recovery is part of the feature, native means native, evidence
beats assumption). The following are added for this campaign:

1. **Protocol is output, not input.** The institution emits documents,
   digests, and verdicts. Players confirm, sign, or choose — they do not
   transcribe their own record back into forms.
2. **No routine fun waits on one human.** Every approval path has a
   configured delegation: standing order, timeout presumption, or digest
   sign-off. Which model applies is policy per action type, audited.
3. **Delegated authority is still authority.** Every auto-action records the
   delegating order, the rule that fired, and is individually revocable by
   the delegating authority. Delegation never covers discipline, dismissal,
   Comisar appointment, or exceptional rewards.
4. **Crime creates the call.** Player actions (kills, escapes, breaks,
   bounties) are the dispatch source. The system routes calls to on-duty
   members by rank band; it does not fabricate NPC crimes for players to
   answer. (NPC-scripted incidents are an explicit non-goal this campaign.)
5. **Custody is gameplay, not storage.** A prisoner always has a decision to
   make: work, dig, bribe, appeal, ride, run. Every prisoner mechanic is
   balanced against the guard-side response it generates.
6. **Every guard-side mechanic has a prisoner-side mirror.** Transfers,
   locks, and escorts exist to be tested. If a mechanic cannot be exploited
   under any circumstances, it is not a mechanic — it is a cutscene.
7. **No required reading.** Laws, manuals, and rules are reference material,
   never prerequisites. A flow is complete when its NPC menu offers the right
   action first, refuses with a concrete remedy, and can be brute-forced by
   clicking through. If a player must read a page to succeed, the flow is
   broken.
8. **NPCs stand, players decide.** Custom NPCs carry the visible soldier work
   (posts, queues, escorts' background presence); player mechanics are the
   consequential decisions — calls, transports, verdicts, pursuits.

## Milestones

### M1 — The institution carries the protocol

**Outcome:** joining, serving, advancing, and reporting no longer require a
player to wait on one human, write essays, or walk checkpoints against a
timer. Protocol still happens — and stays audited — but it is executed by the
institution.

**Issues:** FUN-1 (delegated authority engine), FUN-2 (dumb-proof the
flows), FUN-3 (paperwork absorption), FUN-4 (duty rework).

**Proof:** a new player goes from spawn to first paid duty without waiting on
another player and without reading a single rule page; an on-duty member
completes a shift that contains zero mandatory checkpoint taps; a weekly
report is produced, signed, and filed in under a minute with content drawn
from the member's real activity.

### M2 — Real crime, real response

**Outcome:** player offenses become dispatched work. A kill, escape, or
prison break produces a call that reaches the right rank band within seconds;
the responding member gets the chase, not a clipboard.

**Issues:** FUN-5 (crime→dispatch pipeline), FUN-6 (prison break & manhunt
mechanics).

**Proof:** a player who downs a citizen and flees is hunted by dispatched
members using live position information; two players executing a cell break
trigger a manhunt that is playable for both sides — hunters get tracking,
runners get escape windows.

### M3 — The prisoner is a player

**Outcome:** custody is a playable state. Prisoners choose between work,
escape attempts, transfer-ride gambles, and parole play; every choice has a
guard-side consequence.

**Issues:** FUN-7 (transfer rides), FUN-8 (escape paths), FUN-9 (prison
economy & jobs), FUN-10 (parole & appeal).

**Proof:** a prisoner moved from cell to camp is escorted by a real guard
over a real route and gets at least one exploitable escape window; a
well-behaved prisoner reaches parole without mining a single block; an
escapee is hunted under the M2 pipeline, not merely flagged.

## Dependency order

`FUN-1` is the shared foundation: delegation policy, the decision ledger, and
the digest surface are consumed by FUN-2 (request flows), FUN-3, FUN-4, FUN-5
routing, and FUN-10 hearing intake. `FUN-2` unblocks the two join tracks it
simplifies and hands every request type a clean delegation point. `FUN-5` is
the foundation for FUN-6, FUN-7, and FUN-8 — all of them emit into the
dispatch pipeline. `FUN-7` depends on custody/escort mechanics already in
`CustodyService`; FUN-9 extends `LaborCampService`; FUN-10 depends on FUN-1
(delegated hearing scheduling) and FUN-9 (work credit as good-behavior
input).

```text
FUN-1 ─┬─ FUN-2 ── FUN-3 ── FUN-4
       └─ FUN-10 ◄── FUN-9
FUN-5 ─┬─ FUN-6
       ├─ FUN-7
       └─ FUN-8
```

M1 has no dependency on M2/M3 and can ship alone; M3's dispatch hooks degrade
gracefully if M2 is not yet delivered (calls fall back to the existing
wanted/fugitive flag behavior).

---

## FUN-1 — Delegated authority: the institution decides routine matters itself

**Intent.** Today every admission request, promotion petition, complaint
mission draft, and audience request lands in one human's queue. When the
Comisar is offline, the server freezes. Members experience the institution as
a wall. This issue gives the Comisar and commanding officers the ability to
publish *how* routine decisions get made — once — and lets the institution
execute that policy continuously, with full audit and revocation.

**Expectation.** The Comisar (or any rank granted `DELEGATE` capability)
configures per-action-type delegation policy through the Secretary's
administration surface. For each delegatable action type — admission
requests, trades petitions, promotion petitions, complaint-derived mission
drafts, audience requests, mission template approvals — the authority chooses
one of:

- **Standing order** — an explicit rule ('admit any applicant with no open
  complaint and no fugitive flag'; 'approve routine mission drafts under X
  bronze budget'). Requests matching the rule resolve immediately; the
  decision records the order ID as its authority.
- **Timeout presumption** — requests auto-resolve after a configurable window
  (default e.g. 48h online-time) unless a human objects earlier; resolution
  is flagged `PRESUMED` in audit and in the digest.
- **Digest sign-off** — requests batch into a pending digest the authority
  can review and resolve with one click per item, from the NPC surface or a
  delivered book; nothing auto-resolves, but friction is one click not a
  queue of forms.
- **Manual** — the current behavior, always available.

A request that matches no standing order and is under timeout policy falls
back to whichever non-timeout mode is configured. Every delegated resolution
is auditable end-to-end: request, rule or timeout that fired, order ID,
before/after state, and a per-item revoke path for the delegating authority.

**Acceptance criteria.**

- Delegation policy is durable per action type, per station scope where
  relevant, survives restart, and is editable in-game only by holders of the
  delegating capability.
- A request resolved by standing order is indistinguishable in downstream
  effect from a human approval (same dossier transitions, documents, and
  notifications) but is traceable to the order ID in audit.
- Timeout presumption uses persisted instants; logout, restart, and offline
  windows do not pause or double-fire it; a human decision during the window
  wins.
- Revoking a standing order stops future auto-resolution; already-made
  decisions stand but are individually listed for review.
- Discipline, dismissal, authorization revocation, Comisar appointment, and
  exceptional (above-budget or flagged) actions reject delegation outright —
  they route to a human or fail closed.
- Reciprocal/self/conflicted approvals stay impossible: the delegating
  authority is recorded as the approver, and the existing conflict-set checks
  apply to the order's author.

**Context code cannot infer.** This is the owner's chosen resolution to the
documented "NPC does not silently satisfy a human authority requirement"
constraint: delegation is an explicit human act published in advance, so
every auto-decision is still a human's decision. "Mix per action type" is the
selected model — different action types may use different modes
simultaneously. Default starting configuration is an open decision (see
below).

**Scope.** New `DelegationPolicy`/standing-order domain model and store;
decision engine invoked by `JoinRequestService`, `PromotionService`,
`ComplaintService`/mission-draft approval, `AudienceService`, and mission
template approval paths; Secretary administration surface additions;
`PolicyService` config for windows and budgets; audit vocabulary for
delegated decisions. Issue type: feature. Owner: unassigned (agent).

**Non-goals.** No change to what decisions exist or their downstream effects;
no auto-resolution of disciplinary/dismissal/appointment actions; no Discord
notifications for routine delegated resolutions (existing low-noise rule);
no UI beyond the Secretary/digest surfaces.

**Dependencies.** None upstream — this is the foundation issue. Blocks FUN-2,
FUN-3, FUN-4, FUN-10.

**Verification.** Service tests: order matching, timeout firing across
clock/restart, conflict-set rejection, revocation, audit completeness.
Integration test: submit request → standing order resolves → dossier/documents
emitted; restart mid-window → no double-fire. GameTest or live UAT: Secretary
surface end-to-end.

**Open decisions.** Default mode per action type at ship time (proposal:
standing orders for admission/trades intake, timeout for routine promotion
petitions, digest for complaint mission drafts and audiences). Timeout window
length. Whether officers below Comisar may hold `DELEGATE` for their own
scope. — **needs owner sign-off on the proposal defaults.**

---

## FUN-2 — Dumb-proof the flows: no required reading, one decision per screen

**Intent.** The owner is blunt: "imagine me, tired from work, having to do
each step of the bureaucracy — I'm tired. I don't want to do the
bureaucracy, and none of the players want [to]." Players will not read the
laws, the manual, or a wall of NPC text. Every join, serve, and advance flow
must complete through obvious clicks alone, and every refusal must end in a
concrete next action. This issue is the simplification pass over the
member-facing surface — it changes flow shape, not the authority model.

**Expectation.** The confirmed career model ships mechanically true and
self-explanatory:

- **Part-time corps (volunteers):** three ranks per track, advanced by
  XP (service blocks) plus the track's own gate. Soldier path:
  Stagiar → Străjer → third rank (name TBD) — each step is a fixed,
  learnable exam at the Instructor drawn from the rank's material; wrong
  answers name the right one on the spot, a pass promotes immediately.
  Specialist path: Zilier → Meșter → third rank (name TBD) — each step is a
  one-line ask that **any officer** may stamp; specialist promotion never
  waits on the Comisar and never exams.
- **Specialist entry:** Zilier by leaving a request with a one-line reason
  at the Receptionist's «Meseriași — înscriere» action — the reason goes to
  review under the FUN-1 mode configured for trades intake (open decision:
  whether specialist entry also exams — proposal below).
- **Officers (permanent):** Locotenent → Căpitan → Colonel, entered by
  invitation from an authorized member **or** by application → human
  decision — never by exam. Each grade has its own rank, salary, and base
  authority; a higher rank can grant extra permissions beyond a member's
  base. Professions remain dossier flavor, not capability gates.
- **Promotion:** soldier steps advance by exam; specialist steps advance by
  officer-stamped ask; officer promotions are the petition → book →
  approve/deny loop under the Comisar — that is the whole loop.
- **No required reading anywhere:** every NPC menu leads with the
  contextually-right action; a member can reach the next step by clicking
  the top option at each desk. Rules and the manual stay as optional
  reference. Every refusal still ends with «→ go here / do this».

**Acceptance criteria.**

- A new player reaches Stagiar using only NPC menus, without opening the
  manual or rules page; every wrong quiz answer teaches the answer.
- A new player reaches Zilier through exactly: Receptionist → trades action
  → pick profession → one reason line → submitted. Review path per FUN-1.
- Officer entry has no exam path — invitation and application only; an
  officer's effective permissions equal their grade's base plus anything a
  higher rank granted; dossier profession never gates an officer action.
- A promotion request takes ≤2 fields (target implied, one reason line) and
  reaches the decider as a physical/digestible artifact; approve/deny is one
  click for the reviewing officer or Comisar.
- Every refusal message across the modified surfaces terminates in a remedy
  action; no dead-end "access denied" strings.
- `gameplay-decisions.md` is updated in the same change: officers are
  mechanically unrestricted, quiz is fixed-learnable, trades join is
  reason-petition, promotion is petition→book→decision — the design target
  records this model.

**Context code cannot infer.** The officer-corps unification is the owner's
explicit model: "if you're a military one or doing projects, mechanically
speaking I'm just gonna let them do everything and whatever they want to
do — this is a game, I don't want to be that sweaty." RP provides the
flavor; mechanics provide the freedom. The quiz is intentionally
brute-forceable — its job is orientation, not gatekeeping.

**Scope.** NPC admission/career surfaces (`NpcAdmissionSurfaceService`,
`NpcCareerSurfaceService`, `NpcSecretarySurfaceService`,
`NpcSurfaceActionService`), quiz content/config in `PolicyService`,
profession-capability gating in `PlayerService`/capability resolution,
promotion petition intake in `GuardService`, refusal-message sweep on the
touched surfaces, `gameplay-decisions.md` + quickstart text updates. Issue
type: rework. Owner: unassigned (agent).

**Non-goals.** No change to rank names or the ladder itself; no new
authority paths (delegation config lives in FUN-1); no removal of the quiz;
no mission/transport/dispatch changes (FUN-5 and later); no overhaul of the
Comisar administration surface beyond the petition delivery.

**Dependencies.** FUN-1 (the trades/promotion review mode is configured
through it). Consumed by every other flow issue — do it early in M1.

**Verification.** Join-path walkthroughs per track; capability-matrix tests
for officer grades across member mechanics; refusal-message sweep test;
live UAT: zero-to-Stagiar and zero-to-Zilier with no reading.

**Discovered defect (filed, unfixed here).** `GuardService.suspend/demote/
fire/reinstate` write only `GuardState`, but `PlayerService.applyV2Projection`
re-derives `suspended`/`fired`/`rank` from the personnel record on every read
AND inside `save()` — so legacy discipline ops revert instantly for any
member holding a V2 record. A "suspended" officer keeps `record.active()`
and all V2 capability checks pass (including petition review). Fix: route
the legacy ops through `v2Personnel` status changes when wired. Affects all
V2 surfaces; needs its own issue.

**Open decisions.** Third-rank names and duties on both part-time tracks
(proposal: soldier E3 = a lead/field-capable step feeding officer
petitions; specialist E3 = senior tradesperson). Whether specialist entry
exams too — the owner said part-timers "take exam at their first step too,"
so proposal: yes, Zilier entry gets its own fixed quiz alongside the reason
request. Exam size per step (6 → 10 → ?). Whether the third part-time rank
is the eligibility gate for officer petitions. — **needs owner sign-off.**

---

## FUN-3 — Paperwork absorption: the institution writes, the player signs

**Intent.** Weekly activity reports, promotion petitions, complaints, and
audience requests currently require the player to re-enter facts the system
already recorded. This is the strongest "homework" signal in the mod. The
institution already has the ledger; it should produce the document.

**Expectation.** The Secretary's report interaction produces a pre-drafted
report assembled from the member's real activity ledger (duty time,
checkpoints, missions, incidents led, arrests, fines issued) covering the
reporting window. The player reviews a readable summary, optionally adds a
free-text note through the existing bounded form session, and signs — one
interaction, not a writing assignment. An unsigned draft past deadline
submits as `AUTO_FILED` (flagged) or blocks new duty per the existing
report-policy config.

The same absorption applies to: promotion petitions (prefilled with rank
history, service blocks, training record — player adds one reason line or
submits bare), complaints (guided intake asks only what the system cannot
know: who, what, where — no template prose), and audience requests (one
reason field, queued automatically).

**Acceptance criteria.**

- A report draft reflects recorded activity accurately for the window; no
  invented events, no missing recorded ones; empty windows produce an honest
  "no recorded activity" draft.
- Signing is a single confirm interaction; the submitted artifact is
  identical in authority to today's hand-written report.
- `AUTO_FILED` reports are distinguishable from signed ones in review and
  audit.
- Free-text additions stay inside the existing bounded form/session rules —
  no chat capture, no command arguments.
- Deadline/blocking behavior follows the existing configurable report policy.

**Context code cannot infer.** The owner wants paperwork *absorbed*, not
deleted — the report, petition, and complaint objects and their review
surfaces stay; what changes is who writes the content. This preserves the
institutional audit trail while removing the chore.

**Scope.** `ReportService` draft generation from the activity/audit ledger;
petition prefill in `GuardService.requestPetition`/promotion intake;
complaint guided intake in `ComplaintService`; Secretary surface updates;
`PolicyService` config for auto-file behavior. Issue type: feature. Owner:
unassigned (agent).

**Non-goals.** No removal of review/return/call-in outcomes for the Comisar;
no change to report interval defaults; no auto-approval of the *content* —
absorption is about authorship, not verdicts (those belong to FUN-1's
delegation where configured).

**Dependencies.** FUN-1 (delegated review of auto-filed reports may be
configured through it). Consumes the existing activity/audit records.

**Verification.** Service tests over fixture ledgers: window assembly,
empty-window honesty, auto-file deadline behavior, signature requirement.
Live UAT: full report cycle at the Secretary in under a minute.

**Open decisions.** Whether `AUTO_FILED` counts as submitted-for-review or
as a flagged notice to the Comisar. Whether the draft opens as a native
form page or a delivered book.

---

## FUN-4 — Duty is presence and response, not checkpoint chores

**Intent.** The default enlisted experience is walking a fixed route and
standing at checkpoints against a deadline — a timer job, not policing. This
issue makes the shift about being *present and answerable* in the world:
checkpoints become beats that surface what's happening, deadlines stop being
a punishment timer, and pay follows activity rather than taps.

**Expectation.** Starting duty no longer binds the member to a rigid route
deadline. A shift is a presence period during which the member receives work
from the dispatch pipeline (FUN-5; until M2, from patrol events already in
the system and ad-hoc Comisar missions). Checkpoints remain as declared beat
locations — reaching one registers presence and surfaces local context
(recent incidents, active BOLOs, wanted players nearby) — but missing a
checkpoint window no longer auto-ends the shift. Salary accrues for
qualifying activity: presence ticks at the base rate, response completion
and incident participation pay at a multiplied rate. The existing emergency
required-rounds mechanic is preserved as an explicit state.

**Acceptance criteria.**

- No automatic shift termination on a missed checkpoint under normal state;
  termination paths (manual stop, deadline abuse detection, emergency rules,
  authorization loss) remain explicit and audited.
- Pay: presence-only shifts earn the base hourly rate; response participation
  earns the configured multiplier; accrual stays proportional, persistent,
  and unaffected by promotion mid-shift for already-earned time.
- Checkpoint beats remain functional as presence/context points and as
  arrest-gate locations; the route loop still exists for members who want a
  guided round (config toggle between `ROUTE` and `FREE-BEAT` modes, default
  `FREE-BEAT`).
- Offline rules preserved: offline time is never paid; reconnect validates
  state; no deadline-expiry surprise termination on login.
- Anti-AFK becomes an explicit configurable policy (idle detection → presence
  ticks pause) rather than the implicit deadline mechanism it replaces.

**Context code cannot infer.** The owner rated checkpoint walking one of the
three equal fun-killers. The 10-minute wait / 30-minute deadline mechanism
was designed as an anti-AFK control; this issue replaces the control with an
explicit idle policy rather than deleting it silently.

**Scope.** `DutyEngine`, `GuardService` start/checkpoint/stop paths,
`CheckpointService`, salary accrual in the duty paths, `PolicyService` config
(route mode, idle policy, response multiplier), Secretary duty-start surface.
Issue type: rework/feature. Owner: unassigned (agent).

**Non-goals.** No removal of checkpoints as world features; no change to
wage table values (rates stay configurable); emergency required-rounds
contract preserved; dispatch content itself is FUN-5.

**Dependencies.** FUN-1 (response participation may feed delegated verdicts).
Soft dependency on FUN-5 for the full experience — ships meaningful alone.

**Verification.** `DutyEngine` unit tests for mode transitions, accrual,
idle pause, emergency interplay; reconnect/restart tests; live UAT of a
mixed presence+response shift.

**Open decisions.** Response-multiplier default; idle-timeout default;
whether Străjer+ may run `FREE-BEAT` from shift start while Stagiar still
requires one guided round (proposal: yes, Stagiar rides the route once as
onboarding).

---

## FUN-5 — Crime creates the call: the dispatch pipeline

**Intent.** Straja should react to what players actually do. Today citizen
complaints create incidents, but direct player offenses — killing a citizen,
escaping custody, breaking a prisoner out, a bounty going live — don't become
work for on-duty members. This issue builds the routing layer that turns
player crime into ranked calls.

**Expectation.** A new `DispatchService` consumes offense events from the
existing sources — lethal events, custody escape/break hooks, bounty
activations, complaint-derived incidents — and produces *calls*: targeted,
time-bounded work items pushed to on-duty members by rank band and
proximity. Enlisted bands receive pursuit/respond calls (chase the fleeing
suspect, respond to a fight); officers additionally receive hunt, transport,
and coordination calls. A call shows what happened, where (last known area),
who's wanted, and pays response credit under FUN-4's multiplier. Calls are
accepted/declined; unaccepted calls escalate by severity to wider bands and
ultimately the on-duty commander. The existing wanted/fugitive/BOLO flags
remain the underlying state — dispatch is the notification and work-routing
layer, not a parallel authority.

**Acceptance criteria.**

- Every dispatched call traces to a real offense event with actor, victim,
  location, timestamp; no fabricated calls.
- Rank-band routing is policy-configured; a call targets at most a bounded
  responder set (no server-wide spam) and widens only on expiry/unanswer.
- Accepting a call creates a durable responder record; completion/failure
  feeds the member's activity ledger and response pay.
- Calls respect dimension and offline state; a call never demands response
  from a player who logged out.
- Quiet hours: below a configured severity threshold, calls queue at the
  dispatch board (Secretary NPC) instead of pushing.

**Context code cannot infer.** The owner explicitly chose player-driven
crime over NPC-generated incidents as the fun engine. The pipeline therefore
does not include an NPC-crime spawner; ambient NPC incidents are a separate
future expansion if wanted.

**Scope.** New `DispatchService` + call records/store; event wiring from
`CustodyService` hooks, `LethalEventResolver` outcomes, `BountyService`,
`IncidentService`; on-duty roster queries via `PlayerService`; chat/dispatch
board surfaces; `PolicyService` rank-band and quiet-hours config. Issue
type: feature. Owner: unassigned (agent).

**Non-goals.** No NPC-crime generator; no change to BOLO/wanted authority
semantics (dispatch routes attention, it does not create warrants); no
Discord output for routine calls.

**Dependencies.** Feeds FUN-6, FUN-7, FUN-8 (all emit calls). Consumes FUN-4
response pay. FUN-1 optional (delegated escalation of unanswered calls).

**Verification.** Event-to-call mapping tests per source; routing band and
expiry tests; durability across restart; live UAT: offense → call → accept →
respond → credit.

**Open decisions.** Call expiry defaults and widening ladder; whether
off-duty members may see the dispatch board (proposal: board visible, calls
push only to on-duty).

---

## FUN-6 — Prison breaks and manhunts that are fun for both sides

**Intent.** Cells today are inert boxes: leaving without release marks you
fugitive. Other players can't meaningfully spring someone, and the hunt that
follows is a flag, not a game. This issue makes breaking someone out — and
getting hunted for it — a playable loop.

**Expectation.** Cells gain lock state and tamper mechanics: bolt cutters
succeed only with no guard presence within a radius and after a noisy
channel; lockpicks are faster on cheap cells; forced doors raise an alarm
call through FUN-5. A freed prisoner plus every participant becomes wanted
with a live manhunt call. Hunters get periodic position hints that decay
with distance/time; runners get escape windows — manhunt ends when a
configurable time elapses without the runners being re-cuffed, converting to
a standing arrest warrant (existing BOLO path) instead of an active hunt.
Re-capture returns the prisoner with added time; the breaker earns their own
sentence.

**Acceptance criteria.**

- Break mechanics require the right item, no immediate guard presence, and a
  channel that can be interrupted; success is atomic and audited.
- A break emits a manhunt call with participants and last-known location.
- Hint cadence and radius are configurable; hints degrade, never pinpoint
  forever; the hunt has an explicit end state (captured or cooled to
  warrant).
- Both sides see progress: hunters see hint freshness, runners see their
  pursuit status.
- Guards can call off / the Comisar can pardon mid-hunt; all paths audited.
- A break attempt during an active FUN-7 transfer ride uses the same
  pipeline.

**Scope.** `PrisonService` cell/lock state and tamper mechanics; manhunt
records (new or extending wanted state); `DispatchService` manhunt call type;
position-hint tick task; item behaviors for bolt cutters/lockpick; sentence
adjustment on recapture. Issue type: feature. Owner: unassigned (agent).

**Non-goals.** No insta-teleport escapes; no removal of the existing
fugitive mechanics — manhunt layers on top; no NPC guard spawns (breaks
succeed against *player absence*, detection is the counterplay).

**Dependencies.** FUN-5. Consumes custody/escort state from `CustodyService`.

**Verification.** Tamper gating tests (item, radius, interruption); manhunt
lifecycle tests including restart mid-hunt; hint decay tests; live UAT of a
two-player break vs. a responding guard.

**Open decisions.** Cell lock tiers per prison area; whether a broken cell
stays broken until repaired by trades members (nice coupling with the
Meșter track — proposal: yes).

---

## FUN-7 — Prisoner transfer rides: escorted, ambushable, exploitable

**Intent.** Camp transfer today is an automatic teleport with a book. The
owner specifically wants transfers as events: a chained prisoner walked
across the map by a real escort, with real risk — fun for the escort, the
prisoner, and any friends planning an ambush.

**Expectation.** When a transfer is ordered (debt threshold, security
downgrade, Comisar order, or post-hearing move), it becomes a **transfer
ride**: the prisoner is chained (existing cuff/rope transport state) and a
transfer call is dispatched (FUN-5). An accepting escort takes custody of the
chain; the prisoner follows escort rules (existing escort proximity
mechanics). The route posts waypoints; entering a declared **risk zone**
(forest stretch, open field — configured per route) opens an escape window:
if the escort strays beyond leash range, is downed, or dies, the chain breaks
and the prisoner (and any ambushers) enter the FUN-6 manhunt. Clean delivery
at the destination hands the prisoner to the receiving cell/camp, pays the
escort a transport reward (mission budget formula), and records the ride.
Failed rides return the prisoner to custody or convert to manhunt — never
silently vanish.

**Acceptance criteria.**

- A transfer exists as a durable ordered state with route, escort, prisoner,
  and outcome — resumable across escort logout (re-dispatched) and restart.
- Escape windows open only inside risk zones or on escort failure; leash
  rules follow the existing escort-radius mechanics.
- Outside risk zones the prisoner cannot break the chain by distance alone;
  ambush pressure works anywhere but the manhunt only triggers on actual
  chain break or escort death.
- Delivery settles exactly once: reward, custody record, cell assignment.
- The prisoner sees their window honestly ("the chain is loose — run" cue)
  without a HUD revealing guard state.
- Multiple escorts can share a ride; participation pays per the team-mission
  budget rules.

**Context code cannot infer.** "I present the transfer prisoners" — the
owner treats this as the signature fun mechanic. The prisoner is a willing
participant in the ride's risk, not cargo: their fun is watching for the
window and taking it.

**Scope.** Transfer records/store in `PrisonService`; ride orchestration
service; `CustodyService` chain/leash integration; `DispatchService`
transfer call type; route/risk-zone config in `SetupData`/`PolicyService`;
settlement via `MissionV2Service`/budget formula. Issue type: feature.
Owner: unassigned (agent).

**Non-goals.** No vehicle/mount requirements; no automatic-success teleports
kept for ordered transfers (auto-transfer remains only as admin fallback and
for the case of zero online members, under policy); no NPC ambushers.

**Dependencies.** FUN-5 (call routing), FUN-6 (manhunt on break), custody
escort mechanics (existing). Pays through the existing mission budget
formula.

**Verification.** Ride lifecycle tests including escort logout, chain break
inside/outside risk zones, delivery idempotency; live UAT of a full ride
with a prisoner player.

**Open decisions.** Minimum route length before a ride is required at all
(proposal: same-station transfers stay instant); risk-zone defaults; whether
the prisoner may bribe the escort mid-ride (ties to FUN-8/9 currencies).

---

## FUN-8 — Escape paths: dig, pick, bribe, blend

**Intent.** A prisoner with no options is a player in a menu. This issue
gives custody a decision space: several materially different escape paths,
each with preparation, risk, detection surface, and consequence — feeding
the FUN-5/6 response loop when discovered.

**Expectation.** Four path families inside cells/camp:

- **Dig** — slow block-breaking on declared tunnel blocks; progress persists;
  discovered tunnels are fillable by guards/trades and earn the digger
  time when found.
- **Pick** — faster, item-gated (lockpick/contraband item), on cell locks
  (FUN-6 lock state); failed picks can jam and mark the lock.
- **Bribe** — coin/contraband payment to a corruptible NPC guard post or to
  a player guard (consensual bribe offer through a bounded interaction);
  discovered bribes are crimes for both parties.
- **Blend** — a stolen/issued guard uniform item plus walking out during an
  open window (cell-door cycles, muster); detected by proximity checks on
  real guards.

Each path produces an escape event → manhunt/warrant through the existing
pipeline. Preparation actions (digging progress, held contraband) are
discoverable by cell inspection — a new light guard interaction that gives
patrols a reason to walk the cells.

**Acceptance criteria.**

- Every path has a preparation stage interruptible by inspection or patrol
  presence — no instant escapes.
- Contraband items used by pick/bribe/blend paths are physical, findable,
  and confiscated on detection.
- Detection events (tunnel found, jammed lock, bribe witnessed) notify
  on-duty through FUN-5 at low severity.
- Successful escapes funnel into the manhunt loop; recapture applies the
  existing sentence-extension rules.
- All paths work inside camp perimeters too (camp has its own boundary
  rules — proposal: camp escapes start wanted directly).

**Scope.** `PrisonService`/cell inspection + tunnel block progress;
contraband item definitions; bribe interaction; uniform-check behavior;
`LaborCampService` boundary integration; dispatch wiring. Issue type:
feature. Owner: unassigned (agent).

**Non-goals.** No free teleports, no bed/home tricks (existing rules stand);
no escape path that skips detection entirely; no prisoner vending of
contraband to guards.

**Dependencies.** FUN-5, FUN-6 (locks, manhunt); FUN-9 (contraband economy
supplies the items).

**Verification.** Per-path state machine tests; inspection detection tests;
restart persistence of tunnels; live UAT of a dig→discovery→manhunt cycle.

**Open decisions.** Tunnel block list per prison layout; bribe price formula;
whether player guards can accept bribes as a sanctioned mechanic or only NPC
posts (proposal: both, with player-guard bribery flagged heavier in audit).

---

## FUN-9 — Prison economy and jobs: a small society inside

**Intent.** Mining into a work account is a single treadmill. This issue
makes the inside of a prison/camp a small economy with jobs, trade, and
favors — so time inside produces stories, not just hours.

**Expectation.** Prisoners access posted jobs at camp/prison work points:
mining (existing), smelting/smithing quotas, kitchen/laundry/cleaning tasks,
and trades-member supply contracts. Jobs pay the work account at different
rates and build a **standing** stat used by FUN-10 parole. A contraband
market exists: smuggled-in items (from visitors, corrupt posts, or found
stashes) trade between prisoners for work-account credit or favors; holding
contraband is risk (FUN-8 inspection). Favors are non-currency social debt —
tracked as simple IOU notes between prisoners — usable for escort
cooperation, alibis, or market credit. Guards get inspection quotas turned
into dispatched mini-calls (FUN-5) so the economy has real pressure.

**Acceptance criteria.**

- At least three job types beyond mining with distinct inputs, pacing, and
  pay; job records feed standing and the work account idempotently.
- Contraband items are physical, tradable prisoner-to-prisoner, detectable,
  and confiscate-able with a seizure record.
- Standing is durable, visible to the prisoner (status surface), and readable
  by parole logic.
- Market trades cannot duplicate value (item-for-credit swaps settle
  atomically).
- Camp perimeter and cell inspections interact with held contraband exactly
  as FUN-8 defines.

**Scope.** `LaborCampService` job registry + standing stat; prisoner market
interaction; contraband item/flag plumbing shared with FUN-8; work-point NPC
or block surfaces; `PolicyService` economy config. Issue type: feature.
Owner: unassigned (agent).

**Non-goals.** No external-market laundering (work-account credit stays
internal until release settlement, which already exists); no prisoner
vending to guards; no crafting trees beyond configured job quotas.

**Dependencies.** FUN-8 shares the contraband flag; FUN-10 consumes standing.
Soft on FUN-5 for inspection calls.

**Verification.** Job settlement idempotency; trade atomicity; standing
accumulation; inspection confiscation; live UAT of a market trade.

**Open decisions.** Whether favor IOUs are physical notes or ledger entries
(proposal: physical notes — stealable, fun); which jobs map to which
standing weight.

---

## FUN-10 — Parole and appeal: the social route to freedom

**Intent.** Not every prisoner wants to dig. This issue builds the
institutional route out — hearings, good behavior, advocates — so a
cooperative prisoner has a real path that is itself roleplay.

**Expectation.** Prisoners accumulate parole eligibility from standing
(FUN-9), time served, paid debts, and clean recent record. At threshold they
may file for a hearing through the guard desk; hearings schedule onto a
docket the Comisar/officers work through (delegation-eligible under FUN-1 —
standing orders for routine parole criteria, digest for contested cases). A
hearing is a real interaction: the prisoner appears (escorted, FUN-7 ride if
off-site), an **advocate** player may speak for them (any member or an
outsider retained through a request), and the deciding officer weighs
standing, victim objections (fines unpaid, complaint authors online get a
chance to object), and record. Outcomes: `PAROLED` (early release with
conditions — check-in duty, behavior window, re-arrest on violation),
`DENIED` (cooldown before refiling), or `CONDITIONAL` (release on bail/fine
settlement). Appeals against fines/sentences reuse the same docket.

**Acceptance criteria.**

- Eligibility inputs are ledger-derived and transparent to the prisoner —
  they can see exactly what stands between them and a hearing.
- The docket is durable, ordered, and delegation-aware; hearings never
  silently expire a prisoner's place in line.
- Parole conditions are enforced server-side; violations re-arrest through
  the normal pipeline with audit.
- Advocate participation is consensual and recorded; it carries no authority
  of its own.
- Victim objection is a bounded notification + window, not a veto.

**Scope.** Parole eligibility engine; docket store + scheduling; hearing
interaction at guard desk/Comisar surface; parole-condition tracking and
violation hooks; advocate request flow; appeal reuse of complaint/petition
plumbing. Issue type: feature. Owner: unassigned (agent).

**Non-goals.** No automatic parole without a hearing artifact; no parole for
flagged escapees inside their cooldown; no jury/trial system.

**Dependencies.** FUN-1 (delegated docket processing), FUN-9 (standing),
FUN-7 (off-site hearing transport, optional path).

**Verification.** Eligibility computation tests; docket ordering/durability;
condition-violation re-arrest; live UAT of a full hearing.

**Open decisions.** Parole condition defaults; whether parolees wear a
visible marker (uniform tag) during the condition window (proposal: yes —
it gives the city something to notice).

---

## Aggregate residual risks

- **Design-doc drift.** `gameplay-decisions.md` and
  `roleplay-career-bureaucracy-design.md` describe the protocol-heavy model.
  Each issue updates the design target as it ships — implementation follows
  the new documents, not the old, per the repo's own §17 rule.
- **Delegation over-reach.** FUN-1 must be conservative by default: ship with
  narrow standing-order vocabulary and expand per action type as trust
  proves out.
- **Empty-server economics.** With zero on-duty members, dispatch calls
  queue silently; crime and escape mechanics must still resolve correctly
  (warrants, deadlines) without responders.
- **Scope.** This is ~9 issues across 3 milestones; M1 alone meaningfully
  changes daily play. Milestones are independently shippable in order.
- **Uncommitted working tree** predates this plan (8 modified files,
  rank-overhaul cleanup) — first implementation issue should land it or the
  owner confirms it is disposable before work begins.
