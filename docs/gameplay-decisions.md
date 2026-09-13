# Straja — gameplay and system decisions

This document is the implementation-independent contract for the Straja system.

It intentionally describes **what the system should do**, not how it must be coded. The implementation agent may choose the technical structure as long as these player-facing rules and invariants are preserved.

## 1. Core principles

- Straja membership/authorization is a persistent personnel status. It is **not** inferred from a scoreboard team/faction.
- The scoreboard/team `Straja` is operational: it may be used while a guard is on duty, but joining that team alone never grants authority.
- A player may belong to another main faction and still be an authorized Straja member.
- When such a player starts duty, the system temporarily places them in the operational Straja faction/team. When duty ends, their previous main faction is restored.
- Rank and job/specialization are separate concepts.
- Normal administration should be performed through NPC interactions and in-game menus. Commands/config files are fallback/recovery tools, not the normal player or commissioner workflow.
- Most gameplay values should be configurable in-game instead of being hard-coded.

## 2. Rank hierarchy

Current rank names, lowest to highest:

1. **Stagiar**
2. **Străjer**
3. **Sergent**
4. **Inspector**
5. **Comisar**

Design rules:

- Rank controls authority/seniority, not a narrow profession.
- Ranks 1–3 are field personnel: they patrol, respond to incidents and may use physical force/arrest mechanics as allowed by the rest of the mod.
- `Investigator` is **not** a rank. It is a job/specialization that can be assigned independently.
- Other independent functions can include Instructor, Recrutor, Investigator, patrol lead, administrative roles, etc.
- Display names of ranks should be configurable without invalidating saved player data; the implementation should use stable internal identities rather than relying on the Romanian display string.
- Comisar is the top authority. Inspector is the senior rank directly below the Comisar in the current hierarchy.

## 3. Personnel authorization

An authorized Straja member should have a persistent personnel record independent of current faction/team.

The record should support at least:

- stable player identity (UUID or equivalent stable identity);
- authorized / unauthorized status;
- current rank;
- service/personnel number;
- who/what authorized the member;
- relevant timestamps/history needed by reports or administration.

Rules:

- Manually joining the `Straja` scoreboard team must **not** authorize a player.
- Revoking authorization immediately removes Straja authority. If the player is on duty, the duty session should be ended safely and the main faction restored.
- The Comisar must be able to authorize experienced personnel directly at an appropriate rank instead of forcing them through the normal new-recruit flow.

## 4. Rank presentation

The player's Straja rank should be able to appear as a prefix/title without requiring the player to permanently belong to a special leaderboard/faction team.

Examples:

- `[Stagiar] Nume`
- `[Străjer] Nume`
- `[Sergent] Nume`
- `[Inspector] Nume`

Exact presentation surfaces (chat, TAB, nameplate, etc.) may be configurable, but rank display must remain logically independent from the player's main faction.

## 5. Recruitment flow

Normal recruitment flow:

`Recepție -> cerere/documente -> Recrutor -> quiz -> autorizare ca Stagiar`

### Receptionist

- The Receptionist introduces the system and provides recruitment information/rules.
- The player submits a recruitment application at Reception.
- The Receptionist gives tangible RP documents (paper/book/etc.) and explicitly directs the applicant to the Recruiter.
- The application state is authoritative; losing or renaming the physical document must not erase or grant application status.

### Recruiter

- The Recruiter only tests players with a valid submitted application.
- Recruitment uses a real multi-question quiz, not a single confirmation prompt.
- Wrong answers may apply a cooldown before the applicant can continue/retry.
- Passing the complete recruitment quiz authorizes the player as **Stagiar**.
- A player cannot bypass the Receptionist/application merely by talking to the Recruiter.

### Trainer

- Trainer is a separate role from Recruiter.
- Trainer handles training/instruction and later training/exam-related progression, rather than deciding whether a raw applicant is accepted.
- Whether a specific training module is mandatory before a first normal duty shift remains configurable/policy-driven.

## 6. NPC roles and UX

Visible gameplay NPC roles currently in scope:

- Receptionist
- Recruiter
- Trainer / Instructor
- Secretary

Jailer is intentionally postponed until the related items/mechanics are ready.

NPCs should be the normal UX layer. The player should be able to understand and use the system by speaking to the appropriate NPC rather than memorizing admin commands.

There is no gameplay requirement for a hidden NPC orchestrator. Core state/timers should be reliable at the server/system level and should not depend on a visible NPC chunk being loaded. NPCs are interfaces into the system, not the source of truth.

## 7. Duty / shift rules

- A normal duty shift starts at the Secretary.
- A voluntary duty shift ends at the Secretary.
- The player should not normally end duty from an arbitrary location through a convenience command.
- Starting duty temporarily switches the player into the operational `Straja` faction/team while remembering their main faction/team.
- Ending duty restores the remembered main faction/team.
- If the player had no main faction, ending duty returns them to no main faction.
- If the stored main faction can no longer be restored, the system should fail safely and flag the problem instead of guessing another faction.

