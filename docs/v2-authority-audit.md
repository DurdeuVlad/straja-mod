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
record; payload mismatches fail closed.

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
