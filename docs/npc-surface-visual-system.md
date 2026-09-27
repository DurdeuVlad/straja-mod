# NPC Surface Visual System

Design contract for the icon-driven NPC surface revamp (GitHub milestone
"NPC Surface Visual Revamp", planning doc
`docs/npc-surface-visual-revamp-issues.md`). Everything downstream implements
against this file; deviations require updating it.

**Direction (decided):** Straja institutional theme — a dark institutional
panel with parchment text, leather/steel accents, seal-red authority marks and
brass highlights. Palette is sampled from the shipped item art, not invented.

## 1. Palette tokens

Extracted from `assets/straja/textures/item/*.png` (5 511 opaque pixels,
top colors). Hex values are the actual art values.

| Token | Hex | Sampled role | Usage |
|---|---|---|---|
| `paper` | `#dcd7be` | paper/labels | primary text on dark panels |
| `paper-bright` | `#f0e9c9` | document highlights | titles, emphasized text |
| `paper-dim` | `#b4af96` | aged paper | secondary text, hints |
| `leather` | `#78501e` | leather binding | panel accents, header rule |
| `leather-deep` | `#503c1e` | dark leather | panel edge, pressed state |
| `ink` | `#281400` | deep shadow | icon shadow, darkest accent |
| `steel` | `#787882` | metal fittings | borders, inactive icons |
| `steel-bright` | `#a0a0aa` | polished steel | hover border, focus ring |
| `steel-dark` | `#50505a` | dark metal | disabled components |
| `night` | `#141428` | darkest blue-grey | panel background tint |
| `seal-red` | `#8c2828` | wax seal | authority/destructive actions, denied |
| `seal-bright` | `#a02828` | fresh wax | denied/hover on authority actions |
| `brass` | `#d2b43c` | brass fittings | primary actions, active state |
| `brass-bright` | `#dcbe50` | bright brass | hover/active accent |
| `brass-dim` | `#aa8c14` | dull brass | secondary emphasis |

Contrast: `paper`/`paper-bright` on `night` ≈ readable at GUI scale; never put
`steel-dark` text on `night` (≈2:1 — fails legibility).

## 2. Layout grid

Two GUI classes exist today; the revamp standardizes on them:

| Class | Size (px) | Surfaces |
|---|---|---|
| **Tall** | 421 × 320 | role dialogue surfaces, input/quiz forms |
| **Standard** | 421 × 240 | admin selector, duplicate cleanup, confirmations, status, audit, result |

Common grid (all surfaces):

```
┌─ 12px margin ─────────────────────────────────────────────┐
│ HEADER BAND  y=8..30                                      │
│   icon 16×16 @ (12,8) · title @ (32,10) · header rule y=30│
│ CONTENT      y=34 .. FOOTER_Y-8                           │
│ FOOTER ROW   y=FOOTER_Y (240-class: 214 · 320-class: 292) │
│   buttons h=22, gap 16 between columns                    │
└───────────────────────────────────────────────────────────┘
```

- Margin: **12px** all sides (current convention — keep).
- Header band: 16×16 icon + title baseline at y=10; hairline rule at y=30 in
  `leather`.
- Footer row: 22px-high buttons; left-aligned primary action, right-aligned
  destructive/secondary. 240-class: y=214 (current). 320-class: y=292.
- Columns inside content: left column x=12 w=195, right column x=218 w=190,
  gutter 11px. Labels wrap at `NARROW_LABEL_CHARS`=30 (190px) /
  `WIDE_LABEL_CHARS`=62 (396px); line height 14px; button height 20px with 3px
  gap in scroll panels, 22px at GUI level.
- Scroll panels declare their rect explicitly (`init(x,y,w,h)`); children use
  panel-local coordinates. **Never** attach `setHoverText` to scroll-panel
  children (renders at panel-local origin — known artifact).
- Inline `addLabel` calls do not render inside scroll-panel regions on current
  CustomNPCs builds — multi-line text inside a panel region must use a
  disabled `addTextArea`, not stacked labels.

## 3. Icon taxonomy

Two delivery tiers; both resolve through the same `GuiIcons` registry:

- **Tier 1 (zero-asset, ships first):** `addItemRenderer` + existing Straja
  items — no new files.
- **Tier 2 (generated pack):** `assets/straja/textures/gui/<id>.png` used via
  `addTexturedRect`/`addTexturedButton`, swapped in by constant when present.