## 8. Patrol checkpoints

Current intended patrol model:

- Patrol uses a route of checkpoints.
- After a checkpoint is completed, the next checkpoint has a waiting/unlock period.
- Current default: **10 minutes** until the next checkpoint becomes available.
- Current default deadline: **30 minutes** to reach/complete the required checkpoint after it becomes relevant.
- These values are defaults and should be configurable.
- Completing the final checkpoint completes one patrol **round**, not the entire duty shift.
- The route loops back to the first checkpoint and continues while the player remains on duty.
- A player ends a normal shift by returning to the Secretary.
- Missing a checkpoint deadline automatically ends the shift.

## 9. Logout / offline anti-abuse rules

- Offline time is never paid as duty time.
- Logging out does not freeze a checkpoint's real-time deadline.
- On reconnect, the system validates the existing duty state.
- If the deadline expired while the player was offline, duty is ended automatically and the main faction is restored.
- If the deadline is still valid, the duty state may continue with the remaining time; logging out must never reset the deadline.

## 10. Salary and currency

### Currency ladder

There are **four active coin denominations**, ordered from lowest to highest:

1. Bronze
2. Brass
3. Silver
4. Gold

The economy uses the same **64-to-1 conversion at every step**:

- **64 Bronze = 1 Brass**
- **64 Brass = 1 Silver**
- **64 Silver = 1 Gold**

Implementations should not use the old 1/10/100/1000 denomination assumptions. Currency denomination values/item IDs remain configurable, but the current gameplay default is the 64-based ladder above.

### Rank-based hourly wage

Salary is intentionally tied to rank: **promotion increases the hourly wage** because higher rank means more responsibility, authority and expected work.

Current default hourly wage ladder for one real hour of qualifying, actually-played work:

- **Stagiar:** 16 Bronze/hour
- **Străjer:** 24 Bronze/hour
- **Sergent:** 36 Bronze/hour
- **Inspector:** 64 Bronze/hour = 1 Brass/hour
- **Comisar:** 128 Bronze/hour = 2 Brass/hour

This is an accelerating progression rather than a flat increment. Comisar remains exactly **2× Inspector**.

These are the current gameplay defaults, but each rank's wage remains configurable in-game. The system must enforce only that a configured progression can remain coherent and understandable; the current intended default ordering is:

`Stagiar < Străjer < Sergent < Inspector < Comisar`

The fact that the current Comisar account may usually play in Creative does not remove or special-case the salary rule; the organizational rank still has a defined wage.

Only qualifying online work time counts. Offline time never contributes salary progress.

Salary is earned continuously/proportionally from qualifying work time and accumulates as an unpaid balance. It is claimed/paid through the Secretary rather than automatically dropping a coin every interval.

Rules:

- Salary accounting is based on actual qualifying online work time, with **1 real hour** as the reference unit for displayed/configured hourly rates.
- Promotions take effect on the wage rate from that point forward; already-earned salary is not retroactively recalculated or lost.
- Offline time contributes zero salary progress.
- Partial progress toward the next payable amount is preserved when a voluntary shift ends; a player should not lose legitimate partial progress by clocking out.
- Earned but unclaimed salary is preserved.
- Salary rates/multipliers, accounting granularity and currency item IDs/denominations must be configurable.
- Inventory/payment failure must not silently destroy the player's unpaid balance.
- Conversion to physical coins should use the 64-based four-denomination ladder and should choose sensible denominations without losing value.

Anti-AFK policy is not yet a final gameplay decision and should remain configurable/TBD rather than silently becoming a hard rule.

## 11. Weekly activity reports

- Straja members periodically submit an activity report through the Secretary.
- Current intended interval: **7 real days**, configurable.
- The player should receive/use a tangible RP form/book where appropriate, but the submitted report must also be stored authoritatively by the system so it cannot disappear with an item.
- Report content should cover activity/missions, incidents/problems and notes for the Comisar.
- The Comisar can review submitted reports.
- Review outcomes should include at least:
  - approved/accepted;
  - returned / needs additions;
  - called in to see the Comisar.
- If something is wrong, the member can be explicitly summoned to the Comisar through the Secretary workflow.
- Whether an overdue/returned report blocks starting a new duty shift should be a configurable policy rather than an unchangeable hard-coded behavior.

## 12. Audience requests

At the Secretary, an authorized member can request an audience with the Comisar.

- The request includes the requesting player's stable identity and a reason/message.
- Requests persist across logout/restart until resolved.
- If the Comisar is online, they may receive a notification.
- The Comisar can review and resolve requests through the Secretary/admin UI.

## 13. Missions

Missions should be fast to issue through templates rather than requiring the Comisar to invent every field and reward manually.

General rules:

