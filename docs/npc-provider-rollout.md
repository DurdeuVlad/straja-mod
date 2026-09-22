# NPC provider rollout

Straja stores NPC identity, profile, dialogue, quest state, action tokens,
permissions, rewards, records, and audit history. A provider only renders and
transports the player interaction.

## Modes

`[npc].providerMode` accepts:

- `customnpcs` — the normal player-facing provider. CustomNPCs supplies the
  native GUI/dialogue/quest surface while Straja remains authoritative.
- `debug-text` — a diagnostic mirror. It is never enabled by default and is
  accepted only when both `[npc].debugTextEnabled` and the existing debug
  security gates permit it. The mirror is written to the server log and is
  not a normal player UI.

The mode is read at server startup. Changing it requires a restart so the
active provider is observable and deterministic. StoryNPC is intentionally not
listed as a runtime dependency; it may implement the same provider port later.

## Operator controls

All commands are under the existing OP-gated `/straja npc` surface:

- `/straja npc provider status` — reports configured mode, provider
  availability, and every durable binding.
- `/straja npc provider migrate <customnpcs|debug-text>` — preflights provider
  availability and profile compatibility, then migrates each logical binding
  while preserving its binding id, host identity, profile, and canonical
  gameplay state. A failed replacement attempts to restore the prior mapping.
- `/straja npc provider recover` — reconciles pending provider operations after
  an interrupted bind, publish, unbind, or migration.

Migration is explicit. Straja does not silently switch providers because an
optional provider is unavailable. A provider failure leaves the binding
fail-closed and visible in `status` for recovery.

## Rollback

Rollback is a provider mapping operation, not a gameplay-data rollback:

1. Set `[npc].providerMode` to the previous provider.
2. Restart the server.
3. Run `/straja npc provider migrate <previous-provider>` if bindings were
   moved.
4. Run `/straja npc provider recover` and inspect the status output.

No dialogue, quest, progression, document, custody, reward, or audit state is
deleted by provider migration.
