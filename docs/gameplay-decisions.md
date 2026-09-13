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

Currency decision:

- **64 bronze coins = 1 silver coin**.

Salary is earned for actual qualifying online duty time and accumulates as an unpaid balance. It is claimed/paid through the Secretary rather than automatically dropping a coin every interval.

Current field-rank defaults per one Minecraft-day equivalent of qualifying duty time (20 minutes):

- Stagiar: **28 bronze**
- Străjer: **32 bronze**
- Sergent: **48 bronze**
- Inspector: configurable / not yet finally fixed
- Comisar: configurable / not yet finally fixed

Rules:

- Default paid duty day: **20 minutes** of qualifying online duty time.
- Offline time contributes zero salary progress.
- Partial progress toward the next paid block/day is preserved when a voluntary shift ends; a player should not lose legitimate partial progress by clocking out.
- Earned but unclaimed salary is preserved.
- Salary rates, paid-time interval and currency item IDs/denominations must be configurable.
- Inventory/payment failure must not silently destroy the player's unpaid balance.

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

- The Comisar (and any other ranks explicitly granted permission) can assign missions to Straja personnel.
- Members can inspect their own active mission(s) through the Secretary or another appropriate NPC/UI.
- Mission assignment, status and reports are persistent.
- Mission templates/types should be configurable instead of hard-coded to a tiny fixed list.
- Rank/permission requirements for assigning and receiving missions should be configurable.

## 14. Commissioner / administration UX

The Comisar should have an administrative interface through appropriate NPCs, especially the Secretary.

Expected areas include:

- personnel list / dossiers;
- authorization and revocation;
- rank changes;
- active-duty personnel;
- activity reports;
- audience requests;
- missions;
- relevant gameplay configuration.

Experienced-personnel override is an explicit use case: the Comisar can authorize a known experienced player directly at a higher rank without making them fake a beginner recruitment path.

## 15. In-game configurability

Strong preference: normal configuration should be possible from NPC/admin menus in-game.

At minimum the system should aim to make these editable without recompiling the mod:

- rank display names/order and rank permissions;
- salary rates per rank;
- paid duty interval;
- coin/item IDs and denominations;
- checkpoint wait/unlock and deadline values;
- checkpoint locations/routes;
- recruitment quiz questions/answers/cooldowns;
- Receptionist/Recruiter/Trainer/Secretary dialogue and documents;
- report interval and report policies;
- mission templates and mission permissions;
- equipment/kit rules where applicable;
- role assignment for NPCs where practical.

Commands and config files may remain as operator fallback, emergency recovery and low-level setup, but should not be the preferred day-to-day Commissioner workflow.

## 16. Explicit non-goals / postponed decisions

- Jailer/item-dependent custody expansion is postponed for now.
- Exact Inspector and Comisar salary defaults are not finalized.
- Exact promotion thresholds and exams between Stagiar -> Străjer -> Sergent -> Inspector are not finalized.
- Anti-AFK salary behavior is not finalized.
- Exact UI layout and technical persistence architecture are implementation choices, provided the gameplay invariants above are met.

## 17. Implementation rule for agents

When code and this document disagree on newly discussed gameplay behavior, treat this document as the design target and update implementation/tests accordingly. Do not infer new gameplay rules from an old implementation detail without confirming them first.
