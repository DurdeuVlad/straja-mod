# Straja — gameplay and system decisions

This document is the implementation-independent contract for the Straja system.

It describes **what the system should do**, not how it must be coded. The implementation agent may choose the technical structure as long as these player-facing rules and invariants are preserved.

## 1. Core principles

- Straja membership/authorization is persistent personnel state. It is **not** inferred from a scoreboard team/faction.
- The scoreboard/team `Straja` is operational only. Joining it manually never grants Straja authority.
- A player may have another main faction and still be an authorized Straja member.
- On duty, the system may temporarily move the player to the operational `Straja` team; when duty ends, the previous main faction is restored.
- Rank and job/specialization are separate concepts.
- Normal administration should happen through NPC interactions and in-game menus. Commands/config files are fallback/recovery tools, not the preferred day-to-day workflow.
- Most gameplay values should be configurable in-game instead of hard-coded.

## 2. Rank hierarchy

Current hierarchy, lowest to highest:

1. **Stagiar**
2. **Străjer**
3. **Sergent**
4. **Inspector**
5. **Comisar**

Rules:

- Rank represents authority/seniority, not a narrow profession.
- Ranks 1–3 are field personnel: they patrol, respond to incidents and may use physical force/arrest mechanics as allowed by the rest of the mod.
- `Investigator` is **not** a rank. It is a specialization/function.
- Other independent functions may include Instructor, Recrutor, Investigator, patrol lead and administrative roles.
- Rank display names should be configurable without invalidating saved data; implementations should use stable internal rank identities rather than the Romanian display string itself.
- Inspector is directly below the Comisar in the current hierarchy.

## 3. Personnel authorization

An authorized member should have persistent personnel data containing at least:

- stable player identity (UUID or equivalent);
- authorized / unauthorized status;
- current rank;
- service/personnel number;
- authorization source/actor;
- relevant timestamps/history.

Rules:

- Manually joining the `Straja` scoreboard team must **not** authorize a player.
- Revoking authorization immediately removes Straja authority.
- If authorization is revoked while the player is on duty, duty ends safely and the main faction is restored.
- The Comisar can directly authorize experienced personnel at an appropriate rank instead of forcing them through the beginner recruitment flow.

## 4. Rank presentation

Rank display must remain logically independent from the player's main faction.

Examples:

- `[Stagiar] Nume`
- `[Străjer] Nume`
- `[Sergent] Nume`
- `[Inspector] Nume`

Exact surfaces such as chat, TAB and nameplate may be configurable.

## 5. Recruitment flow

Normal flow:

`Recepție -> cerere/documente -> Recrutor -> quiz -> autorizare ca Stagiar`

### Receptionist

- Introduces the system and rules.
- Records the recruitment application.
- Gives tangible RP documents where appropriate.
- Explicitly directs the applicant to the Recruiter.
- Physical paperwork is non-authoritative: losing/renaming it must not erase or grant application status.

### Recruiter

- Only tests players with a valid submitted application.
- Uses a real multi-question quiz.
- Wrong answers may apply a configurable cooldown.
- Passing the full quiz authorizes the player as **Stagiar**.
- The Recruiter cannot be used to bypass Reception/application.

### Trainer / Instructor

- Separate role from Recruiter.
- Handles training, instruction and later progression/exams.
- Whether specific training is mandatory before first duty remains configurable.

## 6. NPC roles and UX

Visible NPC roles currently in scope:

- Receptionist
- Recruiter
- Trainer / Instructor
- Secretary

Jailer/item-dependent custody expansion is postponed for now.

NPCs are the normal UX layer. Core timers/state must run reliably at server/system level and must not depend on an NPC chunk staying loaded. A hidden orchestrator NPC is not a gameplay requirement.

## 7. Duty / shift rules

- A normal duty shift starts at the Secretary.
- A voluntary duty shift ends at the Secretary.
- Players should not normally stop duty from an arbitrary location via convenience command.
- Starting duty remembers the player's main faction/team and temporarily moves them to operational `Straja` where applicable.
- Ending duty restores the remembered main faction/team.
- If the player had no main faction, they return to no main faction.
- If the stored faction can no longer be restored, fail safely and flag the problem instead of guessing.

## 8. Patrol checkpoints

