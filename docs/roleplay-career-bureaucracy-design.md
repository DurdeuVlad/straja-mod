# Straja roleplay careers, bureaucracy, missions, and stations

Status: `validated-design`

This document records the player-approved design for the next Straja system
expansion. It is a specification for implementation planning, not an
implementation checklist and not a replacement for the current runtime.

## 1. Context and intent

The Straja has a castle headquarters on the capital island. A newly opened
continent contains player factions and settlements, but the Straja does not
yet have a meaningful presence there. The intended story is the expansion of
the castle's influence through a continental military base.

The live CustomNPCs setup is a strong proof of concept and should be preserved
as the target experience:

- players move between named NPC desks;
- signed books and orders make bureaucracy visible in roleplay;
- the player can complete most administration without waiting for the
  Comisar;
- paperwork provides a physical audit trail without making every action a
  typed command;
- boring clerical and guard work can remain NPC work while players receive
  consequential, visible tasks.

The native mod should reproduce this feel with server-authoritative state,
durable dossiers, configurable documents, and station-local NPC routing.

## 2. Contract

### Goal

Create two parallel Straja career paths, a document-driven self-service
administration system, meaningful time-bounded missions, automatic command
escalation, and low-noise Discord event announcements.

### System

Physical documents and NPC conversations are the player-facing interface.
Persistent server state is the authority. NPCs issue or exchange documents
only when a valid order, request, mission, or personnel state justifies it.

### Constraints

- Do not make Specialist a forced daily patrol career.
- Do not give senior command authority to players who retain an incompatible
  active faction affiliation.
- Do not let a book, ticket, or copied template grant authority by itself.
- Do not give unlimited free forms, equipment tickets, money, missions, or
  promotion outcomes.
- Do not require the Comisar to manually process every ordinary player action.
- Do not send routine paperwork, payments, or individual reminders to Discord.
- Do not replace the existing NPC-first UX with typed commands.

### Evaluation

The implementation is ready for acceptance when the following can be shown:

- both career paths persist across restart and recover safely;
- documents, orders, templates, and tickets have explicit provenance and
  one-use/quantity rules where applicable;
- repeated, concurrent, copied, stale, and reconnect-submitted documents or
  missions are idempotent and cannot duplicate authority, quota, equipment, or
  payment;
- admission cannot bypass the Instructor/Recruiter quiz or explicit Comisar
  authorization;
- professional and military missions have separate eligibility and payment
  rules;
- authority actions reject reciprocal/self/conflicted approval paths;
- deadlines escalate through the available chain of command;
- a server with no Inspector still works by escalating to the Comisar;
- multiple stations use the same mechanics with different local NPCs,
  inventories, routes, and dialog;
- Discord receives only allowlisted, deduplicated important events;
- the live POC flow can be reproduced without changing an NPC's code for every
  new station.

## 3. Career model

The two paths are parallel until they converge at Inspector.

```text
Civil
├── Stagiar
│   └── Străjer → Sergent → Inspector
└── Stagiar Specialist
    └── Specialist → Inspector
```

### Military path

- `Stagiar` is the entry military trainee.
- `Străjer` is the part-time military field member.
- `Sergent` is a senior command/field rank.
- `Inspector` is the common operational authority above Sergent and
  Specialist.

Stagiar and Străjer can remain affiliated with another faction while off
duty, subject to the normal conflict-of-interest and operational rules.
Sergent and higher are full-time Straja personnel: they cannot retain an
active incompatible faction rank, office, or operational affiliation.

### Professional path

- `Stagiar Specialist` is recruited into the professional track by a
  personalized appointment/recruitment document.
- It is militarily equivalent to Străjer when mobilized, but it does not
  require routine patrols.
- `Specialist` is the proven professional status.
- Specialist is not another military rank and does not automatically grant
  arrest, investigation, command, or budget authority.
- A Specialist has one primary profession at first: Builder, Miner, Smith,
  Logistician, or another explicitly configured profession.
- The professional track should stay small initially: two statuses and a
  profession, not a large independent rank ladder.

