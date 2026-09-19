package com.dwurdy.straja.domain.model;

import java.util.Objects;

/** A provider-neutral action exposed by an NPC surface. */
public record NpcSurfaceAction(
        NpcContentId actionId,
        String label,
        boolean enabled,
        String disabledReason,
        java.util.List<InputField> inputs) {

    public NpcSurfaceAction(
            NpcContentId actionId,
            String label,
            boolean enabled,
            String disabledReason) {
        this(actionId, label, enabled, disabledReason, java.util.List.of());
    }

    public NpcSurfaceAction {
        Objects.requireNonNull(actionId, "actionId");
        if (label == null || label.isBlank() || label.length() > 256) {
            throw new IllegalArgumentException("label must be non-blank and at most 256 characters");
        }
        disabledReason = disabledReason == null ? "" : disabledReason;
        if (disabledReason.length() > 512) {
            throw new IllegalArgumentException("disabledReason must be at most 512 characters");
        }
        if (enabled && !disabledReason.isEmpty()) {
            throw new IllegalArgumentException("enabled action cannot have a disabled reason");
        }
        inputs = java.util.List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        if (inputs.size() > 16) {
            throw new IllegalArgumentException("inputs cannot contain more than 16 fields");
        }
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (InputField input : inputs) {
            Objects.requireNonNull(input, "inputs cannot contain null");
            if (!keys.add(input.key())) {
                throw new IllegalArgumentException("duplicate input key: " + input.key());
            }
        }
    }

    public static NpcSurfaceAction enabled(NpcContentId actionId, String label) {
        return new NpcSurfaceAction(actionId, label, true, "");
    }

    public static NpcSurfaceAction disabled(
            NpcContentId actionId, String label, String disabledReason) {
        return new NpcSurfaceAction(actionId, label, false, disabledReason);
    }

    /** Provider-neutral input descriptor for a GUI/dialogue action. */
    public record InputField(
            String key,
            String label,
            int maxLength,
            boolean required,
            boolean visible,
            String initialValue) {
        public InputField(String key, String label, int maxLength, boolean required) {
            this(key, label, maxLength, required, true, "");
        }

        public InputField {
            if (key == null || !key.matches("[a-z][a-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("input key has invalid format");
            }
            if (label == null || label.isBlank() || label.length() > 256) {
                throw new IllegalArgumentException("input label must be non-blank and at most 256 characters");
            }
            if (maxLength < 1 || maxLength > 512) {
                throw new IllegalArgumentException("input maxLength must be between 1 and 512");
            }
            if (initialValue == null || initialValue.length() > maxLength) {
                throw new IllegalArgumentException("input initialValue exceeds maxLength");
            }
        }
    }
}
