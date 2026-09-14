# DC-001 — Downed and custody domain contract

`CustodyStore.states` is the canonical per-player aggregate for the downed /
custody feature. `CustodyTransitionEngine` is pure: adapters resolve authority,
items, entities, effects, messages, and persistence, then submit a transition.
The pre-existing `cuffed`, `bound`, `headSacks`, and `downed` maps remain
compatibility projections for the shipped RP-007 implementation and migration;
they are not the contract for new flows.

## Independent dimensions

| Dimension | Values | Meaning |
| --- | --- | --- |
| Condition | `ALIVE`, `DOWNED`, `RESUSCITATING`, `UNCONSCIOUS_CUSTODY`, `CONSCIOUS_RESTRAINED`, `DEAD` | Physical/control state |
| Custody | `FREE`, `HOSTAGE`, `ARRESTED`, `JAILED` | Legal/social context |
| Transport | `NONE`, `CARRIED` | Physical movement only; never implies rescue, death, or arrest |
| Restraint | `NONE`, `ROPE_BOUND`, `CUFFED` | Physical restraint; rope maps to hostage, cuffs to arrest |
| Vision | `NORMAL`, `BLINDFOLDED` | Visibility effect only |

Every control-removing transition records an absolute deadline or an explicit
resolution. The state also retains provider/source provenance, entry time,
deadlines, carrier, restraint and custody actors, destination, and the last
accepted transition identity.

## Executable transition rules

| Transition | Valid precondition and outcome |
| --- | --- |
| `ENTER_DOWNED` | Free, alive, unrestrained target → `DOWNED` with downed deadline |
| `START_RESUSCITATION` | Active `DOWNED` target before deadline → `RESUSCITATING` with timeout |
| `ADVANCE_RESUSCITATION` | Monotonic progress before timeout; required progress → `ALIVE` |
| `APPLY_ROPE` | Free alive/downed target and another actor → `ROPE_BOUND` + `HOSTAGE`; downed target becomes `UNCONSCIOUS_CUSTODY` |
| `APPLY_CUFFS` | Free alive/downed target and another actor → `CUFFED` + `ARRESTED`; downed target becomes `UNCONSCIOUS_CUSTODY` |
| `START_CARRY` | Non-dead, not already carried target with a carrier → `CARRIED`, preserving all other dimensions |
| `STOP_CARRY` | Carried target → `NONE`; carrier and transport deadline cleared |
| `APPLY_BLINDFOLD` / `REMOVE_BLINDFOLD` | Restraint and configured application/removal permission required |
| `WAKE` / `RESOLVE_UNCONSCIOUS_DEADLINE` | Deadline passed → walking condition; physical restraints remain after unconscious custody |
| `DELIVER_TO_JAIL` | Arrested target plus non-empty destination → `JAILED`; carried status is cleared |
| `REVIVE_IN_JAIL` | Jailed unconscious target after configured revival time → conscious condition |
| `RELEASE_RESTRAINT` | Another actor only; self-removal fails closed and custody is released with the physical restraint |
| `RELEASE_CUSTODY` | Another actor, no physical restraint, non-free/non-jailed custody; unconscious target returns to downed with a new deadline |
| `DIE` | Any live state → `DEAD`, then configured death recovery is applied |

Replaying the same non-empty transition id is a successful no-op. A different
id that violates the current state is rejected; this prevents retries from
applying a second transition after persistence or reconnect.

## Policy boundary

The authoritative domain defaults live in `StrajaPolicies`. They are exposed
through the existing server TOML path as `[custody]` and `[recovery]`, and
through the runtime `PolicyRegistry` under `custody.*` and `recovery.*`.
Invalid transition behavior is fail-closed; adapters must still enforce their
capability, item, identity, world, and repository rules before calling the
engine.