Specialists work on projects and contracts. They can be mobilized for defense
or a military emergency without being converted into ordinary patrol staff.
Whether a non-Inspector Specialist may retain an external faction affiliation
is a policy setting, but the check must run at appointment, mobilization,
promotion, faction change, and reconnect. Inspector exclusivity is mandatory.
For every exclusive status, including Sergent, Inspector, and Comisar, the
same conflict check runs transactionally at promotion, appointment, duty
start, duty end/recovery, mobilization, external-faction change, logout
recovery, and reconnect. A conflicting transition fails safely or ends the
incompatible duty/authorization state; it must not leave both active states
valid.

### Convergence at Inspector

Both paths can reach Inspector:

- Military: Stagiar → Străjer → Sergent → Inspector.
- Professional: Stagiar Specialist → Specialist → Inspector.

The dossier retains the career origin (`MILITARY` or `SPECIALIST`) so two
Inspectors can have the same authority while keeping different experience,
profession, and promotion history.

`Investigator`, `Instructor`, `Recruiter`, `Patrol Lead`, and similar labels
are functions or authorizations, not additional rank ladders.

## 4. Inspector and Comisar authority

### Inspector

An Inspector may:

- create and assign missions from approved templates;
- mobilize eligible personnel;
- review reports and professional work;
- approve ordinary completion and payment within a configured budget;
- supervise a station, project, or jurisdiction;
- issue standard forms and orders available to that station;
- recommend or prepare promotion evidence;
- take over overdue work from lower personnel.

An Inspector may not:

- change global setup or policy;
- mint unlimited money, tickets, or equipment;
- approve their own payment, promotion, or exceptional reward;
- create an unbudgeted mission;
- bypass audit, cooldown, or escalation rules.

Inspector powers should be close to Comisar powers but bounded by budget,
scope, approval, cooldown, and anti-self-approval rules.

### Comisar

Comisar is a leadership office, not a mandatory career step. The office may be
held by the owner or delegated explicitly. The Comisar can:

- recruit or appoint personnel;
- approve Inspector promotions;
- create or change mission templates and budgets;
- authorize exceptional missions and rewards;
- decide discipline, dismissal, reinstatement, and major appeals;
- resolve escalated complaints;
- configure stations, NPC roles, fallback routes, and policy;
- appoint or remove Inspectors.

An NPC can provide intake, clerical processing, and explanations, but does not
silently satisfy a human authority requirement.

For compatibility with the current runtime, `comisarAppointment` and the
existing Comisar rank/permission identity must have one explicit mapping. The
appointment is the source of effective Comisar authority; a legacy numeric
rank or permission read-model must not stack a second independent authority
path. Salary, display, and permission resolution must use the same mapping.
If persisted data currently represents Comisar as a rank, migration converts
that representation to the appointment without losing history.

Every authority action validates, atomically, the actor, subject, capability,
active appointment, jurisdiction, station scope, external-faction conflict,
budget, and independent-approval requirement. The conflict set includes the
issuer, assignee, beneficiary, approver, and any actor who materially changed
the order. An Inspector cannot approve a mission, payment, exceptional reward,
promotion evidence, or completion when they are in that conflict set; a
reciprocal Inspector arrangement is not an independent approval. Exceptional
or conflict-bound actions escalate to Comisar.

## 5. Promotion workflow

Promotion is player-initiated but authority-approved.

1. The player submits a promotion request through the appropriate NPC/form.
2. The server creates a durable request in `SUBMITTED` state and notifies the
   relevant authority.
3. Eligibility checks run immediately: service, training, reports,
   profession/project evidence, and any configured prerequisites.
4. Once the request exists, the player may start exams and required training
   without waiting for Comisar approval.
5. Exam results, projects, reports, and endorsements attach to the request.
6. When all requirements are complete, the request becomes
   `READY_FOR_APPROVAL`.
7. The Comisar approves or rejects the final promotion. The rank/status
   changes only after this decision.

There is no promotion without a request ID and no backdated informal approval.
There may be at most one active promotion request per player and target status,
unless a supersession record explicitly closes the previous request. Evidence
has a unique ID, provenance, owning request, validity window, and explicit
reuse policy. Final approval revalidates current personnel status, career
track, profession, affiliation, evidence, and request version atomically.
Failed or rejected requests remain auditable. Reuse or expiry of passed exams
is a policy value, not an implicit behavior.

