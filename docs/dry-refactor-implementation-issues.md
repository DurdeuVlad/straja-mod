# Single-source refactor campaign — milestone & implementation issues

**Outcome:** every rule, literal, vocabulary, and codec in the mod is defined
in exactly one place. The campaign's only philosophical rule:

> **You may modify something in only one place.** If a change forces you to
> edit two files to stay consistent, the thing being changed lives in the
> wrong place — consolidate it first, then make the change.

This is DRY applied as an architectural gate, not a style preference. Each
issue below cites the counted duplication it removes; each acceptance
criterion is grep-verifiable ("the definition exists exactly once").

**Audience:** agents and contributors working this repo. Behavior must not
change unless an issue says so; the existing JUnit suite is the regression
boundary and must stay green after every issue.

---

## Duplication survey — evidence baseline (HEAD `ab3c1a0` + working tree)

| # | Knowledge | Lives today in | Sites |
|---|---|---|---|
| D1 | petition/decision text cap (500 chars) | `REASON_LIMIT` constant ×2 (`JoinRequestService:23`, `PromotionService:20`); literal `500` in field defs (`NpcRoles:492,551,1293,1307,1325,1395`, `NpcCivicSurfaceService:158,171`); trim+truncate idiom ×4 | ~10 |
| D2 | `<id>_<version>` composite review-key format | encode `NpcRoles:544,1390`; parse `FormSubmissionRouter:179-188,254-263` | 4 |
| D3 | approve/deny word vocabulary | verbatim dup in `FormSubmissionRouter:200-204,275-279`; divergent sets `ComplaintService:626`, `ReportService:192`, `AudienceService:158`; labels `NpcCivicSurfaceService:170`, `NpcSecretarySurfaceService:308` | 5+ |
| D4 | legacy `Rank` → `CareerGrade` mapping | `StrajaRuntime:310-314`, `GuardService:910-915`, `PersonnelService:396-398` | 3 |
| D5 | member rank/grade display name | canonical `StrajaPolicies.rankName(int|CareerGrade)`; competing impl `PromotionService.display()` (raw enum suffix — user-visible bug: outcome message prints `LOCOTENENT`, not configured name); `Rank.displayName()` fallbacks `NpcFaqSurface:35`, `NpcCareerSurfaceService:58,64` | 4 |
| D6 | uuid → display-name resolver | `NpcRoles.subjectName():1400`, `NpcPresentationRuntime.subjectName():402` — identical bodies | 2 |
| D7 | `"[Straja] "` chat prefix | hardcoded literal | 71 |
| D8 | `if (store.X == null) store.X = new LinkedHashMap<>()` | services + `SavedStores` | 26 |
| D9 | audit result/action vocabulary | `"SUCCESS"`, `"APPROVED"`, `"REJECTED"`, action names — stringly typed literals | 100+ |
| D10 | form field ids (`reason`,`note`,`decision`,`subject`,...) | implicit string contract between form producers (`NpcRoles`, surface services) and `FormSubmissionRouter` consumers | 2 sides |
| D11 | `if (audit != null) audit.record(...)` guard | `JoinRequestService`, `PromotionService`, `ComplaintEscalationService` | 3 |
| D12 | `trim().toUpperCase(Locale.ROOT)` + `toLowerCase(Locale.ROOT)` profession normalization | `JoinRequestService:54`, `NpcRoles:478`, `NpcCivicSurfaceService:154`, `NpcRoles:157,489` | 5+ |

### Known dual-truth systems (decision issues, not mechanical refactors)

- **D13 — member rank dual model:** legacy `GuardState.rank`/`Rank` and V2
  `PersonnelRecord.careerGrade` coexist via `v2PersonnelProjection` sync glue.
  The projection *is* a second source of truth by design during migration;
  eliminating it is the V1→V2 cutover decision, not a refactor.
- **D14 — user text dual system:** Romanian `Component.literal(...)` strings
  (e.g. `FormSubmissionRouter:214,219,289`, service `tell()` bodies) vs
  `translatable` keys (`straja.*` in `en_us.json`/`ro_ro.json`). Picking one
  layer per surface is a product decision (RO literals are canonical per
  README).

