# Zero-knowledge UX — milestone & implementation issues

**Outcome:** a first-join player and a fresh server admin can each reach their
first success without outside help, documentation, or prior Straja knowledge.
Assume zero domain knowledge ("dumb as a rock" is the design constraint, not a
slur): every screen answers *what is this, what do I do next, why did that
fail*.

**Audience:** Romanian-speaking RusticCraft 2 players and server operators.
Romanian in-game text is the deliberate canonical choice (README decision);
English fallback coverage is noted but out of scope for this milestone.

---

## UX audit — what exists today

### Personas

| Actor | Knows | Needs | First success |
|---|---|---|---|
| **P1 — first-join civilian** | nothing | "what is this, where do I start" | submits an admission request at the Receptionist |
| **P2 — member (Stagiar+)** | basics | next action for their state | starts duty, sees salary/kit/missions without hunting |
| **A1 — server admin / commissioner** | server admin basics | a to-do list that ends in a working install | `/straja setup` green, NPCs live, one test recruit flowable |

### Assets that already exist (do not rebuild)

- **Context-aware FAQ tree** (`NpcFaqSurface`) — status-aware topics
  (`memberRecord`, `activeMember`, `commissioner` gating), 36 answers, back
  navigation, station-location templating. The best UX asset in the mod.
- **Structured admin help** (`AdminCommandHelp`) — a `help` subcommand attached
  to every brigadier node; structured specs carry purpose / authority /
  side-effects / error codes / recovery command for the V2 subtrees.
- **Guided setup spine** — `/straja setup` checklist + `setup here` /
  `setup patrol` / `setup npcs` / `setup tools`; `SetupChecklist.nextStep`
  computes the next missing install step; the **commissioner gets a login
  nudge** (`StrajaEvents.onLogin` → `PlayerQueryUseCase.setupHintFor`).
- **Clickable chat actions** — all NPC surfaces drive `/straja npc-action
  <token>` links with hover hints; no command memorization for players.
- **Bounded, validated forms** — labeled fields ("Categorie", "Descriere",
  "Note"), owner-bound one-use sessions, 5-field adaptive layout.
- **Destructive-action confirms** — e.g. `tool-npc-remove-confirm`.

### Gaps (evidence-linked)

- **G1 — no first-join orientation.** `StrajaEvents.onLogin` nudges only the
  commissioner. A brand-new civilian gets zero orientation: no "what is this",
  no pointer to the Receptionist, no first step. *(Owner opted out of a
  proactive login message — coverage is recovery-path only, via issues 1 and
  3; see Decisions.)*
- **G2 — no player-facing help.** `/straja help` requires OP 3 — the `help`
  literal attached to every node carries `requires(ADMIN_PERMISSION)`
  (`AdminCommandHelp.attach`, line ~308), so at permission 0 the command
  fails Brigadier's `requires` check before reaching `show` — a generic
  unknown/incorrect-argument parse error. The styled refusal at line 339
  ("Ajutorul Straja cere OP 3.") sits behind that gate and never reaches
  players. Bare `/straja` is no better: the root literal has no `executes`,
  so it fails as an unknown/incomplete command for everyone, OPs included.
  The only public commands are `status`, `rules`/`regulament`, `stop`. The
  NPC FAQ exists but is unreachable by command and buried as the last of 16
  chat links.
- **G3 — surface overload without state-gating.** The Receptionist presents
  16 chat links in a row to *every* player: a Civil sees "Promovarea mea V2",
  "Stare cameră", "Declară facțiunea nativă" next to "Depune cererea". No
  ordering by next-expected-action; the FAQ's context model is not reused on
  the main surface. Cognitive load is maximal exactly for the user who knows
  least.
- **G4 — localization split-brain** *(partially in scope — owner pulled EN
  fallback in: every player-facing string issues 1–4 touch or add lands in
  lang files with `ro_ro` canonical + `en_us` parity; the wholesale sweep of
  untouched literals stays deferred)*.
  ~150 `Component.literal` call sites carry gameplay/chat text across 17
  files, while both lang files (`en_us.json` and `ro_ro.json`, identical
  49-key sets) cover only item and entity names. RO-first is deliberate;
  EN clients see mixed text.
- **G5 — refusals don't always name the remedy.** e.g. trades refusals do
  point at the Commissioner (good pattern); other denials stop at the reason
  ("Acțiunea NPC nu mai este disponibilă…"). Sweep needed so every denial
  ends with the concrete next step.
