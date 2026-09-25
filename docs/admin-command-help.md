# Administrator command help

The commands below are OP3 unless marked OP4. The command layer is only an
adapter; authorization, station scope, version checks, and idempotency are
enforced by the application services.

## V2 groups

| Group | Purpose |
|---|---|
| `/straja personnel` | Roster, dossiers, authorization, suspension, reinstatement and termination |
| `/straja promotion` | Submit, attach evidence, readiness, approval and rejection |
| `/straja document` | Inspect/revoke documents and issue/redeem instruments |
| `/straja equipment ledger` | Inspect authoritative equipment obligations |
| `/straja mobilization` | Inspect, muster and end specialist mobilizations |
| `/straja campaign` | Inspect, start/end campaigns, reserve quota, fulfill delivery and release reservations |
| `/straja settlement` | Inspect and place ambiguous payouts in review |
| `/straja station` | Inspect and validate station fallback configuration |
| `/straja outbox` | Inspect, retry and list dead-letter outbound events |

## Doctor

`/straja doctor consistency` runs every read-only invariant check. The scoped
forms `/straja doctor operations`, `equipment`, `settlements`, `stations`, and
`outbox` limit the report to one operational area. Reports intentionally omit
webhook secrets, evidence bodies, coordinates, and internal approval details.

## Compatibility

Existing V1 commands remain available as compatibility aliases. A V1 command
may update the legacy projection, but migrated sensitive paths first consult
the V2 personnel record when one exists. Physical items, NPC names, scoreboard
membership, and command visibility never grant authority.