---

## Milestone DRY-1 — mechanical consolidations

**Outcome:** every literal/vocabulary/codec in D1–D8, D11–D12 has exactly one
definition. No behavior change except D5's user-visible naming fix.
**Proof:** per-issue grep checks show one definition site; `./gradlew test` green.

### Issue 1 — One petition-text cap and one `bounded` helper

- **Problem (D1, D12):** the 500-char cap is declared ~10 times; the
  trim-then-truncate idiom is copied 4 times; `NpcCivicSurfaceService` already
  has a private `bounded()` the services didn't reuse.
- **Intended behavior:** a single `domain.model` text type (e.g.
  `PetitionText` or `Text.cap(String, int)`) owns `PETITION_REASON_LIMIT = 500`,
  the trim+cap operation, and profession `normalize()`/`display()` casing.
  Both services, `NpcRoles`, and both NPC surface services call it.
- **Affected area:** `JoinRequestService`, `PromotionService`, `NpcRoles`,
  `NpcCivicSurfaceService`, `NpcSecretarySurfaceService`.
- **Non-goals:** changing the cap value; touching other form fields' limits.
- **Acceptance:** `grep -n "REASON_LIMIT\|= 500\|, 500," src/main/java` shows
  only the single definition; `substring(0, ...)` truncations exist once;
  all existing cap tests (`petitionReasonIsCappedAtServiceLimit`,
  `decisionReasonIsCappedAtServiceLimit`) still pass unmodified.
- **Verification:** `./gradlew test --tests '*JoinPetitionTest' --tests '*CareerRemodelTest'` + grep.
- **Deps:** none. **Owner:** unassigned. **Type:** refactor. **Milestone:** DRY-1.

### Issue 2 — One versioned-review-key codec

- **Problem (D2):** `requestId + "_" + version` is built in `NpcRoles` (2×)
  and hand-parsed in `FormSubmissionRouter` (2×, verbatim blocks); a format
  change requires 4 coordinated edits and the parse silently degrades to
  `expected=null` on malformed input.
- **Intended behavior:** a `ReviewRef`/`VersionedId` value type with
  `encode(id, version)` and `parse(raw) -> Optional<(id, version)>` used by
  all four sites.
- **Non-goals:** changing the wire format itself; provider-surface record ids.
- **Acceptance:** one encode + one parse implementation; both router blocks
  call it; stale-version refusals still fail closed (existing form tests +
  `staleReviewVersionFailsClosed` pass).
- **Verification:** unit test for the codec + existing suite.
- **Deps:** none. **Owner:** unassigned. **Type:** refactor. **Milestone:** DRY-1.

### Issue 3 — One review-decision vocabulary

- **Problem (D3):** the approve/deny synonym lists are duplicated verbatim
  inside `FormSubmissionRouter` and exist as three more *divergent* sets in
  `ComplaintService`, `ReportService`, `AudienceService` — adding a synonym
  (e.g. "refuz") today changes some surfaces but not others.
- **Intended behavior:** a single `ReviewDecision`/`DecisionWords` type maps
  a raw string → `APPROVE|DENY|RESOLVE|...`; every review surface consumes it;
  labels that hardcode "(aproba / respinge)" derive from it.
- **Non-goals:** changing which words are accepted per surface (keep current
  sets; the issue is *one place*, not *one set* — per-surface subsets are
  allowed but must be declared in the vocabulary type).
- **Acceptance:** word lists appear in exactly one file; the router's two
  inline lists are gone; decision parsing behavior unchanged per existing tests.
- **Verification:** new unit test on the vocabulary + existing suite.
- **Deps:** none. **Owner:** unassigned. **Type:** refactor. **Milestone:** DRY-1.

### Issue 4 — One legacy-Rank↔CareerGrade bridge and one grade-name source

- **Problem (D4, D5):** three switches encode `Rank → CareerGrade`;
  `PromotionService.display()` re-implements grade naming and shows players a
  raw enum suffix (`LOCOTENENT`) instead of the configured Romanian rank name
  — a real user-visible inconsistency in `deliverOutcome`; two more surfaces
  fall back to `Rank.displayName()` (English static names).
