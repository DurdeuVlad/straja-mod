package com.dwurdy.straja.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.application.port.out.NpcBindingRepository;
import com.dwurdy.straja.application.port.out.NpcSurfaceProvider;
import com.dwurdy.straja.application.port.in.NpcProvisioningUseCase;
import com.dwurdy.straja.application.service.NpcBindingLifecycleService;
import com.dwurdy.straja.application.service.NpcContentCatalog;
import com.dwurdy.straja.application.service.NpcProvisioningService;
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
import org.junit.jupiter.api.Test;

class NpcProvisioningServiceTest {
    private static final NpcProviderId PROVIDER = NpcProviderId.of("storynpc-test");
    private static final NpcContentId FIRST = NpcContentId.of("straja.reception.test");
    private static final NpcContentId SECOND = NpcContentId.of("straja.secretary.test");
    private static final NpcContentId GUI_ONLY = NpcContentId.of("straja.gui.test");

    @Test
    void catalogOptionsExposeProviderCapabilityGating() {
        Fixture fixture = fixture(false);

        List<NpcProvisioningUseCase.ProfileOption> options = fixture.provisioning.profiles(PROVIDER);

        assertEquals(List.of(GUI_ONLY.value(), FIRST.value(), SECOND.value()),
                options.stream().map(NpcProvisioningUseCase.ProfileOption::profileId).toList());
        assertTrue(options.stream().filter(option -> option.profileId().equals(FIRST.value()))
                .findFirst().orElseThrow().enabled());
        NpcProvisioningUseCase.ProfileOption guiOnly = options.stream()
                .filter(option -> option.profileId().equals(GUI_ONLY.value()))
                .findFirst().orElseThrow();
        assertFalse(guiOnly.enabled());
        assertTrue(guiOnly.disabledReason().contains("GUI"));
    }

    @Test
    void assignmentReplacementAndUnassignmentAreDurableAndAudited() {
        Fixture fixture = fixture(false);

        NpcProvisioningUseCase.ProvisioningResult first = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin-one", FIRST.value());
        NpcProvisioningUseCase.AssignmentView firstView = fixture.provisioning
                .current(PROVIDER, "npc-one").orElseThrow();

        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, first.status());
        assertEquals(FIRST.value(), firstView.profileId());
        assertEquals("receptionist", firstView.roleId());
        assertEquals("admin-one", firstView.assignedBy());
        assertEquals(42L, firstView.assignedAtEpochMillis());

        NpcProvisioningUseCase.ProvisioningResult replacement = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin-two", SECOND.value());
        NpcProvisioningUseCase.AssignmentView replacementView = fixture.provisioning
                .current(PROVIDER, "npc-one").orElseThrow();

        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, replacement.status());
        assertEquals(firstView.bindingId(), replacementView.bindingId());
        assertEquals(SECOND.value(), replacementView.profileId());
        assertEquals("secretary", replacementView.roleId());
        assertEquals("admin-two", replacementView.assignedBy());
        assertEquals(42L, replacementView.assignedAtEpochMillis());

        NpcProvisioningUseCase.ProvisioningResult unassigned = fixture.provisioning.unassign(
                PROVIDER, "npc-one", "admin-two");

        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, unassigned.status());
        assertTrue(fixture.provisioning.current(PROVIDER, "npc-one").isEmpty());
        assertTrue(fixture.repository.store.bindings.isEmpty());
    }

    @Test
    void failedReplacementRestoresPreviousAssignment() {
        Fixture fixture = fixture(true);
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED,
                fixture.provisioning.assign(PROVIDER, "npc-one", "admin", FIRST.value()).status());

        NpcProvisioningUseCase.ProvisioningResult failed = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin", SECOND.value());

        assertEquals(NpcProvisioningUseCase.Status.REJECTED, failed.status());
        assertEquals(FIRST.value(), fixture.provisioning.current(PROVIDER, "npc-one")
                .orElseThrow().profileId());
        assertEquals(1, fixture.repository.store.bindings.size());
    }

    @Test
    void legacyBindingConstructorHasEmptyAuditMetadata() {
        NpcBinding binding = new NpcBinding(
                "straja.legacy.binding",
                PROVIDER,
                "npc-one",
                "",
                "receptionist",
                "hq",
                FIRST,
                1);

        assertEquals("", binding.assignedBy());
        assertEquals(0L, binding.assignedAtEpochMillis());
    }

    private static Fixture fixture(boolean rejectSecondPublish) {
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(new FakeProvider(PROVIDER, rejectSecondPublish));
        NpcContentCatalog catalog = new NpcContentCatalog(List.of(
                profile(FIRST, "Reception desk", "receptionist", Set.of(NpcCapability.TEXT_MIRROR)),
                profile(SECOND, "Secretary desk", "secretary", Set.of(NpcCapability.TEXT_MIRROR)),
                profile(GUI_ONLY, "GUI desk", "gui", Set.of(NpcCapability.GUI))));
        MemoryRepository repository = new MemoryRepository();
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(providers, repository, catalog);
        NpcProvisioningService provisioning = new NpcProvisioningService(
                lifecycle, catalog, providers, () -> 42L);
        return new Fixture(provisioning, repository);
    }

    private static NpcContentProfile profile(
            NpcContentId id, String title, String ignoredRole, Set<NpcCapability> required) {
        return new NpcContentProfile(
                id,
                1,
                title,
                title + " body",
                List.of(NpcSurfaceAction.enabled(
                        NpcContentId.of(id.value() + ".action"), "Open")),
                List.of(),
                List.of(),
                required,
                Set.of());
    }

    private record Fixture(NpcProvisioningService provisioning, MemoryRepository repository) {}

    private static final class FakeProvider implements NpcSurfaceProvider {
        private final NpcProviderId id;
        private final boolean rejectSecondPublish;
        private final Map<String, NpcBinding> bindings = new HashMap<>();

        private FakeProvider(NpcProviderId id, boolean rejectSecondPublish) {
            this.id = id;
            this.rejectSecondPublish = rejectSecondPublish;
        }

        @Override public NpcProviderId providerId() { return id; }
        @Override public Set<NpcCapability> capabilities() { return Set.of(NpcCapability.TEXT_MIRROR); }
        @Override public NpcProviderResult bind(NpcBinding binding) {
            NpcBinding current = bindings.putIfAbsent(binding.bindingId(), binding);
            return current == null || current.equals(binding)
                    ? NpcProviderResult.accepted("bound")
                    : NpcProviderResult.rejected("binding-owned", "different binding");
        }
        @Override public NpcProviderResult unbind(NpcBinding binding) {
            return bindings.remove(binding.bindingId(), binding)
                    ? NpcProviderResult.accepted("unbound")
                    : NpcProviderResult.rejected("not-bound", "not bound");
        }
        @Override public NpcProviderResult publish(NpcSurfaceSnapshot surface) {
            if (rejectSecondPublish && surface.profileId().equals(SECOND)) {
                return NpcProviderResult.rejected("provider-rejected", "test provider rejected profile");
            }
            return NpcProviderResult.accepted("published");
        }
    }

    private static final class MemoryRepository implements NpcBindingRepository {
        private NpcBindingStore store = new NpcBindingStore();
        @Override public NpcBindingStore read() { return store; }
        @Override public void write(NpcBindingStore value) { store = value; }
    }
}
