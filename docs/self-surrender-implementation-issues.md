# Predarea voluntară — implementation issues

**Goal:** a wanted player can turn themselves in by clicking a chat button
(or `/straja predare`). The institution reads their outstanding offences
and resolves them without any human officer: money offences cost a
penalty, violent offences cost prison time — mitigated for volunteering.

## Design

- `SurrenderService` (headless, Minecraft-free) owns the decision:
  assess → resolve. The command and the chat action dispatch call the
  concrete service, same convention as `FriskService`.
- **Eligibility:** wanted per `WantedService` truth (live BOLO, register
  FUGITIVE, unexpired legacy mark), online, not already restrained,
  escorted or jailed. Policy gate `surrender.enabled`.
- **Assessment** maps wanted sources to offence kinds:
  - *Violent:* any active BOLO that does not trace to a payable
    `linkedFineId`, any register FUGITIVE, any legacy mark — marks whose
    cause is not a collectable debt.
  - *Debt:* outstanding fines (status ISSUED) including fines behind
    `linkedFineId` BOLOs.
- **Penalty path** (debt only): withdraw the outstanding total through
  `FineService.settleAllFor` — insufficient balance refuses and the
  player stays wanted. Settled fines resolve the marks that priced them.
- **Prison path** (any violent mark): institutional cuffs
  (`straja:system` issuer) then `PrisonService.arrestNearest` — the same
  booking path a patrol uses, so cells, waitlist and mark resolution are
  inherited. Waitlisted prisoners stay cuffed until a bunk frees, as
  with patrol holds.
- **Mitigation:** voluntary surrender discounts the sentence by
  `surrender.discountPercent` (default 25%) — turning yourself in is
  always cheaper than being dragged in.
- **Notice surface:** a once-per-second tick re-notices every wanted
  online player with `[Predă-te]` at most once per
  `surrender.offerSeconds` — every mark source surfaces the offer
  without touching mark write paths. `/straja predare` (alias
  `/straja surrender`) is the typed fallback. The `surrender` action
  registers in `PARAMETERIZED_ACTIONS`, gates in
  `isStillValidForPlayer` (wanted at click time), and dispatches in
  `performParameterizedAction`.

## Issues

| # | Issue | Status | Commit | Notes |
|---|-------|--------|--------|-------|
| SURR-1 | Self-surrender: chat button + `/straja predare`, offence assessment, penalty-or-prison resolution, mitigation | done | b850cbc | Adversarial review remediated: dead `surrender` alias (Brigadier merge kept the bounty capture-flag — repointed, flag preserved as `/straja bounty surrender`); debt classifier traces the links producers actually write (linkedFineId / linkedArrestTaskId→task.fineId / linkedIncidentId→incident.linkedFineId); `settleAllFor` routes through the DEBT-1 ledger, gates `finesEnabled`, flags partial debits PAYMENT_REVIEW; offer tick inside the 1Hz gate on a `wantedUuids()` snapshot |

## Execution receipt

| Issue | Commit | State |
|---|---|---|
| SURR-1 | b850cbc | done — 11/11 service tests, 48/48 live GameTests (`surrenderBooksViolentOffender` proves cuff→book→resolve with clemency pricing) |
