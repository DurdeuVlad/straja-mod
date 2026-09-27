# NPC Surface Visual Revamp — Milestone & Issue Plan

Status: **ready-for-handoff** — all decisions resolved 2026-09-27.
Planning date: 2026-09-27. Base: `main` @ `9e45d7f` (includes PR #179 layout fixes).
Filing: GitHub issues + this doc committed per repo convention.

## Contract

- **Goal:** Replace the text-only CustomNPCs/Straja menus with an icon-driven,
  Minecraft-native visual system: consistent layout grid, per-role and per-state
  pixel-art icons, AI-generated mockups informing the design, and a frontend
  revamp of every player/admin surface — without weakening server authority.
- **System:** `CustomNpcsNpcSurfaceProvider` (native `GuiCustom` surfaces),
  `StrajaFormScreen` (vanilla `AbstractContainerScreen`), `assets/straja`
  textures, mc-pilot real-client verification harness.
- **Constraints:** CustomNPCs stays optional + reflective (no compile dep);
  permissions/progression/tokens/audit stay server-authoritative; surfaces must
  remain readable at vanilla GUI scale; pixel-art language must match existing
  `straja` item/entity textures; no gameplay-rule changes (gameplay-decisions.md
  §17, line 364 explicitly leaves UI layout as an implementation choice).
- **Evaluation:** every surface re-captured via mc-pilot screenshots in the
  live harness; unit tests for pure helpers; no visual regression vs. the
  `screenshots-fixed/` baseline; acceptance criteria per issue are observable.

## Verified API capabilities (from `CustomNPCs-Unofficial-NeoForge-1.21.1.20251230.jar`)

Inspected via `javap` — facts, not assumptions:

| Capability | Signature | Use |
|---|---|---|
| Custom GUI background | `ICustomGui.setBackgroundTexture(String)` | panel/skin texture |
| Static image region | `addTexturedRect(id, texture, x, y, w, h[, tx, ty])` | icons, banners, dividers |
| Icon button | `addTexturedButton(id, label, x, y, w, h, texture[, tx, ty])` | choice/action buttons |
| Item icon renderer | `addItemRenderer(id, x, y, w, h, IItemStack)` + `setHoverBox` | **icons with zero new assets** |
| Live entity portrait | `addEntityDisplay(id, x, y, z, IEntity)` | NPC portrait in selector/headers |
| Item-stack creation | `NpcAPI.getIItemStack(ItemStack)` | wrap server-side stacks |
| Sub-GUIs | `openSubGui(ICustomGui)` | drill-down flows if needed |

Texture strings resolve as `ResourceLocation`s client-side; the `straja` jar
is present in the client mods dir, so `straja:textures/gui/...` paths work.

**Zero-asset icon path already available:** 31 registered Straja items, 27 with
dedicated pixel-art PNGs (`cuffs`, `archive_folder`, `training_manual`,
`mission_carnet`, `fine_book`, `official_envelope`, `patrol_wand`,
`prison_marker`, `identity_card`*, …). `addItemRenderer` can show these as role
icons immediately — no generated art required. (*4 items lack dedicated PNGs —
verify texture presence per item at implementation time.)

## Surface inventory (revamp scope)

| # | Surface | Method / file | Path |
|---|---|---|---|
| 1 | Role dialogue surface (node text + choices + quest journal) | `openSurface` | GuiCustom |
| 2 | Admin profile selector (paged) | `openAdminSelector` | GuiCustom |
| 3 | Admin duplicate-binding cleanup | `openAdminDuplicateCleanup` | GuiCustom |
| 4 | Assign confirmation | `openAdminConfirmation` | GuiCustom |
| 5 | Unassign confirmation | `openAdminUnassignConfirmation` | GuiCustom |
| 6 | Admin status | `openAdminStatus` | GuiCustom |
| 7 | Admin audit history | `openAdminAudit` | GuiCustom |
| 8 | Terminal result screen | `openAdminResult` | GuiCustom |
| 9 | Input/quiz form | `openInputGui` | GuiCustom |
| 10 | Native form screen | `StrajaFormScreen` | vanilla |
| 11 | Training manual book | vanilla `BookViewScreen` | out of scope (vanilla widget) |

Known residual defects to close during the revamp (from PR #179 review):
320-tall surfaces clip on very small windows; body TextArea first line clips
slightly; quiz input does not restate the question; scenario clicks are
coordinate-fragile.

## Decisions (resolved 2026-09-27)

1. **Icon asset source — RESOLVED.** Prompt-pack-driven external generation:
   Issue 3 produces `docs/icon-generation-prompt-pack.md`; an external agent
   (Codex "Luna 5.6" or "agy", whichever is working) generates the images from
   it; `normalize.py` enforces the asset contract before commit. Issue 2 still
   ships item-renderer icons first so the revamp never blocks on generation.
2. **Visual direction — RESOLVED.** Straja-specific institutional theme
   (dark/parchment, anchored to the existing item-art palette).
3. **Scope of native/vanilla surfaces — RESOLVED.** StrajaFormScreen restyle
   is Issue 5; vanilla book UI stays out of scope.

## Milestones

### M0 — Design contract *(Issue 1)*
**Outcome:** a committed visual spec (palette, grid, icon taxonomy, state
semantics, per-surface wireframes as annotated mockups) that all later work
implements against. **Proof:** spec doc + mockup set reviewed and accepted.
**Skills:** flux-ux (IA/navigation), flux-design (visual system),
flux-prototype (mockups). **Risk:** direction chosen without user input →
rework; mitigated by the needs-input gate.

### M1 — Theme infrastructure + zero-asset icons *(Issue 2)*
**Outcome:** provider gains a `GuiTheme` layout/icon layer; every surface gets
a consistent header (role/action icon via `addItemRenderer`, title, divider);
background texture hook wired. **Proof:** screenshot sweep; unit tests for the
icon/text mapping. **Depends on:** M0 spec (icon map, constants). Delivers
visible icons **before** any generated art lands.

### M2 — AI pixel-art icon pack *(Issue 3)*
**Outcome:** `assets/straja/textures/gui/` icon set (roles ×6, quest states,
action glyphs, panel background) generated per the prompt pack and normalized
by a repo script (16×16/32×32, fixed palette, alpha, nearest-neighbor).
**Proof:** assets render in-game via `addTexturedRect`; screenshots.
**Depends on:** Decision 1 + M0 taxonomy. **Blocked if Decision 1 unresolved.**

### M3 — Surface-by-surface revamp *(Issue 4)*
**Outcome:** all nine GuiCustom surfaces rebuilt on the theme layer: selector
with entity portrait + profile icons; role surfaces with role icon header and
quest-state glyphs; textured action buttons; input screen restates the
question (closes residual defect); result screens get success/error iconography;
sizing fixes the 320px clip. **Proof:** full mc-pilot screenshot sweep vs.
`screenshots-fixed/` baseline + audit trail still `ACCEPTED/ok`.
**Depends on:** M1 (required), M2 (textures upgrade icons when present —
icons degrade to item-renderers if M2 isn't done).

### M4 — Native screen + harness hardening *(Issue 5)*
**Outcome:** `StrajaFormScreen` restyled to match (header, icon, spacing);
CI scenarios click by component semantics instead of fragile coordinates where
the API allows. **Proof:** native screen screenshot + green client-UI gate.
**Depends on:** M0, M1.

**Skill coverage per user request ("all the frontend skills"):**
flux-ux → M0; flux-design → M0; flux-prototype → M0 mockups;
flux-frontend → M1/M3/M4 implementation; flux-accessibility → contrast/scale
criteria in M0 + M3 acceptance; flux-ui-test → mc-pilot screenshot evidence in
M1–M4; flux-test → unit-test design in M1; flux-verify / flux-prove-it-works →
live server+client proof each milestone.

---

## Issue 1 — Visual design contract and icon taxonomy for NPC surfaces

**Intent.** The current menus are text-only with ad-hoc coordinates; the user
wants an icon-driven Minecrafty look but no visual direction exists. A shared
spec prevents every later issue from inventing its own style.

**Expectation.** `docs/npc-surface-visual-system.md` exists covering: palette
(sampled from existing `straja` item art), GUI grid (margins, header band,
column gutters, footer action row), icon taxonomy (role icons, quest-state
icons, action glyphs, status/severity icons), component states
(default/hover/disabled/denied/success), text-wrap rules, small-window
behavior, and annotated mockups for all nine GuiCustom surfaces + the native
form screen.

**Acceptance criteria.**
- Every surface in the inventory table has a wireframe/mockup with component
  types chosen from the verified API table.
- Icon taxonomy assigns an icon to each of the 6 roles, ≥3 quest states, and
  each action button kind; each has a zero-asset item-icon fallback.
- Mockups annotated with pixel measurements at 320px GUI width and behavior at
  logical height < 320.
- Decision on small-window clipping is explicit (compress vs. min-size vs.
  accept), recorded in the doc.
- Spec conforms to the approved direction: Straja institutional theme
  (Decision 2).

**Context code cannot infer.** Target users: players (dialogue surfaces) and
ops/admins (provisioning surfaces). Romanian-language content; client locale
may differ. CustomNPCs GUI coordinates are pixel-absolute; tooltips on
scroll-panel children render broken — tooltips are GUI-children only.

**Scope.** Docs + mockups only. Type: design/planning. Labels: `npc-platform`,
`customnpcs`, `enhancement`. Milestone M0. **Unassigned.**

**Non-goals.** No production code, no generated assets, no GitHub filing.

**Dependencies.** Blocks Issues 2–5. All planning decisions resolved.

**Verification.** Doc review; mockup legibility check; implementer test — can
a developer build Issue 2 without asking a style question? If not, incomplete.

---

## Issue 2 — GuiTheme layer and zero-asset icons for all CustomNPCs surfaces

**Intent.** Icons now, without waiting on generated art: the mod already ships
27 pixel-art item textures and the API renders item stacks in GUIs.

**Expectation.** A `GuiTheme` helper in the provider package owns: palette
constants, grid constants (margin/header/columns/footer), `roleIcon(profileId)
→ IItemStack` mapping via `NpcAPI.getIItemStack`, `addHeader(gui, icon,
title)`, `addDivider`, and textured-button/icon-rect helpers that read texture
paths from constants (so M2 can later point them at generated PNGs).
Every surface uses the header helper; role surfaces show the role item icon.

**Acceptance criteria.**
- All 9 GuiCustom surfaces render a header band: icon + title, no overlap.
- Role→item icon map covers all 6 roles; missing/failed icon → omits icon,
  never throws (provider must fail closed to text-only, not crash).
- No compile-time dependency on CustomNPCs classes (reflective calls only, as
  today); surfaces still render when CustomNPCs absent = N/A (provider unused).
- Live screenshot sweep of all surfaces shows icons rendered; audit chain
  still `ACCEPTED/ok` after interaction.
- Unit tests for icon mapping, wrap/layout math, and theme constants.

**Context.** Reflective API bridge; `IItemStack` created server-side via
`NpcAPI.getIItemStack(new ItemStack(StrajaItems.X.get()))`. Verify each mapped
item has a client texture (4 registered items lack dedicated PNGs).

**Scope.** `CustomNpcsNpcSurfaceProvider` + new `GuiTheme` helper + tests.
Type: enhancement. Labels: `npc-platform`, `customnpcs`, `enhancement`,
`java`. Milestone M1. **Unassigned.**

**Non-goals.** No generated art, no per-surface redesign beyond the header/
theme layer, no StrajaFormScreen changes, no layout-grid rebuild of bodies
(that is Issue 4).

**Dependencies.** Issue 1 (spec). Feeds Issues 3–4.

**Verification.** `./gradlew test`; live mc-pilot screenshot pass per surface;
interaction smoke (assign → surface → quiz answer → audit row present).

**PR contract required:** intent, expectation, acceptance criteria w/ evidence,
non-code context, scope/non-goals, verification & risk.

---

## Issue 3 — AI-generated pixel-art icon pack + normalization pipeline

**Intent.** The "AI-generated pixelated icons that look minecrafty" deliverable:
a real texture set under `assets/straja/textures/gui/` that survives code
review like any asset.

**Expectation.** Two artifacts: (a) `docs/icon-generation-prompt-pack.md` —
per-icon prompts (subject, palette constraint, "16×16 pixel art, flat shaded,
Minecraft item style, transparent background") for the full taxonomy from
Issue 1; (b) `tools/icons/normalize.py` — a Pillow script enforcing the asset
contract: exact canvas size, palette-quantize to the approved palette,
nearest-neighbor only, alpha preserved, output to `textures/gui/`. Generated
PNGs committed after normalization.

**Acceptance criteria.**
- Every taxonomy icon exists as a PNG passing `normalize.py --check`.
- A panel background tile exists and is wired via `setBackgroundTexture`
  behind a constant (fallback: no texture → current look).
- Icons render in-game on at least the selector + one role surface via
  `addTexturedRect`, with item-renderer fallback if a texture is missing.
- Provenance recorded: prompt, generator, date per icon in the doc (license/
  attribution check is the reviewer's gate).

**Context.** Generator pipeline per Decision 1: this issue authors the prompt
pack; the images are produced by an external agent (Codex "Luna 5.6" or "agy",
whichever is working at execution time) and committed only after
`normalize.py` passes. M1 item-icons keep surfaces working without this pack.

**Scope.** Assets + one tool script + doc. Type: assets/tooling. Labels:
`npc-platform`, `enhancement`, `documentation`. Milestone M2. **Unassigned.**

**Non-goals.** No surface layout changes; no animation/`.mcmeta` (except
explicitly approved); no replacing item textures.

**Dependencies.** Issue 1 (taxonomy/palette). Optional dependency of Issue 4 —
textured icons swap in via constants when the pack lands.

**Verification.** `normalize.py --check` clean; in-game screenshots; asset
diff review; confirmed no executable/network content in assets.

---

## Issue 4 — Full surface revamp on the theme layer

**Intent.** Turn every GuiCustom surface into the designed system — this is
the user-visible "frontend revamp."

**Expectation.** Per Issue 1 spec: selector gains `IEntityDisplay` NPC portrait
+ per-profile icons + paged footer; role surfaces get role-icon header, wrapped
body in the text column, quest journal with state glyphs; action buttons become
icon buttons (`addTexturedButton` — superseded: inert in this build, see the
visual-system doc §3 build-limitation note; item icons + glyph prefixes carry
the cue instead); input GUI restates the question above the
field; result screens carry success/denial icons; surfaces respect the
small-window decision; the 320px clip and first-line textarea clip are fixed
or explicitly accepted per the M0 ruling.

**Acceptance criteria.**
- Screenshot sweep of all 9 GuiCustom surfaces matches the approved mockups
  within the agreed tolerance; no text collision at 320px width.
- Selector page navigation, confirm/unassign/duplicate flows, quiz input +
  submit, status, audit, result all verified live via mc-pilot with server
  audit still `ACCEPTED/ok`; denied path shows the denial result screen.
- No behavior change to permissions, tokens, stale-action guards, or audit
  writes (server-side paths untouched).
- Scenario JSON click coordinates updated for the new layout; client-UI
  scenario suite still passes.

**Context.** All authority checks stay server-side — this issue touches only
`open*`/`show*` presentation methods. Keep `setHoverText` off scroll-panel
children (renders at panel-local origin — known artifact).

**Scope.** `CustomNpcsNpcSurfaceProvider` + scenario JSONs + tests. Type:
enhancement. Labels: `npc-platform`, `customnpcs`, `enhancement`, `java`.
Milestone M3. **Unassigned.**

**Non-goals.** StrajaFormScreen (Issue 5); new roles/dialogue content; API
changes; book UI.

**Dependencies.** Issue 2 (theme layer). Issue 3 optional (textured icons swap
in via constants when present).

**Verification.** mc-pilot annotated screenshot sweep (all surfaces, incl.
small-window capture); `./gradlew test`; scenario suite; regression diff vs.
`screenshots-fixed/`.

**PR contract required** (same sections as Issue 2).

---

## Issue 5 — StrajaFormScreen restyle + scenario click hardening

**Intent.** The one native screen should match the new system, and scenario
clicks should stop depending on fixed GUI coordinates where avoidable.

**Expectation.** `StrajaFormScreen` renders the theme header (icon + title),
uses the palette/spacing tokens, and shows the question text above its input
(mirroring the GuiCustom input fix). Where mc-pilot/the API expose component
ids or labels, scenarios target those instead of absolute coordinates; a
comment documents remaining coordinate clicks.

**Acceptance criteria.**
- Native screen screenshot shows header/icon/consistent spacing; question
  visible above the field.
- At least the role-surface choice clicks in `admin_provisioning.json` and
  `jailer_custody.json` are label/id-driven if the harness supports it;
  otherwise coordinates updated + documented.
- `./gradlew test` + client-UI scenario suite green.

**Scope.** `StrajaFormScreen`, theme constants reuse, scenario files. Type:
enhancement. Labels: `npc-platform`, `enhancement`, `java`. Milestone M4.
**Unassigned.**

**Non-goals.** Vanilla book UI; menu/container behavior changes; new form
fields.

**Dependencies.** Issues 1–2; lands after/parallel to Issue 4.

**Verification.** Screenshot of restyled screen; scenario suite; unit tests
where logic moved.

**PR contract required** (same sections as Issue 2).

---

## Handoff notes

- Sequencing: Issue 1 → 2 → (3 ∥ 4, with 4 consuming 3's assets if ready) → 5.
- GitHub issues filed under milestone "NPC Surface Visual Revamp"; this doc
  committed per repo convention (`docs/*-implementation-issues.md`).
- Residual risks: CustomNPCs texture-path resolution for `straja:` resources
  is verified as API surface but not yet exercised live — first task in Issue 2
  is a spike proving `addTexturedRect("straja:textures/...")` renders, before
  building on it.
- Next useful action: execute Issue 1 (design contract + mockups).
