# NPC binding lifecycle

M2 makes provider mappings durable without making a provider authoritative.

## Durable records

`NpcBindingStore` is saved under the `npc_bindings` SavedData key. Each record
keeps the stable logical binding id, provider id, host entity UUID, optional
provider NPC id, role, station, canonical profile id, schema version, and the
operator/time that assigned it. Pending provider operations are persisted
separately in the same aggregate.

The host entity UUID is a mapping used for proximity and event translation. It
is not a player permission, reward, quest, or progression key. The logical
binding id remains the stable identity when the host entity is replaced or the
provider changes.

## Operations

- `bind` refuses a different mapping under an existing logical id. A caller
  must use `rebind` explicitly.
- `bindAndPublish` validates the canonical profile before binding, then
  publishes it through the provider registry. A publish failure leaves the
  binding durable and inspectable but without a trusted published surface.
- `rebind` explicitly unbinds the old mapping before binding the replacement.
  A failed replacement cleans up and restores the previous mapping; if the
  provider is unavailable during rollback, the old mapping is retained with a
  pending recovery operation instead of being discarded.
- `inspect` returns `UNBOUND`, `BOUND`, or `UNKNOWN`; unknown is fail-closed.
- `unbind` removes the durable record only after the provider confirms removal.

## Restart recovery

On server start the lifecycle service replays durable bindings. It first
reconciles any provider operation whose prior result was unknown, then confirms
provider ownership, and finally republishes the canonical profile. If the
optional provider is missing, recovery remains pending; it does not delete the
logical record or claim success.

The existing provider registry still prevents two providers from claiming one
logical binding in-process. The persisted pending operation closes the restart
gap around bind, unbind, and publish.

## Legacy migration

`NpcBindingMigrationService` creates a side-effect-free, deterministic proposal
from the legacy Straja entity registry. The proposal preserves the entity UUID
and derives a stable `straja.legacy.<uuid-without-dashes>` logical id. It does
not claim external provider ownership; an explicit lifecycle bind is required.

## StoryNPC boundary

StoryNPC is deferred. It will consume the same canonical profile and lifecycle
ports when ready. No CustomNPCs class, script, quest object, or GUI object is
stored in the canonical binding record.
