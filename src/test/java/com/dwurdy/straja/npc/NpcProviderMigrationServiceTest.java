package com.dwurdy.straja.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.adapter.out.npc.debug.DebugTextNpcSurfaceProvider;
import com.dwurdy.straja.application.port.out.NpcBindingRepository;
import com.dwurdy.straja.application.port.out.NpcSurfaceProvider;
import com.dwurdy.straja.application.service.NpcBindingLifecycleService;
import com.dwurdy.straja.application.service.NpcContentCatalog;
import com.dwurdy.straja.application.service.NpcProviderMigrationService;
import com.dwurdy.straja.application.service.NpcSurfaceProviderRegistry;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcBindingStore;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcContentProfile;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcProviderMigrationServiceTest {
    private static final NpcProviderId SOURCE = NpcProviderId.of("source-test");
    private static final NpcProviderId TARGET = NpcProviderId.of("target-test");
    private static final NpcContentId PROFILE = NpcContentId.of("straja.migration.test");

    @Test
    void migrationPreservesLogicalIdentityAndProfileForDiagnosticProvider() {
        MemoryRepository repository = new MemoryRepository();
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(new FakeProvider(SOURCE, false));
        providers.register(new DebugTextNpcSurfaceProvider(ignored -> {}));
        NpcContentCatalog catalog = new NpcContentCatalog(List.of(profile()));
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers, repository, catalog);
        NpcBinding original = binding(SOURCE);
        assertEquals(NpcProviderResult.Status.ACCEPTED, lifecycle.bindAndPublish(original).status());

        NpcProviderMigrationService migration = new NpcProviderMigrationService(
                lifecycle, providers, catalog, () -> 42L);
        var result = migration.migrate(NpcProviderId.DEBUG_TEXT, "operator");

        assertEquals(NpcProviderResult.Status.ACCEPTED, result.result().status());
        NpcBinding migrated = lifecycle.inspect(original.bindingId()).binding();
        assertEquals(NpcProviderId.DEBUG_TEXT, migrated.providerId());
        assertEquals(original.bindingId(), migrated.bindingId());
        assertEquals(original.hostEntityUuid(), migrated.hostEntityUuid());
        assertEquals(original.surfaceProfileId(), migrated.surfaceProfileId());
        assertEquals("operator", migrated.assignedBy());
        assertEquals(42L, migrated.assignedAtEpochMillis());
    }

    @Test
    void failedTargetPublishRollsBackEveryChangedBinding() {
        MemoryRepository repository = new MemoryRepository();
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(new FakeProvider(SOURCE, false));
        providers.register(new FakeProvider(TARGET, true));
        NpcContentCatalog catalog = new NpcContentCatalog(List.of(profile()));
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers, repository, catalog);
        NpcBinding original = binding(SOURCE);
        assertEquals(NpcProviderResult.Status.ACCEPTED, lifecycle.bindAndPublish(original).status());

        var result = new NpcProviderMigrationService(
                lifecycle, providers, catalog, () -> 43L).migrate(TARGET, "operator");

        assertEquals(NpcProviderResult.Status.REJECTED, result.result().status());
        assertTrue(result.rollback().stream().allMatch(item ->
                item.result().status() == NpcProviderResult.Status.ACCEPTED));
        assertEquals(SOURCE, lifecycle.inspect(original.bindingId()).binding().providerId());
    }

    private static NpcContentProfile profile() {
        return new NpcContentProfile(
                PROFILE, 1, "Migration desk", "Migration surface",
                List.of(NpcSurfaceAction.enabled(NpcContentId.of("migration-action"), "Open")),
                List.of(), List.of(), Set.of(NpcCapability.GUI), Set.of());
    }

    private static NpcBinding binding(NpcProviderId provider) {
        return new NpcBinding(
                "straja.migration.binding", provider, UUID.randomUUID().toString(), "",
                "receptionist", "hq", PROFILE, 1);
    }

    private static final class FakeProvider implements NpcSurfaceProvider {
        private final NpcProviderId id;
        private final boolean rejectPublish;
        private final Map<String, NpcBinding> bindings = new HashMap<>();

        private FakeProvider(NpcProviderId id, boolean rejectPublish) {
            this.id = id;
            this.rejectPublish = rejectPublish;
        }

        @Override public NpcProviderId providerId() { return id; }
        @Override public Set<NpcCapability> capabilities() { return Set.of(NpcCapability.GUI); }
        @Override public NpcProviderResult bind(NpcBinding binding) {
            NpcBinding existing = bindings.putIfAbsent(binding.bindingId(), binding);
            return existing == null || existing.equals(binding)
                    ? NpcProviderResult.accepted("bound")
                    : NpcProviderResult.rejected("binding-owned", "different binding");
        }
        @Override public NpcProviderResult unbind(NpcBinding binding) {
            return bindings.remove(binding.bindingId(), binding)
                    ? NpcProviderResult.accepted("unbound")
                    : NpcProviderResult.rejected("not-bound", "not bound");
        }
        @Override public NpcProviderResult publish(NpcSurfaceSnapshot surface) {
            return rejectPublish
                    ? NpcProviderResult.rejected("publish-failed", "test target rejected publish")
                    : NpcProviderResult.accepted("published");
        }
    }

    private static final class MemoryRepository implements NpcBindingRepository {
        private NpcBindingStore store = new NpcBindingStore();
        @Override public NpcBindingStore read() { return store; }
        @Override public void write(NpcBindingStore value) { store = value; }
    }
}