- The Comisar (and any other ranks explicitly granted permission) can assign missions to Straja personnel.
- Members can inspect their own active mission(s) through the Secretary or another appropriate NPC/UI.
- Mission assignment, status and reports are persistent.
- Mission templates/types are configurable and can be created, edited, duplicated, enabled or disabled from the in-game administration UI.
- Rank/permission requirements for assigning and receiving missions are configurable.
- A mission reward is a **completion bonus** and is separate from normal hourly duty salary. If the member is also on qualifying duty while doing the mission, normal salary continues independently.
- Mission rewards are calculated/stored internally in the lowest currency unit (Bronze-equivalent value) and converted to the 64-based physical coin ladder only when paid.

### Mission budget model

The default recommended mission reward is derived from the same wage ladder as salaries so rewards remain economically consistent when salaries are changed.

Recommended per-participant reward:

`hourly wage of template minimum rank × expected duration in hours × mission reward multiplier`

The reward is based on the **template minimum rank**, not the assignee's personal rank. This keeps the same mission predictably priced even when a higher-ranking member accepts it.

For team missions:

- the template defines a maximum participant count;
- the UI shows the recommended **reward per participant** and **maximum total budget** before issuance;
- maximum total budget = per-participant reward × maximum paid participants;
- only qualifying participants who actually complete/earn the mission reward are paid;
- unused reserved budget is released;
- reward delivery must be idempotent so reconnect/retry cannot duplicate payouts.

Default reward multipliers:

- **Routine:** 0.50×
- **Standard:** 1.00×
- **Risky:** 1.50×
- **Critical / emergency:** 2.00×

All multipliers are configurable.

### Default mission templates

These are starter templates, not a closed list:

| Template | Minimum rank | Expected time | Default multiplier | Recommended reward / participant |
| --- | --- | ---: | ---: | ---: |
| Verificare / livrare / prezență | Stagiar | 15 min | 0.50× | 2 Bronze |
| Escortă / patrulă suplimentară | Străjer | 30 min | 0.75× | 9 Bronze |
| Investigație / urmărire / recuperare probe | Străjer | 60 min | 1.00× | 24 Bronze |
| Reținere / mandat / capturare țintă | Străjer | 30 min | 1.50× | 18 Bronze |
| Operațiune cu risc ridicat | Sergent | 60 min | 1.50× | 54 Bronze |
| Operațiune specială condusă de Inspector | Inspector | 60 min | 1.50× | 96 Bronze = 1 Brass + 32 Bronze |
| Urgență majoră / criză | Inspector | 60 min | 2.00× | 128 Bronze = 2 Brass |

The administration UI should allow the issuer to choose a template and then fill only the mission-specific data, for example:

- target/player/entity if applicable;
- location/area;
- objective text;
- deadline;
- number of participant slots;
- optional notes/evidence/context.

The template should prefill minimum rank, expected duration, reward multiplier, recommended reward and sensible deadline defaults.

A **Custom mission** option should exist for unusual cases. The system should still calculate and display a recommended budget from chosen minimum rank, expected duration and risk multiplier. Authorized issuers may override the recommendation within configurable limits; unusually high overrides should require confirmation/reason text and remain visible in audit/history.

Mission templates may also define non-cash rewards, required equipment, prerequisite specialization, automatic report requirements or whether the mission temporarily supersedes normal patrol routing.

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

Experienced-personnel override is an explicit use case: the Comisar can authorize a known experienced player directly at a higher rank without making them fake a beginner recruitment path.

## 15. In-game configurability

Strong preference: normal configuration should be possible from NPC/admin menus in-game.

At minimum the system should aim to make these editable without recompiling the mod:

- rank display names/order and rank permissions;
- salary rates/multipliers per rank;
- base hourly wage / salary accounting granularity;
- coin/item IDs, denomination order and conversion ratios;
- checkpoint wait/unlock and deadline values;
- checkpoint locations/routes;
- recruitment quiz questions/answers/cooldowns;
- Receptionist/Recruiter/Trainer/Secretary dialogue and documents;
- report interval and report policies;
- mission templates, expected durations, reward multipliers, participant limits and mission permissions;
- equipment/kit rules where applicable;
- role assignment for NPCs where practical.

Commands and config files may remain as operator fallback, emergency recovery and low-level setup, but should not be the preferred day-to-day Commissioner workflow.

## 16. Explicit non-goals / postponed decisions

- Jailer/item-dependent custody expansion is postponed for now.
- Current default hourly salaries are fixed in this design document at 16 / 24 / 36 / 64 / 128 Bronze for Stagiar / Străjer / Sergent / Inspector / Comisar, while remaining configurable in-game.
- Exact promotion thresholds and exams between Stagiar -> Străjer -> Sergent -> Inspector are not finalized.
- Anti-AFK salary behavior is not finalized.
- Exact UI layout and technical persistence architecture are implementation choices, provided the gameplay invariants above are met.

## 17. Implementation rule for agents

When code and this document disagree on newly discussed gameplay behavior, treat this document as the design target and update implementation/tests accordingly. Do not infer new gameplay rules from an old implementation detail without confirming them first.