- **G6 — setup checklist covers only locations/patrol/NPCs.**
  `SetupChecklist.nextStep` checks exactly three categories —
  `missingLocations` (via `SetupData.LOCATION_KEYS`), `unplacedCheckpoints`,
  `missingNpcRoles`. Jail geometry, armory/economy provider, commissioner
  appointment, and any post-setup smoke check are not covered — `isComplete`
  reports green on an install that may still not work for players.
- **G7 — CustomNPCs parity.** Dialog trees exist
  (`docs/dialog-trees.md`, `tools/ci/client_scenarios/customnpcs/`); any
  onboarding/gating change must hold on both provider surfaces.

---

## Milestone: "Zero-knowledge UX"

**Proof of completion:** a fresh server — admin follows only `/straja setup`
and reaches a working install; a first-join player follows only in-game
prompts and submits an admission request without asking anyone; every NPC
surface presents the player's next expected action first.

**Scope boundary:** player-facing chat surfaces, player commands, setup
checklist, refusal text, shipped docs — plus lang-file externalization for
every player-facing string those issues touch or add (`ro_ro` canonical,
`en_us` parity). **Not in scope:** wholesale externalization of untouched
literals, admin-facing (OP 3/4) strings, CustomNPCs dialog redesign beyond
parity fixes, any gameplay-mechanic change.

| # | Issue | Depends on |
|---|---|---|
| 1 | Public `/straja help` + bare-`/straja` orientation for non-OP players | — |
| 2 | First-join orientation nudge — **DESCOPED** (owner: no login message) | — |
| 3 | State-ordered, progressive-disclosure NPC surfaces | 1 |
| 4 | Refusal → remedy sweep on player-facing paths | 3 (shares surface code) |
| 5 | Setup checklist completeness + post-setup smoke step | — |
| 6 | Player quick-start + admin quick-start docs | 1, 3–5 (documents final flows) |

---

## Issue 1 — Public `/straja help` and bare `/straja` for players

**Intent.** A player who knows nothing should be able to type `/straja` and
recover. Today `/straja help` is OP-3-only and the root has no player path;
a civilian gets a generic Brigadier parse error as their first interaction
(see G2 — the `requires` gate fires before the styled refusal).

**Expectation.** Permission-0 players running `/straja` or `/straja help`
(alias `/straja ajutor`) see a short Romanian orientation: what Straja is in
one line, the clickable-first-step pointer ("mergi la Recepție și apasă
«Depune cererea»"), the 3 public commands (`status`, `rules`/`regulament`,
`stop`), and a pointer to the NPC FAQ ("întreabă NPC-urile"). No OP-gated
command names leak into the output.

**Acceptance criteria.**
- `/straja` (no args) and `/straja help` at permission 0 both print the
  player orientation; at OP 3+ `/straja help` keeps the existing admin index
  and bare `/straja` resolves to permission-appropriate help instead of the
  generic "incomplete command" parse error it produces today.
- Output mentions Receptionist + admission action + the FAQ entry point;
  every command listed is actually runnable at permission 0.
- New/changed player-facing strings resolve through lang keys (`ro_ro`
  canonical, `en_us` parity — owner decision on G4).
- Existing admin help behavior unchanged (regression test).

**Context code cannot infer.** Player-facing, Romanian; this is the primary
recovery surface for a lost player, so it must exist even on servers with no
NPCs spawned yet.

**Scope.** `StrajaCommands` root + `AdminCommandHelp`. Two gates to handle,
not one: the `requires(ADMIN_PERMISSION)` on the attached `help` literal
(`attach`, ~line 308) makes perm-0 help unparseable, and the runtime check
in `show` (~line 338) must keep guarding admin-subtree `help` output so
`/straja v2 … help` can't leak admin specs at perm 0. For bare `/straja`,
a root `executes` dispatching to `rootHelpLines(effectivePermission)`
already serves both audiences — the permission<3 branch returns the 3
public commands today, so extend it into the player orientation rather
than forking a `PlayerHelp`. Issue type: enhancement. Labels:
`ux`, `player-facing`. Unassigned.

**Non-goals.** Command auto-completion redesign; translating admin help;
help *inside* NPC forms.

**Verification.** Unit test asserting permission-0 help text shape and that
no ADMIN_PERMISSION node names appear; GameTest or existing command-harness
asserting `/straja` executes at permission 0 without failure.

---

## Issue 2 — First-join orientation nudge for civilians — **DESCOPED**

**Owner decision: no login message.** The proactive civilian nudge is out.
First-join orientation is carried entirely by issue 1 (`/straja` /
`/straja help` recovery path) and issue 3 (state-ordered surfaces putting
«Depune cererea» first at the Receptionist).

