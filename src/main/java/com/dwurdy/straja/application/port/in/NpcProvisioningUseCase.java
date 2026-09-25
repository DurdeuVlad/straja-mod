package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProvisioningAuditEntry;
import com.dwurdy.straja.domain.model.NpcHostLocation;
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

    /** All durable assignments targeting the host, including legacy duplicates requiring cleanup. */
    default List<AssignmentView> assignments(NpcProviderId providerId, String hostEntityUuid) {
        return current(providerId, hostEntityUuid).stream().toList();
    }

    /** Opaque durable revision for optimistic-concurrency checks on an admin confirmation. */
    String assignmentRevision(String hostEntityUuid);

    ProvisioningResult assign(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            String profileId);

    /** Assigns a profile while recording a last-known host realm/position when available. */
    ProvisioningResult assignWithLocation(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            String profileId,
            java.util.Optional<NpcHostLocation> hostLocation);

    /** Assigns only if the host still has the revision shown when its confirmation opened. */
    ProvisioningResult assignIfRevisionMatches(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            String profileId,
            java.util.Optional<NpcHostLocation> hostLocation,
            String expectedRevision);

    ProvisioningResult unassign(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId);

    /** Unassigns only if no administrator changed the host after its confirmation opened. */
    ProvisioningResult unassignIfRevisionMatches(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            String expectedRevision);

    /** Unassigns one exact durable binding while preserving the host revision guard. */
    ProvisioningResult unassignBindingIfRevisionMatches(
            NpcProviderId providerId,
            String hostEntityUuid,
            String bindingId,
            String actorId,
            String expectedRevision);

    ProvisioningResult reproject(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId);

    Optional<AssignmentStatus> status(NpcProviderId providerId, String hostEntityUuid);

    List<NpcProvisioningAuditEntry> auditTrail(NpcProviderId providerId, String hostEntityUuid);

    record ProfileOption(
            String profileId,
            String title,
            String summary,
            String roleId,
            String stationId,
            int schemaVersion,
            boolean enabled,
            String disabledReason,
            List<NpcContentId> dialogueContentIds,
            List<NpcContentId> questContentIds,
            List<NpcContentId> actionContentIds) {
        public ProfileOption {
            if (profileId == null || profileId.isBlank()) throw new IllegalArgumentException("profileId");
            if (title == null || title.isBlank()) throw new IllegalArgumentException("title");
            if (summary == null || summary.isBlank()) throw new IllegalArgumentException("summary");
            if (roleId == null || roleId.isBlank()) throw new IllegalArgumentException("roleId");
            if (stationId == null || stationId.isBlank()) throw new IllegalArgumentException("stationId");
            if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
            if (disabledReason == null) disabledReason = "";
            dialogueContentIds = contentIds(dialogueContentIds, "dialogueContentIds");
            questContentIds = contentIds(questContentIds, "questContentIds");
            actionContentIds = contentIds(actionContentIds, "actionContentIds");
            if (enabled && !disabledReason.isBlank()) {
                throw new IllegalArgumentException("enabled profiles cannot have a disabled reason");
            }
        }

        private static List<NpcContentId> contentIds(List<NpcContentId> values, String name) {
            List<NpcContentId> copy = List.copyOf(java.util.Objects.requireNonNull(values, name));
            if (copy.size() > 64 || new java.util.HashSet<>(copy).size() != copy.size()) {
                throw new IllegalArgumentException(name + " must contain at most 64 unique IDs");
            }
            return copy;
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
            long assignedAtEpochMillis,
            int schemaVersion,
            NpcHostLocation hostLocation) {}

    record AssignmentStatus(
            AssignmentView assignment,
            String lifecycleState,
            String pendingOperation,
            String lastProjectionError) {
        public AssignmentStatus {
            if (assignment == null) throw new IllegalArgumentException("assignment");
            if (lifecycleState == null || lifecycleState.isBlank()) {
                throw new IllegalArgumentException("lifecycleState");
            }
            if (pendingOperation == null) pendingOperation = "";
            if (lastProjectionError == null) lastProjectionError = "";
        }
    }

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
