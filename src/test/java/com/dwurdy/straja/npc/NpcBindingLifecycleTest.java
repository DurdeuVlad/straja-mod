package com.dwurdy.straja.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.dwurdy.straja.domain.model.NpcProfileId;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderOperation;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcRegistry;
import com.dwurdy.straja.domain.model.NpcProvisioningAuditEntry;
import com.dwurdy.straja.domain.model.NpcRebindPhase;
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
            PROFILE, NpcProfileId.of("straja:test-desk"), 1, "Test desk", "Test desk surface",
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
    void restartRecoveryIncludesPendingOnlyInitialBind() {
        MemoryRepository repository = new MemoryRepository();
        NpcBinding binding = binding("straja.test.pending-only", UUID.randomUUID().toString(),
                NpcProviderId.of("pending-only"));
        NpcSurfaceProvider firstBootProvider = new NpcSurfaceProvider() {
            @Override public NpcProviderId providerId() { return NpcProviderId.of("pending-only"); }
            @Override public Set<NpcCapability> capabilities() { return Set.of(NpcCapability.TEXT_MIRROR); }
            @Override public NpcProviderResult bind(NpcBinding candidate) {
                return NpcProviderResult.unknown("provider response was lost");
            }
            @Override public NpcProviderResult unbind(NpcBinding candidate) {
                return NpcProviderResult.accepted("unbound");
            }
            @Override public NpcProviderResult publish(com.dwurdy.straja.domain.model.NpcSurfaceSnapshot surface) {
                return NpcProviderResult.accepted("published");
            }
        };
        NpcSurfaceProviderRegistry firstRegistry = new NpcSurfaceProviderRegistry();
        firstRegistry.register(firstBootProvider);
        NpcBindingLifecycleService firstLifecycle = new NpcBindingLifecycleService(
                firstRegistry, repository, new NpcContentCatalog(List.of(CONTENT)));

        assertEquals(NpcProviderResult.Status.UNKNOWN, firstLifecycle.bindAndPublish(binding).status());
        assertTrue(repository.store.bindings.isEmpty());
        assertEquals(NpcProviderOperation.BIND,
                repository.store.pendingOperations.get(binding.bindingId()).operation);

        NpcSurfaceProvider restartedProvider = new NpcSurfaceProvider() {
            @Override public NpcProviderId providerId() { return NpcProviderId.of("pending-only"); }
            @Override public Set<NpcCapability> capabilities() { return Set.of(NpcCapability.TEXT_MIRROR); }
            @Override public NpcProviderResult bind(NpcBinding candidate) {
                return NpcProviderResult.accepted("bound after restart");
            }
            @Override public NpcProviderResult unbind(NpcBinding candidate) {
                return NpcProviderResult.accepted("unbound");
            }
            @Override public NpcProviderResult publish(com.dwurdy.straja.domain.model.NpcSurfaceSnapshot surface) {
                return NpcProviderResult.accepted("published");
            }
            @Override public NpcProviderResult reconcile(NpcBinding candidate) {
                return NpcProviderResult.rejected("not-bound", "fresh provider has no in-memory owner");
            }
        };
        NpcSurfaceProviderRegistry restartedRegistry = new NpcSurfaceProviderRegistry();
        restartedRegistry.register(restartedProvider);
        NpcBindingLifecycleService restartedLifecycle = new NpcBindingLifecycleService(
                restartedRegistry, repository, new NpcContentCatalog(List.of(CONTENT)));

        assertEquals(NpcProviderResult.Status.ACCEPTED,
                restartedLifecycle.recover().forBinding(binding.bindingId()).orElseThrow().result().status());
        assertEquals(NpcBindingLifecycleService.State.BOUND,
                restartedLifecycle.inspect(binding.bindingId()).state());
        assertEquals(binding, restartedLifecycle.bindings().get(binding.bindingId()));
        assertFalse(repository.store.pendingOperations.containsKey(binding.bindingId()));
    }

    @Test
    void uncertainProviderSwitchNeverRestoresOldOwnerOverUnknownReplacement() {
        MemoryRepository repository = new MemoryRepository();
        NpcProviderId replacementProviderId = NpcProviderId.of("replacement-provider");
        NpcBinding previous = binding("straja.test.provider-switch", UUID.randomUUID().toString());
        NpcBinding replacement = binding(
                previous.bindingId(), previous.hostEntityUuid(), replacementProviderId);
        Map<String, NpcBinding> replacementOwnership = new LinkedHashMap<>();
        AtomicBoolean reconciliationUnknown = new AtomicBoolean(false);
        NpcSurfaceProvider previousProvider = simpleProvider(NpcProviderId.DEBUG_TEXT, new LinkedHashMap<>());
        NpcSurfaceProvider replacementProvider = new NpcSurfaceProvider() {
            @Override public NpcProviderId providerId() { return replacementProviderId; }
            @Override public Set<NpcCapability> capabilities() { return Set.of(NpcCapability.TEXT_MIRROR); }
            @Override public NpcProviderResult bind(NpcBinding candidate) {
                replacementOwnership.put(candidate.bindingId(), candidate);
                reconciliationUnknown.set(true);
                return NpcProviderResult.unknown("replacement bind response was lost");
            }
            @Override public NpcProviderResult unbind(NpcBinding candidate) {
                return replacementOwnership.remove(candidate.bindingId(), candidate)
                        ? NpcProviderResult.accepted("unbound")
                        : NpcProviderResult.rejected("not-bound", "not bound");
            }
            @Override public NpcProviderResult publish(com.dwurdy.straja.domain.model.NpcSurfaceSnapshot surface) {
                return NpcProviderResult.accepted("published");
            }
            @Override public NpcProviderResult reconcile(NpcBinding candidate) {
                if (reconciliationUnknown.get()) return NpcProviderResult.unknown("ownership probe is inconclusive");
                return replacementOwnership.containsValue(candidate)
                        ? NpcProviderResult.accepted("replacement is owned")
                        : NpcProviderResult.rejected("not-bound", "replacement is not owned");
            }
        };
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(previousProvider);
        providers.register(replacementProvider);
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers, repository, new NpcContentCatalog(List.of(CONTENT)));
        assertEquals(NpcProviderResult.Status.ACCEPTED, lifecycle.bindAndPublish(previous).status());

        assertEquals(NpcProviderResult.Status.UNKNOWN, lifecycle.rebind(replacement).status());
        assertEquals(previous, lifecycle.bindings().get(previous.bindingId()));
        assertEquals(NpcRebindPhase.RESTORE,
                repository.store.pendingRebinds.get(previous.bindingId()).phase);
        assertTrue(providers.binding(previous.bindingId()).isEmpty());
        assertEquals(replacement, providers.unknownBinding(previous.bindingId()).orElseThrow());

        reconciliationUnknown.set(false);
        NpcProviderResult recovered = lifecycle.recover()
                .forBinding(previous.bindingId()).orElseThrow().result();

        assertEquals(NpcProviderResult.Status.REJECTED, recovered.status());
        assertEquals(previous, lifecycle.bindings().get(previous.bindingId()));
        assertTrue(repository.store.pendingRebinds.isEmpty());
        assertTrue(providers.owns(previous.bindingId(), previous.providerId()));
        assertFalse(providers.owns(replacement.bindingId(), replacement.providerId()));
    }

    @Test
    void freshRegistryRestoresPreviousProviderFromPersistedRestorePhase() {
        MemoryRepository repository = new MemoryRepository();
        NpcProviderId replacementProviderId = NpcProviderId.of("fresh-replacement-provider");
        NpcBinding previous = binding("straja.test.fresh-restore", UUID.randomUUID().toString());
        NpcBinding replacement = binding(
                previous.bindingId(), previous.hostEntityUuid(), replacementProviderId);
        repository.store.bindings.put(previous.bindingId(), previous);
        repository.store.pendingRebinds.put(previous.bindingId(), new NpcBindingStore.PendingRebind(
                previous, replacement, NpcRebindPhase.RESTORE));
        Map<String, NpcBinding> previousProviderBindings = new LinkedHashMap<>();
        Map<String, NpcBinding> replacementProviderBindings = new LinkedHashMap<>();
        replacementProviderBindings.put(replacement.bindingId(), replacement);
        NpcSurfaceProviderRegistry freshRegistry = new NpcSurfaceProviderRegistry();
        freshRegistry.register(simpleProvider(NpcProviderId.DEBUG_TEXT, previousProviderBindings));
        freshRegistry.register(simpleProvider(replacementProviderId, replacementProviderBindings));
        NpcBindingLifecycleService restartedLifecycle = new NpcBindingLifecycleService(
                freshRegistry, repository, new NpcContentCatalog(List.of(CONTENT)));

        NpcProviderResult result = restartedLifecycle.recover()
                .forBinding(previous.bindingId()).orElseThrow().result();

        assertEquals(NpcProviderResult.Status.REJECTED, result.status());
        assertEquals(previous, restartedLifecycle.bindings().get(previous.bindingId()));
        assertTrue(repository.store.pendingRebinds.isEmpty());
        assertTrue(freshRegistry.owns(previous.bindingId(), previous.providerId()));
        assertFalse(freshRegistry.owns(replacement.bindingId(), replacement.providerId()));
        assertTrue(replacementProviderBindings.isEmpty());
    }

    @Test
    void recoveryCompletesProvisioningAuditIntentsFromDurableFinalState() {
        MemoryRepository repository = new MemoryRepository();
        NpcProviderId providerId = NpcProviderId.of("audit-recovery");
        NpcBinding assigned = binding("straja.test.audit-assigned", UUID.randomUUID().toString(), providerId);
        NpcBinding unassigned = binding("straja.test.audit-unassigned", UUID.randomUUID().toString(), providerId);
        repository.store.bindings.put(assigned.bindingId(), assigned);
        repository.store.bindings.put(unassigned.bindingId(), unassigned);
        repository.store.provisioningAudit.add(pendingAudit(
                "audit-assign", assigned, NpcProvisioningAuditEntry.Action.ASSIGN, "", CONTENT.profileId().value()));
        repository.store.provisioningAudit.add(pendingAudit(
                "audit-unassign", unassigned, NpcProvisioningAuditEntry.Action.UNASSIGN,
                CONTENT.profileId().value(), ""));
        repository.store.pendingProvisioningCandidates.put("audit-assign", assigned);
        Map<String, NpcBinding> providerBindings = new LinkedHashMap<>();
        providerBindings.put(assigned.bindingId(), assigned);
        providerBindings.put(unassigned.bindingId(), unassigned);
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(simpleProvider(providerId, providerBindings));
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers, repository, new NpcContentCatalog(List.of(CONTENT)));

        lifecycle.recover();

        assertEquals(NpcProvisioningAuditEntry.Outcome.ACCEPTED,
                lifecycle.provisioningAudit().get(0).outcome());
        assertEquals(NpcProvisioningAuditEntry.Outcome.ACCEPTED,
                lifecycle.provisioningAudit().get(1).outcome());
        assertTrue(lifecycle.bindings().containsKey(assigned.bindingId()));
        assertFalse(lifecycle.bindings().containsKey(unassigned.bindingId()));
        assertTrue(repository.store.pendingProvisioningCandidates.isEmpty());
    }

    @Test
    void interruptedInitialAssignmentReplaysTheExactPersistedCandidate() {
        MemoryRepository repository = new MemoryRepository();
        NpcProviderId providerId = NpcProviderId.of("candidate-recovery");
        NpcBinding candidate = new NpcBinding(
                "straja.test.candidate-recovery", providerId, UUID.randomUUID().toString(), "external-7",
                "receptionist", "hq", PROFILE, CONTENT.profileId(), 1, "admin", 99L,
                new com.dwurdy.straja.domain.model.NpcHostLocation("minecraft:overworld", 3, 64, 8));
        NpcProvisioningAuditEntry intent = pendingAudit(
                "candidate-audit", candidate, NpcProvisioningAuditEntry.Action.ASSIGN,
                "", CONTENT.profileId().value());
        repository.store.provisioningAudit.add(intent);
        repository.store.pendingProvisioningCandidates.put(intent.eventId(), candidate);
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(simpleProvider(providerId, new LinkedHashMap<>()));
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers, repository, new NpcContentCatalog(List.of(CONTENT)));

        NpcProviderResult result = lifecycle.recover()
                .forBinding(candidate.bindingId()).orElseThrow().result();

        assertEquals(NpcProviderResult.Status.ACCEPTED, result.status());
        assertEquals(candidate, lifecycle.bindings().get(candidate.bindingId()));
        assertEquals(NpcProvisioningAuditEntry.Outcome.ACCEPTED,
                lifecycle.provisioningAudit().getFirst().outcome());
        assertTrue(repository.store.pendingProvisioningCandidates.isEmpty());
    }

    @Test
    void recoveryDoesNotMarkPendingAssignmentAcceptedWhenProviderRejectsProjection() {
        MemoryRepository repository = new MemoryRepository();
        NpcProviderId providerId = NpcProviderId.of("rejecting-recovery");
        NpcBinding binding = binding("straja.test.rejecting-recovery", UUID.randomUUID().toString(), providerId);
        NpcProvisioningAuditEntry intent = pendingAudit(
                "rejected-audit", binding, NpcProvisioningAuditEntry.Action.ASSIGN,
                "", CONTENT.profileId().value());
        repository.store.bindings.put(binding.bindingId(), binding);
        repository.store.provisioningAudit.add(intent);
        repository.store.pendingProvisioningCandidates.put(intent.eventId(), binding);
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(new NpcSurfaceProvider() {
            @Override public NpcProviderId providerId() { return providerId; }
            @Override public Set<NpcCapability> capabilities() { return Set.of(NpcCapability.TEXT_MIRROR); }
            @Override public NpcProviderResult bind(NpcBinding candidate) {
                return NpcProviderResult.accepted("bound");
            }
            @Override public NpcProviderResult unbind(NpcBinding candidate) {
                return NpcProviderResult.accepted("unbound");
            }
            @Override public NpcProviderResult publish(com.dwurdy.straja.domain.model.NpcSurfaceSnapshot surface) {
                return NpcProviderResult.rejected("projection-rejected", "provider rejected projection");
            }
            @Override public NpcProviderResult reconcile(NpcBinding candidate) {
                return NpcProviderResult.accepted("provider confirms binding ownership");
            }
        });
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers, repository, new NpcContentCatalog(List.of(CONTENT)));

        NpcProviderResult result = lifecycle.recover()
                .forBinding(binding.bindingId()).orElseThrow().result();

        assertEquals(NpcProviderResult.Status.REJECTED, result.status());
        assertEquals(NpcProvisioningAuditEntry.Outcome.REJECTED,
                lifecycle.provisioningAudit().getFirst().outcome());
        assertEquals("projection-rejected", lifecycle.provisioningAudit().getFirst().resultCode());
        assertTrue(repository.store.pendingProvisioningCandidates.isEmpty());
    }

    @Test
    void laterPendingUnassignSupersedesEarlierPendingInitialAssignmentOnRestart() {
        MemoryRepository repository = new MemoryRepository();
        NpcProviderId providerId = NpcProviderId.of("cancel-on-restart");
        NpcBinding candidate = binding("straja.test.cancel-on-restart", UUID.randomUUID().toString(), providerId);
        NpcProvisioningAuditEntry assignment = pendingAudit(
                "pending-assignment", candidate, NpcProvisioningAuditEntry.Action.ASSIGN,
                "", CONTENT.profileId().value());
        NpcProvisioningAuditEntry unassign = pendingAudit(
                "pending-unassign", candidate, NpcProvisioningAuditEntry.Action.UNASSIGN,
                CONTENT.profileId().value(), "");
        repository.store.provisioningAudit.add(assignment);
        repository.store.provisioningAudit.add(unassign);
        repository.store.pendingProvisioningCandidates.put(assignment.eventId(), candidate);
        repository.store.pendingOperations.put(candidate.bindingId(), new NpcBindingStore.PendingOperation(
                candidate, NpcProviderOperation.BIND, true));
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(simpleProvider(providerId, new LinkedHashMap<>()));
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers, repository, new NpcContentCatalog(List.of(CONTENT)));

        lifecycle.recover();

        assertTrue(lifecycle.bindings().isEmpty());
        assertTrue(lifecycle.inspections().isEmpty());
        assertEquals(NpcProvisioningAuditEntry.Outcome.REJECTED,
                lifecycle.provisioningAudit().get(0).outcome());
        assertEquals("assignment-cancelled", lifecycle.provisioningAudit().get(0).resultCode());
        assertEquals(NpcProvisioningAuditEntry.Outcome.ACCEPTED,
                lifecycle.provisioningAudit().get(1).outcome());
        assertTrue(repository.store.pendingProvisioningCandidates.isEmpty());
    }

    @Test
    void restartRecoversPendingUnassignFromItsCandidateBeforeLifecyclePhaseWasPersisted() {
        MemoryRepository repository = new MemoryRepository();
        NpcProviderId providerId = NpcProviderId.of("unassign-candidate-recovery");
        NpcBinding candidate = binding(
                "straja.test.unassign-candidate-recovery", UUID.randomUUID().toString(), providerId);
        NpcProvisioningAuditEntry unassign = pendingAudit(
                "pending-unassign-candidate", candidate, NpcProvisioningAuditEntry.Action.UNASSIGN,
                CONTENT.profileId().value(), "");
        repository.store.provisioningAudit.add(unassign);
        repository.store.pendingProvisioningCandidates.put(unassign.eventId(), candidate);
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(simpleProvider(providerId, new LinkedHashMap<>()));
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers, repository, new NpcContentCatalog(List.of(CONTENT)));

        NpcProviderResult recovered = lifecycle.recover()
                .forBinding(candidate.bindingId()).orElseThrow().result();

        assertEquals(NpcProviderResult.Status.ACCEPTED, recovered.status());
        assertTrue(lifecycle.bindings().isEmpty());
        assertTrue(lifecycle.inspections().isEmpty());
        assertEquals(NpcProvisioningAuditEntry.Outcome.ACCEPTED,
                lifecycle.provisioningAudit().getFirst().outcome());
        assertTrue(repository.store.pendingProvisioningCandidates.isEmpty());
    }

    private static NpcProvisioningAuditEntry pendingAudit(
            String eventId,
            NpcBinding binding,
            NpcProvisioningAuditEntry.Action action,
            String oldProfile,
            String newProfile) {
        return new NpcProvisioningAuditEntry(
                eventId, binding.bindingId(), action, binding.providerId().value(),
                binding.hostEntityUuid(), "admin", oldProfile, newProfile,
                NpcProvisioningAuditEntry.Outcome.PENDING, "operation-pending", "in progress", 1L);
    }

    private static NpcSurfaceProvider simpleProvider(
            NpcProviderId providerId,
            Map<String, NpcBinding> ownedBindings) {
        return new NpcSurfaceProvider() {
            @Override public NpcProviderId providerId() { return providerId; }
            @Override public Set<NpcCapability> capabilities() { return Set.of(NpcCapability.TEXT_MIRROR); }
            @Override public NpcProviderResult bind(NpcBinding candidate) {
                NpcBinding existing = ownedBindings.putIfAbsent(candidate.bindingId(), candidate);
                return existing == null || existing.equals(candidate)
                        ? NpcProviderResult.accepted("bound")
                        : NpcProviderResult.rejected("binding-owned", "different mapping");
            }
            @Override public NpcProviderResult unbind(NpcBinding candidate) {
                return ownedBindings.remove(candidate.bindingId(), candidate)
                        ? NpcProviderResult.accepted("unbound")
                        : NpcProviderResult.rejected("not-bound", "not bound");
            }
            @Override public NpcProviderResult publish(com.dwurdy.straja.domain.model.NpcSurfaceSnapshot surface) {
                return NpcProviderResult.accepted("published");
            }
            @Override public NpcProviderResult reconcile(NpcBinding candidate) {
                NpcBinding existing = ownedBindings.get(candidate.bindingId());
                return candidate.equals(existing)
                        ? NpcProviderResult.accepted("provider confirms ownership")
                        : NpcProviderResult.rejected("not-bound", "provider does not own the expected mapping");
            }
        };
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

    @Test
    void legacyBindingProfileIdIsCanonicalizedAndPersisted() {
        NpcBinding legacyBinding = new NpcBinding(
                "straja.test.legacy", NpcProviderId.DEBUG_TEXT, UUID.randomUUID().toString(), "",
                "receptionist", "hq", PROFILE, 1, "first-operator", 123L,
                new com.dwurdy.straja.domain.model.NpcHostLocation("minecraft:overworld", 7, 68, 11));
        var legacyJson = new com.google.gson.Gson().toJsonTree(legacyBinding).getAsJsonObject();
        legacyJson.remove("profileId");
        NpcBinding decodedLegacyBinding = new com.google.gson.Gson().fromJson(
                legacyJson, NpcBinding.class);

        MemoryRepository repository = new MemoryRepository();
        repository.store.schemaVersion = 2;
        repository.store.assignmentRevisions = null;
        repository.store.bindings.put(decodedLegacyBinding.bindingId(), decodedLegacyBinding);
        repository.store.pendingOperations.put(decodedLegacyBinding.bindingId(),
                new NpcBindingStore.PendingOperation(decodedLegacyBinding, NpcProviderOperation.PUBLISH));
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(new DebugTextNpcSurfaceProvider(ignored -> {}));

        new NpcBindingLifecycleService(
                providers, repository, new NpcContentCatalog(List.of(CONTENT)));

        assertEquals(NpcBindingStore.CURRENT_SCHEMA_VERSION, repository.store.schemaVersion);
        assertEquals(NpcProfileId.of("straja:test-desk"),
                repository.store.bindings.get(legacyBinding.bindingId()).profileId());
        NpcBinding migrated = repository.store.bindings.get(legacyBinding.bindingId());
        assertEquals(legacyBinding.bindingId(), migrated.bindingId());
        assertEquals(legacyBinding.providerId(), migrated.providerId());
        assertEquals(legacyBinding.hostEntityUuid(), migrated.hostEntityUuid());
        assertEquals(legacyBinding.hostLocation(), migrated.hostLocation());
        assertEquals("first-operator", migrated.assignedBy());
        assertEquals(123L, migrated.assignedAtEpochMillis());
        assertEquals(NpcProviderOperation.PUBLISH,
                repository.store.pendingOperations.get(legacyBinding.bindingId()).operation);
        assertEquals(migrated,
                repository.store.pendingOperations.get(legacyBinding.bindingId()).binding);
        var persisted = new com.google.gson.Gson().toJsonTree(repository.store).getAsJsonObject()
                .getAsJsonObject("bindings").getAsJsonObject(legacyBinding.bindingId());
        assertEquals(PROFILE.value(), persisted.getAsJsonObject("surfaceProfileId").get("value").getAsString());
        assertEquals(CONTENT.profileId().value(), persisted.getAsJsonObject("profileId").get("value").getAsString());
        assertTrue(repository.store.assignmentRevisions.isEmpty());
    }

    @Test
    void assignmentRevisionsSurviveSavedDataJsonRoundTrip() {
        NpcBindingStore store = new NpcBindingStore();
        store.assignmentRevisions.put("npc-one", 17L);
        com.google.gson.Gson gson = new com.google.gson.Gson();

        NpcBindingStore restored = gson.fromJson(gson.toJson(store), NpcBindingStore.class);

        assertEquals(17L, restored.assignmentRevisions.get("npc-one"));
    }

    @Test
    void schemaTwoLayoutMigratesToUnifiedSchemaFour() {
        MemoryRepository repository = new MemoryRepository();
        repository.store = new com.google.gson.Gson().fromJson("""
                {
                  "schemaVersion": 2,
                  "bindings": {},
                  "pendingOperations": {}
                }
                """, NpcBindingStore.class);

        new NpcBindingLifecycleService(
                new NpcSurfaceProviderRegistry(), repository, new NpcContentCatalog(List.of(CONTENT)));

        assertEquals(4, repository.store.schemaVersion);
        assertTrue(repository.store.pendingRebinds != null);
        assertTrue(repository.store.assignmentRevisions != null);
        assertTrue(repository.store.provisioningAudit != null);
        assertTrue(repository.store.pendingProvisioningCandidates != null);
    }

    @Test
    void rolloutSchemaThreeLayoutGainsUnifiedRecoveryFieldsWithoutDataLoss() {
        MemoryRepository repository = new MemoryRepository();
        NpcBindingStore decoded = new com.google.gson.Gson().fromJson("""
                {
                  "schemaVersion": 3,
                  "bindings": {},
                  "pendingOperations": {},
                  "pendingRebinds": {}
                }
                """, NpcBindingStore.class);
        repository.store = decoded;

        new NpcBindingLifecycleService(
                new NpcSurfaceProviderRegistry(), repository, new NpcContentCatalog(List.of(CONTENT)));

        assertEquals(4, repository.store.schemaVersion);
        assertTrue(repository.store.assignmentRevisions.isEmpty());
        assertTrue(repository.store.provisioningAudit.isEmpty());
        assertTrue(repository.store.pendingProvisioningCandidates.isEmpty());
    }

    @Test
    void provisioningSchemaThreeLayoutPreservesRecoveryFieldsDuringUnifiedMigration() {
        MemoryRepository repository = new MemoryRepository();
        NpcBinding candidate = binding(
                "straja.test.schema-three", UUID.randomUUID().toString(), NpcProviderId.DEBUG_TEXT);
        NpcBindingStore store = new NpcBindingStore();
        store.schemaVersion = 3;
        store.assignmentRevisions.put(candidate.hostEntityUuid(), 17L);
        store.pendingProvisioningCandidates.put("schema-three-intent", candidate);
        repository.store = new com.google.gson.Gson().fromJson(
                new com.google.gson.Gson().toJson(store), NpcBindingStore.class);

        new NpcBindingLifecycleService(
                new NpcSurfaceProviderRegistry(), repository, new NpcContentCatalog(List.of(CONTENT)));

        assertEquals(4, repository.store.schemaVersion);
        assertEquals(17L, repository.store.assignmentRevisions.get(candidate.hostEntityUuid()));
        assertEquals(candidate,
                repository.store.pendingProvisioningCandidates.get("schema-three-intent"));
    }

    @Test
    void stablePublicProfileIdSurvivesInternalContentIdAndSchemaChanges() {
        NpcBinding previous = new NpcBinding(
                "straja.test.renamed-content", NpcProviderId.DEBUG_TEXT,
                UUID.randomUUID().toString(), "", "receptionist", "hq",
                PROFILE, CONTENT.profileId(), 1, "admin", 12L, null);
        MemoryRepository repository = new MemoryRepository();
        repository.store.bindings.put(previous.bindingId(), previous);
        NpcContentProfile revised = new NpcContentProfile(
                NpcContentId.of("straja.test.profile.v2"), CONTENT.profileId(), 2,
                "Test desk v2", "Updated canonical content",
                CONTENT.actions(), CONTENT.dialogue(), CONTENT.quests(),
                CONTENT.requiredCapabilities(), CONTENT.optionalCapabilities());
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(new DebugTextNpcSurfaceProvider(ignored -> {}));

        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers, repository, new NpcContentCatalog(List.of(revised)));
        NpcBinding migrated = repository.store.bindings.get(previous.bindingId());

        assertEquals(CONTENT.profileId(), migrated.profileId());
        assertEquals(revised.contentId(), migrated.contentProfileId());
        assertEquals(revised.schemaVersion(), migrated.schemaVersion());
        assertEquals(NpcProviderResult.Status.ACCEPTED,
                lifecycle.recover().forBinding(previous.bindingId()).orElseThrow().result().status());
    }

    @Test
    void retiredPublicProfileDoesNotResolveThroughAReusedContentId() {
        NpcProfileId retiredId = NpcProfileId.of("straja:retired-desk");
        NpcBinding retired = new NpcBinding(
                "straja.test.retired", NpcProviderId.DEBUG_TEXT,
                UUID.randomUUID().toString(), "", "receptionist", "hq",
                PROFILE, retiredId, 1, "admin", 12L, null);
        MemoryRepository repository = new MemoryRepository();
        repository.store.bindings.put(retired.bindingId(), retired);
        NpcContentProfile reused = new NpcContentProfile(
                PROFILE, NpcProfileId.of("straja:replacement-desk"), 1,
                "Replacement desk", "Different profile using the old internal key",
                CONTENT.actions(), CONTENT.dialogue(), CONTENT.quests(),
                CONTENT.requiredCapabilities(), CONTENT.optionalCapabilities());
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        providers.register(new DebugTextNpcSurfaceProvider(ignored -> {}));
        NpcBindingLifecycleService lifecycle = new NpcBindingLifecycleService(
                providers, repository, new NpcContentCatalog(List.of(reused)));
        NpcProviderResult recovery = lifecycle.recover()
                .forBinding(retired.bindingId()).orElseThrow().result();

        assertEquals(retiredId, repository.store.bindings.get(retired.bindingId()).profileId());
        assertEquals(NpcBindingLifecycleService.State.UNKNOWN,
                lifecycle.inspect(retired.bindingId()).state());
        assertEquals(NpcProviderResult.Status.REJECTED, recovery.status());
        assertEquals("unknown-profile", recovery.code());
        assertTrue(providers.binding(retired.bindingId()).isEmpty());
    }

    private static NpcBinding binding(String id, String hostUuid) {
        return binding(id, hostUuid, NpcProviderId.DEBUG_TEXT);
    }

    private static NpcBinding binding(String id, String hostUuid, NpcProviderId provider) {
        return new NpcBinding(id, provider, hostUuid, "",
                "receptionist", "hq", PROFILE, CONTENT.profileId(), 1, "", 0L, null);
    }

    private static final class MemoryRepository implements NpcBindingRepository {
        private NpcBindingStore store = new NpcBindingStore();
        @Override public NpcBindingStore read() { return store; }
        @Override public void write(NpcBindingStore value) { store = value; }
    }
}