**Consequence / residual risk.** G1 is only partially covered: a fresh
player must discover `/straja` or walk to the Receptionist unprompted — the
milestone's player proof ("submits an admission request without asking
anyone") now depends on physical NPC discoverability and the help command.
Revisit if onboarding feedback shows first-join drop-off.

---

## Issue 3 — State-ordered NPC surfaces with progressive disclosure

**Intent.** The Receptionist shows 16 links to everyone. The next expected
action for the player's actual state should come first; irrelevant actions
should not crowd the line.

**Expectation.** `NpcPlayerSurface.surfaceFor` (and the dynamic action
mappers) order actions by the player's state — reuse
`NpcFaqSurface.Context`-equivalent gating so the surface knows Civil /
candidate / member / commissioner:

- **Civil:** `Depune cererea`, `Regulament`, `Am o întrebare` first; member
  bookkeeping actions (Fișa mea V2, Promovarea, Actele, Stare cameră,
  reputație, faction) hidden or trailing.
- **Member:** status/duty/document actions first, FAQ retained.
- **All states:** FAQ entry moves from last position to top-3.

No action is removed outright — anything still reachable stays reachable —
but primary ordering follows state. Apply to Receptionist at minimum; other
role surfaces get the same ordering pass where they mix civil/member
actions. CustomNPCs dialog parity where the dialog tree mirrors these
actions.

**Acceptance criteria.**
- Civil clicking Receptionist sees the admission action first and ≤ 6
  primary links (owner-decided cap).
- Member sees no "Depune cererea" noise they can't use.
- Hidden-at-state actions live one level deeper under a trailing
  «Mai multe…» overflow submenu reusing the FAQ menu machinery
  (owner-chosen pattern over grayed-with-reason — chat surfaces have no
  tooltip/explain mechanics; apply consistently).
- FAQ entry visible in the first 3 actions on every state.
- New/changed player-facing strings resolve through lang keys (`ro_ro`
  canonical, `en_us` parity — owner decision on G4).

**Context.** `NpcFaqSurface.Context` already models the state gates — the
main surface deliberately does not reuse it today. Ordering must be
deterministic for tests (source-scan + unit-testable pure function pattern
like the existing `*Actions` mappers).

**Scope.** `NpcPlayerSurface` surface construction + ordering helper;
CustomNPCs dialog-tree equivalents where they exist. Labels: `ux`,
`player-facing`, `npc-surface`. Unassigned.

**Non-goals.** Custom GUI screens to replace chat; per-player action
customization; hiding actions based on permission state the user can't
remedy (prefer "shown but explained").

**Verification.** Unit tests per state × surface asserting ordering and the
FAQ position; extend `tools/ci` client scenario with a civil-clicks-
receptionist path asserting first-action text; GameTest for at least one
state transition (apply → actions reorder).

---

## Issue 4 — Refusal → remedy sweep on player-facing paths

**Intent.** Every denial a player can hit through NPC surfaces should end
with the concrete next step, not just the reason. The trades refusals
already do this ("…ask the Commissioner"); make it uniform.

**Expectation.** Enumerate player-reachable refusal/denial messages on NPC
and command surfaces (application denied, wrong rank, cooldown, stale form,
item/state mismatches, permission-gated surface actions). Each message ends
with one of: the exact action/NPC that unblocks it, or the FAQ topic that
explains it. Establish a `refusal(reason, remedy)` message convention so new
denials can't ship bare.

**Acceptance criteria.**
- A written inventory (in the PR) of covered refusal sites; every entry ends
  with an actionable next step.
- No refusal text changes alter server decision logic — text-only.
- Every touched refusal string resolves through lang keys (`ro_ro`
  canonical, `en_us` parity — owner decision on G4).
- The convention is testable: source-scan test asserting refusal message
  construction goes through the helper (mirroring `StrajaFormBudgetTest`'s
  approach).

**Context.** Romanian text; keep messages short enough for one chat line.
Depends on issue 3 landing first so the sweep touches the final action set.

**Scope.** `NpcRoles`, service-level player messages surfaced through NPC
actions, `StrajaCommands` player subtree. Labels: `ux`, `copy`. Unassigned.

**Non-goals.** Changing denial policy/authorization logic; admin-facing
error text (already structured via HelpSpec error codes).

**Verification.** Inventory table in PR with before/after strings;
source-scan unit test for the helper; spot GameTest on one refusal path
(e.g., civil tries member-only action → sees remedy).

---

## Issue 5 — Setup checklist completeness + post-setup smoke step

