package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcContentProfile;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Performs an explicit provider migration while keeping the logical binding,
 * host identity, profile, and canonical gameplay state unchanged.
 */
public final class NpcProviderMigrationService {
    private final NpcBindingLifecycleService lifecycle;
    private final NpcSurfaceProviderRegistry providers;
    private final NpcContentCatalog catalog;
    private final LongSupplier clock;

    public NpcProviderMigrationService(
            NpcBindingLifecycleService lifecycle,
            NpcSurfaceProviderRegistry providers,
            NpcContentCatalog catalog) {
        this(lifecycle, providers, catalog, System::currentTimeMillis);
    }

    public NpcProviderMigrationService(
            NpcBindingLifecycleService lifecycle,
            NpcSurfaceProviderRegistry providers,
            NpcContentCatalog catalog,
            LongSupplier clock) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized MigrationResult migrate(NpcProviderId target, String actorId) {
        Objects.requireNonNull(target, "target");
        requireActor(actorId);
        if (!providers.available(target)) {
            return MigrationResult.of(
                    NpcProviderResult.unavailable("target NPC provider is unavailable: " + target.value()),
                    List.of(), List.of());
        }

        Map<String, NpcBinding> current = lifecycle.bindings();
        for (NpcBinding binding : current.values()) {
            if (binding.providerId().equals(target)) continue;
            if (lifecycle.inspect(binding.bindingId()).state()
                    != NpcBindingLifecycleService.State.BOUND) {
                return MigrationResult.of(
                        NpcProviderResult.unknown(
                                "binding has an unresolved provider operation: " + binding.bindingId()),
                        List.of(), List.of());
            }
            NpcContentProfile profile;
            try {
                profile = catalog.require(binding.profileId());
            } catch (IllegalArgumentException error) {
                return MigrationResult.of(
                        NpcProviderResult.rejected(
                                "invalid-profile", "binding references an unknown profile: "
                                        + binding.profileId().value()),
                        List.of(), List.of());
            }
            if (!providers.supports(target, profile.requiredCapabilities())) {
                return MigrationResult.of(
                        NpcProviderResult.rejected(
                                "unsupported-capability",
                                "target provider cannot project profile " + binding.profileId().value()),
                        List.of(), List.of());
            }
        }

        List<NpcBinding> migrated = new ArrayList<>();
        List<MigrationItem> items = new ArrayList<>();
        for (NpcBinding original : current.values()) {
            if (original.providerId().equals(target)) continue;
            NpcBinding replacement = new NpcBinding(
                    original.bindingId(),
                    target,
                    original.hostEntityUuid(),
                    original.externalNpcId(),
                    original.roleId(),
                    original.stationId(),
                    original.contentProfileId(),
                    original.profileId(),
                    original.schemaVersion(),
                    actorId,
                    Math.max(0L, clock.getAsLong()),
                    original.hostLocation());
            NpcProviderResult result = lifecycle.rebind(replacement);
            items.add(new MigrationItem(original.bindingId(), original, replacement, result));
            if (result.status() != NpcProviderResult.Status.ACCEPTED) {
                List<MigrationItem> rollback = rollback(migrated, actorId);
                return MigrationResult.of(
                        result,
                        List.copyOf(items),
                        rollback);
            }
            migrated.add(original);
        }
        return MigrationResult.of(
                NpcProviderResult.accepted("migrated " + migrated.size() + " NPC binding(s) to " + target.value()),
                List.copyOf(items), List.of());
    }

    public synchronized NpcBindingLifecycleService.RecoveryReport recover() {
        return lifecycle.recover();
    }

    private List<MigrationItem> rollback(List<NpcBinding> migrated, String actorId) {
        List<MigrationItem> rollback = new ArrayList<>();
        for (int i = migrated.size() - 1; i >= 0; i--) {
            NpcBinding original = migrated.get(i);
            NpcBinding current = new NpcBinding(
                    original.bindingId(),
                    original.providerId(),
                    original.hostEntityUuid(),
                    original.externalNpcId(),
                    original.roleId(),
                    original.stationId(),
                    original.contentProfileId(),
                    original.profileId(),
                    original.schemaVersion(),
                    actorId,
                    Math.max(0L, clock.getAsLong()),
                    original.hostLocation());
            NpcBindingLifecycleService.Inspection inspection = lifecycle.inspect(original.bindingId());
            NpcBinding active = inspection.binding();
            NpcProviderResult result = active == null
                    ? NpcProviderResult.rejected("rollback-missing", "migrated binding is no longer durable")
                    : lifecycle.rebind(current);
            rollback.add(new MigrationItem(original.bindingId(), active, current, result));
        }
        return List.copyOf(rollback);
    }

    private static void requireActor(String actorId) {
        if (actorId == null || actorId.isBlank() || actorId.length() > 128) {
            throw new IllegalArgumentException("actorId must be a non-blank identifier");
        }
    }

    public record MigrationItem(
            String bindingId,
            NpcBinding before,
            NpcBinding after,
            NpcProviderResult result) {}

    public record MigrationResult(
            NpcProviderResult result,
            List<MigrationItem> items,
            List<MigrationItem> rollback) {
        public MigrationResult {
            Objects.requireNonNull(result, "result");
            items = List.copyOf(items);
            rollback = List.copyOf(rollback);
        }

        private static MigrationResult of(
                NpcProviderResult result,
                List<MigrationItem> items,
                List<MigrationItem> rollback) {
            return new MigrationResult(result, items, rollback);
        }
    }
}
