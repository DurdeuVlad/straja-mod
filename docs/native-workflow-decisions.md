# Native Straja workflow decisions

This document captures the gameplay decisions that replace the temporary CustomNPCs scripting plan. The native mod is the only authoritative implementation.

## Identity and authorization

- Being in the scoreboard team `Straja` does **not** make a player an authorized guard.
- Authorization is persistent Straja state, keyed by UUID.
- An authorized member has a service number and a rank.
- Normal authorization flow: Receptionist -> application -> Recruiter -> recruitment quiz -> Străjer Junior.
- The Commissioner may explicitly authorize experienced personnel directly at a chosen rank (for example a former captain).
- Revocation removes Straja authority immediately and ends any active duty.

## Ranks

Gameplay rank labels:

1. Străjer Junior
2. Străjer
3. Străjer Senior
4. Căpitan

The internal level-4 constant may remain compatibility-oriented, but the player-facing rank is Căpitan.

## Faction / scoreboard team behavior

- A guard may permanently belong to another scoreboard faction/team.
- On duty start, the current team is remembered and the player is temporarily moved to `Straja`.
- On voluntary duty end, forced duty end, timeout, suspension, firing, resignation, or recovery after login, the previous team is restored.
- If a player had no team before duty, they return to no team.
- Authorization is never derived from the scoreboard team.

## NPC responsibilities

### Receptionist

- Civil-facing intake point.
- Explains rules and recruitment requirements.
- Opens/records an application.
- Provides the recruitment paperwork / instructions.
- Directs the applicant to the Recruiter.
- Does not automatically recruit the applicant.

### Recruiter

- Only handles applicants with a valid application/invitation state.
- Runs the full recruitment quiz.
- Wrong answers use the configured cooldown.
- Passing the full recruitment quiz grants authorization and rank Străjer Junior.
- Assigns a persistent unique service number.

### Trainer

- Handles post-recruitment training modules and rank examinations.
- Does not create membership by itself.
- Promotion eligibility remains server-authoritative.

### Secretary

- Primary guard administration NPC.
- Starts normal duty.
- Ends normal duty voluntarily.
- Pays accumulated salary.
- Shows mission status and accepts audience requests.
- Manages weekly activity reports.
- Gives the Commissioner access to personnel/administrative flows.

### Jailer

- Deferred for now; existing implementation stays untouched until item/content dependencies are ready.

## Duty lifecycle

- A normal duty may only be started through the Secretary NPC in ordinary player gameplay.
- A normal duty may only be voluntarily ended through the Secretary NPC.
- Console/test/admin hooks may still call the underlying service directly for testing and recovery.
- Duty uses the configured four-checkpoint route.
- The first checkpoint is active immediately.
- After reaching a checkpoint, the next checkpoint unlocks after the configured wait period (default 10 minutes).
- Once unlocked, the player has the configured deadline (default 30 minutes) to reach it.
- Missing the deadline ends duty automatically.
- Reaching checkpoint 4 completes a **patrol round**, not the whole duty. The route loops back to checkpoint 1 and duty continues.
- Voluntary duty end happens at the Secretary.

## Offline / anti-abuse rules

- Offline time is never paid.
- Checkpoint deadlines use wall-clock time and continue while the player is offline.
- Logging out therefore cannot freeze a checkpoint deadline.
- On login, if the deadline expired while offline, duty is closed immediately and the home faction is restored.
- If the deadline did not expire, duty resumes with the remaining time.

## Salary

Salary is accrued only while the player is online, authorized, and on duty.

Current intended rates per Minecraft day of paid duty time (20 minutes):

- Străjer Junior: 28 bronze
- Străjer: 32 bronze
- Străjer Senior: 48 bronze
- Căpitan: configurable separately; until decided, default to the Senior rate rather than inventing a larger value.

Economy convention:

- 64 bronze = 1 silver.
- Payout remains physical-item based and must preserve idempotent/retry-safe behavior.

## Activity reports

- Every authorized guard must submit an activity report every 7 real days.
- The report is reviewed by the Commissioner.
- The Secretary is the submission/review access point.
- A report can be accepted, returned for additions, or flagged so the guard is called to the Commissioner.
- A due/returned report may block starting a new duty, but must not destroy already-earned salary.
- Report identity is UUID-based, not name-based.

## Audience requests

- A guard can request an audience with the Commissioner through the Secretary.
- Requests are persistent and UUID-backed.
- The Commissioner can review/resolve them from the Secretary/admin flow.

## Missions

- The Commissioner can issue missions to guards.
- Existing mission infrastructure remains the source of truth; Secretary interaction should expose it rather than create a parallel mission store.

## Display names / rank prefixes

- Rank display must be independent of scoreboard faction/team.
- Chat/tab/name formatting should derive from authoritative Straja state where practical.
- Do not use scoreboard team membership as the source of rank or authorization.

## Architecture

- No hidden scripted NPC/orchestrator is required in the native mod.
- The server event loop and application services are the orchestrator.
- Business rules remain in domain/application services; Minecraft/NeoForge adapters perform team switching, item/book delivery, NPC interaction, and rendering only.
- UUID is authoritative for player identity.
- Existing audit, idempotent economy, migration, and test-mode invariants must be preserved.