For Specialist → Inspector, the evidence includes completed professional work,
military knowledge, administrative knowledge, command judgment, and Comisar
approval. The Specialist does not need to pass through Sergent first.

## 6. Documents and order economics

### Standard, fungible documents

Documents used as currency or quota must be generic, stackable, and
interchangeable:

- equipment/requisition tickets;
- material vouchers;
- ration or repair tickets;
- other configured quotas.

They carry type, value, issuer seal, and validity rules, but not a player's
personal narrative. They are consumed or decremented on exchange.

### Personalized, immersive documents

Documents meant to remain with a player are named and serialized:

- military recruitment order;
- Specialist appointment order;
- enlistment/authorization certificate;
- personalized mission order;
- promotion request or certificate;
- return-of-equipment proof;
- complaint, report, appeal, or audience document.

They contain player, issuer, date, purpose, station, and document ID. They are
roleplay evidence; the server dossier remains authoritative.

Every document and fungible ticket has server-side lifecycle state, issuer,
subject or redemption policy, station scope, quantity, expiry, and a
consumption ledger. Issuance and redemption are atomic transitions keyed by a
unique request/order/document ID. Personalized documents are explicitly
single-use unless their type says otherwise. Fungible tickets decrement a
server quantity rather than trusting the physical item count. Reconnects,
copied items, repeated NPC interactions, and concurrent submissions return the
existing result without repeating the effect.

### No free cards or tickets

NPCs do not hand out unbounded blank books, forms, or tickets for free. A
valid order or request must justify every issue:

- recruitment order → enlistment order;
- enlistment order → equipment order and quota;
- active mission order → report form;
- registered complaint request → complaint form;
- approved professional project → work order and material quota.

The player still experiences self-service. The NPC performs the validation and
exchange automatically, but every physical item has a reason and a limit.

## 7. Recruitment and equipment flow

The live POC flow is the reference experience:

1. The Receptionist/entrance desk accepts only an existing application or
   explicit invitation. The live POC may label this NPC “Recruiter,” but this
   is operational intake and may make the CustomNPCs faction relation
   friendly; it never authorizes personnel, assigns Stagiar, starts duty, or
   creates equipment/payment eligibility.
2. A military applicant receives a military recruitment order only after the
   application/invitation is valid. The Instructor/Recrutor admission quiz
   remains the authorization gate for military Stagiar status, unless the
   Comisar explicitly authorizes an exception.
3. A Specialist applicant receives a personalized Specialist appointment
   order from the Comisar/authorized recruiter.
4. After admission authorization, the player takes the order to Secretariat.
5. Secretariat issues enlistment/authorization paperwork and a configured
   number of equipment tickets.
6. The player takes standard tickets to the local Armorer.
7. The Armorer exchanges them for the player's chosen weapons and equipment.

Uniform, flag, basic identification, and optionally a helmet or ration are
configured baseline issue. Weapons and additional equipment are chosen from
the available stock rather than forced into one identical kit.

Ticket quantities remain configuration, not code constants. The POC currently
uses a larger siege allocation; the final normal/siege values must be chosen
before implementation (candidate baselines discussed: 4/8 or 8/12).

### Resignation and rejoin

Leaving the Straja does not magically delete equipment. The player must either:

- return the equipment to the Armorer and receive a personalized return proof;
- or settle the value of missing equipment as a debt/payment.

Rejoin requires a new valid enlistment/equipment order. The return/debt path is
an auditable roleplay flow, not an inventory teleport. Equipment issuance
creates a per-player ledger of item identity, quantity, condition/custom data
where relevant, and outstanding liability. Return proofs are single-use and
bound to exact ledger entries. Partial return, replacement, destruction,
transfer, duplicate proof, reconnect retry, and mixed return-plus-payment cases
must be explicit ledger transitions. Rejoin is blocked until the ledger is
returned, settled, waived by authorized personnel, or placed under an explicit
repayment policy; possession of a return document alone is not sufficient.

## 8. Templates and forms

