# Devin Handoff Prompt

Copy the following as the implementation task for Devin:

```text
You are taking over the RusticCraft Straja Mod workspace at
C:\Users\Vlad\Documents\Github\RusticCraft Straja Mod.

Read docs/player-roleplay-implementation-issues.md completely and loop over
RP-001 through RP-012 in priority order. Implement the issues directly in the
workspace, testing after each coherent slice. Do not merely report suggestions:
make the code changes, add/update tests, run the build, and keep the issue
document and parity documentation accurate.

Product boundary is non-negotiable:

1. Admin operations such as `/straja backup`, setup, migration, NPC registry
   administration, diagnostics, test tooling, and rank administration remain
   OP 3/OP 4 typed commands. `/straja backup` must remain an OP 3 admin command;
   do not replace it with an NPC button.
2. Player/roleplay actions must be reachable through native Straja NPCs,
   physical Straja items, native forms, or clickable chat opened by those
   surfaces. Do not require ordinary players to type gameplay commands.
3. NPC clickable chat may internally run `/straja npc-action <opaque-token>`,
   but tokens must be short-lived, one-use, player-UUID-bound, and revalidated
   against current persisted state. Never put typed gameplay commands or
   untrusted authorization data into the click.
4. Existing application services remain authoritative. Adapters translate
   events/UI into service calls; they must not duplicate capability, location,
   identity, status, budget, or persistence rules.

Start by checking the current worktree and preserving unrelated user changes.
Do not use git reset --hard, git checkout, or broad destructive cleanup. The
worktree may already contain changes to the release workflow, README, docs/media,
command gating, NPC tokens, persistence/recovery, room/custody hooks, and tests.

Critical implementation order:

- First build the native server-authoritative form/session layer. It needs an
  owner UUID, allowlisted action ID, expiry, bounded field length, opaque
  session ID, atomic one-use consumption, owner-checked cancel, server-thread
  payload handling, and a client EditBox/MultiLineEditBox screen. Support
  mission reports, mission failure reasons, quiz answers, complaint submission
  fields, investigation reports, withdrawal reasons, appeals, and other
  bounded text flows without chat capture or writable-book workarounds.
- Then wire the receptionist recruitment/training quiz to the real persisted
  GuardService state; the client must not select the question or expected answer.
- Then complete secretary mission flows: invited secondary join, accept/decline,
  report form, voluntary failure form, issuer completion, order-book draft and
  issue actions, reward claim/recovery, stale-button handling.
- Then complete receptionist complaint flows: multi-field submission,
  investigator assignment/report, complainant confirm/withdraw reason, and all
  status/location/capability checks.
- Then complete fine and officer task flows through fine_book/fine_notice and
  the appropriate NPC: drafts, issue, pay/refuse, appeals, review, task
  accept/complete/refuse/arrest/warrant, and pending reward/payment recovery.
- Then close custody, prison, head-sack, and room item/NPC flows with explicit
  state validation and restart/logout/death recovery.
- Then finish archive folder/document, carbon paper, stamp, envelope, and
  order-book item workflows. Treat item metadata as an untrusted hint.

For every action, inspect the current persisted aggregate immediately before
mutation and fail closed on missing records, wrong UUID/name provenance,
expired status, wrong location, missing capability, malformed metadata, or
duplicate submission. Reuse the existing services and audit records.

Verification loop for every slice:

- add focused Minecraft-free unit tests where possible;
- add NeoForge/GameTest or integration coverage for event hooks and forms where
  mocks cannot prove registration or client/server behavior;
- run `./gradlew.bat test --rerun-tasks`;
- run `./gradlew.bat build`;
- run `git diff --check`;
- if `mct` is installed, run live client/RCON UAT for NPC clicks, forms, item
  use, stale actions, reconnect recovery, and `/straja backup`. If it is not
  installed, state that exact limitation and do not claim live UAT passed.

Keep docs/player-roleplay-implementation-issues.md as the checklist. Update
README.md and docs/parity-matrix.md only after behavior and tests support the
new status. Remove stale “form needed” or “no custom GUI” caveats once the
native form is actually shipped.

At the end, review the full diff and return:

- files changed;
- each RP issue status and evidence;
- exact test/build/lint/UAT commands and results;
- unresolved caveats and their impact;
- a final verdict of PASS, PASS WITH CAVEATS, or FAIL.
Do not claim completion if a P0 issue is still unreachable or unverified.
```
