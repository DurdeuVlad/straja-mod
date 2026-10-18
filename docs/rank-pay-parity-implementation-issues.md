# Rank & Pay Parity — Implementation Issues

Status: `ready-for-handoff` (decided from the owner's public recruitment
announcement, 2026-10-18; branch + PR per issue, delivery to `main`)

This queue closes the gaps between the mod's career/pay model and the
publicly announced job distribution. The announcement is the spec; the work
makes the mod mechanically true to it.

## The announcement (source of truth)

**Part-time corps** — two branches:

- Military: **Stagiar → Străjer → Sergent** — patrols, interventions,
  escorts, guard, operational missions.
- Professional: **Zilier → Meseriaș → Maistru** — construction, logistics,
  repairs, production, provisioning.
- Pay: **1 / 2 / 4 Brass Coins per hour while on shift**, by grade.
  Generous bonuses for assigned missions and performance.
- Minimum activity: at least once a week; absences are reported to Command.
- Entry: apply for Stagiar (military) or Zilier (professional).

**Officer corps** — permanent members, single ladder across domains:

- **Locotenent → Căpitan → Maior → Colonel**
- Pay per hour worked: **8 / 16 / 24 / 32 Brass Coins** respectively.
- Functions: police section (Basex), Pale Fortress garrison, prison and
  prison-mine, industry/workshops/armament production; plus operational and
  specialized domains (investigation, logistics/acquisitions, high-skill
  crafts). Assignments are set by Command.
- Entry: find the Comisar; candidates are invited or apply.

## Gap analysis (vs `feat/rank-overhaul` as merged by PR #274)

| Announced | Mod today |
|---|---|
| Military 3 rungs | 2 rungs (STAGIAR → STRAJER); "Sergent" is a legacy alias of LT |
| Professional 3 rungs | 2 rungs (ZILIER → MESTER/"Meșter") |
| Officer 4 grades | 3 grades (LT → CPT → COL), no Maior |
| Part-time 1/2/4 Brass/h | `salaryPerHour` bronze defaults 16/24 (≈0.25/0.375 brass) |
| Officer 8/16/24/32 Brass/h | bronze defaults 36/64/96 (≈0.56/1/1.5 brass) |
| Both part-time branches paid per shift hour | Professionals cannot hold duty (`ROUTINE_PATROL` denied); their legacy level 0 pays 0/h |
| Min. weekly activity + absence notice | Weekly stipend eligibility exists but nothing flags inactive members; no absence-notice mechanic |
| Officer functions/postings per section | Uniform officer capabilities by design; appointments limited to Comisar/Instructor/Recruiter/Patrol Lead; stations+jurisdictions exist but no named posting record |

## Decisions (resolved this plan; announcement binds where it speaks)

1. **New enum values, never renumber persisted legacy ints.** `Rank` levels
   stay 0–5; new grades project conservatively onto the legacy ladder:
   SERGEANT → GUARD(2), MESERIAS → CIVIL(0), MAIOR → CAPITAN(4). Grade-aware
   pay/name paths distinguish what the projection cannot.
2. **Enum names avoid recycled persisted names.** `MILITARY_SERGEANT` (not
   `MILITARY_SERGENT`, which `CareerGrade.of` must keep mapping to
   OFFICER_LOCOTENENT for pre-remodel saves). `PROFESSIONAL_MESERIAS` is new;
   `PROFESSIONAL_MESTER` stays the top rung and its display becomes
   "Maistru" (the announced name; "MAISTRU" was already a parse alias).
3. **`careerStep` renumbers 1–7**: both entry grades step 1, both second
   grades step 2, both third grades step 3, officers LT/CPT/MAIOR/COL at
   4–7. Threshold maps key by step; existing entries re-map.
4. **Grade-keyed pay and names.** `salaryPerGrade` (enum-name → bronze/h)
   and `gradeNames` (enum-name → display) are the V2 surfaces; legacy
   level-keyed maps remain the fallback for records without a V2 grade.
   Defaults equal the announced rates at the 64:1 bronze:brass ladder.
   Comisar is not in the announcement; default moves to 2560 bronze
   (40 brass, +25% over Colonel) — flagged, owner-tunable via config.
5. **Sergent is exam-gated** like every soldier step; the exam bank filter
   keys on `careerStep` so a third-step exam draws a deeper pool
   (professional steps remain officer-approved petitions).
6. **Professional work shift = duty, not patrol.** Professionals start a
   work-duty shift gated on `PROFESSIONAL_JOBS`; it accrues hourly wage
   under the same anti-AFK movement gate as enlisted duty (they are paid
   for presence, not trusted like officer free-duty). No patrol route.