**Intent.** `/straja setup` + `SetupChecklist` + the commissioner login
nudge are the admin onboarding spine, but the checklist verifies only three
categories (locations, checkpoints, NPC roles — see `SetupChecklist`);
economy provider, jail/cell geometry, armory stock, commissioner
appointment, and a final "does it work" step are not covered, so the
checklist can report "complete" on an install that isn't.

**Expectation.** `/straja setup` prints a complete ordered checklist —
locations, patrol checkpoints, NPC roles, **economy/coin provider config,
jail cell geometry, commissioner appointment, station validation** — each
with status ✓/✗ and the exact next command. A final step or subcommand runs
a bounded self-check (e.g., `station validate` + `doctor consistency` +
spawn-check) and reports "ready for players" or the failing item. The
commissioner login nudge uses the same checklist so the two can never
disagree.

**Acceptance criteria.**
- Fresh world + `/straja setup` lists every category with next-command
  hints; completing them in order reaches "complete" without external docs.
- `SetupChecklist.nextStep` covers the new categories; nudge text unchanged
  in contract (one next step, actionable).
- Post-setup smoke step is read-only or clearly marked when it mutates
  (e.g., test-recruit spawn must be opt-in).
- Checklist categories (owner-decided): locations, patrol checkpoints, NPC
  roles (existing) **+ commissioner appointed, prison cell geometry,
  currency provider active, station-validate pass**. Armory stock is
  excluded — content drift, not install state.

**Context.** Admin-facing, OP 4, Romanian. This is the difference between
"install works" and "admin thinks it works".

**Scope.** `SetupChecklist`, `AdminToolService` setup commands,
`SetupData`/`SetupStore` if new keys are needed. Labels: `ux`, `admin`,
`setup`. Unassigned.

**Non-goals.** A GUI setup wizard; auto-fixing missing pieces beyond the
existing `setup here/patrol/npcs` pattern; touching `policy` semantics.

**Verification.** Unit tests per checklist category (present/missing);
GameTest or integration test: fresh setup → checklist empty → smoke step
reports ready.

---

## Issue 6 — Player quick-start + admin quick-start docs

**Intent.** In-game help covers the happy path; a shipped one-page doc
covers "I just installed this" for admins and "I just joined" for players —
linkable from README and the CurseForge page.

**Expectation.** `docs/player-quickstart.md` and `docs/admin-quickstart.md`
(or one `docs/quickstart.md` with two sections — implementer's call):
screenshots optional, Romanian primary; admin page mirrors the real
`/straja setup` checklist order from issue 5; player page mirrors the real
first-join flow from issues 1–3. README links them above the fold.

**Acceptance criteria.**
- Every command/action named in the docs exists and is permission-correct
  (docs can't lie — spot-check each).
- Player page assumes zero knowledge and ends at "admission request
  submitted"; admin page ends at the smoke-check "ready" state.
- RO text; EN translation optional.

**Scope.** `docs/`, README link block, CurseForge/Modrinth description
pointers. Labels: `docs`, `ux`. Unassigned. Depends on 1–5 (documents final
behavior, not aspirational behavior).

**Non-goals.** Full rewrite of `guard-manual.md` (keep as the deep manual;
quickstart links to it); video/media production.

**Verification.** Doc-driven walkthrough on a real test server: follow the
admin page verbatim to a working install; follow the player page verbatim to
a submitted application. That walkthrough is the milestone's exit check.

---

## Decisions (owner-resolved)

1. **Primary-action cap** (issue 3): **6** primary links; overflow via a
   trailing «Mai multe…» submenu reusing the FAQ menu machinery — chosen
   over grayed-with-reason (chat surfaces lack explain mechanics).
2. **Setup checklist categories** (issue 5): commissioner appointed,
   prison cell geometry, currency provider active, station-validate pass —
   added to the existing three. Armory stock excluded (content drift, not
   install state). Smoke step = `station validate` + `doctor consistency` +
   opt-in spawn-check.
3. **Orientation cadence** (issue 2): **no login message** — issue
   descoped. First-join orientation rests on issues 1 and 3; residual risk
   recorded there.
4. **EN fallback** (G4): **pulled into the milestone** for player-facing
   strings touched or added by issues 1–4 (`ro_ro` canonical, `en_us`
   parity). Wholesale externalization of untouched literals and
   admin-facing strings remains out of scope.

## Authority / handoff status

Local plan only — `docs/ux-onboarding-implementation-issues.md`. No GitHub
issues created; say the word and they can be filed verbatim under a
"Zero-knowledge UX" milestone.
