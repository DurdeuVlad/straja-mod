package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Untrusted action input submitted by a provider adapter to Straja. */
public record NpcActionRequest(
        NpcProviderId providerId,
        String bindingId,
        UUID playerId,
        NpcContentId actionId,
        String interactionToken,
        Map<String, String> input) {

    private static final int MAX_INPUTS = 32;
    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_VALUE_LENGTH = 512;

    public NpcActionRequest {
        Objects.requireNonNull(providerId, "providerId");
        bindingId = requireText(bindingId, 128, "bindingId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(actionId, "actionId");
        interactionToken = requireText(interactionToken, 256, "interactionToken");
        input = copyInput(input);
    }

    private static String requireText(String value, int maxLength, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most "
                    + maxLength + " characters");
        }
        return value;
    }

    private static Map<String, String> copyInput(Map<String, String> input) {
        Objects.requireNonNull(input, "input");
        if (input.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("input cannot contain more than " + MAX_INPUTS + " values");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
                throw new IllegalArgumentException("input keys must be non-blank and at most 64 characters");
            }
            if (value == null || value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("input values must be non-null and at most 512 characters");
            }
            copy.put(key, value);
        });
        return Map.copyOf(copy);
    }
}