7. **Weekly minimum is visibility + notice, not auto-discipline.**
   Inactivity = failing the existing weekly stipend service-block bar at
   week end; surfaced to members and to officers as a digest. A filed
   `/straja absence` notice suspends the flag for the noticed window —
   the announcement's "anunțați Comandamentul" made mechanical.
8. **Postings are titles, not capability gates** (per the remodel's own
   rule that officer specialties are RP flavor). A durable `Posting`
   record binds officer → station + section/domain + title, assigned and
   revoked by command authority, surfaced on the member record and
   station view. Capabilities stay uniform.

## Issues

### RPP-1 — Ladder parity: Sergent, Meseriaș/Maistru, Maior

**Intent.** The persisted career ladder matches the announced rungs end to
end: two 3-rung part-time tracks feeding a 4-grade officer corps.

**Scope.** `CareerGrade` (+3 values, chains, steps, parse/of/legacy names),
`rankName(CareerGrade)` via `gradeNames`, `meseriasRankNames` widened to 3
trades levels, capability matrix (SERGEANT inherits STRAJER's combat set;
MESERIAS mirrors ZILIER; MAIOR full officer set), Sergent exam path
(`examBank` keyed by `careerStep`), promotion petition/exam flows, NPC
surfaces and i18n, `promotionServiceBlocks`/`careerStep` renumbering,
demotion resolution for LT → correct origin rung.

**Acceptance.** Every announced rung is reachable via its announced
transition; persisted pre-remodel names still read forward (MILITARY_SERGENT
→ LT, PROFESSIONAL_MAISTRU → MESTER); parse accepts announced names;
demotion walks back correctly; existing tests green + new coverage for each
new grade.

### RPP-2 — Pay parity: grade-aware salary at announced rates

**Intent.** A member's hourly wage follows their `CareerGrade`, at the
announced figures, not their legacy projection.

**Scope.** `salaryPerGrade` policy map + `salaryPerHour(CareerGrade)` with
legacy fallback; `salaryFor` resolves the V2 grade when a record exists;
defaults = 64/128/256 bronze part-time, 512/1024/1536/2048 officer
(brass 64:1); commissioner default → 2560 (assumption, §D4); mission
reward displays reference grade wage correctly.

**Acceptance.** Each announced grade accrues its announced bronze rate;
legacy-record members still accrue via the level map; overrides work via
config; tests cover every rung.

### RPP-3 — Professional work shift

**Intent.** Zilier/Meseriaș/Maistru members get paid "pe oră cât timp
sunteți în tură" exactly like the military branch — a work shift, not a
patrol.

**Scope.** `startDuty` admits `PROFESSIONAL_JOBS` holders into a work-duty
shift (no checkpoint route, no free-duty trust); accrual goes through the
grade-aware wage with the same anti-AFK movement gate as enlisted duty;
refusal/remedy surfaces name the professional path.

**Acceptance.** A Zilier starts a shift, accrues at the professional tier,
stalls when AFK, ends cleanly; patrol-only surfaces stay military-only.

### RPP-4 — Weekly minimum activity + absence notice

**Intent.** "At least once a week" becomes visible and "anunțați
Comandamentul" becomes mechanical.

**Scope.** Weekly activity flag computed from the stipend service-block
signal; member status shows met/missed; officer digest lists members who
missed the week (excluding noticed absences); `/straja absence <days>
[motiv]` files a durable notice with audit; absence suppresses the flag
inside its window.

**Acceptance.** A member with zero qualifying activity surfaces as missed
on the digest; a filed absence hides it for its window; audit records
filing; tests cover edges (expired absence, partial week).

### RPP-5 — Officer postings

**Intent.** Command assigns named functions per the announcement — section
lead, garrison, prison, industry, plus domain specialties — as durable,
audited, revocable postings.

**Scope.** `PostingRecord`/`PostingStore`/`PostingService`: assign,
revoke, list (member + station views); authority = command (commissioner,
op, or officer); jurisdiction/station validated against `StationService`;
postings are titles — capabilities remain uniform by design.

**Acceptance.** An officer receives and loses a posting with audit; the
member's record and the station view show it; non-authority attempts
refuse with remedy.

## Dependency order

```text
RPP-1 ── RPP-2 ── RPP-3
RPP-4 (independent)
RPP-5 (independent)
```

RPP-2 needs the new grades; RPP-3 needs grade-aware pay for professionals.
RPP-4/RPP-5 can ship any time.

## Risks

- **Economy inflation.** Announced rates are ~4–21× the old defaults; the
  announcement is public so the defaults move, but the owner should eyeball
  the config on deploy.
- **Recycled names.** MILITARY_SERGENT once meant officer-tier; mitigated
  by the SERGEANT enum-name choice and preserved `of()` mapping.
- **careerStep renumber.** Threshold maps keyed by step shift meaning for
  officers (3→4); entries are advisory since FUN-2 made petitions
  request-driven, so the risk is config display, not gating.
