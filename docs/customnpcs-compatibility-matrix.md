# CustomNPCs provider compatibility matrix

This is the M1 execution record for the optional production provider. Straja
does not bundle CustomNPCs and does not compile against its classes.

## Supported target

| Component | Supported value | Evidence |
|---|---|---|
| Minecraft | 1.21.1 | CustomNPCs NeoForge artifact metadata |
| NeoForge | 21.1.x; tested target 21.1.248 | CustomNPCs `neoforge.mods.toml` declares `[21.1.0,)`; Straja `neo_version` is 21.1.248 |
| CustomNPCs | `CustomNPCs-Unofficial-NeoForge-1.21.1.20251230.jar` | CurseForge file id 7411561, 10,789,250 bytes, SHA-1 `E2F3B58CEB5AAC4021D7BFD130E320925581471B` |
| CustomNPCs mod id | `customnpcs` | `META-INF/neoforge.mods.toml` |
| Straja integration mode | Optional reflective bridge | No CustomNPCs classes in the Straja compile or distributable jar |

The artifact is published by Goodbird as CustomNPCs-Unofficial. The public
repository's `master` branch is the 1.12.2 implementation and its `1.20.1`
branch is Forge-only; neither is treated as the 1.21.1 NeoForge source of
truth. The 1.21.1 API evidence therefore comes from the target jar and its
public runtime signatures.

## Verified API surface

The target jar contains and exposes:

| Capability | Verified API evidence | Straja use |
|---|---|---|
| NPC interaction | `noppes.npcs.api.event.NpcEvent$InteractEvent`, public `npc` and `player` fields, cancellable event | Resolve the host UUID, open the owned surface, cancel only owned interactions |
| GUI | `NpcAPI.createCustomGui(...)`, `IPlayer.showCustomGui(...)`, `ICustomGui` component methods | Render title, body, dialogue choices, quest journal, and action buttons |
| Action input | `IButton.setOnPress(GuiComponentClicked)` and `IPlayer.getMCEntity()` | Mint a Straja token and submit an `NpcActionRequest`; no command forwarding |
| Dialogue | `NpcAPI.getDialogs()`, `IDialogHandler`, `IDialog`, `IDialogOption`, `ICustomNpc.setDialog(...)` | API capability recorded; Straja renders canonical dialogue choices in `ICustomGui` and keeps dialogue state authoritative |
| Quest journal | `NpcAPI.getQuests()`, `IQuestHandler`, `IQuest`, `IQuestObjective`, player quest methods | API capability recorded; Straja renders canonical objectives/progress in `ICustomGui` and keeps quest state authoritative |
| Player identity | `IEntity.getUUID()`, `IPlayer.getMCEntity()` | Bind external host identity and recover the server player |
| Event bus | `NpcAPI.events()` returning the NeoForge event bus | Install a listener without linking Straja's classloader to optional API classes |

The M1 adapter currently renders canonical content through the CustomNPCs
custom GUI API. Dialogue and quest records are displayed as provider-neutral
content; they are not mutated through CustomNPCs handlers. This avoids making
provider-side scripts or quest persistence the authority for Straja outcomes.

## Explicit exclusions

- CustomNPCs scripting is not a Straja gameplay API.
- `executeCommand`, arbitrary script evaluation, NPC script callbacks, and
  generic command forwarding are excluded from the adapter.
- CustomNPCs cannot decide permissions, distance, rewards, progression, quest
  completion, or audit results.
- Unbound CustomNPCs are never cancelled by Straja and retain native behavior.
- Provider-specific portraits, rich text, and visual styling are optional;
  they cannot change the canonical action set or outcome.

## Verification status

- Source/API inspection: complete against the exact NeoForge artifact.
- Strict canonical profile loader and validation: covered by unit tests.
- Optional bridge absence/fail-closed behavior: covered by unit tests.
- Real disposable 1.21.1 server with the target jar and a populated NPC test
  world: verified on 2026-09-19 with Minecraft 1.21.1, NeoForge 21.1.248,
  and the exact SHA-1 recorded above. The server reached `Done` and Straja
  logged `CustomNPCs available=true`.
- Real client exercise: verified against a created `EntityCustomNpc`. The
  client applied `CustomNpcsNbttagsMixin`, opened native
  `noppes.npcs.client.gui.custom.GuiCustom`, decoded seven native components
  including `CustomGuiButton` and `CustomGuiTextArea`, invoked a native button
  callback, and received the refreshed surface.
- Canonical action/state security: covered by the M3 provider and admission
  tests, including player-bound tokens, stale-question rejection, bounded
  inputs, identity checks, and reconstruction after reopening.
- Native CustomNPCs dialogue and quest handlers are intentionally presentation
  capability evidence, not Straja persistence. CustomNPCs cannot become the
  authority for permissions, rewards, progression, or audit state.

## Rollout and rollback

CustomNPCs is optional. Removing its jar disables the provider; Straja remains
loadable and the provider returns `UNAVAILABLE` rather than claiming ownership.
Removing the provider registration leaves existing CustomNPCs interactions
native. StoryNPC can later implement the same `NpcSurfaceProvider` port without
changing canonical profiles or Straja action authority.
