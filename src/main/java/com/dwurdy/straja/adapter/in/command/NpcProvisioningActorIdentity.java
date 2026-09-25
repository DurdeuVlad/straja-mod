package com.dwurdy.straja.adapter.in.command;

/** Stable audit identity for command-driven NPC provisioning. */
final class NpcProvisioningActorIdentity {
    private NpcProvisioningActorIdentity() {}

    static String fromSource(String sourceName, String playerUuid) {
        if (playerUuid != null && !playerUuid.isBlank()) {
            try {
                return java.util.UUID.fromString(playerUuid.strip()).toString();
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("player command source must have a valid UUID", error);
            }
        }
        String normalized = sourceName == null ? "" : sourceName.strip();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException(
                    "command source name must be non-blank and at most 128 characters");
        }
        return "server".equalsIgnoreCase(normalized) ? "console" : normalized;
    }
}