- **Intended behavior:** `CareerGrade.fromLegacyRank(Rank)` (or
  `Rank.careerGrade()`) is the only mapping; grade display flows through
  `StrajaPolicies.rankName(CareerGrade)` — `PromotionService` gets a name
  resolver injected (or the domain type gains no-config display only as the
  documented fallback inside `rankName`).
- **Non-goals:** retiring the legacy `Rank` enum (see DRY-3/D13); renaming
  config keys.
- **Acceptance:** the three switch tables collapse to one definition;
  `deliverOutcome` prints `Locotenent` (configured name) — assert in a test;
  `displayName()` fallbacks removed or documented as the single fallback path.
- **Verification:** `CareerRemodelTest` green + a new outcome-message test.
- **Deps:** none. **Owner:** unassigned. **Type:** refactor + small bugfix.
  **Milestone:** DRY-1.

### Issue 5 — One `"[Straja]"` message prefix

- **Problem (D7):** the brand prefix is a literal at 71 sites; renaming or
  styling it means 71 edits.
- **Intended behavior:** a single constant (on `PlayerGateway` or
  `StrajaPolicies`) — or better, `tell()` applies it — and call sites stop
  repeating it.
- **Non-goals:** changing message bodies; deciding literal-vs-translatable
  (D14 decision).
- **Acceptance:** `grep -c '"\[Straja\]' src/main/java` → 1 (the definition).
- **Verification:** suite green; spot-check one in-game message path.
- **Deps:** none. **Owner:** unassigned. **Type:** refactor. **Milestone:** DRY-1.

### Issue 6 — Store collections self-initialize

- **Problem (D8):** 26 copies of `if (store.X == null) store.X = new
  LinkedHashMap<>()` guard against Gson-built nulls; new collections keep
  re-learning the same lesson.
- **Intended behavior:** store classes expose `ensureX()`/accessor methods
  that lazily initialize (Gson bypasses field initializers, so a method — not
  a field default — is the correct single place).
- **Non-goals:** schema changes; touching serialization format.
- **Acceptance:** the guard idiom appears once per store (inside the model),
  not per service; store round-trip tests still pass.
- **Verification:** existing persistence tests + suite.
- **Deps:** none. **Owner:** unassigned. **Type:** refactor. **Milestone:** DRY-1.

### Issue 7 — Audit vocabulary constants and no-null sink

- **Problem (D9, D11):** action/result strings are bare literals at 100+ call
  sites; three services each carry `private AuditService audit` +
  `useAuditService` + `if (audit != null)` guards.
- **Intended behavior:** `AuditActions`/`AuditResults` constant holders (or a
  small `AuditSink` façade defaulting to no-op so services never null-check);
  migrate call sites file-by-file — the issue lands the vocabulary and the
  petition/complaint/guard surfaces first, remaining files may follow in
  listed sub-checkboxes.
- **Non-goals:** changing what gets audited or entry schema.
- **Acceptance:** `AuditService.record` signature unchanged; constants defined
  once; `grep '"SUCCESS"' src/main/java` shrinks to the constant (or a
  documented remaining-set list in the issue).
- **Verification:** audit-tail assertions in `JoinPetitionTest`,
  `CareerRemodelTest`, `CivicServiceTest` stay green.
- **Deps:** none. **Owner:** unassigned. **Type:** refactor. **Milestone:** DRY-1.

---

## Milestone DRY-2 — surface plumbing convergence

**Outcome:** the two petition surfaces stop being parallel handwritten
implementations of the same flow.
**Proof:** shared helpers own the shape; surfaces contain only their deltas.

### Issue 8 — One subject-name resolver and one petition-review form builder

- **Problem (D3 labels, D6):** `subjectName(runtime, uuid)` is implemented
  twice verbatim (`NpcRoles:1400`, `NpcPresentationRuntime:402`); the "Decizie
  + Motiv pentru solicitant" field pair is hand-built in 4+ places
  (`NpcRoles:547-551,1393-1395`, `NpcCivicSurfaceService:170-171`,
  `NpcSecretarySurfaceService:305-308`); petition book pages
  (`readJoinRequest` vs `readPetition`) share layout ad hoc.