Template forms should be easy to request in roleplay, but they are not
untracked inventory. They are issued only through a justified order or
request.

Examples:

- citizen → complaint template → Receptionist;
- guard → mission report template → Secretary;
- Inspector → mission order template;
- Armorer → equipment request/return form;
- player → audience or appeal form;
- member → weekly activity report.

A blank template is standard. Once filled, it becomes a personalized,
one-use submission with author, timestamp, station, and unique ID.

Complaint flow:

1. Citizen opens a request at Receptionist.
2. Receptionist issues a complaint form under that request.
3. Citizen completes and returns it.
4. The system creates a durable incident and mission draft.
5. Inspector approves it when available; otherwise it routes to Comisar.
6. Only approved drafts become active missions.

This preserves the desired “write something whenever you want something done”
mechanic without allowing blank-paper spam to create unlimited missions.
Repeated submission of the same complaint request is idempotent. A player may
not create unlimited open complaints for the same subject and incident by
replaying a form; the system correlates duplicates, applies configured rate
limits, and records withdrawal or resubmission explicitly.

## 9. Mission generation

The common lifecycle is:

```text
TEMPLATE → DRAFT → APPROVED → ORDERED → ACTIVE → REPORTED → COMPLETED
             ↓          ↓         ↓         ↓          ↓
          REJECTED   CANCELLED  EXPIRED  REASSIGNED  REPORT_REJECTED
```

`OVERDUE`, `CANCELLED`, `EXPIRED`, `REASSIGNED`, and `REPORT_REJECTED` are
explicit states or transitions, not missing data. Every transition names its
allowed predecessor, actor, evidence, timestamp, and idempotency key. Mission
completion uses a server-side compare-and-set transition and a unique claim
receipt. Payment, campaign quota contribution, and audit event commit exactly
once with that receipt. A retry returns the existing result. No payment is
valid after cancellation, expiry, reassignment, or removal unless an explicit
authorized exception is recorded.

### One-off player-facing missions

Builders and story-critical work use missions created by Comisar or Inspector:

- military-base construction;
- walls, towers, workshops, roads, and repairs;
- special investigations;
- defense operations;
- story or corruption events.

They have unique IDs, budgets, phases, dependencies, and no automatic repeat
generation.

### Repeatable professional contracts

Miners, Smiths, and Logisticians can receive repeatable contracts from NPCs.
The system checks profession, station, budget, cooldown, quota, and active
mission state before issuing an order.

Examples:

- mine a resource quota;
- smelt or craft a weapon quota;
- move materials between stations;
- repair configured infrastructure;
- supply a current project.

Functionary work remains NPC work unless a future design gives it a clearly
player-facing consequence. Paper processing and routine guard posts should not
be used to pad the player quest list.

### Time-limited campaigns

Special demand can open a temporary campaign:

```text
Campaign: Silver arming
Available: 7 days
Global target: 256 silver + 32 silver weapons
```

Miners receive repeatable extraction contracts. Smiths receive repeatable
production contracts. Each order has an individual deadline, while the
campaign has a global expiry and quota. The campaign closes when the target is
met or the window expires.

Remaining campaign quota is reserved atomically when a contract is issued.
The same resource unit or crafted item cannot satisfy multiple orders. Delivery
records evidence/material provenance and consumes the reservation. Late,
partial, cancelled, over-target, and post-expiry delivery have explicit
outcomes; restart and concurrent submissions must reconcile reservations,
progress, accepted orders, and payouts.

This turns resource work into coordinated Straja activity instead of endless
resource farming.

## 10. Deadlines and escalation

Every active order can have:

- availability window;
- acceptance deadline;
- individual due date;
- severity/priority;
- expected evidence;
- escalation policy.

Deadlines use persisted UTC instants and server time. Offline time and restart
do not pause a deadline unless the order type explicitly says so. Acceptance,
due, expiry, extension, and escalation timestamps are stored. Extensions
require an authority, reason, maximum-extension policy, and audit event; a
series of extensions cannot silently reset severity forever.

Complaints additionally have acceptance, investigation, and resolution
deadlines.

Complaint lifecycle:

