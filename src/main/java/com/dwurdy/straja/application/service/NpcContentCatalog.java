package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcContentProfile;
import com.dwurdy.straja.domain.model.NpcProfileDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable catalog of validated canonical NPC content profiles. */
public final class NpcContentCatalog {
    private final Map<NpcContentId, NpcContentProfile> profiles;
    private final Map<NpcContentId, NpcProfileDescriptor> descriptors;

    public NpcContentCatalog(List<NpcContentProfile> profiles) {
        Objects.requireNonNull(profiles, "profiles");
        Map<NpcContentId, NpcContentProfile> index = new LinkedHashMap<>();
        Map<NpcContentId, NpcProfileDescriptor> descriptorIndex = new LinkedHashMap<>();
        for (NpcContentProfile profile : profiles) {
            Objects.requireNonNull(profile, "profiles cannot contain null");
            if (index.putIfAbsent(profile.profileId(), profile) != null) {
                throw new IllegalArgumentException(
                        "duplicate NPC content profile: " + profile.profileId().value());
            }
            descriptorIndex.put(profile.profileId(), NpcProfileDescriptor.from(
                    profile, roleFor(profile.profileId()), "hq"));
        }
        this.profiles = Map.copyOf(index);
        this.descriptors = Map.copyOf(descriptorIndex);
    }

    public NpcContentProfile require(NpcContentId profileId) {
        NpcContentProfile profile = profiles.get(Objects.requireNonNull(profileId, "profileId"));
        if (profile == null) {
            throw new IllegalArgumentException("unknown NPC content profile: " + profileId.value());
        }
        return profile;
    }

    public List<String> validateCapabilities(NpcContentId profileId, Set<NpcCapability> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        NpcContentProfile profile = require(profileId);
        return profile.requiredCapabilities().stream()
                .filter(required -> !capabilities.contains(required))
                .sorted(java.util.Comparator.comparing(Enum::name))
                .map(missing -> "profile " + profileId.value()
                        + " requires unsupported capability " + missing.name())
                .toList();
    }

    public Map<NpcContentId, NpcContentProfile> snapshot() {
        return profiles;
    }

    public NpcProfileDescriptor descriptor(NpcContentId profileId) {
        NpcProfileDescriptor descriptor = descriptors.get(Objects.requireNonNull(profileId, "profileId"));
        if (descriptor == null) {
            throw new IllegalArgumentException("unknown NPC content profile: " + profileId.value());
        }
        return descriptor;
    }

    public List<NpcProfileDescriptor> descriptors() {
        return List.copyOf(descriptors.values());
    }

    private static String roleFor(NpcContentId profileId) {
        String value = profileId.value();
        if (value.contains("reception")) return "receptionist";
        if (value.contains("instructor")) return "trainer";
        if (value.contains("secretary")) return "secretary";
        if (value.contains("armorer")) return "armorer";
        if (value.contains("jailer")) return "jailer";
        return "npc";
    }
}
