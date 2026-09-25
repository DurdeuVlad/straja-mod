package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.NpcBinding;
import java.util.List;
import java.util.Optional;

/** Resolves canonical provider-neutral NPC ownership and role for game event routing. */
public final class NpcBindingRoleResolver {
    private NpcBindingRoleResolver() {}

    public static boolean hasBindingForHost(
            NpcBindingLifecycleService lifecycle, String hostEntityUuid) {
        return !bindingsForHost(lifecycle, hostEntityUuid).isEmpty();
    }

    /** Jailer wins a corrupt role conflict so safety protections fail closed. */
    public static Optional<String> assignedRoleForHost(
            NpcBindingLifecycleService lifecycle, String hostEntityUuid) {
        List<String> roles = bindingsForHost(lifecycle, hostEntityUuid).stream()
                .map(NpcBinding::roleId)
                .distinct()
                .toList();
        if (roles.isEmpty()) return Optional.empty();
        if (roles.contains("jailer")) return Optional.of("jailer");
        return roles.size() == 1 ? Optional.of(roles.getFirst()) : Optional.of("");
    }

    private static List<NpcBinding> bindingsForHost(
            NpcBindingLifecycleService lifecycle, String hostEntityUuid) {
        if (hostEntityUuid == null || hostEntityUuid.isBlank()) return List.of();
        return lifecycle.inspections().values().stream()
                .map(NpcBindingLifecycleService.Inspection::binding)
                .filter(binding -> binding != null)
                .filter(binding -> hostEntityUuid.equals(binding.hostEntityUuid()))
                .sorted(java.util.Comparator.comparing(NpcBinding::bindingId))
                .toList();
    }
}
