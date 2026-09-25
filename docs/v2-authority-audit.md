# V2 authority audit

Sensitive V2 mutations use `AuthorizationService` with persisted personnel,
appointment, station/fallback, jurisdiction, conflict, version, and
mobilization context. No V2 service treats an item, NPC, GUI token, scoreboard
rank, or display name as authority.

The remaining direct `rank` checks are in V1 compatibility services such as
legacy patrol, fines, complaints, archive, armory, custody, and report flows.
They operate on `GuardState` and are retained for additive migration. The
runtime projects legacy state into V2 on login; once a V2 personnel record
exists, `PlayerService` delegates capability decisions to the V2 authorization
predicate and stale V1 flags cannot resurrect suspended or terminated records.

Value-moving V2 paths require stable keys: operation journal keys, document
redemption keys, equipment operation keys, campaign reservation/fulfillment
keys, settlement keys, and outbox event keys. Replays return the original
record; canonical request fingerprints make a reused key with a different
request fail closed. The same rule applies to personnel authorization,
settlement creation, document issue/instrument/redemption, reprints, form
requests, equipment issues and deliveries, generated mission offers, and the
operation journal.

## Recovery and outbound delivery

Authoritative aggregates persist their outbound notification intent in the
same SavedData-backed aggregate write as the state mutation. The server-thread
projection replays those intents into the shared outbox on startup and before
each delivery pass. HTTP delivery runs on a bounded worker, but claim,
completion, retry, and dead-letter transitions return to the server executor.
Event keys include the aggregate version where a record can be authorized or
changed more than once; a later event therefore cannot be suppressed by the
first event for that aggregate.

Promotion approval persists an APPROVED/PENDING intent before updating the
personnel projection. Startup reconciliation either completes the projection
or records a durable CONFLICT without overwriting a newer personnel version.
Configured commissioners are normalized to an active full-time Inspector
before appointment, including legacy lower-grade or part-time records.

Physical payout is treated as a single atomic provider operation. A partial
provider result or an incomplete historical receipt is REVIEW-only and is not
automatically retried. A normal inventory/provider failure is
FAILED_RETRYABLE; only that state enters the automatic retry queue.

Qualification evidence cannot be submitted through a free-form caller result.
The server training ledger and the commissioner assessment path are the only
trusted writers, and the latter passes the central approval predicate.

The search also reviewed the older V1 value-moving adapters. `ArchiveService`
copy/envelope delivery, legacy `ArmoryService` purchases, and legacy reward
receipts remain compatibility surfaces over the pre-V2 stores; they are not
used as V2 authority and are not represented as V2 operation-journal entries.
The native form route is one-use, while the V2 document, equipment, campaign,
settlement, and outbox paths use the stable keys listed above. Any future
cutover that removes those V1 surfaces must either retire them or route them
through the V2 operation journal before claiming V2 retry guarantees for
those commands.

Physical item metadata is routing input only. The owning service reloads the
authoritative record and checks status, holder, recipient, station, and
capability before any read, redemption, or delivery.

Player-facing V2 workflows are provider-neutral content: the CustomNPCs
adapter receives GUI, dialogue, action-input, and quest-journal surfaces for
career, documents, mission registers, equipment, and professional work. FAQ
answers resolve station templates from the authoritative V2 station record.
The debug-text provider remains a diagnostic mirror and is not the normal
player surface. StoryNPC remains an intentionally deferred future provider.
