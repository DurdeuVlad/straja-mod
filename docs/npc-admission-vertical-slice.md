# CustomNPCs admission vertical slice

M3 makes the first player-facing journey a real CustomNPCs GUI flow while
keeping admission and training authority in Straja.

## Product boundary

The canonical sequence is:

1. The player opens the Receptionist NPC GUI.
2. `application-submit` calls `GuardRecruitmentUseCase.applyForStraja`.
3. The persisted `GuardState.applicationState` changes to `APPLIED`.
4. The player opens the Instructor GUI and selects `recruit`.
5. Straja selects and persists the next quiz question.
6. The GUI shows the question and opens a bounded answer input.
7. The hidden `question-id` input is submitted with the answer. Straja
   compares it with the current persisted question before accepting progress.
8. The final correct answer performs the existing atomic promotion to
   `AUTHORIZED`/`Stagiar`; the existing reward and room-assignment services
   remain authoritative.

The provider only renders the current `NpcSurfaceSnapshot`, collects bounded
input, mints a player/binding/action token, and submits an untrusted
`NpcActionRequest`. It does not own application state, quiz state, quest
completion, rank, rewards, or permissions.

## Setup

An administrator binds the external CustomNPCs entity UUID to the admission
surface:

```text
/straja npc bind-custom <customnpcs-entity-uuid> receptionist hq
/straja npc bind-custom <customnpcs-entity-uuid> trainer hq
```

The `recruiter` role is accepted as a compatibility alias and maps to the
same Instructor surface. Binding is explicit and durable; an unbound
CustomNPCs NPC keeps its native behavior.

## State reconstruction

The published profile is static canonical content. On each interaction the
CustomNPCs adapter resolves a player-specific projection:

- Reception shows `NONE`, `APPLIED`, `INVITED`, `AUTHORIZED`, suspended, or
  fired status and disables duplicate application.
- Instructor shows the current server-selected question, enables answer only
  when a question exists, and marks the admission quest `ACTIVE` or
  `COMPLETED` from persisted Straja state.
- Reopening the GUI after a relog or restart reads the saved state again; no
  provider quest record is used as authority.

The text provider remains available for contract tests and diagnostics. It is
not the normal player-facing lane.

## Verification

Unit coverage proves application-state projection, hidden question binding,
bounded input descriptors, completed-quest reconstruction, profile loading,
provider token ownership, and action-boundary validation. A disposable live
server/client exercise with the exact CustomNPCs artifact remains a release
acceptance requirement because this repository does not yet contain a
populated external-NPC world fixture.

StoryNPC remains deferred. It can implement the same provider port and consume
these canonical profiles without changing the admission services or saved
state.
