package com.dwurdy.straja.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.adapter.out.npc.debug.DebugTextNpcSurfaceProvider;
import com.dwurdy.straja.application.port.out.NpcBindingRepository;
import com.dwurdy.straja.application.port.out.NpcSurfaceProvider;
import com.dwurdy.straja.application.service.NpcBindingLifecycleService;
import com.dwurdy.straja.application.service.NpcBindingMigrationService;
import com.dwurdy.straja.application.service.NpcContentCatalog;
import com.dwurdy.straja.application.service.NpcSurfaceProviderRegistry;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcBindingStore;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcContentProfile;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderOperation;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcRegistry;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class NpcBindingLifecycleTest {
    private static final NpcContentId PROFILE = NpcContentId.of("straja.test.profile");
    private static final NpcContentProfile CONTENT = new NpcContentProfile(
            PROFILE, 1, "Test desk", "Test desk surface",
            List.of(NpcSurfaceAction.enabled(NpcContentId.of("test-action"), "Test action")),
            List.of(), List.of(), Set.of(NpcCapability.TEXT_MIRROR), Set.of());

    @Test
    void bindInspectRebindAndUnbindAreExplicitAndDurable() {
        MemoryRepository repository = new MemoryRepository();
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(new DebugTextNpcSurfaceProvider(ignored -> {}));
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers, repository, new NpcContentCatalog(List.of(CONTENT)));
        NpcBinding first = binding("straja.test.desk", UUID.randomUUID().toString());
        NpcBinding replacement = binding(first.bindingId(), UUID.randomUUID().toString());

        assertEquals(NpcProviderResult.Status.ACCEPTED, lifecycle.bindAndPublish(first).status());
        assertEquals(NpcBindingLifecycleService.State.BOUND,
                lifecycle.inspect(first.bindingId()).state());
        assertEquals(NpcProviderResult.Status.REJECTED, lifecycle.bind(replacement).status());
        assertEquals(NpcProviderResult.Status.ACCEPTED, lifecycle.rebind(replacement).status());
        assertEquals(replacement, lifecycle.inspect(first.bindingId()).binding());
        assertEquals(NpcProviderResult.Status.ACCEPTED, lifecycle.unbind(first.bindingId()).status());
        assertEquals(NpcBindingLifecycleService.State.UNBOUND,
                lifecycle.inspect(first.bindingId()).state());
        assertTrue(repository.store.bindings.isEmpty());
    }

    @Test
    void restartRecoveryReconcilesAnUnknownProviderOperationBeforeRepublishing() {
        MemoryRepository repository = new MemoryRepository();
        NpcBinding binding = binding(
                "straja.test.recovery", UUID.randomUUID().toString(), NpcProviderId.of("recovery"));
        repository.store.bindings.put(binding.bindingId(), binding);
        AtomicBoolean fail = new AtomicBoolean(true);
        NpcSurfaceProvider provider = new NpcSurfaceProvider() {
            @Override public NpcProviderId providerId() { return NpcProviderId.of("recovery"); }
            @Override public Set<NpcCapability> capabilities() { return Set.of(NpcCapability.TEXT_MIRROR); }
            @Override public NpcProviderResult bind(NpcBinding candidate) {
                if (fail.get()) throw new IllegalStateException("unknown");
                return NpcProviderResult.accepted("bound");
            }
            @Override public NpcProviderResult unbind(NpcBinding candidate) {
                return NpcProviderResult.accepted("unbound");
            }
            @Override public NpcProviderResult publish(com.dwurdy.straja.domain.model.NpcSurfaceSnapshot surface) {
                return NpcProviderResult.accepted("published");
            }
            @Override public NpcProviderResult reconcile(NpcBinding candidate) {
                return NpcProviderResult.rejected("not-owned", "provider reports no ownership");
            }
        };
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(provider);
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers,
                repository,
                new NpcContentCatalog(List.of(CONTENT)));

        assertEquals(NpcProviderResult.Status.UNKNOWN,
                lifecycle.recover().forBinding(binding.bindingId()).orElseThrow().result().status());
        assertEquals(NpcBindingLifecycleService.State.UNKNOWN,
                lifecycle.inspect(binding.bindingId()).state());
        fail.set(false);
        assertEquals(NpcProviderResult.Status.ACCEPTED,
                lifecycle.recover().forBinding(binding.bindingId()).orElseThrow().result().status());
        assertEquals(NpcBindingLifecycleService.State.BOUND,
                lifecycle.inspect(binding.bindingId()).state());
        assertEquals(null, lifecycle.inspect(binding.bindingId()).pendingOperation());
    }

    @Test
    void legacyMigrationIsAStableSideEffectFreePlan() {
        NpcRegistry legacy = new NpcRegistry();
        NpcRegistry.Record record = new NpcRegistry.Record();
        record.entityUuid = UUID.randomUUID().toString();
        record.role = "receptionist";
        legacy.npcs.put(record.entityUuid, record);

        List<NpcBinding> first = new NpcBindingMigrationService().plan(
                legacy, NpcProviderId.CUSTOM_NPCS, PROFILE);
        List<NpcBinding> second = new NpcBindingMigrationService().plan(
                legacy, NpcProviderId.CUSTOM_NPCS, PROFILE);

        assertEquals(first, second);
        assertEquals(1, first.size());
        assertEquals("", first.getFirst().externalNpcId());
        assertEquals(1, legacy.npcs.size());
    }

    private static NpcBinding binding(String id, String hostUuid) {
        return binding(id, hostUuid, NpcProviderId.DEBUG_TEXT);
    }

    private static NpcBinding binding(String id, String hostUuid, NpcProviderId provider) {
        return new NpcBinding(id, provider, hostUuid, "",
                "receptionist", "hq", PROFILE, 1);
    }

    private static final class MemoryRepository implements NpcBindingRepository {
        private NpcBindingStore store = new NpcBindingStore();
        @Override public NpcBindingStore read() { return store; }
        @Override public void write(NpcBindingStore value) { store = value; }
    }
}
