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
| COR-1 | Bribe money-trail record + witnessed auto-mark + `/straja denunt` report→trail→warrant | pending | | |

## Execution receipt

| Issue | Commit | State |
|---|---|---|
| COR-1 | | |
