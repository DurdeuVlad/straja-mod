package com.dwurdy.straja.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable logical identity for a Straja NPC presentation surface.
 *
 * <p>The logical binding survives replacement of the host entity. Provider and
 * host identifiers are mappings, not gameplay authority. The serialized
 * {@code surfaceProfileId} component name is retained for old world saves;
 * application code should use {@link #contentProfileId()} for its meaning.</p>
 */
public record NpcBinding(
        String bindingId,
        NpcProviderId providerId,
        String hostEntityUuid,
        String externalNpcId,
        String roleId,
        String stationId,
        NpcContentId surfaceProfileId,
        NpcProfileId profileId,
        int schemaVersion,
        String assignedBy,
        long assignedAtEpochMillis,
        NpcHostLocation hostLocation) {

    private static final Pattern BINDING_ID = Pattern.compile("[a-z][a-z0-9._-]{0,127}");
    private static final Pattern ROLE_OR_STATION_ID = Pattern.compile("[a-z][a-z0-9._-]{0,63}");

    public NpcBinding {
        bindingId = requireId(bindingId, BINDING_ID, "bindingId");
        Objects.requireNonNull(providerId, "providerId");
        hostEntityUuid = requireText(hostEntityUuid, 128, "hostEntityUuid");
        externalNpcId = optionalText(externalNpcId, 256, "externalNpcId");
        roleId = requireId(roleId, ROLE_OR_STATION_ID, "roleId");
        stationId = requireId(stationId, ROLE_OR_STATION_ID, "stationId");
        Objects.requireNonNull(surfaceProfileId, "surfaceProfileId");
        if (profileId == null) profileId = NpcProfileId.fromContentId(surfaceProfileId);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        assignedBy = optionalText(assignedBy, 128, "assignedBy");
        if (assignedAtEpochMillis < 0) {
            throw new IllegalArgumentException("assignedAtEpochMillis must not be negative");
        }
    }

    /** Compatibility constructor for persisted bindings that predate stable public profile IDs. */
    public NpcBinding(
            String bindingId,
            NpcProviderId providerId,
            String hostEntityUuid,
            String externalNpcId,
            String roleId,
            String stationId,
            NpcContentId contentProfileId,
            int schemaVersion,
            String assignedBy,
            long assignedAtEpochMillis,
            NpcHostLocation hostLocation) {
        this(bindingId, providerId, hostEntityUuid, externalNpcId, roleId, stationId,
                contentProfileId, NpcProfileId.fromContentId(contentProfileId), schemaVersion,
                assignedBy, assignedAtEpochMillis, hostLocation);
    }

    /** Backward-compatible constructor for bindings created before host location metadata. */
    public NpcBinding(
            String bindingId,
            NpcProviderId providerId,
            String hostEntityUuid,
            String externalNpcId,
            String roleId,
            String stationId,
            NpcContentId contentProfileId,
            int schemaVersion,
            String assignedBy,
            long assignedAtEpochMillis) {
        this(bindingId, providerId, hostEntityUuid, externalNpcId, roleId, stationId,
                contentProfileId, NpcProfileId.fromContentId(contentProfileId), schemaVersion,
                assignedBy, assignedAtEpochMillis, null);
    }

    /** Backward-compatible constructor for bindings created before assignment audit metadata. */
    public NpcBinding(
            String bindingId,
            NpcProviderId providerId,
            String hostEntityUuid,
            String externalNpcId,
            String roleId,
            String stationId,
            NpcContentId contentProfileId,
            int schemaVersion) {
        this(bindingId, providerId, hostEntityUuid, externalNpcId, roleId, stationId,
                contentProfileId, NpcProfileId.fromContentId(contentProfileId), schemaVersion,
                "", 0L, null);
    }

    /** Preferred semantic name; the record component remains for saved-data compatibility. */
    public NpcContentId contentProfileId() {
        return surfaceProfileId;
    }

    private static String requireId(String value, Pattern pattern, String name) {
        Objects.requireNonNull(value, name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid format: " + value);
        }
        return value;
    }

    private static String requireText(String value, int maxLength, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most "
                    + maxLength + " characters");
        }
        return value;
    }

    private static String optionalText(String value, int maxLength, String name) {
        if (value == null) return "";
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters");
        }
        return value;
    }
}