| Icon id | Meaning | Tier-1 item fallback | Planned PNG |
|---|---|---|---|
| `role.receptionist` | front-desk clerk | `mission_carnet` | `gui/icons/role_receptionist.png` |
| `role.secretary` | records secretary | `archive_stamp` | `gui/icons/role_secretary.png` |
| `role.instructor` | trainer/instructor | `fine_book` (verify `training_manual` texture — registered but no PNG today) | `gui/icons/role_instructor.png` |
| `role.armorer` | armory/quartermaster | `baton` | `gui/icons/role_armorer.png` |
| `role.jailer` | custody officer | `cuffs` | `gui/icons/role_jailer.png` |
| `role.archivist` | archive keeper | `archive_folder` | `gui/icons/role_archivist.png` |
| `action.assign` | confirm/assign | `archive_stamp` | `gui/icons/act_assign.png` |
| `action.unassign` | unbind/remove | `bolt_cutters` | `gui/icons/act_unassign.png` |
| `action.status` | status/info | `official_envelope` | `gui/icons/act_status.png` |
| `action.audit` | audit ledger | `order_book` | `gui/icons/act_audit.png` |
| `action.cleanup` | duplicate cleanup | `npc_cloner` | `gui/icons/act_cleanup.png` |
| `action.input` | written answer | `carbon_paper` | `gui/icons/act_input.png` |
| `quest.active` | quest in progress | `mission_carnet` | `gui/icons/quest_active.png` |
| `quest.new` | new quest | `official_envelope` | `gui/icons/quest_new.png` |
| `quest.done` | completed | `archive_stamp` | `gui/icons/quest_done.png` |
| `state.ok` | success | — (glyph) | `gui/icons/state_ok.png` |
| `state.denied` | denied/error | `fine_notice` | `gui/icons/state_denied.png` |
| `state.warn` | warning | `alarm_whistle` | `gui/icons/state_warn.png` |

Icon render size: **16×16** in headers and inline (GUI scale already zooms);
32×32 source PNGs downscaled only if 16 proves muddy at review.

Panel background: `gui/surface_panel.png` — 9-slice-friendly dark parchment
tile wired through `ICustomGui.setBackgroundTexture` behind a constant;
absence → current default look (degrade, never break).

## 4. Component states

| State | Visual |
|---|---|
| Default button | vanilla CNPC button; label in `paper` |
| Primary action | icon `brass`; label `paper-bright` |
| Destructive/authority | icon `seal-red`; label `paper`; confirm screen only |
| Disabled | `setEnabled(false)`; label `steel-dark` |
| Denied result | `state.denied` icon + `seal-bright` title text |
| Success result | `state.ok` icon + `brass` title text |
| Header | 16×16 icon + `paper-bright` title + `leather` rule |

Hover tooltips (`setHoverText`) on GUI-level buttons only; informational hover
moves to the header or an `ⓘ`-style info line, never scroll-panel children.

## 5. Wrap & text rules

- Wrap widths: 30 chars / 190px column, 62 chars / 396px full width — reuse
  `wrapText`; never emit a single-line label wider than its column.
- Role surface body: `addTextArea` (12,34,396,58) disabled — reads as flavor
  text; choices go in the left scroll panel, quest journal in the right column.
- Long values (UUIDs, profile ids): truncate middle with `…` at column width.

## 6. Small-window decision — **compress to 240**

Decision: all surfaces standardize to **421×240**. Rationale: the tall 320px
class already clips ±40px at small logical heights (observed: title rendered
off-screen); no content requires 320px once wrapped text scrolls. Role
surfaces move choices+journal into the two-column scroll region
(y=34..206) and the footer to y=214. This closes a known residual defect
rather than decorating around it.

## 7. Accessibility

- `paper`-on-`night` primary text ≥7:1 — passes at GUI scale.
- Denied/destructive never signaled by color alone — `state.denied` icon +
  explicit text.
- All actions remain button-activatable (keyboard focus = vanilla CNPC
  behavior); icon is additive, never the sole carrier of meaning.
- Text stays ≥9px effective height (14px rows); no condensed fonts.

## 8. Surface mockups

Annotated wireframes in `docs/media/mockups/` (SVG, viewable in browser):

| File | Surface | Class |
|---|---|---|
| `surface-role.svg` | role dialogue (instructor shown) | tall→240 |
| `surface-input.svg` | input/quiz form | tall→240 |
| `surface-selector.svg` | admin profile selector | 240 |
| `surface-confirm.svg` | assign confirmation | 240 |
| `surface-unassign.svg` | unassign confirmation | 240 |
| `surface-duplicates.svg` | duplicate-binding cleanup | 240 |
| `surface-status.svg` | admin status | 240 |
| `surface-audit.svg` | audit history | 240 |
| `surface-result.svg` | terminal result (denied shown) | 240 |
| `screen-native-form.svg` | `StrajaFormScreen` | native |

Each mockup marks pixel coordinates and the icon/component used; they are the
reference the M1–M4 implementations are reviewed against.
