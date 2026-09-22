package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.domain.model.NpcProviderId;
import java.util.List;
import java.util.Optional;

/**
 * Provider-neutral admin surface for assigning authored NPC profiles to host
 * entities. Provider adapters render this contract; they do not decide which
 * profile, quest, or dialog is authoritative.
 */
public interface NpcProvisioningUseCase {
    List<ProfileOption> profiles(NpcProviderId providerId);

    Optional<AssignmentView> current(NpcProviderId providerId, String hostEntityUuid);

    ProvisioningResult assign(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            String profileId);

    ProvisioningResult unassign(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId);

    record ProfileOption(
            String profileId,
            String title,
            String summary,
            String roleId,
            String stationId,
            int schemaVersion,
            boolean enabled,
            String disabledReason) {
        public ProfileOption {
            if (profileId == null || profileId.isBlank()) throw new IllegalArgumentException("profileId");
            if (title == null || title.isBlank()) throw new IllegalArgumentException("title");
            if (summary == null || summary.isBlank()) throw new IllegalArgumentException("summary");
            if (roleId == null || roleId.isBlank()) throw new IllegalArgumentException("roleId");
            if (stationId == null || stationId.isBlank()) throw new IllegalArgumentException("stationId");
            if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
            if (disabledReason == null) disabledReason = "";
            if (enabled && !disabledReason.isBlank()) {
                throw new IllegalArgumentException("enabled profiles cannot have a disabled reason");
            }
        }
    }

    record AssignmentView(
            String bindingId,
            String providerId,
            String hostEntityUuid,
            String profileId,
            String roleId,
            String stationId,
            String assignedBy,
            long assignedAtEpochMillis) {}

    record ProvisioningResult(Status status, String code, String message) {
        public ProvisioningResult {
            if (status == null) throw new IllegalArgumentException("status");
            if (code == null || code.isBlank()) throw new IllegalArgumentException("code");
            if (message == null || message.isBlank()) throw new IllegalArgumentException("message");
        }

        public static ProvisioningResult accepted(String message) {
            return new ProvisioningResult(Status.ACCEPTED, "ok", message);
        }

        public static ProvisioningResult rejected(String code, String message) {
            return new ProvisioningResult(Status.REJECTED, code, message);
        }

        public static ProvisioningResult unavailable(String message) {
            return new ProvisioningResult(Status.UNAVAILABLE, "provider-unavailable", message);
        }

        public static ProvisioningResult unknown(String message) {
            return new ProvisioningResult(Status.UNKNOWN, "provider-state-unknown", message);
        }
    }

    enum Status {
        ACCEPTED,
        REJECTED,
        UNAVAILABLE,
        UNKNOWN
    }
}