```text
SUBMITTED → ACCEPTED → INVESTIGATING → RESOLVED
                         ↓
                      OVERDUE
                         ↓
                     ESCALATED
```

At first delay, the responsible player receives a reminder. The case then
routes to the next available authority. The authority can extend the deadline
with a reason, reassign the work, remove the player from the mission, or ask
for an explanation. Delay is not an automatic punishment, but it affects
history, rewards, and access to important future work.

### General command-chain fallback

This rule applies to complaints, missions, approvals, budgets, and reports:

```text
Stagiar → Străjer → Sergent → Inspector → Comisar
```

If an action requires a rank that has no eligible holder, it climbs until an
available higher authority is found. A higher rank may perform a lower-rank
task; a lower rank may not satisfy a higher-rank requirement.

If no Inspector exists, all Inspector-level escalations go directly to the
Comisar. The system must not fabricate an NPC Inspector as a workaround.

Professional requirements are not silently replaced by a different profession:
if no Builder is available, the case escalates to authority rather than being
assigned to a Miner.

## 11. Specialist payment

- Stagiar Specialist: payment per validated professional order; no guaranteed
  weekly stipend.
- Specialist: payment per validated order plus a weekly stipend if at least one
  qualifying activity was completed during the week.
- Military mobilization: separate military payment under the active order.
- No double payment for the same time or evidence.

Professional work must have verifiable evidence: delivered resources,
constructed/repaired blocks or structures, completed transfers, or an
explicit NPC/service validation. Daily passive salary is avoided because it
rewards AFK time; conditional weekly settlement preserves continuity without
paying inactive personnel indefinitely.

Settlement uses a unique key such as `(player, ISO week, career status)` plus
an evidence ledger. One activity can contribute only to the reward and weekly
qualification allowed by policy. A professional order plus weekly stipend,
professional order plus military mobilization, reassignment, partial
completion, or a retried submission must never pay the same evidence twice.
Settlement and every payment component are persisted and idempotent.

## 12. Multiple bases and stations

Each operational location is a first-class station:

```text
CASTLE_CENTRAL  → Sediul Central al Străjii
CONTINENT_BASE  → Baza Continentală
```

Every station may have local instances of Receptionist, Secretariat, Armorer,
Comisar, mission desk, and supply roles. The mechanics are shared; the
configuration differs by station.

Station-scoped configuration includes:

- display name;
- jurisdiction;
- NPC roles and local locations;
- local inventory and budget;
- available mission pools;
- fallback station;
- dialog templates;
- whether an order is local or globally redeemable.

Orders record their issuing station, jurisdiction, and redemption scope. A
continental Armorer can therefore have different stock from the Castle
Armorer without a second implementation of the Armory service.

Fallback routing validates jurisdiction, redemption scope, local authority,
inventory, budget, and budget owner before presenting an actionable route. The
router rejects cycles and has a bounded fallback depth. Every redemption
records issuing station, redemption station, jurisdiction, and budget owner.

NPC routing is contextual:

```text
find required NPC at current station
→ if present, route locally
→ otherwise route to configured fallback station
→ otherwise explain that the service is unavailable
```

Messages use placeholders such as `{station}`, `{armorer}`, `{secretary}`,
and `{fallback_station}`. An unavailable-service message states the current
station, required role, reason, fallback station, and whether the player must
wait, travel, or request authority. No hard-coded “go upstairs” directions
survive multi-station deployment.

## 13. Discord event notifications

