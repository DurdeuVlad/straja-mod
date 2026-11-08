# Corupție — denunț și arestare — implementation issues

**Goal:** corrupt guards are accountable inside the institution, not by
admin decree. A bribe a guard accepts leaves a money trail; anyone can
report them; a report that finds the trail produces a warrant — and the
same patrol pipeline that arrests anyone arrests them.

## Design

- `CorruptionService` (headless, Minecraft-free) owns the rule:
  report → trail check → warrant.
- **The money trail** is the append-only audit log, not a new store:
  every accepted bribe already writes `PRISON_BREAK … corrupt_guard
  price=N`; the service additionally records a first-class `CORRUPTION`
  audit entry (`actor=prisoner, target=guard, amount=N, witnessed=`).
  `trailFor(guardUuid)` scans `ctx.audit()` for entries naming the guard
  — "caught with the money" is an evidence read, not an accusation.
- **Witnessed exchange** (`bribeAnswer` accept with a second guard in
  `witnessNear` range) marks immediately — the institution saw the
  money move.
- **`/straja denunt <player>`** (perm 0, cooldown
  `corruption.reportCooldownSeconds`) files an accusation: if the audit
  trail holds bribe income for the accused, `bolos.flagSystem` issues
  the corruption warrant (`reason: corupție`) and the patrol arrests
  them like any wanted player. No trail → the report files as
  unproven; false accusations cost nothing but time.
- **Self/civilian guards:** self-reports refuse; the accused must be
  personnel (rank > 0, on-duty guard, or commissioner) or carry a bribe
  trail — no marking random players for corruption.
- **Idempotent:** `flagSystem` does not stack marks on an already
  wanted guard.
- Audit: `CORRUPTION` action on the money record, the report, and the
  warrant — the whole chain is readable in the institutional ledger.

## Issues

| # | Issue | Status | Commit | Notes |
|---|-------|--------|--------|-------|
| COR-1 | Bribe money-trail record + witnessed auto-mark + `/straja denunt` report→trail→warrant | done | `a8f56e4` | 13 service tests, 49/49 GameTests |

## Execution receipt

| Issue | Commit | State |
|---|---|---|
| COR-1 | `a8f56e4` | done — planned `eebddc7`, implemented, adversarially reviewed (1 P0, 4 P1s remediated), re-verified: 13/13 CorruptionServiceTest, full unit suite, 49/49 live GameTests (`denuntFindsTheMoneyTrail`) |

### Review findings remediated

- **P0:** `witnessNear`/`nearestGuard` excluded the involved player by
  `==` reference — production gateways are re-minted per call, so every
  bribe self-witnessed. Now compares uuid identity; the payer is also
  excluded from the witness pool.
- **P1:** one bribe counted twice (new `kind=bribe` + legacy
  `corrupt_guard` twin) — `trailFor` dedupes by same-tick pairing.
- **P1:** `/straja denunt` only reached online suspects — now resolves
  offline guards by uuid or by the name on the money trail.
- **P1:** `corruption.enabled=false` still issued witnessed warrants —
  the mark is now policy-gated (the ledger record stays).
- **P1:** "Mandat emis" was claimed before the warrant existed —
  `mark()` returns the outcome; bolos-off reports file as
  `trail_found`.
- **P2:** witness radius decoupled to `corruption.witnessRadius`
  (default 8.0); unproven reports audit as `PENDING` not `REFUSED`;
  `CORRUPTION` constant re-alphabetized.

### Accepted limitations

- Report cooldown defaults to 300s (anti-spam, tunable via
  `corruption.reportCooldownSeconds`); refused `not_staff` reports do
  not consume it, so name-guessing can't lock out a real report.
- `audit.enabled=false` silently degrades the feature (no trail → all
  reports unproven) while witnessed marking still works — audit and
  corruption are independent toggles by design.
- An already-wanted guard re-flagged by a report reuses the active
  BOLO; the `kind=warrant` audit line still records each substantiated
  report as its own event.
