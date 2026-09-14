# DC-005 — Vampirism provider boundary

The compatibility target is the exact NeoForge artifact
`Vampirism-1.21-1.10.13.jar`, with compile-time API coordinate
`de.teamlapen.vampirism:Vampirism:1.21-1.10.13:api`.

When Vampirism is present, the isolated adapter observes the public vampire
player API and supplies ownership flags to the pure lethal-event resolver.
The initial eligible vampire lethal hit is deliberately allowed through
Straja's `LivingIncomingDamageEvent` handler so Vampirism's later
`LivingDeathEvent` hook can create DBNO and cancel death. Follow-up ordinary
hits against active Vampirism DBNO are preserved, while stake remains the
explicit Vampirism finisher.

Straja does not create a second downed record for Vampirism and does not infer
capture or jail from the provider. Capture and jail remain explicit Straja
actions and cannot overlap the provider-owned DBNO/resurrection lifecycle.

The adapter is loaded reflectively only after the `vampirism` loader ID is
detected. Without Vampirism, the optional class is not linked and Straja's
normal absence behavior remains available. If the optional adapter cannot be
loaded, the fail-safe disables generic Straja downed ownership rather than
allowing two providers to claim the same event.
