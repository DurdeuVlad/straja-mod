package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcContentProfile;
import com.dwurdy.straja.domain.model.NpcProfileDescriptor;
import com.dwurdy.straja.domain.model.NpcProfileId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable catalog indexed by stable public profile ID and internal content profile ID. */
public final class NpcContentCatalog {
    private final Map<NpcContentId, NpcContentProfile> profilesByContentId;
    private final Map<NpcProfileId, NpcContentProfile> profilesByProfileId;
    private final Map<NpcContentId, NpcProfileDescriptor> descriptorsByContentId;
    private final Map<NpcProfileId, NpcProfileDescriptor> descriptorsByProfileId;

    public NpcContentCatalog(List<NpcContentProfile> profiles) {
        Objects.requireNonNull(profiles, "profiles");
        Map<NpcContentId, NpcContentProfile> contentIndex = new LinkedHashMap<>();
        Map<String, NpcContentProfile> contentProfilesByValue = new LinkedHashMap<>();
        Map<NpcProfileId, NpcContentProfile> profileIndex = new LinkedHashMap<>();
        Map<NpcContentId, NpcProfileDescriptor> contentDescriptorIndex = new LinkedHashMap<>();
        Map<NpcProfileId, NpcProfileDescriptor> profileDescriptorIndex = new LinkedHashMap<>();
        for (NpcContentProfile profile : profiles) {
            Objects.requireNonNull(profile, "profiles cannot contain null");
            if (contentIndex.putIfAbsent(profile.contentId(), profile) != null) {
                throw new IllegalArgumentException(
                        "duplicate NPC content profile: " + profile.contentId().value());
            }
            contentProfilesByValue.put(profile.contentId().value(), profile);
            if (profileIndex.putIfAbsent(profile.profileId(), profile) != null) {
                throw new IllegalArgumentException(
                        "duplicate NPC profile ID: " + profile.profileId().value());
            }
            NpcProfileDescriptor descriptor = NpcProfileDescriptor.from(
                    profile, roleFor(profile.contentId()), "hq");
            contentDescriptorIndex.put(profile.contentId(), descriptor);
            profileDescriptorIndex.put(profile.profileId(), descriptor);
        }
        for (NpcContentProfile profile : profileIndex.values()) {
            NpcContentProfile contentIdCollision = contentProfilesByValue.get(profile.profileId().value());
            if (contentIdCollision != null && contentIdCollision != profile) {
                throw new IllegalArgumentException(
                        "NPC public profile ID collides with another profile's internal content ID: "
                                + profile.profileId().value());
            }
        }
        this.profilesByContentId = Map.copyOf(contentIndex);
        this.profilesByProfileId = Map.copyOf(profileIndex);
        this.descriptorsByContentId = Map.copyOf(contentDescriptorIndex);
        this.descriptorsByProfileId = Map.copyOf(profileDescriptorIndex);
    }

    public NpcContentProfile require(NpcContentId contentId) {
        NpcContentProfile profile = profilesByContentId.get(Objects.requireNonNull(contentId, "contentId"));
        if (profile == null) {
            throw new IllegalArgumentException("unknown NPC content profile: " + contentId.value());
        }
        return profile;
    }

    public NpcContentProfile require(NpcProfileId profileId) {
        NpcContentProfile profile = profilesByProfileId.get(Objects.requireNonNull(profileId, "profileId"));
        if (profile == null) {
            throw new IllegalArgumentException("unknown NPC profile: " + profileId.value());
        }
        return profile;
    }

    public List<String> validateCapabilities(
            NpcContentId contentId, Set<NpcCapability> capabilities) {
        return missingCapabilities(require(contentId), capabilities);
    }

    public List<String> validateCapabilities(
            NpcProfileId profileId, Set<NpcCapability> capabilities) {
        return missingCapabilities(require(profileId), capabilities);
    }

    public Map<NpcContentId, NpcContentProfile> snapshot() {
        return profilesByContentId;
    }

    /** Resolves canonical namespaced IDs; accepts dotted legacy content IDs for existing callers. */
    public NpcProfileDescriptor descriptor(String id) {
        Objects.requireNonNull(id, "id");
        if (NpcProfileId.isValid(id)) return descriptor(NpcProfileId.of(id));
        return descriptor(NpcContentId.of(id));
    }

    /** Compatibility lookup for pre-catalog content IDs. */
    public NpcProfileDescriptor descriptor(NpcContentId contentId) {
        NpcProfileDescriptor descriptor = descriptorsByContentId.get(
                Objects.requireNonNull(contentId, "contentId"));
        if (descriptor == null) {
            throw new IllegalArgumentException("unknown NPC content profile: " + contentId.value());
        }
        return descriptor;
    }

    public NpcProfileDescriptor descriptor(NpcProfileId profileId) {
        NpcProfileDescriptor descriptor = descriptorsByProfileId.get(
                Objects.requireNonNull(profileId, "profileId"));
        if (descriptor == null) {
            throw new IllegalArgumentException("unknown NPC profile: " + profileId.value());
        }
        return descriptor;
    }

    public boolean hasProfile(NpcProfileId profileId) {
        return profilesByProfileId.containsKey(Objects.requireNonNull(profileId, "profileId"));
    }

    public List<NpcProfileDescriptor> descriptors() {
        return List.copyOf(descriptorsByProfileId.values());
    }

    private static List<String> missingCapabilities(
            NpcContentProfile profile, Set<NpcCapability> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        return profile.requiredCapabilities().stream()
                .filter(required -> !capabilities.contains(required))
                .sorted(java.util.Comparator.comparing(Enum::name))
                .map(missing -> "profile " + profile.profileId().value()
                        + " requires unsupported capability " + missing.name())
                .toList();
    }

    private static String roleFor(NpcContentId contentId) {
        String value = contentId.value();
        if (value.contains("reception")) return "receptionist";
        if (value.contains("instructor")) return "trainer";
        if (value.contains("secretary")) return "secretary";
        if (value.contains("armorer")) return "armorer";
        if (value.contains("jailer")) return "jailer";
        if (value.contains("archive")) return "archivist";
        if (value.contains("archivist")) return "archivist";
        return "npc";
    }
}
