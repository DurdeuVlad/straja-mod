# NPC binding lifecycle

M2 makes provider mappings durable without making a provider authoritative.

## Durable records

`NpcBindingStore` is saved under the `npc_bindings` SavedData key. Each record
keeps the stable logical binding id, provider id, host entity UUID, optional
provider NPC id, role, station, public `NpcProfileId`, internal content-profile
id, profile schema version, and the operator/time that assigned it. Public
profile IDs use stable lowercase `namespace:path` names such as
`straja:jailer`; internal content-profile IDs continue to address the authored
JSON resource. Profile resources spell these fields `npcProfileId` and
`contentProfileId`; legacy resources may use the old `profileId` field for the
internal content key. The catalog descriptor carries the dialogue-node, quest,
and action content IDs referenced by each profile. It also keeps the last-known
realm and block position when the provider can supply them; this location is
diagnostic, not an authority for proximity, permissions, or gameplay. Pending
provider operations, provider-switch transaction phases, exact pending
assignment candidates, per-host assignment revisions, and the structured
provisioning audit are persisted in the same aggregate. Assignment revisions
are monotonic across provider changes and unassigns; they are independent of
the bounded audit history. At most 2,048 audit events, 256 unresolved audit
intents, and 256 distinct active recovery bindings are accepted. The recovery
binding count includes pending provider operations and rebind transactions, so
re-sync cannot create work outside the limit. At either cap, new provider
mutations fail closed before invoking a provider; a safe unassign may supersede
its own pending assignment intent without losing the exact candidate. Existing
operations can still be reconciled and completed. During load, legacy over-cap
pending state is preserved rather than discarded, and terminal history is
pruned as that state resolves.

The host entity UUID is a mapping used for proximity and event translation. It
is not a player permission, reward, quest, or progression key. The logical
binding id remains the stable identity when the host entity is replaced or the
provider changes.

## Admin provisioning and diagnosis

The CustomNPCs authoring gesture is deliberately short: spawn a normal
CustomNPC, use the admin-only Straja NPC wand to left-click it, choose a
preconfigured profile, and confirm. Straja persists the assignment and
projects the selected canonical dialogue, quest, and action surface through
the provider's native GUI. The provider does not own profile content or
gameplay outcomes. The selector and assignment status use public profile IDs;
existing dotted content IDs remain accepted only as a compatibility input for
older command callers.

The admin selector exposes Status, Re-sync profile, and Unassign. Status shows
the provider/instance and binding ids, profile/schema, role/station,
last-known location, lifecycle/pending state, updated-by/time, current host
resolution, and last projection error. Audit history shows the last ten events
from the durable bounded audit (assignment, replacement, re-projection, and
unassignment; actor, old/new profile, result, reason, and timestamp). Re-sync
reprojects the canonical profile without changing the assignment. Unassign is
allowed for a possible orphan so administrators can clean up a record even if
its entity cannot currently be resolved.

An entity not found among loaded server levels is reported as “possible
orphan,” not as proven deletion: an unloaded chunk can explain the absence.
Straja preserves the assignment until an administrator re-syncs or explicitly
unassigns it. Last-known location supports diagnosis but does not auto-delete
or retarget the NPC.

A host with an unresolved provider operation is visible as `UNKNOWN` and cannot
be assigned to a second provider or profile until recovery reconciles the
operation. This preserves one logical binding during explicit provider
switches and prevents an ambiguous timeout from creating a duplicate. New
assignment/replacement/unassignment operations are serialized by the
provisioning service. A host that already has multiple durable mappings from a
legacy version is not guessed or auto-repaired: new assignment is rejected
until an administrator explicitly unassigns the unintended mapping.

## Operations

- `bind` refuses a different mapping under an existing logical id. A caller
  must use `rebind` explicitly.
- `bindAndPublish` validates the canonical profile before binding, then
  publishes it through the provider registry. A publish failure leaves the
  binding durable and inspectable but without a trusted published surface.
- `rebind` first persists the previous mapping, replacement mapping, and phase
  (`UNBIND`, `BIND`, `PUBLISH`, `RESTORE`, or `UNASSIGN`), then advances one
  provider step at a time. The previous logical assignment stays durable until replacement
  publication succeeds. A failed replacement is reconciled and removed before
  restoring the previous provider. If ownership cannot be proven, the phase
  stays pending and the logical NPC remains `UNKNOWN`; recovery never writes
  the previous provider into the registry while a replacement may still own
  the binding.
- `inspect` returns `UNBOUND`, `BOUND`, or `UNKNOWN`; unknown is fail-closed.
- `unbind` removes the durable record only after the provider confirms removal.
  An administrator can cancel a pending first bind or provider switch from the
  same GUI. Cancellation first persists `UNBIND`, reconciles the candidate, and
  removes it only after the provider confirms no ownership or confirms removal;
  unknown/unavailable ownership stays locked rather than risking an orphan or
  cross-provider duplicate.

## Restart recovery

On server start the lifecycle service replays both durable bindings and
pending-only first binds. It reconstructs an interrupted assignment from its
persisted candidate, resumes any rebind phase, and reconciles provider ownership
before retrying a possibly-mutating operation. Interrupted unassigns are also
reconciled before the logical record is removed. Once provider recovery reaches
a durable final state, any still-pending assignment audit entry is completed
from that state. If the optional provider is missing or its ownership probe is
inconclusive, recovery remains pending; it does not delete the logical record,
claim success, or overwrite another provider's ownership.

The provider registry prevents two providers from claiming one logical binding
in-process. The persisted pending operation, exact provisioning candidate, and
rebind phase close the restart gaps around bind, unbind, publish, and provider
switch. A provider's `reconcile(binding)` must return accepted only after
confirming the expected mapping; rejection means it confirmed no ownership of
that mapping, while unknown/unavailable keeps the transaction fail-closed.

## Legacy migration

`NpcBindingMigrationService` creates a side-effect-free, deterministic proposal
from the legacy Straja entity registry. The proposal preserves the entity UUID
and derives a stable `straja.legacy.<uuid-without-dashes>` logical id. It does
not claim external provider ownership; an explicit lifecycle bind is required.
On load, schema-2 bindings that have only the old internal content-profile ID
are upgraded to schema 3. When that content profile still exists, Straja
resolves and persists its current public `NpcProfileId`; an unknown profile
remains inspectable and is not deleted as part of migration. When an authored
resource keeps its public ID but changes its internal content ID or schema,
the saved binding follows the public ID to the current resource and version.
An unknown/retired explicit public ID is not rebound through a coincidentally
reused internal content ID; it stays `UNKNOWN` and can be explicitly unassigned.

## StoryNPC boundary

StoryNPC is deferred. It will consume the same canonical profile and lifecycle
ports when ready. No CustomNPCs class, script, quest object, or GUI object is
stored in the canonical binding record.
