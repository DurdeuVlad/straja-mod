# V2 gear ledger

The equipment ledger is authoritative for issued obligations, returned
quantities, loss/debt, and waivers. An inventory stack, item name, NBT, or
physical ticket is only a projection or delivery surface.

Every issue, fulfillment, return, and waiver has an idempotency key. Replaying
the same key returns the original result; reusing a key with a different
quantity is rejected as `OPERATION_PAYLOAD_MISMATCH`. Partial returns cannot
drive an obligation below zero. Debt waivers require central authorization.

Existing owned V1 gear can be imported as a one-time compatibility record. It
does not silently mint a new entitlement and it does not grant rank,
appointment, or document validity.
