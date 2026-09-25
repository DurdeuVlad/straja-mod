# Testing and release checks

Fast checks:

```text
./gradlew.bat test
./gradlew.bat build
```

The unit suite covers schema migration, idempotent operation replay, central
authorization, promotion evidence/versioning, station fallback, documents,
equipment partial return, mobilization scope, campaign quota, missions and
settlement uniqueness. `CommandSurfaceTest` verifies permission boundaries and
the NPC token boundary.

GameTests run against a fresh disposable world. Reusing a previous GameTest
world is unsupported by the harness and can retain loaded legacy state; when
validating a release, use an isolated run directory and require the log line
`All ... required tests passed :)`.

Before release, also inspect `/straja doctor consistency` and each scoped
doctor report, confirm pending outbox events are expected, and verify that a
restart/login retries pending settlements and interrupted outbox sends.