- **Intended behavior:** a `PlayerNames`/name-resolution port method (the
  `lastKnownName → uuid` fallback lives once, near `playerQueries`); a
  `ReviewFormSpec`/`petitionReviewFields()` factory returning the standard
  field set; optional shared book-page builder.
- **Non-goals:** redesigning the surfaces; changing field limits (that's D1's
  constant, consumed here).
- **Acceptance:** one resolver implementation; form field pair built in one
  factory; surface snapshot tests still pass.
- **Verification:** existing NPC surface tests + a resolver unit test.
- **Deps:** Issue 1 (field cap constant), Issue 3 (vocabulary for labels).
  **Owner:** unassigned. **Type:** refactor. **Milestone:** DRY-2.

### Issue 9 — One form-field-id vocabulary

- **Problem (D10):** `"reason"`, `"note"`, `"decision"`, `"subject"` are an
  implicit contract: producers put them in `Field` ids, `FormSubmissionRouter`
  reads `values.get("...")` — a rename compiles fine and silently breaks
  submissions.
- **Intended behavior:** a `FormFields` constants holder (or per-action field
  spec records) shared by producers and the router; router reads via the same
  constants.
- **Non-goals:** changing wire behavior; i18n of labels.
- **Acceptance:** `values.get("reason")`-style string literals for contract
  fields appear once; a mismatched-id form fails a new unit test.
- **Verification:** form session tests + suite.
- **Deps:** Issue 8 (same producers touched). **Owner:** unassigned.
  **Type:** refactor. **Milestone:** DRY-2.

---

## Milestone DRY-3 — dual-truth decisions (owner calls, not refactors)

**Outcome:** the two remaining "two sources of truth" get an explicit decision
and a tracked path, or an explicit acceptance that they stay.

### Issue 10 — DECIDE: canonical member-rank record (D13)

- `GuardState.rank` (legacy int + `Rank`) and `PersonnelRecord.careerGrade`
  (V2) are synced by `v2PersonnelProjection` — every rank-relevant change is
  written in two places by construction. `docs/straja-v2-implementation.md`
  already frames V1 as "compatibility paths… until the cutover window."
- **Ask:** confirm V2 personnel is the canonical record and schedule the
  legacy-rank cutover, or explicitly accept the projection as permanent and
  document it as the sanctioned bridge. **needs-input — owner decision.**

### Issue 11 — DECIDE: user-facing text ownership (D14)

- Romanian `Component.literal` strings (chat-outcome messages in services and
  the router) vs `translatable` `straja.*` keys — two text systems currently
  interleaved. README makes RO canonical; the question is whether literals are
  sanctioned inside services or everything user-visible goes through keys.
- **Ask:** pick one rule per layer (e.g. "services emit keys, literals banned"
  or "RO literals are the canonical system, keys only for NPC/provider
  surfaces"), then record it in CONTRIBUTING. **needs-input — owner decision.**

---

## Campaign rules for every issue

- **The one-place rule is the acceptance test.** Each issue's grep check is
  part of done; if the PR can't prove a single definition site, it isn't done.
- **No behavior drift.** Behavior changes only where stated (Issue 4's naming
  fix). Everything else is pure consolidation — reviewers reject on any
  incidental semantic change.
- **No new abstraction without a consumer count ≥ 2.** If consolidation would
  wrap something used once, leave it; the rule targets *duplicated* knowledge.
- **PR body contract** (per `flux-milestone`): restate intent, expectation,
  acceptance criteria with the grep evidence, scope/non-goals, affected files,
  verification output, and open decisions — `None`/`Not applicable` explicitly
  where empty.

## Status

`executed` — issues 1–9 landed as one commit each on `feat/rank-overhaul`
(79b138c Text cap · 1e9b526 ReviewRef · e1dc390 DecisionWords · 71e909b
career bridge · 423e060 brand prefix · 0194955 ensureX() · 2f46a70 audit
vocabulary+sink · 579cd8a resolver+ReviewForm · e6c778c FormField), each
with the full JUnit suite green (final: 82 classes, 1269 tests, 0
failures). Issues 10–11 remain `needs-input` owner decisions.