- Patrol uses an ordered checkpoint route.
- Current default wait/unlock time after completing a checkpoint: **10 minutes**.
- Current default checkpoint deadline: **30 minutes**.
- Both are configurable.
- Completing the final checkpoint completes one **round**, not the entire duty shift.
- The route loops back to checkpoint 1 and continues until duty ends.
- Missing the checkpoint deadline automatically ends the shift.

## 9. Logout / offline rules

- Offline time is never paid.
- Logging out does not freeze or reset checkpoint deadlines.
- On reconnect, duty state is validated.
- If the deadline expired while offline, duty ends automatically and the main faction is restored.
- If still valid, duty may continue with the remaining time.

## 10. Salary and currency

### Currency ladder

There are four active denominations:

1. Bronze
2. Brass
3. Silver
4. Gold

Current default conversion ladder:

- **64 Bronze = 1 Brass**
- **64 Brass = 1 Silver**
- **64 Silver = 1 Gold**

The old 1/10/100/1000 denomination assumption must not be used.

### Rank-based hourly wage

Promotion increases hourly pay because higher rank carries more responsibility.

Current default hourly rates for one real hour of qualifying, actually-played duty:

- **Stagiar:** 16 Bronze/hour
- **Străjer:** 24 Bronze/hour
- **Sergent:** 36 Bronze/hour
- **Inspector:** 64 Bronze/hour = 1 Brass/hour
- **Comisar:** 128 Bronze/hour = 2 Brass/hour

This is an accelerating progression. Comisar remains exactly **2× Inspector**.

Rules:

- Hourly wages are configurable in-game.
- Only qualifying online work time counts.
- Salary accrues proportionally/continuously rather than requiring a full completed hour.
- Promotions affect the wage rate from that point forward only; already-earned salary is not retroactively changed or lost.
- Partial salary progress survives voluntary clock-out.
- Earned but unclaimed salary is persistent.
- Inventory/payment failure must not destroy the unpaid balance.
- Physical payout should use the 64-based denomination ladder without losing value.
- Anti-AFK salary behavior remains configurable/TBD rather than an implicit hard rule.

## 11. Weekly activity reports

- Members submit periodic activity reports through the Secretary.
- Current intended interval: **7 real days**, configurable.
- A tangible RP form/book may be used, but the authoritative report is stored by the system.
- Report content should cover activity/missions, incidents/problems and notes for the Comisar.
- Review outcomes include at least: accepted, returned/needs additions, and called in to see the Comisar.
- Whether overdue/returned reports block a new duty shift is configurable rather than permanently hard-coded.

## 12. Audience requests

- Authorized members can request an audience with the Comisar through the Secretary.
- The request stores stable player identity plus a reason/message.
- Requests persist across logout/restart until resolved.
- The Comisar may receive an online notification and can review/resolve requests through the Secretary/admin UI.

## 13. Missions

Missions should be fast to issue through configurable templates rather than requiring the Comisar to rebuild every field manually.

General rules:

- The Comisar, plus any ranks explicitly granted permission, can assign missions.
- Members can inspect their active missions through the Secretary or another appropriate NPC/UI.
- Mission assignment, status, completion and reports are persistent.
- Templates can be created, edited, duplicated, enabled or disabled in-game.
- Rank/permission requirements for assigning and receiving missions are configurable.
- A mission reward is a completion bonus separate from normal hourly duty salary. If the member is also doing qualifying duty, normal salary continues independently.
- Rewards/budgets are stored internally in Bronze-equivalent value and converted to physical denominations only when paid.

### Mission budget formula

Mission budgets are **not fixed flat rewards by template**. They are calculated from the estimated amount of work.

For each paid participant:

`mission reward = hourly wage of the mission's minimum required rank × estimated mission hours × risk multiplier`

Therefore both **estimated hours** and **risk** directly scale the mission budget.

Examples with a Străjer minimum rank (24 Bronze/hour):

- 0.5 estimated hours at 1.0× risk = **12 Bronze**
- 1 estimated hour at 1.0× risk = **24 Bronze**
- 2 estimated hours at 1.0× risk = **48 Bronze**
- 2 estimated hours at 1.5× risk = **72 Bronze** = 1 Brass + 8 Bronze
- 3 estimated hours at 2.0× risk = **144 Bronze** = 2 Brass + 16 Bronze

