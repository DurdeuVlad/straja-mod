package com.dwurdy.straja.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import com.dwurdy.straja.domain.model.NpcProfileId;
import com.dwurdy.straja.domain.model.NpcHostLocation;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderOperation;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcProvisioningAuditEntry;
import com.dwurdy.straja.domain.model.NpcRebindPhase;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NpcProvisioningServiceTest {
    private static final NpcProviderId PROVIDER = NpcProviderId.of("storynpc-test");
    private static final NpcContentId FIRST = NpcContentId.of("straja.reception.test");
    private static final NpcContentId SECOND = NpcContentId.of("straja.secretary.test");
    private static final NpcContentId GUI_ONLY = NpcContentId.of("straja.gui.test");
    private static final NpcProfileId FIRST_PUBLIC = NpcProfileId.of("straja:receptionist");
    private static final NpcProfileId SECOND_PUBLIC = NpcProfileId.of("straja:secretary");
    private static final NpcProfileId GUI_ONLY_PUBLIC = NpcProfileId.of("straja:gui");

    @Test
    void catalogOptionsExposeProviderCapabilityGating() {
        Fixture fixture = fixture(false);

        List<NpcProvisioningUseCase.ProfileOption> options = fixture.provisioning.profiles(PROVIDER);

        assertEquals(List.of(GUI_ONLY_PUBLIC.value(), FIRST_PUBLIC.value(), SECOND_PUBLIC.value()),
                options.stream().map(NpcProvisioningUseCase.ProfileOption::profileId).toList());
        assertTrue(options.stream().filter(option -> option.profileId().equals(FIRST_PUBLIC.value()))
                .findFirst().orElseThrow().enabled());
        NpcProvisioningUseCase.ProfileOption first = options.stream()
                .filter(option -> option.profileId().equals(FIRST_PUBLIC.value()))
                .findFirst().orElseThrow();
        assertEquals(List.of(NpcContentId.of(FIRST.value() + ".dialogue")), first.dialogueContentIds());
        assertEquals(List.of(NpcContentId.of(FIRST.value() + ".quest")), first.questContentIds());
        assertEquals(List.of(NpcContentId.of(FIRST.value() + ".action")), first.actionContentIds());
        NpcProvisioningUseCase.ProfileOption guiOnly = options.stream()
                .filter(option -> option.profileId().equals(GUI_ONLY_PUBLIC.value()))
                .findFirst().orElseThrow();
        assertFalse(guiOnly.enabled());
        assertTrue(guiOnly.disabledReason().contains("GUI"));
    }

    @Test
    void canonicalProfileIdsAssignAndUnknownNamespacedIdsDoNotFallBack() {
        Fixture fixture = fixture(false);

        NpcProvisioningUseCase.ProvisioningResult assigned = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin", FIRST_PUBLIC.value());
        NpcProvisioningUseCase.ProvisioningResult unknown = fixture.provisioning.assign(
                PROVIDER, "npc-two", "admin", "straja:missing-profile");

        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, assigned.status());
        assertEquals(FIRST_PUBLIC.value(), fixture.repository.store.bindings.values().iterator()
                .next().profileId().value());
        assertEquals(NpcProvisioningUseCase.Status.REJECTED, unknown.status());
        assertEquals("unknown-profile", unknown.code());
        assertEquals(1, fixture.repository.store.bindings.size());
    }

    @Test
    void staleAssignmentAndUnassignmentConfirmationsCannotOverwriteNewerAssignment() {
        Fixture fixture = fixture(false);
        String unassignedRevision = fixture.provisioning.assignmentRevision("npc-one");

        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED,
                fixture.provisioning.assign(PROVIDER, "npc-one", "admin-b", SECOND.value()).status());
        NpcProvisioningUseCase.ProvisioningResult staleAssign = fixture.provisioning
                .assignIfRevisionMatches(
                        PROVIDER, "npc-one", "admin-a", FIRST.value(), Optional.empty(), unassignedRevision);

        assertEquals(NpcProvisioningUseCase.Status.REJECTED, staleAssign.status());
        assertEquals("stale-assignment", staleAssign.code());
        assertEquals(SECOND_PUBLIC.value(), fixture.provisioning.current(PROVIDER, "npc-one")
                .orElseThrow().profileId());

        String secondRevision = fixture.provisioning.assignmentRevision("npc-one");
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED,
                fixture.provisioning.assign(PROVIDER, "npc-one", "admin-b", FIRST.value()).status());
        NpcProvisioningUseCase.ProvisioningResult staleUnassign = fixture.provisioning
                .unassignIfRevisionMatches(PROVIDER, "npc-one", "admin-a", secondRevision);

        assertEquals(NpcProvisioningUseCase.Status.REJECTED, staleUnassign.status());
        assertEquals("stale-assignment", staleUnassign.code());
        assertEquals(FIRST_PUBLIC.value(), fixture.provisioning.current(PROVIDER, "npc-one")
                .orElseThrow().profileId());
        assertEquals(2, fixture.provisioning.auditTrail(PROVIDER, "npc-one").size());
    }

    @Test
    void providerMutationDoesNotStartWhenRevisionIntentCannotBePersisted() {
        Fixture fixture = fixture(false);
        fixture.repository.failNextWrite = true;

        NpcProvisioningUseCase.ProvisioningResult result = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin", FIRST.value());

        assertEquals(NpcProvisioningUseCase.Status.UNKNOWN, result.status());
        assertTrue(fixture.provider.bindings.isEmpty());
        assertTrue(fixture.lifecycle.bindings().isEmpty());
        assertEquals("0", fixture.lifecycle.assignmentRevision("npc-one"));
        assertTrue(fixture.lifecycle.provisioningAudit().isEmpty());
        assertFalse(fixture.repository.store.assignmentRevisions.containsKey("npc-one"));
        assertTrue(fixture.repository.store.provisioningAudit.isEmpty());
    }

    @Test
    void pendingIntentCandidateLocksHostWhenLifecycleMarkerWriteFailsAndRecoversExactProfile() {
        Fixture fixture = fixture(false);
        fixture.repository.failWriteAfter = 2;

        NpcProvisioningUseCase.ProvisioningResult first = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin-one", FIRST_PUBLIC.value());
        NpcBindingLifecycleService.Inspection pending = fixture.lifecycle.inspections().values()
                .stream().filter(value -> value.binding() != null).findFirst().orElseThrow();
        NpcProvisioningUseCase.ProvisioningResult competing = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin-two", SECOND_PUBLIC.value());

        assertEquals(NpcProvisioningUseCase.Status.UNKNOWN, first.status());
        assertEquals(NpcBindingLifecycleService.State.UNKNOWN, pending.state());
        assertEquals(FIRST_PUBLIC.value(), pending.binding().profileId().value());
        assertEquals(NpcProvisioningUseCase.Status.UNKNOWN, competing.status());
        assertTrue(fixture.provider.bindings.isEmpty(), "neither intent may reach the provider");

        NpcBindingLifecycleService restarted = new NpcBindingLifecycleService(
                fixture.providers, fixture.repository, fixture.catalog);
        NpcProvisioningService recovered = new NpcProvisioningService(
                restarted, fixture.catalog, fixture.providers, () -> 42L);
        assertEquals(NpcProviderResult.Status.ACCEPTED,
                restarted.recover().forBinding(pending.binding().bindingId()).orElseThrow().result().status());
        assertEquals(FIRST_PUBLIC.value(), recovered.current(PROVIDER, "npc-one")
                .orElseThrow().profileId());
        assertTrue(fixture.provider.bindings.values().stream()
                .allMatch(binding -> FIRST_PUBLIC.value().equals(binding.profileId().value())));
    }

    @Test
    void catalogRejectsDuplicatePublicProfileIds() {
        NpcContentProfile duplicate = profile(
                NpcContentId.of("straja.other.profile"), FIRST_PUBLIC, "Duplicate", "other",
                Set.of(NpcCapability.TEXT_MIRROR));

        assertThrows(IllegalArgumentException.class, () -> new NpcContentCatalog(List.of(
                profile(FIRST, FIRST_PUBLIC, "Reception desk", "receptionist",
                        Set.of(NpcCapability.TEXT_MIRROR)),
                duplicate)));
    }

    @Test
    void catalogRejectsPublicIdThatMatchesAnotherProfilesInternalId() {
        NpcContentProfile collidingContent = new NpcContentProfile(
                NpcContentId.of(FIRST_PUBLIC.value()), SECOND_PUBLIC, 1,
                "Different NPC", "Different content", List.of(), List.of(), List.of(),
                Set.of(), Set.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new NpcContentCatalog(List.of(
                        profile(FIRST, FIRST_PUBLIC, "Reception desk", "receptionist",
                                Set.of(NpcCapability.TEXT_MIRROR)),
                        collidingContent)));
        assertTrue(error.getMessage().contains(FIRST_PUBLIC.value()));
    }

    @Test
    void assignmentReplacementAndUnassignmentAreDurableAndAudited() {
        Fixture fixture = fixture(false);

        NpcProvisioningUseCase.ProvisioningResult first = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin-one", FIRST.value());
        NpcProvisioningUseCase.AssignmentView firstView = fixture.provisioning
                .current(PROVIDER, "npc-one").orElseThrow();

        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, first.status());
        assertEquals(FIRST_PUBLIC.value(), firstView.profileId());
        assertEquals("receptionist", firstView.roleId());
        assertEquals("admin-one", firstView.assignedBy());
        assertEquals(42L, firstView.assignedAtEpochMillis());

        NpcProvisioningUseCase.ProvisioningResult replacement = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin-two", SECOND.value());
        NpcProvisioningUseCase.AssignmentView replacementView = fixture.provisioning
                .current(PROVIDER, "npc-one").orElseThrow();

        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, replacement.status());
        assertEquals(firstView.bindingId(), replacementView.bindingId());
        assertEquals(SECOND_PUBLIC.value(), replacementView.profileId());
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
        assertEquals(FIRST_PUBLIC.value(), fixture.provisioning.current(PROVIDER, "npc-one")
                .orElseThrow().profileId());
        assertEquals(1, fixture.repository.store.bindings.size());
    }

    @Test
    void auditTrailRecordsActorTargetOldAndNewProfilesAndPersistsAcrossLifecycleRestart() {
        Fixture fixture = fixture(false);
        String originalRevision = fixture.provisioning.assignmentRevision("npc-one");
        fixture.provisioning.assign(PROVIDER, "npc-one", "admin-one", FIRST.value());
        fixture.provisioning.assign(PROVIDER, "npc-one", "admin-two", SECOND.value());
        fixture.provisioning.unassign(PROVIDER, "npc-one", "admin-two");
        String finalRevision = fixture.provisioning.assignmentRevision("npc-one");

        List<NpcProvisioningAuditEntry> events = fixture.provisioning.auditTrail(PROVIDER, "npc-one");
        assertEquals(3, events.size());
        assertEquals(NpcProvisioningAuditEntry.Action.ASSIGN, events.get(0).action());
        assertEquals("npc-one", events.get(0).providerInstanceId());
        assertEquals("admin-one", events.get(0).actorId());
        assertEquals("", events.get(0).oldProfileId());
        assertEquals(FIRST_PUBLIC.value(), events.get(0).newProfileId());
        assertEquals(NpcProvisioningAuditEntry.Outcome.ACCEPTED, events.get(0).outcome());
        assertEquals(FIRST_PUBLIC.value(), events.get(1).oldProfileId());
        assertEquals(SECOND_PUBLIC.value(), events.get(1).newProfileId());
        assertEquals(SECOND_PUBLIC.value(), events.get(2).oldProfileId());
        assertEquals("", events.get(2).newProfileId());
        assertEquals(NpcProvisioningAuditEntry.Action.UNASSIGN, events.get(2).action());

        NpcSurfaceProviderRegistry restartedProviders = new NpcSurfaceProviderRegistry();
        restartedProviders.register(new FakeProvider(PROVIDER, false));
        NpcBindingLifecycleService restartedLifecycle = new NpcBindingLifecycleService(
                restartedProviders, fixture.repository, fixture.catalog);
        NpcProvisioningService restartedService = new NpcProvisioningService(
                restartedLifecycle, fixture.catalog, restartedProviders, () -> 99L);

        assertEquals(events, restartedService.auditTrail(PROVIDER, "npc-one"));
        assertEquals(finalRevision, restartedService.assignmentRevision("npc-one"));
        NpcProvisioningUseCase.ProvisioningResult staleAfterRestart = restartedService.assignIfRevisionMatches(
                PROVIDER, "npc-one", "stale-admin", FIRST.value(), Optional.empty(), originalRevision);
        assertEquals(NpcProvisioningUseCase.Status.REJECTED, staleAfterRestart.status());
        assertEquals("stale-assignment", staleAfterRestart.code());
        assertTrue(restartedService.current(PROVIDER, "npc-one").isEmpty());
    }

    @Test
    void assignmentRevisionSurvivesAuditPruningAndLifecycleRestart() {
        Fixture fixture = fixture(false);
        String originalRevision = fixture.provisioning.assignmentRevision("npc-one");
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED,
                fixture.provisioning.assign(PROVIDER, "npc-one", "admin", FIRST.value()).status());
        String assignedRevision = fixture.provisioning.assignmentRevision("npc-one");

        for (int index = 0; index < 2_050; index++) {
            fixture.lifecycle.recordProvisioningAudit(new NpcProvisioningAuditEntry(
                    java.util.UUID.randomUUID().toString(), "", NpcProvisioningAuditEntry.Action.REPROJECT,
                    "other-provider", "other-host-" + index, "system", "", "",
                    NpcProvisioningAuditEntry.Outcome.ACCEPTED, "ok", "", index));
        }
        assertTrue(fixture.provisioning.auditTrail(PROVIDER, "npc-one").isEmpty());
        assertEquals(assignedRevision, fixture.provisioning.assignmentRevision("npc-one"));

        NpcSurfaceProviderRegistry restartedProviders = new NpcSurfaceProviderRegistry();
        restartedProviders.register(new FakeProvider(PROVIDER, false));
        NpcBindingLifecycleService restartedLifecycle = new NpcBindingLifecycleService(
                restartedProviders, fixture.repository, fixture.catalog);
        NpcProvisioningService restartedService = new NpcProvisioningService(
                restartedLifecycle, fixture.catalog, restartedProviders, () -> 99L);
        NpcProvisioningUseCase.ProvisioningResult stale = restartedService.assignIfRevisionMatches(
                PROVIDER, "npc-one", "stale-admin", FIRST.value(), Optional.empty(), originalRevision);

        assertEquals(NpcProvisioningUseCase.Status.REJECTED, stale.status());
        assertEquals("stale-assignment", stale.code());
        assertEquals(assignedRevision, restartedService.assignmentRevision("npc-one"));
        assertEquals(FIRST_PUBLIC.value(), restartedService.current(PROVIDER, "npc-one")
                .orElseThrow().profileId());
        NpcProvisioningUseCase.ProvisioningResult staleUnassign = restartedService
                .unassignIfRevisionMatches(PROVIDER, "npc-one", "stale-admin", originalRevision);
        assertEquals(NpcProvisioningUseCase.Status.REJECTED, staleUnassign.status());
        assertEquals("stale-assignment", staleUnassign.code());
        assertEquals(1, fixture.repository.store.bindings.size());
        assertEquals(FIRST_PUBLIC, fixture.repository.store.bindings.values().iterator().next().profileId());
    }

    @Test
    void statusReportsProfileVersionAndMostRecentProjectionFailure() {
        Fixture fixture = fixture(false);
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED,
                fixture.provisioning.assign(PROVIDER, "npc-one", "admin", FIRST.value()).status());
        assertTrue(fixture.provisioning.status(PROVIDER, "npc-one").orElseThrow()
                .lastProjectionError().isEmpty());

        fixture.provider.rejectNextPublish = true;
        NpcProvisioningUseCase.ProvisioningResult reprojection = fixture.provisioning.reproject(
                PROVIDER, "npc-one", "admin");
        NpcProvisioningUseCase.AssignmentStatus status = fixture.provisioning
                .status(PROVIDER, "npc-one").orElseThrow();

        assertEquals(NpcProvisioningUseCase.Status.REJECTED, reprojection.status());
        assertEquals("BOUND", status.lifecycleState());
        assertEquals(1, status.assignment().schemaVersion());
        assertEquals("admin", status.assignment().assignedBy());
        assertEquals("test provider rejected projection", status.lastProjectionError());
        assertEquals(2, fixture.provider.publishCount);
        assertEquals(NpcProvisioningAuditEntry.Action.REPROJECT,
                fixture.provisioning.auditTrail(PROVIDER, "npc-one").getLast().action());
    }

    @Test
    void reprojectDoesNotCallProviderWhenDurableRecoveryIntentCannotBeWritten() {
        Fixture fixture = fixture(false);
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED,
                fixture.provisioning.assign(PROVIDER, "npc-one", "admin", FIRST.value()).status());
        int publishCountBefore = fixture.provider.publishCount;
        fixture.repository.failNextWrite = true;

        NpcProvisioningUseCase.ProvisioningResult result = fixture.provisioning.reproject(
                PROVIDER, "npc-one", "admin");

        assertEquals(NpcProvisioningUseCase.Status.UNKNOWN, result.status());
        assertEquals(publishCountBefore, fixture.provider.publishCount,
                "provider publication must not start without its durable retry marker");
        NpcBinding binding = fixture.lifecycle.bindings().values().iterator().next();
        assertFalse(fixture.repository.store.pendingOperations.containsKey(binding.bindingId()));
    }

    @Test
    void uncertainReprojectKeepsDurableRecoveryIntentAcrossRestartAfterAuditFailure() {
        Fixture fixture = fixture(false);
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED,
                fixture.provisioning.assign(PROVIDER, "npc-one", "admin", FIRST.value()).status());
        fixture.provider.unknownNextPublish = true;
        fixture.provider.beforePublish = () -> fixture.repository.failNextWrite = true;

        NpcProvisioningUseCase.ProvisioningResult result = fixture.provisioning.reproject(
                PROVIDER, "npc-one", "admin");

        assertEquals(NpcProvisioningUseCase.Status.UNKNOWN, result.status());
        NpcBinding binding = fixture.lifecycle.bindings().values().iterator().next();
        assertEquals(NpcProviderOperation.PUBLISH,
                fixture.repository.store.pendingOperations.get(binding.bindingId()).operation);
        assertEquals(2, fixture.provider.publishCount);

        fixture.provider.unknownReconciliation = false;
        NpcBindingLifecycleService restartedLifecycle = new NpcBindingLifecycleService(
                fixture.providers, fixture.repository, fixture.catalog);
        assertEquals(NpcProviderResult.Status.ACCEPTED,
                restartedLifecycle.recover().forBinding(binding.bindingId()).orElseThrow().result().status());
        assertFalse(fixture.repository.store.pendingOperations.containsKey(binding.bindingId()));
    }

    @Test
    void assignmentCapturesAndRetainsLastKnownHostLocation() {
        Fixture fixture = fixture(false);
        NpcHostLocation location = new NpcHostLocation("minecraft:overworld", 12, 68, -34);

        NpcProvisioningUseCase.ProvisioningResult assigned = fixture.provisioning.assignWithLocation(
                PROVIDER, "npc-one", "admin", FIRST.value(), Optional.of(location));
        NpcProvisioningUseCase.AssignmentView initial = fixture.provisioning
                .current(PROVIDER, "npc-one").orElseThrow();

        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, assigned.status());
        assertEquals(location, initial.hostLocation());
        assertEquals("minecraft:overworld @ 12, 68, -34", initial.hostLocation().displayValue());

        fixture.provisioning.assign(PROVIDER, "npc-one", "admin", SECOND.value());
        assertEquals(location, fixture.provisioning.current(PROVIDER, "npc-one")
                .orElseThrow().hostLocation());
    }

    @Test
    void explicitProviderSwitchReusesTheLogicalBindingAndRemovesOldOwnership() {
        Fixture fixture = fixture(false);
        NpcProviderId secondProviderId = NpcProviderId.of("customnpcs-test");
        FakeProvider secondProvider = new FakeProvider(secondProviderId, false);
        fixture.providers.register(secondProvider);
        fixture.provisioning.assign(PROVIDER, "npc-one", "admin-one", FIRST.value());
        assertEquals(FIRST_PUBLIC.value(), fixture.provisioning.current(secondProviderId, "npc-one")
                .orElseThrow().profileId());
        String oldProviderRevision = fixture.provisioning.assignmentRevision("npc-one");
        String bindingId = fixture.provisioning.current(PROVIDER, "npc-one")
                .orElseThrow().bindingId();

        NpcProvisioningUseCase.ProvisioningResult switched = fixture.provisioning.assign(
                secondProviderId, "npc-one", "admin-two", FIRST.value());

        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, switched.status());
        NpcProvisioningUseCase.AssignmentView current = fixture.provisioning
                .current(secondProviderId, "npc-one").orElseThrow();
        assertEquals(bindingId, current.bindingId());
        assertEquals(FIRST_PUBLIC.value(), current.profileId());
        assertEquals(1, fixture.lifecycle.inspections().values().stream()
                .filter(value -> value.binding() != null)
                .filter(value -> value.binding().hostEntityUuid().equals("npc-one"))
                .count());
        assertTrue(fixture.provider.bindings.isEmpty());
        assertEquals(1, secondProvider.bindings.size());

        NpcProvisioningUseCase.ProvisioningResult staleOldProviderConfirmation = fixture.provisioning
                .assignIfRevisionMatches(
                        PROVIDER, "npc-one", "admin-one", SECOND.value(), Optional.empty(), oldProviderRevision);
        assertEquals(NpcProvisioningUseCase.Status.REJECTED, staleOldProviderConfirmation.status());
        assertEquals("stale-assignment", staleOldProviderConfirmation.code());
        NpcProvisioningUseCase.ProvisioningResult staleOldProviderUnassign = fixture.provisioning
                .unassignIfRevisionMatches(PROVIDER, "npc-one", "admin-one", oldProviderRevision);
        assertEquals(NpcProvisioningUseCase.Status.REJECTED, staleOldProviderUnassign.status());
        assertEquals("stale-assignment", staleOldProviderUnassign.code());
        assertEquals(FIRST_PUBLIC.value(), fixture.provisioning.current(secondProviderId, "npc-one")
                .orElseThrow().profileId());
        assertEquals(secondProviderId, fixture.repository.store.bindings.get(bindingId).providerId());
        assertTrue(fixture.provider.bindings.isEmpty());
        assertEquals(1, secondProvider.bindings.size());
    }

    @Test
    void targetProviderCanInspectAndUnassignTheCurrentOwnerFromAnotherProvider() {
        Fixture fixture = fixture(false);
        NpcProviderId targetProviderId = NpcProviderId.of("target-provider");
        fixture.providers.register(new FakeProvider(targetProviderId, false));
        fixture.provisioning.assign(PROVIDER, "npc-one", "admin", FIRST.value());
        String revision = fixture.provisioning.assignmentRevision("npc-one");

        assertEquals(FIRST_PUBLIC.value(), fixture.provisioning.current(targetProviderId, "npc-one")
                .orElseThrow().profileId());
        assertEquals(PROVIDER.value(), fixture.provisioning.status(targetProviderId, "npc-one")
                .orElseThrow().assignment().providerId());
        NpcProvisioningUseCase.ProvisioningResult result = fixture.provisioning.unassignIfRevisionMatches(
                targetProviderId, "npc-one", "admin", revision);

        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, result.status());
        assertTrue(fixture.provider.bindings.isEmpty());
        assertTrue(fixture.provisioning.current(targetProviderId, "npc-one").isEmpty());
        assertEquals(NpcProvisioningAuditEntry.Action.UNASSIGN,
                fixture.provisioning.auditTrail(PROVIDER, "npc-one").getLast().action());
    }

    @Test
    void pendingCrossProviderSwitchCanBeSafelyCanceledFromTargetProviderGui() {
        Fixture fixture = fixture(false);
        NpcProviderId targetProviderId = NpcProviderId.of("target-provider");
        FakeProvider targetProvider = new FakeProvider(targetProviderId, false);
        targetProvider.unknownNextBind = true;
        fixture.providers.register(targetProvider);
        fixture.provisioning.assign(PROVIDER, "npc-one", "admin-one", FIRST.value());
        String beforeSwitch = fixture.provisioning.assignmentRevision("npc-one");

        NpcProvisioningUseCase.ProvisioningResult switched = fixture.provisioning.assignIfRevisionMatches(
                targetProviderId, "npc-one", "admin-two", FIRST.value(), Optional.empty(), beforeSwitch);
        NpcProvisioningUseCase.AssignmentStatus status = fixture.provisioning.status(
                targetProviderId, "npc-one").orElseThrow();
        String afterSwitch = fixture.provisioning.assignmentRevision("npc-one");
        NpcProvisioningUseCase.ProvisioningResult canceled = fixture.provisioning.unassignIfRevisionMatches(
                targetProviderId, "npc-one", "admin-two", afterSwitch);

        assertEquals(NpcProvisioningUseCase.Status.UNKNOWN, switched.status());
        assertEquals("UNKNOWN", status.lifecycleState());
        assertEquals(PROVIDER.value(), status.assignment().providerId());
        assertEquals(NpcProvisioningUseCase.Status.UNKNOWN, canceled.status());
        assertEquals(NpcRebindPhase.UNASSIGN,
                fixture.repository.store.pendingRebinds.values().iterator().next().phase);

        targetProvider.unknownReconciliation = false;
        fixture.lifecycle.recover();

        assertTrue(fixture.lifecycle.inspections().isEmpty());
        assertTrue(fixture.provider.bindings.isEmpty());
        assertTrue(targetProvider.bindings.isEmpty());
    }

    @Test
    void cancelDuringUnknownPreviousUnbindReconcilesTheActualOwnerFirst() {
        Fixture fixture = fixture(false);
        NpcProviderId targetProviderId = NpcProviderId.of("target-provider");
        fixture.providers.register(new FakeProvider(targetProviderId, false));
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED,
                fixture.provisioning.assign(PROVIDER, "npc-one", "admin-one", FIRST.value()).status());
        fixture.provider.unknownNextUnbind = true;
        String beforeSwitch = fixture.provisioning.assignmentRevision("npc-one");

        NpcProvisioningUseCase.ProvisioningResult switched = fixture.provisioning.assignIfRevisionMatches(
                targetProviderId, "npc-one", "admin-two", FIRST.value(), Optional.empty(), beforeSwitch);
        assertEquals(NpcRebindPhase.UNBIND,
                fixture.repository.store.pendingRebinds.values().iterator().next().phase);

        fixture.provider.unknownReconciliation = false;
        NpcProvisioningUseCase.ProvisioningResult canceled = fixture.provisioning.unassignIfRevisionMatches(
                targetProviderId, "npc-one", "admin-two",
                fixture.provisioning.assignmentRevision("npc-one"));

        assertEquals(NpcProvisioningUseCase.Status.UNKNOWN, switched.status());
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, canceled.status());
        assertTrue(fixture.lifecycle.inspections().isEmpty());
        assertTrue(fixture.provider.bindings.isEmpty());
        assertTrue(fixture.providers.unknownBinding(
                fixture.provisioning.auditTrail(PROVIDER, "npc-one").getFirst().bindingId()).isEmpty());
    }

    @Test
    void unresolvedInitialBindBlocksAssignmentToAnotherProvider() {
        Fixture fixture = fixture(false);
        NpcProviderId secondProviderId = NpcProviderId.of("customnpcs-test");
        FakeProvider secondProvider = new FakeProvider(secondProviderId, false);
        fixture.providers.register(secondProvider);
        fixture.provider.unknownNextBind = true;

        NpcProvisioningUseCase.ProvisioningResult first = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin-one", FIRST.value());
        NpcBindingLifecycleService.Inspection pending = fixture.lifecycle.inspections().values()
                .stream().filter(value -> value.binding() != null).findFirst().orElseThrow();
        NpcProvisioningUseCase.ProvisioningResult second = fixture.provisioning.assign(
                secondProviderId, "npc-one", "admin-two", SECOND.value());

        assertEquals(NpcProvisioningUseCase.Status.UNKNOWN, first.status());
        assertEquals(NpcBindingLifecycleService.State.UNKNOWN, pending.state());
        assertEquals(PROVIDER, pending.binding().providerId());
        assertEquals(NpcProvisioningUseCase.Status.UNKNOWN, second.status());
        assertTrue(second.message().contains("unresolved provider operation"));
        assertTrue(secondProvider.bindings.isEmpty());
        assertEquals(1, fixture.lifecycle.inspections().size());
    }

    @Test
    void adminCanCancelPendingOnlyAssignmentAfterProviderConfirmsItIsUnbound() {
        Fixture fixture = fixture(false);
        fixture.provider.unknownNextBind = true;
        NpcProvisioningUseCase.ProvisioningResult initial = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin-one", FIRST.value());
        String currentRevision = fixture.provisioning.assignmentRevision("npc-one");
        // The initial bind response was uncertain; model the provider's later
        // ownership probe becoming available before testing cancellation.
        fixture.provider.unknownReconciliation = false;

        NpcProvisioningUseCase.ProvisioningResult canceled = fixture.provisioning.unassignIfRevisionMatches(
                PROVIDER, "npc-one", "admin-one", currentRevision);

        assertEquals(NpcProvisioningUseCase.Status.UNKNOWN, initial.status());
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, canceled.status());
        assertTrue(fixture.provisioning.current(PROVIDER, "npc-one").isEmpty());
        assertTrue(fixture.lifecycle.inspections().isEmpty());
        assertTrue(fixture.provider.bindings.isEmpty());
        assertEquals(NpcProvisioningAuditEntry.Action.UNASSIGN,
                fixture.provisioning.auditTrail(PROVIDER, "npc-one").getLast().action());
        assertEquals(NpcProvisioningAuditEntry.Outcome.ACCEPTED,
                fixture.provisioning.auditTrail(PROVIDER, "npc-one").getLast().outcome());
    }

    @Test
    void pendingProvisioningAuditCapacityBlocksNewProviderMutations() {
        Fixture fixture = fixture(false);
        for (int index = 0; index < 256; index++) {
            String host = "npc-pending-" + index;
            assertTrue(fixture.lifecycle.recordProvisioningAuditIfRevision(
                    new NpcProvisioningAuditEntry(
                            "pending-event-" + index,
                            "binding-" + index,
                            NpcProvisioningAuditEntry.Action.ASSIGN,
                            PROVIDER.value(),
                            host,
                            "admin-one",
                            "",
                            FIRST.value(),
                            NpcProvisioningAuditEntry.Outcome.PENDING,
                            "operation-pending",
                            "Pending recovery fixture.",
                            index),
                    "0"));
        }

        NpcProvisioningUseCase.ProvisioningResult blocked = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin-one", FIRST.value());

        assertEquals(NpcProvisioningUseCase.Status.REJECTED, blocked.status());
        assertEquals("recovery-backlog-full", blocked.code());
        assertTrue(blocked.message().contains("recovery backlog is full"));
        assertEquals("0", fixture.provisioning.assignmentRevision("npc-one"));
        assertTrue(fixture.provider.bindings.isEmpty());
        assertEquals(256, fixture.lifecycle.provisioningAudit().size());

        NpcProvisioningAuditEntry first = fixture.lifecycle.provisioningAudit().getFirst();
        fixture.lifecycle.completeProvisioningAudit(new NpcProvisioningAuditEntry(
                first.eventId(), first.bindingId(), first.action(), first.providerId(),
                first.providerInstanceId(), first.actorId(), first.oldProfileId(), first.newProfileId(),
                NpcProvisioningAuditEntry.Outcome.REJECTED, "recovered-rejection", "Resolved for capacity test.",
                first.occurredAtEpochMillis()));
        NpcProvisioningUseCase.ProvisioningResult resumed = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin-one", FIRST.value());
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, resumed.status());
        assertEquals(1, fixture.provider.bindings.size());
    }

    @Test
    void reprojectIsBlockedBeforePublishWhenCombinedRecoveryBacklogIsFull() {
        Fixture fixture = fixture(false);
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED,
                fixture.provisioning.assign(PROVIDER, "npc-one", "admin-one", FIRST.value()).status());
        int publishCountBefore = fixture.provider.publishCount;
        fixture.provider.unknownNextBind = true;
        assertEquals(NpcProvisioningUseCase.Status.UNKNOWN,
                fixture.provisioning.assign(PROVIDER, "npc-recovery", "admin-one", FIRST.value()).status());

        for (int index = 0; index < 255; index++) {
            String host = "npc-pending-" + index;
            assertTrue(fixture.lifecycle.recordProvisioningAuditIfRevision(
                    new NpcProvisioningAuditEntry(
                            "pending-event-" + index,
                            "binding-" + index,
                            NpcProvisioningAuditEntry.Action.ASSIGN,
                            PROVIDER.value(),
                            host,
                            "admin-one",
                            "",
                            FIRST_PUBLIC.value(),
                            NpcProvisioningAuditEntry.Outcome.PENDING,
                            "operation-pending",
                            "Pending recovery fixture.",
                            index),
                    "0"));
        }

        NpcProvisioningUseCase.ProvisioningResult reprojected = fixture.provisioning.reproject(
                PROVIDER, "npc-one", "admin-one");

        assertEquals(NpcProvisioningUseCase.Status.REJECTED, reprojected.status());
        assertEquals("recovery-backlog-full", reprojected.code());
        assertTrue(reprojected.message().contains("recovery backlog is full"));
        assertEquals(publishCountBefore, fixture.provider.publishCount);
        assertEquals(NpcBindingLifecycleService.State.BOUND,
                fixture.lifecycle.inspect(fixture.provisioning.current(PROVIDER, "npc-one")
                        .orElseThrow().bindingId()).state());
    }

    @Test
    void unassignAtPendingIntentCapacitySupersedesItsAssignmentIntent() {
        Fixture fixture = fixture(false);
        for (int index = 0; index < 255; index++) {
            String host = "npc-pending-" + index;
            assertTrue(fixture.lifecycle.recordProvisioningAuditIfRevision(
                    new NpcProvisioningAuditEntry(
                            "pending-event-" + index,
                            "binding-" + index,
                            NpcProvisioningAuditEntry.Action.ASSIGN,
                            PROVIDER.value(),
                            host,
                            "admin-one",
                            "",
                            FIRST.value(),
                            NpcProvisioningAuditEntry.Outcome.PENDING,
                            "operation-pending",
                            "Pending recovery fixture.",
                            index),
                    "0"));
        }
        fixture.provider.unknownNextBind = true;
        NpcProvisioningUseCase.ProvisioningResult initial = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin-one", FIRST.value());
        NpcBinding candidate = fixture.lifecycle.inspect(
                fixture.provisioning.current(PROVIDER, "npc-one").orElseThrow().bindingId()).binding();
        assertTrue(fixture.lifecycle.recordProvisioningAuditIfRevision(
                new NpcProvisioningAuditEntry(
                        "pending-cancel-event",
                        candidate.bindingId(),
                        NpcProvisioningAuditEntry.Action.ASSIGN,
                        PROVIDER.value(),
                        "npc-one",
                        "admin-one",
                        "",
                        FIRST_PUBLIC.value(),
                        NpcProvisioningAuditEntry.Outcome.PENDING,
                        "operation-pending",
                        "Simulated interrupted audit completion.",
                        43L),
                fixture.provisioning.assignmentRevision("npc-one"),
                candidate));
        fixture.provider.unknownReconciliation = false;

        NpcProvisioningUseCase.ProvisioningResult canceled = fixture.provisioning.unassignIfRevisionMatches(
                PROVIDER, "npc-one", "admin-one", fixture.provisioning.assignmentRevision("npc-one"));

        assertEquals(NpcProvisioningUseCase.Status.UNKNOWN, initial.status());
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, canceled.status());
        assertTrue(fixture.provisioning.current(PROVIDER, "npc-one").isEmpty());
        assertTrue(fixture.provider.bindings.isEmpty());
        assertEquals(258, fixture.lifecycle.provisioningAudit().size());
        assertEquals(255, fixture.lifecycle.provisioningAudit().stream()
                .filter(event -> event.outcome() == NpcProvisioningAuditEntry.Outcome.PENDING)
                .count());
        assertEquals(NpcProvisioningAuditEntry.Outcome.REJECTED,
                fixture.provisioning.auditTrail(PROVIDER, "npc-one").stream()
                        .filter(event -> event.eventId().equals("pending-cancel-event"))
                        .findFirst().orElseThrow().outcome());
        assertEquals(NpcProvisioningAuditEntry.Outcome.ACCEPTED,
                fixture.provisioning.auditTrail(PROVIDER, "npc-one").getLast().outcome());
    }

    @Test
    void pendingOnlyUnassignStaysLockedWhileProviderOwnershipIsUnknown() {
        Fixture fixture = fixture(false);
        fixture.provider.unknownNextBind = true;
        fixture.provider.unknownReconciliation = true;
        fixture.provisioning.assign(PROVIDER, "npc-one", "admin-one", FIRST.value());

        NpcProvisioningUseCase.ProvisioningResult canceled = fixture.provisioning.unassignIfRevisionMatches(
                PROVIDER, "npc-one", "admin-one", fixture.provisioning.assignmentRevision("npc-one"));

        assertEquals(NpcProvisioningUseCase.Status.UNKNOWN, canceled.status());
        String bindingId = fixture.lifecycle.inspections().values().iterator().next().bindingId();
        assertEquals(NpcBindingLifecycleService.State.UNKNOWN,
                fixture.lifecycle.inspect(bindingId).state());
        assertEquals(NpcProviderOperation.UNBIND,
                fixture.lifecycle.inspect(bindingId).pendingOperation());
        assertEquals(1, fixture.lifecycle.inspections().size());
    }

    @Test
    void legacyCrossProviderDuplicateMustBeCleanedUpBeforeReassignment() {
        Fixture fixture = fixture(false);
        NpcProviderId secondProviderId = NpcProviderId.of("customnpcs-test");
        fixture.providers.register(new FakeProvider(secondProviderId, false));
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED,
                fixture.provisioning.assign(PROVIDER, "npc-one", "legacy-admin", FIRST.value()).status());
        NpcBinding legacyDuplicate = new NpcBinding(
                "straja.customnpcs.duplicate", secondProviderId, "npc-one", "",
                "secretary", "hq", SECOND, 1);
        assertEquals(NpcProviderResult.Status.ACCEPTED,
                fixture.lifecycle.bindAndPublish(legacyDuplicate).status());

        NpcProvisioningUseCase.ProvisioningResult result = fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin", FIRST.value());

        assertEquals(NpcProvisioningUseCase.Status.REJECTED, result.status());
        assertEquals("duplicate-host-assignment", result.code());
        assertEquals(2, fixture.lifecycle.inspections().size());
        assertTrue(fixture.provisioning.auditTrail(PROVIDER, "npc-one").getLast()
                .failureReason().contains("multiple durable assignments"));
    }

    @Test
    void exactDuplicateBindingCanBeUnassignedThroughTheAdminRecoveryPath() {
        Fixture fixture = fixture(false);
        NpcProviderId secondProviderId = NpcProviderId.of("debug-text");
        fixture.providers.register(new FakeProvider(secondProviderId, false));
        NpcBinding first = new NpcBinding(
                "straja.customnpcs.first", PROVIDER, "npc-one", "",
                "secretary", "hq", FIRST, 1);
        NpcBinding second = new NpcBinding(
                "straja.debug-text.second", secondProviderId, "npc-one", "",
                "secretary", "hq", SECOND, 1);
        assertEquals(NpcProviderResult.Status.ACCEPTED, fixture.lifecycle.bindAndPublish(first).status());
        assertEquals(NpcProviderResult.Status.ACCEPTED, fixture.lifecycle.bindAndPublish(second).status());

        assertEquals(2, fixture.provisioning.assignments(PROVIDER, "npc-one").size());
        String revision = fixture.provisioning.assignmentRevision("npc-one");
        NpcProvisioningUseCase.ProvisioningResult result = fixture.provisioning
                .unassignBindingIfRevisionMatches(PROVIDER, "npc-one", second.bindingId(), "admin", revision);

        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, result.status());
        assertTrue(fixture.lifecycle.bindings().containsKey(first.bindingId()));
        assertFalse(fixture.lifecycle.bindings().containsKey(second.bindingId()));
        assertEquals(1, fixture.provisioning.assignments(PROVIDER, "npc-one").size());
    }

    @Test
    void concurrentFirstAssignmentsCannotCreateTwoProviderBindings() throws Exception {
        Fixture fixture = fixture(false);
        NpcProviderId secondProviderId = NpcProviderId.of("customnpcs-test");
        FakeProvider secondProvider = new FakeProvider(secondProviderId, false);
        fixture.providers.register(secondProvider);
        CountDownLatch firstBindEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstBind = new CountDownLatch(1);
        fixture.provider.holdNextBind(firstBindEntered, releaseFirstBind);
        AtomicReference<NpcProvisioningUseCase.ProvisioningResult> firstResult = new AtomicReference<>();
        AtomicReference<NpcProvisioningUseCase.ProvisioningResult> secondResult = new AtomicReference<>();

        Thread first = new Thread(() -> firstResult.set(fixture.provisioning.assign(
                PROVIDER, "npc-one", "admin-one", FIRST.value())));
        Thread second = new Thread(() -> secondResult.set(fixture.provisioning.assign(
                secondProviderId, "npc-one", "admin-two", SECOND.value())));
        first.start();
        assertTrue(firstBindEntered.await(2, TimeUnit.SECONDS));
        second.start();
        releaseFirstBind.countDown();
        first.join(2_000);
        second.join(2_000);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, firstResult.get().status());
        assertEquals(NpcProvisioningUseCase.Status.ACCEPTED, secondResult.get().status());
        assertEquals(1, fixture.lifecycle.inspections().values().stream()
                .filter(value -> value.binding() != null)
                .filter(value -> value.binding().hostEntityUuid().equals("npc-one"))
                .count());
        assertTrue(fixture.provider.bindings.isEmpty());
        assertEquals(1, secondProvider.bindings.size());
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
                profile(FIRST, FIRST_PUBLIC, "Reception desk", "receptionist", Set.of(NpcCapability.TEXT_MIRROR)),
                profile(SECOND, SECOND_PUBLIC, "Secretary desk", "secretary", Set.of(NpcCapability.TEXT_MIRROR)),
                profile(GUI_ONLY, GUI_ONLY_PUBLIC, "GUI desk", "gui", Set.of(NpcCapability.GUI))));
        MemoryRepository repository = new MemoryRepository();
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(providers, repository, catalog);
        FakeProvider fakeProvider = (FakeProvider) providers.find(PROVIDER).orElseThrow();
        NpcProvisioningService provisioning = new NpcProvisioningService(
                lifecycle, catalog, providers, () -> 42L);
        return new Fixture(provisioning, repository, catalog, fakeProvider, providers, lifecycle);
    }

    private static NpcContentProfile profile(
            NpcContentId id, NpcProfileId profileId, String title, String ignoredRole,
            Set<NpcCapability> required) {
        return new NpcContentProfile(
                id,
                profileId,
                1,
                title,
                title + " body",
                List.of(NpcSurfaceAction.enabled(
                        NpcContentId.of(id.value() + ".action"), "Open")),
                List.of(new NpcSurfaceSnapshot.DialogueNode(
                        NpcContentId.of(id.value() + ".dialogue"), "Purpose for " + title, List.of())),
                List.of(new NpcSurfaceSnapshot.QuestEntry(
                        NpcContentId.of(id.value() + ".quest"), title + " quest",
                        NpcSurfaceSnapshot.QuestState.AVAILABLE)),
                required,
                Set.of());
    }

    private record Fixture(
            NpcProvisioningService provisioning,
            MemoryRepository repository,
            NpcContentCatalog catalog,
            FakeProvider provider,
            NpcSurfaceProviderRegistry providers,
            NpcBindingLifecycleService lifecycle) {}

    private static final class FakeProvider implements NpcSurfaceProvider {
        private final NpcProviderId id;
        private final boolean rejectSecondPublish;
        private final Map<String, NpcBinding> bindings = new HashMap<>();
        private boolean rejectNextPublish;
        private boolean unknownNextPublish;
        private boolean unknownNextBind;
        private boolean unknownNextUnbind;
        private boolean unknownReconciliation;
        private Runnable beforePublish = () -> {};
        private CountDownLatch bindEntered;
        private CountDownLatch releaseBind;
        private int publishCount;

        private FakeProvider(NpcProviderId id, boolean rejectSecondPublish) {
            this.id = id;
            this.rejectSecondPublish = rejectSecondPublish;
        }

        private void holdNextBind(CountDownLatch entered, CountDownLatch release) {
            bindEntered = entered;
            releaseBind = release;
        }

        @Override public NpcProviderId providerId() { return id; }
        @Override public Set<NpcCapability> capabilities() { return Set.of(NpcCapability.TEXT_MIRROR); }
        @Override public NpcProviderResult bind(NpcBinding binding) {
            if (releaseBind != null) {
                bindEntered.countDown();
                try {
                    if (!releaseBind.await(2, TimeUnit.SECONDS)) {
                        return NpcProviderResult.unknown("test bind wait timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return NpcProviderResult.unknown("test bind wait interrupted");
                } finally {
                    releaseBind = null;
                }
            }
            if (unknownNextBind) {
                unknownNextBind = false;
                bindings.put(binding.bindingId(), binding);
                unknownReconciliation = true;
                return NpcProviderResult.unknown("test provider lost bind response after side effect");
            }
            NpcBinding current = bindings.putIfAbsent(binding.bindingId(), binding);
            return current == null || current.equals(binding)
                    ? NpcProviderResult.accepted("bound")
                    : NpcProviderResult.rejected("binding-owned", "different binding");
        }
        @Override public NpcProviderResult unbind(NpcBinding binding) {
            if (unknownNextUnbind) {
                unknownNextUnbind = false;
                unknownReconciliation = true;
                return NpcProviderResult.unknown("test provider lost unbind response before removal");
            }
            return bindings.remove(binding.bindingId(), binding)
                    ? NpcProviderResult.accepted("unbound")
                    : NpcProviderResult.rejected("not-bound", "not bound");
        }
        @Override public NpcProviderResult publish(NpcSurfaceSnapshot surface) {
            publishCount++;
            beforePublish.run();
            beforePublish = () -> {};
            if (unknownNextPublish) {
                unknownNextPublish = false;
                unknownReconciliation = true;
                return NpcProviderResult.unknown("test provider lost publish response after side effect");
            }
            if (rejectNextPublish) {
                rejectNextPublish = false;
                return NpcProviderResult.rejected("projection-rejected", "test provider rejected projection");
            }
            if (rejectSecondPublish && surface.profileId().equals(SECOND)) {
                return NpcProviderResult.rejected("provider-rejected", "test provider rejected profile");
            }
            return NpcProviderResult.accepted("published");
        }
        @Override public NpcProviderResult reconcile(NpcBinding candidate) {
            if (unknownReconciliation) return NpcProviderResult.unknown("test ownership probe is inconclusive");
            NpcBinding current = bindings.get(candidate.bindingId());
            if (candidate.equals(current)) {
                return NpcProviderResult.accepted("provider confirms binding ownership");
            }
            if (current == null) return NpcProviderResult.rejected("not-bound", "provider does not own binding");
            return NpcProviderResult.unknown("provider owns a different mapping");
        }
    }

    private static final class MemoryRepository implements NpcBindingRepository {
        private NpcBindingStore store = new NpcBindingStore();
        private boolean failNextWrite;
        private int failWriteAfter;
        @Override public NpcBindingStore read() { return copy(store); }
        @Override public void write(NpcBindingStore value) {
            if (failNextWrite) {
                failNextWrite = false;
                throw new IllegalStateException("test persistence failure");
            }
            if (failWriteAfter > 0 && --failWriteAfter == 0) {
                throw new IllegalStateException("test delayed persistence failure");
            }
            store = copy(value);
        }
        private static NpcBindingStore copy(NpcBindingStore value) {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            return gson.fromJson(gson.toJson(value), NpcBindingStore.class);
        }
    }
}
