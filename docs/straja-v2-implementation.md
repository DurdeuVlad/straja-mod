# Straja V2 implementation status

The V2 foundation is implemented incrementally beside the V1 compatibility
surface. The authoritative records are SavedData-backed JSON stores; Minecraft
items, NPCs, scoreboard teams, and GUI tokens are projections or transport
boundaries only.

Implemented foundations:

- personnel records with separate military/professional careers, employment,
  appointments, affiliations, optimistic versions, and legacy projection;
- central contextual authorization with station, jurisdiction, appointment,
  conflict, stale-state, and mobilization checks;
- typed promotion applications and immutable qualification evidence;
- multi-station records with safe fallback-cycle validation;
- server-backed personalized documents and fungible instruments with
  idempotent redemption;
- line-level equipment issues and persistent return/loss/debt obligations;
- specialist mobilization with time-bounded temporary authority;
- typed mission lifecycle, campaign quota reservations, and settlement keys;
- persistent operation journal and redacted allowlisted outbound outbox;
- aggregate-local outbound intents with server-thread projection and bounded
  asynchronous delivery;
- schema metadata on all new stores and backup coverage for every production
  SavedData store.

V1 `GuardState`, commands, NPC action tokens, and physical items remain
compatible during migration. The legacy promotion surface delegates to the V2
promotion application when the runtime is using the V2 composition root; unit
and compatibility contexts retain their existing behavior until cutover.

The V2 composition root, native command surfaces, physical-item projections,
provider-neutral CustomNPC GUI/dialogue/quest surfaces, restart recovery, and
release checks are wired. V1 services that still contain
legacy rank predicates are compatibility paths; they do not authorize V2
records, documents, equipment, missions, campaign quota, settlements, or
outbox delivery. Those paths remain intentionally additive until the cutover
window removes the legacy projection.

The automated suite is the current verification boundary for this foundation.
Real Minecraft server/client acceptance is deliberately deferred until the
final NPC milestone; this checkpoint does not claim that live-server gate.
