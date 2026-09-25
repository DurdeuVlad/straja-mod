# V1/V2 parity and cutover

| Area | V1 compatibility | V2 authority | Current bridge |
|---|---|---|---|
| Personnel | `GuardState` rank/lifecycle | `PersonnelRecord` | login projection; V2 wins once present |
| Commissioner | configured UUID/name bootstrap | appointment on personnel record | configured identity only creates bootstrap appointment |
| Promotion | legacy quiz/service blocks | application + evidence + versioned approval | Instructor submits; approval is V2 |
| Documents | physical books/cards | document and instrument stores | items are presentation/projection only |
| Equipment | inventory and kits | obligation/issue/return ledger | legacy owned items can be recorded, not trusted for authority |
| Missions | legacy roleplay missions | typed lifecycle + settlement | V1 remains readable while V2 missions use typed paths |
| Campaigns | none | campaign/quota stores | station and authority checks at create/start/end |
| Settlements | direct legacy rewards | exact-once settlement store | pending payouts retry on login |
| Discord | none | persistent redacted outbox | optional async webhook sender |

Migration is deliberately additive. New writes use V2 services for migrated
paths; old records are projected on login and remain readable until the world
has completed its cutover window.
