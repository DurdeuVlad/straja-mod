# Icon generation prompt pack — NPC surface revamp (M2)

Generator: **Codex Luna 5.6** (external agent). Produces the Tier-2 icon pack
for `assets/straja/textures/gui/icons/`.

## Global constraints (apply to every icon)

- **Canvas:** exactly 16×16 px, PNG, binary alpha (0 or 255 — no
  semi-transparency). Icon art sits inside a 14×14 safe area with 1px
  transparent padding.
- **Style:** flat-shaded Minecraft item-style pixel art. Single light source
  from top-left; 1px dark outline (`ink` #281400) on the outer silhouette only;
  no gradients, no anti-aliasing, no dithering.
- **Palette:** ONLY these 15 colors (+transparent). No others may appear:

  | Token | Hex | Use for |
  |---|---|---|
  | paper | #dcd7be | paper, labels |
  | paper-bright | #f0e9c9 | lit paper edge, highlights |
  | paper-dim | #b4af96 | paper shadow |
  | leather | #78501e | leather, wood |
  | leather-deep | #503c1e | leather shadow |
  | ink | #281400 | outline, darkest shadow |
  | steel | #787882 | metal |
  | steel-bright | #a0a0aa | metal highlight |
  | steel-dark | #50505a | metal shadow |
  | night | #141428 | dark fill only (never dominant) |
  | seal-red | #8c2828 | wax, authority marks |
  | seal-bright | #a02828 | wax highlight |
  | brass | #d2b43c | brass fittings, success |
  | brass-bright | #dcbe50 | brass highlight |
  | brass-dim | #aa8c14 | brass shadow |

- **Readability:** silhouette must be identifiable at 100% zoom; max ~3 hues
  per icon (outline excluded); no text glyphs smaller than 3×5 px — prefer
  symbols over letters.

## Per-icon prompts

Standard suffix for every prompt: "16×16 pixel art, flat shaded, Minecraft
item style, transparent background, 1px dark outline, palette per spec".

| Icon id → file | Subject prompt | Palette emphasis |
|---|---|---|
| `role.receptionist` → `role_receptionist.png` | a wooden front-desk counter bell with a small paper ticket leaning on it | brass bell, paper ticket |
| `role.secretary` → `role_secretary.png` | a rubber stamp pressing a sealed document | leather stamp, seal-red wax |
| `role.instructor` → `role_instructor.png` | an open instruction manual with a pointer stick across it | paper pages, leather cover |
| `role.armorer` → `role_armorer.png` | a steel pauldron with a brass rivet band | steel, brass band |
| `role.jailer` → `role_jailer.png` | a pair of steel handcuffs with a short chain | steel, steel-bright |
| `role.archivist` → `role_archivist.png` | a leather archive folder with paper edge showing | leather, paper |
| `action.assign` → `act_assign.png` | a rubber stamp mid-press leaving a red seal mark | seal-red, leather handle |
| `action.unassign` → `act_unassign.png` | bolt cutters cutting a chain link | steel jaws, steel-dark link |
| `action.status` → `act_status.png` | a sealed envelope with a brass clasp | paper, brass clasp |
| `action.audit` → `act_audit.png` | a ledger book with ruled lines and a brass corner | leather cover, paper lines |
| `action.cleanup` → `act_cleanup.png` | a broom sweeping two overlapping ghost outlines away | leather handle, paper-dim bristles |
| `action.input` → `act_input.png` | a quill writing a stroke on paper | ink quill, paper |
| `quest.active` → `quest_active.png` | a rolled mission carnet with a brass band | paper scroll, brass band |
| `quest.new` → `quest_new.png` | an envelope with a bright seal, unread | paper-bright, seal-bright |
| `quest.done` → `quest_done.png` | a document stamped with a red check seal | paper, seal-red check |
| `state.ok` → `state_ok.png` | a bold check glyph inside a brass circle | brass ring, brass-bright check |
| `state.denied` → `state_denied.png` | a bold X glyph inside a red seal ring | seal-red ring, seal-bright X |
| `state.warn` → `state_warn.png` | a brass alarm whistle | brass, brass-bright lip |

## Panel background tile

| Id → file | Prompt | Notes |
|---|---|---|
| `panel_bg` → `gui/panel_bg.png` | 32×32 tileable parchment-dark panel texture: `night` base with faint `leather-deep` grain streaks and a 1px `leather` inner edge | tiles seamlessly; deliberately low-contrast — it sits behind text |

## Provenance

`tools/icons/PROVENANCE.md` records, per file: source prompt, generator
name/version, generation date, and normalization result.