Important rules:

- **Estimated hours are a mission input**, not the actual completion time.
- The reward is calculated when the mission is issued/confirmed from the chosen estimate and risk.
- Taking longer than estimated does not automatically increase the reward; finishing faster does not automatically reduce it. This prevents deliberate time-padding and makes the budget predictable.
- Templates may prefill a sensible estimated duration and risk category, but the issuer can adjust them before confirming the mission.
- Fractional estimates are valid where useful (for example 0.25, 0.5, 1.5 hours).
- Risk is a multiplier, not a separate arbitrary bonus added afterward.
- Exact default risk categories/multipliers remain configurable; templates may provide defaults.
- The base calculation uses the **minimum rank required by the mission**, not the individual assignee's higher personal wage. That keeps a mission's price stable regardless of which qualified higher-ranking member accepts it.

### Team mission budgets

For a team mission:

`maximum mission budget = reward per participant × maximum paid participants`

The issue UI should show before confirmation:

- minimum required rank and its hourly rate;
- estimated hours;
- risk multiplier;
- calculated reward per participant;
- maximum number of paid participants;
- maximum total mission budget.

Only qualifying participants who earn/complete the mission are paid. Unused reserved budget is released. Payout must be idempotent so reconnect/retry cannot duplicate rewards.

### Mission templates

A template should define/prefill things such as:

- template name/type;
- minimum rank;
- **default estimated hours**;
- **default risk multiplier/category**;
- maximum participants;
- sensible deadline;
- objective/description placeholders;
- optional required specialization/equipment;
- optional automatic report requirement;
- whether it supersedes normal patrol routing while active.

Templates do **not** own a permanently fixed cash reward. Their displayed recommended reward/budget is recalculated from the current hourly wage table, estimated hours and risk multiplier.

When issuing a mission, the Comisar/authorized issuer should normally only need to choose a template and fill/change mission-specific data such as target, location, objective, estimated hours if different, risk if different, participant slots, deadline and notes.

A **Custom mission** option should exist for unusual cases and use the exact same calculation model.

Authorized overrides of the calculated reward may exist within configurable limits, but unusual overrides should require confirmation/reason text and remain visible in audit/history.

## 14. Commissioner / administration UX

The Comisar should have an administrative interface through appropriate NPCs, especially the Secretary.

Expected areas include:

- personnel list / dossiers;
- authorization and revocation;
- rank changes;
- active-duty personnel;
- activity reports;
- audience requests;
- missions and mission templates;
- relevant gameplay configuration.

Experienced-personnel override remains an explicit use case: the Comisar can authorize a known experienced player directly at a higher rank without pretending they are a new recruit.

## 15. In-game configurability

Normal configuration should preferably be available through NPC/admin menus in-game.

At minimum, the system should aim to make these editable without recompiling:

- rank display names/order and permissions;
- hourly salary per rank;
- salary accounting granularity;
- coin/item IDs, denomination order and conversion ratios;
- checkpoint routes, locations, waits and deadlines;
- recruitment quiz questions/answers/cooldowns;
- Receptionist/Recruiter/Trainer/Secretary dialogue and documents;
- report interval and report policies;
- mission templates, minimum ranks, default estimated hours, risk multipliers/categories, participant limits and permissions;
- equipment/kit rules where applicable;
- NPC role assignment where practical.

Commands/config files may remain as operator fallback and emergency recovery, but should not be the preferred day-to-day Commissioner workflow.

## 16. Explicit non-goals / postponed decisions

- Jailer/item-dependent custody expansion is postponed for now.
- Current default hourly salaries are **16 / 24 / 36 / 64 / 128 Bronze** for Stagiar / Străjer / Sergent / Inspector / Comisar, while remaining configurable in-game.
- Exact promotion thresholds/exams between ranks are not finalized.
- Anti-AFK salary behavior is not finalized.
- Exact default mission risk categories/multipliers are not finalized; the invariant is that risk **multiplies** the hours-based wage calculation.
- Exact UI layout and technical persistence architecture are implementation choices as long as the gameplay invariants above are preserved.

## 17. Implementation rule for agents

When code and this document disagree on newly discussed gameplay behavior, treat this document as the design target and update implementation/tests accordingly. Do not infer new gameplay rules from old implementation details without confirming them first.
