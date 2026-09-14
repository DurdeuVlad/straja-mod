# Foreign NPC Binding — Implementation Issues

**Status:** implemented (issues #77–#80)
**Decisions:** NPC wand is the bind surface; Straja consumes the click on
bound NPCs (host dialog suppressed); jailer damage parity is implemented;
binding keys on entity UUID (host mods that re-key on respawn recover via
detach + rebind).
**Motivation:** the server already has placeholder NPCs from a previous
CustomNPCs-style setup. Admins should be able to attach a Straja role
(the "script") to an existing foreign entity in place — no respawn, no
appearance change — instead of replacing every NPC with a freshly spawned
`StrajaNpcEntity`.

## Model

The NPC registry is already keyed by entity UUID and is entity-agnostic:
`NpcRegistry.Record` maps `entityUuid → {role, skin, displayName}` and
`NpcAdminService.assignRole`/`register` accept any UUID string. A "bound"
foreign NPC is therefore just a registry record whose UUID belongs to an
entity that is **not** a `StrajaNpcEntity`. The role lives entirely in the
registry — the foreign entity's type, model, skin, name and AI stay
untouched.

Three edges currently assume `StrajaNpcEntity` and define the work:

- `StrajaNpcEntity.mobInteract` is the only entry into
  `NpcInteractionService.interact`; `StrajaEvents.onEntityInteract` drops
  non-player, non-tool targets before any registry lookup.
- `AdminToolService.npcWandMenu` refuses unregistered targets and
  `npcAssign` requires an existing record — there is no bind path.
- `tool-npc-remove-confirm` discards the entity — correct for a spawned
  `StrajaNpcEntity`, destructive for a bound foreign NPC.

Out of scope: importing/reading the foreign NPC's own configuration,
migrating CustomNPCs data, and changing how `StrajaNpcEntity` behaves.

---

## BIND-001 — Route clicks on bound foreign entities to the role surface

Today a right-click on a non-player entity only reaches Straja through
`StrajaNpcEntity.mobInteract`. A bound foreign NPC needs the same role
routing via `PlayerInteractEvent.EntityInteract`.

- In `StrajaEvents.onEntityInteract`, after the admin-tool claim and
  before the player-target routing, look up
  `runtime.npcRegistry().registration(targetUuid)` for non-player targets;
  when a record with a known role exists, route to
  `NpcRoles.interact(role, player, level)` and consume the event
  (**decided: Straja owns the click** — the host NPC's own dialog/GUI is
  suppressed). Claim both hands but act only on `MAIN_HAND` — same
  pattern as the wand/cloner claim, including the `toolClickHandledAt`
  stamp so a trailing item-use packet can't fire a held tool's air
  gesture (e.g. sneak-click a bound NPC while holding the patrol wand
  must not finish the route).
- The same routing covers registered `StrajaNpcEntity` targets — the
  registry is the source of truth (`onNpcJoin` already reflects the
  record onto the entity); an unregistered `StrajaNpcEntity` still falls
  through to `mobInteract` on its entity field, unchanged.
- Relax `NpcRoles.interact(String, StrajaNpcEntity, Player, ServerLevel)`
  — the `npc` parameter is unused by the body; drop it or accept a generic
  `Entity` so the registry role alone drives the surface.
- Registry records with no role / unknown role fall through untouched
  (foreign NPC keeps its native behavior).
- Players (`ServerPlayer` targets) must never be routable as NPCs — the
  custody routing keeps precedence and player UUIDs cannot be bound.

## BIND-002 — Wand bind / rebind / detach on foreign entities

**Decided surface:** the existing NPC wand — it already renders a role
menu via `tool-npc-assign:<uuid>-<role>` tokens.

- `npcWandMenu` on an **unregistered** bindable entity shows the role
  list ("Atașează un rol"); choosing a role creates the registry record
  via `NpcAdminService.assignRole` (creates-on-missing). No spawn, no
  name/skin writes — appearance untouched.
- The event layer knows the target's real type; pass `strajaNative` and
  `bindable` (non-player) flags into the menu op: player targets keep
  today's refusal (players are never NPC-bound), and `Redenumește` /
  `Skin nou` are hidden for foreign targets — those registry fields only
  reflect onto `StrajaNpcEntity` on join and would be dead options.
- `npcAssign` accepts a not-yet-registered target (bind = create); the
  `tool-npc-assign` stale-click guard relaxes to "holder still gated"
  since a first bind has no record. The `NpcRoles` handler already
  tolerates a null `loadedNpc` — keep the "reflect persisted record, not
  raw token" check for native entities.
- Detach semantics: `Elimină NPC` on a bound foreign entity removes the
  registry record **only** — `loadedNpc` already returns null for foreign
  entities so `discard()` never runs; confirm wording should say the
  entity itself is kept. On a loaded `StrajaNpcEntity` keep today's
  remove + discard.
- Optional (cheap, same plumbing): cloner applies its captured template
  role onto a clicked foreign entity instead of only spawning — capture
  from a registered NPC, click foreign NPC → `assignRole`, no spawn.

## BIND-003 — Jailer damage lifecycle parity

**Decided:** implement parity. The assault lifecycle already lives in
`StrajaEvents` (`LivingDamageEvent.Post` → `onNpcDamage`,
`LivingDeathEvent` → `onEntityDeath`) and `createJailerAssaultMission`
dedupes per `incidentKey` + upgrades severity — only the entity-type
filter ties it to `StrajaNpcEntity`.

- `onNpcDamage` / `onEntityDeath`: resolve the role registry-first
  (`npcRegistry().registration(uuid)`), falling back to the entity field
  for unregistered `StrajaNpcEntity`. A bound foreign jailer then
  produces the same WOUNDED mission / KILLED escalation as a native one.
- New `LivingIncomingDamageEvent` hook for bound **foreign** NPCs with
  role jailer: when `jailerGuardImmunity` is on and the attacker is an
  on-duty guard, cancel the hit — matching `jailerMayTakeDamage` for
  native entities. Non-jailer bound NPCs keep the host mod's damage
  rules untouched.
- Documented requirement: for the assault lifecycle to fire on a bound
  foreign jailer, the host NPC must be damageable in its own mod (a
  fully invulnerable foreign entity produces no damage events); if the
  host mod instead blocks the hit after `LivingIncomingDamageEvent`, the
  worst case is a WOUNDED mission without landed damage — acceptable and
  still roleplay-correct (the assault attempt is what is punished).

Related assumption (UUID binding, decided): bindings key on entity UUID.
If the host mod respawns a dead NPC under a fresh UUID the record
dangles — recovery is detach + rebind; a dangling-record cleanup is
acceptable as manual admin action.

## BIND-004 — Tests, docs, parity

- Unit: registry bind on arbitrary UUID; wand menu on unregistered target
  lists roles; assign creates record; detach removes record without
  touching entity; `EntityInteract` routing resolves role from registry
  (adapter-level seam or integration test); players cannot be bound;
  stale-click guard refuses a just-detached NPC; foreign jailer immunity
  cancels on-duty-guard hits when `jailerGuardImmunity` is on;
  damage/death events create the assault mission by registry role.
- `parity-matrix.md` rows for bind/attach/detach.
- README admin-tools section: document that the wand binds roles onto
  existing NPCs without appearance changes; note the damageability
  requirement for bound jailers and the dangling-UUID recovery path.
- `docs/foreign-npc-binding-implementation-issues.md` status flips as
  issues close.
