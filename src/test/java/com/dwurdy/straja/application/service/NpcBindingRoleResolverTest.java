package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.adapter.out.npc.debug.DebugTextNpcSurfaceProvider;
import com.dwurdy.straja.application.port.out.NpcBindingRepository;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcBindingStore;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcContentProfile;
import com.dwurdy.straja.domain.model.NpcProfileId;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcBindingRoleResolverTest {
    private static final NpcProviderId PROVIDER = NpcProviderId.DEBUG_TEXT;

    @Test
    void resolvesCanonicalRoleAcrossAssignmentReplacementAndUnassignment() {
        String hostId = UUID.randomUUID().toString();
        NpcContentProfile jailer = profile("straja.jailer.test", "straja:jailer");
        NpcContentProfile armorer = profile("straja.armorer.test", "straja:armorer");
        NpcContentCatalog catalog = new NpcContentCatalog(List.of(jailer, armorer));
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(new DebugTextNpcSurfaceProvider(ignored -> {}));
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers, new MemoryRepository(), catalog);
        NpcBinding first = binding("npc-binding", hostId, jailer, "jailer");

        assertEquals("ACCEPTED", lifecycle.bindAndPublish(first).status().name());
        assertTrue(NpcBindingRoleResolver.hasBindingForHost(lifecycle, hostId));
        assertEquals("jailer", NpcBindingRoleResolver.assignedRoleForHost(lifecycle, hostId).orElseThrow());

        NpcBinding replacement = binding("npc-binding", hostId, armorer, "armorer");
        assertEquals("ACCEPTED", lifecycle.rebind(replacement).status().name());
        assertEquals("armorer", NpcBindingRoleResolver.assignedRoleForHost(lifecycle, hostId).orElseThrow());

        assertEquals("ACCEPTED", lifecycle.unbind(first.bindingId()).status().name());
        assertFalse(NpcBindingRoleResolver.hasBindingForHost(lifecycle, hostId));
        assertTrue(NpcBindingRoleResolver.assignedRoleForHost(lifecycle, hostId).isEmpty());
    }

    @Test
    void jailerRoleWinsConservativelyWhenHostHasConflictingCanonicalBindings() {
        String hostId = UUID.randomUUID().toString();
        NpcContentProfile jailer = profile("straja.jailer.test", "straja:jailer");
        NpcContentProfile armorer = profile("straja.armorer.test", "straja:armorer");
        NpcContentCatalog catalog = new NpcContentCatalog(List.of(jailer, armorer));
        NpcBindingStore store = new NpcBindingStore();
        store.bindings.put("jailer-binding", binding("jailer-binding", hostId, jailer, "jailer"));
        store.bindings.put("armorer-binding", binding("armorer-binding", hostId, armorer, "armorer"));
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                new NpcSurfaceProviderRegistry(), new MemoryRepository(store), catalog);

        assertTrue(NpcBindingRoleResolver.hasBindingForHost(lifecycle, hostId));
        assertEquals("jailer", NpcBindingRoleResolver.assignedRoleForHost(lifecycle, hostId).orElseThrow());
    }

    @Test
    void ambiguousNonJailerRolesRemainOwnedButDoNotChooseAnArbitraryRole() {
        String hostId = UUID.randomUUID().toString();
        NpcContentProfile armorer = profile("straja.armorer.test", "straja:armorer");
        NpcContentProfile trainer = profile("straja.trainer.test", "straja:trainer");
        NpcContentCatalog catalog = new NpcContentCatalog(List.of(armorer, trainer));
        NpcBindingStore store = new NpcBindingStore();
        store.bindings.put("armorer-binding", binding("armorer-binding", hostId, armorer, "armorer"));
        store.bindings.put("trainer-binding", binding("trainer-binding", hostId, trainer, "trainer"));
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                new NpcSurfaceProviderRegistry(), new MemoryRepository(store), catalog);

        assertTrue(NpcBindingRoleResolver.hasBindingForHost(lifecycle, hostId));
        assertEquals("", NpcBindingRoleResolver.assignedRoleForHost(lifecycle, hostId).orElseThrow());
    }

    private static NpcContentProfile profile(String contentId, String publicId) {
        NpcContentId id = NpcContentId.of(contentId);
        return new NpcContentProfile(
                id, NpcProfileId.of(publicId), 1, publicId, publicId + " body",
                List.of(NpcSurfaceAction.enabled(NpcContentId.of(contentId + ".action"), "Open")),
                List.of(), List.of(), Set.of(NpcCapability.TEXT_MIRROR), Set.of());
    }

    private static NpcBinding binding(
            String bindingId, String hostId, NpcContentProfile profile, String role) {
        return new NpcBinding(bindingId, PROVIDER, hostId, "", role, "hq",
                profile.contentId(), profile.profileId(), 1, "admin", 1L, null);
    }

    private static final class MemoryRepository implements NpcBindingRepository {
        private final NpcBindingStore store;

        private MemoryRepository() {
            this(new NpcBindingStore());
        }

        private MemoryRepository(NpcBindingStore store) {
            this.store = store;
        }

        @Override public NpcBindingStore read() { return store; }
        @Override public void write(NpcBindingStore value) {}
    }
}