The first integration should be an outbound Discord webhook adapter, not a
full Gateway bot. Discord documents incoming webhooks as a low-effort way to
post to a channel without a separate bot user; a full bot can be added later
if Discord commands or interactive approvals are required. Reference:
[Discord Webhook Resource](https://docs.discord.com/developers/resources/webhook).

The mod should use:

```text
committed domain event
→ audit record
→ allowlist/severity filter
→ persistent notification outbox
→ coalesce/deduplicate
→ asynchronous webhook sender
```

Discord payloads use an allowlisted schema containing only the minimum public
summary: event type, sanitized display name where appropriate, station,
mission/campaign title, and stable public ID. Complaint text, evidence,
coordinates, private dossier fields, and uncontrolled player text are not
sent. Mentions are disabled by default and only explicitly allowlisted roles
may be mentioned. The outbox has a uniqueness key based on stable domain event
identity, entity, event type, and coalescing window; transport attempt IDs
alone are not sufficient. Failed deliveries go to a bounded retry/dead-letter
state, with secret rotation and retention rules defined by configuration.

Immediate events:

- personnel authorization committed after the admission quiz or explicit
  Comisar authorization;
- Specialist, Sergent, or Inspector promotion;
- Inspector/Comisar appointment;
- major mission published;
- base attack, siege, corruption, or critical failure.

Grouped events:

- several missions created close together;
- several new recruits;
- campaign progress milestones.

Application/intake acceptance is not announced as Straja membership. If it is
ever surfaced, its event type must be `APPLICATION_ACCEPTED`, separate from
`PERSONNEL_AUTHORIZED`.

Silent events:

- individual forms and template copies;
- equipment tickets;
- routine payments;
- ordinary work contracts;
- reminders and internal audit details.

The webhook sender must run off the server tick thread, persist pending
delivery, deduplicate by event ID, sanitize player-provided text, disable
unintended mentions, and respect Discord's `Retry-After` and rate-limit
headers. See [Discord Rate Limits](https://docs.discord.com/developers/topics/rate-limits).
The webhook URL is secret configuration and never belongs in source or
player-visible documents.

## 14. Durable dossier model (conceptual)

The implementation should evolve the existing persistent personnel model with
explicit fields or subrecords for:

- `careerTrack`: `MILITARY` or `SPECIALIST`;
- `careerStatus`: `STAGIAR`, `STRAJER`, `SERGENT`, `STAGIAR_SPECIALIST`,
  `SPECIALIST`, `INSPECTOR`;
- `profession`;
- `comisarAppointment`;
- `externalFaction` and conflict status;
- `homeStationId` and current station/jurisdiction;
- promotion request and exam evidence;
- mobilization and active order;
- professional activity and weekly settlement;
- equipment ledger and return/debt state;
- active document IDs and consumed ticket quantities.

The authority resolver also exposes an explicit eligibility matrix for each
career status and profession: routine duty, emergency mobilization, arrest,
investigation, command, budget, promotion evidence, and form access are
separate capabilities. Specialist military equivalence applies only to the
configured mobilization/defense capabilities; it does not imply command,
arrest, investigation, or budget access.

All mutations remain server-authoritative, persistent, auditable, and safe on
logout/restart. Physical items are references/evidence, not the only source
of truth.

## 15. Decision log

1. **Use two parallel career paths.**
   - Military: Stagiar → Străjer → Sergent → Inspector.
   - Professional: Stagiar Specialist → Specialist → Inspector.
   - Reason: allow meaningful non-patrol players without creating an entirely
     separate command hierarchy.

2. **Specialist is a professional status, not a normal military rank.**
   - Reason: profession and authority must remain distinct.

3. **Inspector is the convergence point.**
   - Reason: both proven field and professional personnel can reach real
     command authority through different evidence.

4. **Comisar is an office/appointment.**
   - Reason: the highest administrative authority should not be a mandatory
     ladder step for every player.

5. **Documents are divided into fungible currency and personalized evidence.**
   - Reason: tickets should be convenient to exchange, while recruitment,
     mission, promotion, and return documents should carry narrative identity.

6. **Self-service is order-gated.**
   - Reason: preserve the bureaucracy fantasy without free-item spam or manual
     Comisar intervention for every action.

7. **Forms create drafts, not automatically authorized paid missions.**
   - Reason: preserve citizen agency while preventing complaint/misson spam.

8. **Builder work is one-off; Miner, Smith, and Logistician work can repeat.**
   - Reason: players should receive consequential work while NPCs handle boring
     routine administration.

9. **Time-limited campaigns create temporary demand.**
   - Reason: force coordination and punctuality around real objectives such as
     silver armament.

10. **Escalation climbs the chain when a rank is absent.**
    - Reason: the system must work before Inspectors exist and must never lower
      an authority requirement to make an assignment fit.

11. **Station-local NPC routing is configuration-driven.**
    - Reason: multiple bases need the same mechanics with different names,
      stock, jurisdiction, and directions.

12. **Discord starts as a filtered asynchronous webhook integration.**
    - Reason: announcements need outbound delivery, not a full interactive
      bot; the existing audit/coalescing pattern is already the local model.

13. **The entrance Recruiter never bypasses admission authorization.**
    - Reason: the live faction-friendly interaction is intake UX; Instructor
      success or explicit Comisar authorization remains the personnel gate.

14. **Every document, ticket, mission, campaign quota, payment, and equipment
    settlement has an atomic idempotent ledger transition.**
    - Reason: physical items and reconnectable NPC interactions are replayable
      by nature and cannot be the source of truth.

15. **Authority approval is conflict-aware, not merely non-self approval.**
    - Reason: reciprocal Inspectors, issuer-as-assignee, and delegated
      approvals can otherwise mint money or authority without direct self-use.

16. **Deadlines and campaign quotas are persisted resources.**
    - Reason: offline time, restart, concurrent submissions, late delivery, and
      extensions must reconcile deterministically.

17. **Comisar compatibility is resolved through one authority mapping.**
    - Reason: the current runtime and the new office model must not create two
      stackable permission or salary identities.

## 16. Implementation planning boundaries

### Slice A — career and dossier foundation

- career track and Specialist statuses;
- full-time/conflict rules;
- Inspector convergence;
- Comisar appointment;
- promotion request and exam evidence;
- generic command-chain router.

### Slice B — document and station foundation

- standard versus personalized document metadata;
- order-gated form issuance;
- equipment/ticket ledger;
- return/debt flow;
- station IDs, local NPC role bindings, fallback routing, and dialog
  placeholders.

### Slice C — mission and campaign runtime

- one-off mission templates and project phases;
- repeatable professional contracts;
- time-limited campaign quotas;
- per-order deadlines and evidence validation;
- complaint SLA and escalation;
- payment and weekly settlement.

### Slice D — Discord delivery

- allowlisted domain events;
- persistent outbox and deduplication;
- asynchronous webhook adapter;
- coalesced summaries and channel configuration;
- retry, failure, and secret-handling tests.

## 17. Known implementation parameters still to choose

These are intentionally deferred configuration/product values, not unresolved
architecture:

- normal versus siege equipment-ticket quantities;
- the exact profession list and profession-specific evidence;
- promotion service/project thresholds;
- Specialist weekly stipend and contract reward formulas;
- default campaign duration and global quotas;
- complaint deadlines by priority;
- station IDs, NPC names, fallback locations, and dialog wording;
- Discord channel IDs, event colors, and summary windows.

## 18. Two-agent QA result

Two independent read-only reviewers performed an intent/fidelity review and an
adversarial player/exploit review. They cross-reviewed each other's findings,
the document was hardened, and both performed a final validation pass on the
updated text.

The final shared verdict is: **no substantive modification needed at the
design level**.

The review specifically exercised or checked:

- admission bypass through the entrance Recruiter;
- Comisar rank/office compatibility and authority stacking;
- self-approval and reciprocal Inspector collusion;
- copied/replayed documents, tickets, forms, reports, and return proofs;
- equipment debt, partial returns, reconnects, and rejoin;
- concurrent mission completion, payments, and campaign quota delivery;
- stale/duplicate promotion requests and evidence;
- faction exclusivity during promotion, duty, mobilization, faction changes,
  logout recovery, and reconnect;
- offline/restart deadlines and repeated extensions;
- station fallback cycles, jurisdiction, redemption scope, and budget ownership;
- Specialist settlement/double-payment paths;
- Discord payload minimization, mentions, semantic deduplication, retries,
  and secret handling;
- the distinction between `APPLICATION_ACCEPTED` and
  `PERSONNEL_AUTHORIZED`.

Remaining items are explicitly deferred configuration or presentation polish,
not design blockers: ticket quantities, profession/evidence lists, promotion
thresholds and exam expiry, stipend/reward formulas, campaign and complaint
deadlines, station labels/dialogue, and Discord channel/presentation values.

The design is approved for implementation planning. No code change is implied
by this document alone.
