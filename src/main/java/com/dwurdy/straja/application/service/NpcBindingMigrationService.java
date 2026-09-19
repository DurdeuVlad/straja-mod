package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts the legacy Straja entity registry into stable logical binding
 * proposals. Planning is side-effect free; ownership is still an explicit
 * lifecycle operation through {@link NpcBindingLifecycleService}.
 */
public final class NpcBindingMigrationService {
    public List<NpcBinding> plan(
            NpcRegistry legacy,
            NpcProviderId providerId,
            NpcContentId profileId) {
        Objects.requireNonNull(legacy, "legacy");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(profileId, "profileId");
        List<NpcBinding> proposals = new ArrayList<>();
        if (legacy.npcs == null) return proposals;
        for (NpcRegistry.Record record : legacy.npcs.values()) {
            if (record == null || record.entityUuid == null || record.entityUuid.isBlank()) continue;
            String role = safeId(record.role, "unknown");
            String station = safeId(record.stationId, "hq");
            String stableSuffix = record.entityUuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
            proposals.add(new NpcBinding(
                    "straja.legacy." + stableSuffix,
                    providerId,
                    record.entityUuid,
                    "",
                    role,
                    station,
                    profileId,
                    1));
        }
        return List.copyOf(proposals);
    }

    private static String safeId(String value, String fallback) {
        if (value == null || !value.matches("[a-z][a-z0-9._-]{0,63}")) return fallback;
        return value;
    }
}
