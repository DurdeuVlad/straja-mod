package com.dwurdy.straja.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable logical identity for a Straja NPC presentation surface.
 *
 * <p>The logical binding survives replacement of the host entity. Provider and
 * host identifiers are mappings, not gameplay authority.</p>
 */
public record NpcBinding(
        String bindingId,
        NpcProviderId providerId,
        String hostEntityUuid,
        String externalNpcId,
        String roleId,
        String stationId,
        NpcContentId surfaceProfileId,
        int schemaVersion) {

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
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
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
