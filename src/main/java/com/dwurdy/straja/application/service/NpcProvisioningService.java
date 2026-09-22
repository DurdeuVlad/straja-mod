package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.in.NpcProvisioningUseCase;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProfileDescriptor;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Coordinates the admin assignment flow without importing a provider API.
 * Content validation and durable lifecycle ownership remain application-owned;
 * the provider only receives the resulting binding and published surface.
 */
public final class NpcProvisioningService implements NpcProvisioningUseCase {
    private static final Pattern HOST = Pattern.compile("[^a-zA-Z0-9._:-]");

    private final NpcBindingLifecycleService lifecycle;
    private final NpcContentCatalog catalog;
    private final NpcSurfaceProviderRegistry providers;
    private final LongSupplier clock;

    public NpcProvisioningService(
            NpcBindingLifecycleService lifecycle,
            NpcContentCatalog catalog,
            NpcSurfaceProviderRegistry providers) {
        this(lifecycle, catalog, providers, System::currentTimeMillis);
    }

    public NpcProvisioningService(
            NpcBindingLifecycleService lifecycle,
            NpcContentCatalog catalog,
            NpcSurfaceProviderRegistry providers,
            LongSupplier clock) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<ProfileOption> profiles(NpcProviderId providerId) {
        Objects.requireNonNull(providerId, "providerId");
        boolean available = providers.available(providerId);
        var capabilities = providers.capabilities(providerId);
        return catalog.descriptors().stream()
                .sorted(Comparator.comparing(descriptor -> descriptor.profileId().value()))
                .map(descriptor -> {
                    List<String> missing = catalog.validateCapabilities(
                            descriptor.profileId(), capabilities);
                    String reason = !available
                            ? "NPC provider is unavailable"
                            : String.join("; ", missing);
                    return new ProfileOption(
                            descriptor.profileId().value(),
                            descriptor.title(),
                            descriptor.summary(),
                            descriptor.roleId(),
                            descriptor.stationId(),
                            descriptor.schemaVersion(),
                            available && missing.isEmpty(),
                            reason);
                })
                .toList();
    }

    @Override
    public Optional<AssignmentView> current(NpcProviderId providerId, String hostEntityUuid) {
        Objects.requireNonNull(providerId, "providerId");
        requireHost(hostEntityUuid);
        return lifecycle.bindings().values().stream()
                .filter(binding -> binding.providerId().equals(providerId))
                .filter(binding -> binding.hostEntityUuid().equals(hostEntityUuid))
                .findFirst()
                .map(NpcProvisioningService::view);
    }

    @Override
    public ProvisioningResult assign(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            String profileId) {
        return assign(providerId, hostEntityUuid, actorId, profileId, null);
    }

    /** Legacy command seam may retain an explicit station while the GUI uses the catalog default. */
    public ProvisioningResult assign(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            String profileId,
            String stationOverride) {
        Objects.requireNonNull(providerId, "providerId");
        requireHost(hostEntityUuid);
        requireActor(actorId);
        Objects.requireNonNull(profileId, "profileId");
        if (!providers.available(providerId)) {
            return ProvisioningResult.unavailable(
                    "NPC provider is unavailable: " + providerId.value());
        }

        NpcContentId contentId;
        try {
            contentId = NpcContentId.of(profileId);
        } catch (IllegalArgumentException error) {
            return ProvisioningResult.rejected("invalid-profile", "invalid NPC profile id");
        }

        NpcProfileDescriptor descriptor;
        try {
            descriptor = catalog.descriptor(contentId);
        } catch (IllegalArgumentException error) {
            return ProvisioningResult.rejected("unknown-profile", error.getMessage());
        }

        List<String> missing = catalog.validateCapabilities(
                contentId, providers.capabilities(providerId));
        if (!missing.isEmpty()) {
            return ProvisioningResult.rejected("unsupported-capability", String.join("; ", missing));
        }

        NpcBinding current = lifecycle.bindings().values().stream()
                .filter(binding -> binding.providerId().equals(providerId))
                .filter(binding -> binding.hostEntityUuid().equals(hostEntityUuid))
                .findFirst()
                .orElse(null);
        String bindingId = current == null
                ? stableBindingId(providerId, hostEntityUuid)
                : current.bindingId();
        NpcBinding candidate;
        try {
            candidate = new NpcBinding(
                    bindingId,
                    providerId,
                    hostEntityUuid,
                    current == null ? "" : current.externalNpcId(),
                    descriptor.roleId(),
                    station(stationOverride, descriptor.stationId()),
                    descriptor.profileId(),
                    descriptor.schemaVersion(),
                    actorId,
                    nonnegativeNow());
        } catch (IllegalArgumentException error) {
            return ProvisioningResult.rejected("invalid-assignment", error.getMessage());
        }

        NpcProviderResult result = current == null
                ? lifecycle.bindAndPublish(candidate)
                : lifecycle.rebind(candidate);
        return map(result);
    }

    @Override
    public ProvisioningResult unassign(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId) {
        Objects.requireNonNull(providerId, "providerId");
        requireHost(hostEntityUuid);
        requireActor(actorId);
        NpcBinding current = lifecycle.bindings().values().stream()
                .filter(binding -> binding.providerId().equals(providerId))
                .filter(binding -> binding.hostEntityUuid().equals(hostEntityUuid))
                .findFirst()
                .orElse(null);
        if (current == null) {
            return ProvisioningResult.rejected("not-bound", "NPC has no Straja profile assignment");
        }
        return map(lifecycle.unbind(current.bindingId()));
    }

    private long nonnegativeNow() {
        return Math.max(0L, clock.getAsLong());
    }

    private static AssignmentView view(NpcBinding binding) {
        return new AssignmentView(
                binding.bindingId(),
                binding.providerId().value(),
                binding.hostEntityUuid(),
                binding.surfaceProfileId().value(),
                binding.roleId(),
                binding.stationId(),
                binding.assignedBy(),
                binding.assignedAtEpochMillis());
    }

    private static ProvisioningResult map(NpcProviderResult result) {
        return switch (result.status()) {
            case ACCEPTED, RECONCILED -> ProvisioningResult.accepted(result.message());
            case REJECTED -> ProvisioningResult.rejected(result.code(), result.message());
            case UNAVAILABLE -> ProvisioningResult.unavailable(result.message());
            case UNKNOWN -> ProvisioningResult.unknown(result.message());
        };
    }

    private static String stableBindingId(NpcProviderId providerId, String hostEntityUuid) {
        String hash = java.util.UUID.nameUUIDFromBytes(
                hostEntityUuid.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
        return "straja." + providerId.value() + "." + hash.substring(0, 32);
    }

    private static void requireHost(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || HOST.matcher(value).find()) {
            throw new IllegalArgumentException("hostEntityUuid must be a non-blank safe identifier");
        }
    }

    private static void requireActor(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("actorId must be a non-blank identifier");
        }
    }

    private static String station(String override, String fallback) {
        if (override == null || override.isBlank()) return fallback;
        if (!override.matches("[a-z][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("stationId has invalid format: " + override);
        }
        return override;
    }
}
