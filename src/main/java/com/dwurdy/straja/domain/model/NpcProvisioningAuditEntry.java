package com.dwurdy.straja.domain.model;

import java.util.Objects;

/** Durable, provider-neutral audit record for an NPC provisioning operation. */
public record NpcProvisioningAuditEntry(
        String eventId,
        String bindingId,
        Action action,
        String providerId,
        String providerInstanceId,
        String actorId,
        String oldProfileId,
        String newProfileId,
        Outcome outcome,
        String resultCode,
        String failureReason,
        long occurredAtEpochMillis) {

    public NpcProvisioningAuditEntry {
        eventId = required(eventId, 64, "eventId");
        bindingId = optional(bindingId, 128, "bindingId");
        Objects.requireNonNull(action, "action");
        providerId = required(providerId, 64, "providerId");
        providerInstanceId = required(providerInstanceId, 128, "providerInstanceId");
        actorId = required(actorId, 128, "actorId");
        oldProfileId = optional(oldProfileId, 128, "oldProfileId");
        newProfileId = optional(newProfileId, 128, "newProfileId");
        Objects.requireNonNull(outcome, "outcome");
        resultCode = required(resultCode, 64, "resultCode");
        failureReason = optional(failureReason, 512, "failureReason");
        if (occurredAtEpochMillis < 0) {
            throw new IllegalArgumentException("occurredAtEpochMillis must not be negative");
        }
    }

    private static String required(String value, int maxLength, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maxLength + " characters");
        }
        return normalized;
    }

    private static String optional(String value, int maxLength, String name) {
        if (value == null) return "";
        String normalized = value.replace('\r', ' ').replace('\n', ' ').strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    public enum Action {
        ASSIGN,
        REPROJECT,
        UNASSIGN
    }

    public enum Outcome {
        PENDING,
        ACCEPTED,
        REJECTED,
        UNAVAILABLE,
        UNKNOWN
    }
}
