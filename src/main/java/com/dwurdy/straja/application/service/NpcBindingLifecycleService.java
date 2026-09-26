package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.NpcBindingRepository;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcBindingStore;
import com.dwurdy.straja.domain.model.NpcContentProfile;
import com.dwurdy.straja.domain.model.NpcProviderOperation;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcRebindPhase;
import com.dwurdy.straja.domain.model.NpcProvisioningAuditEntry;
import com.dwurdy.straja.domain.model.NpcProfileId;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable application service for bind, explicit rebind, inspect, unbind, and
 * server-restart provider reconciliation.
 */
public final class NpcBindingLifecycleService {
    private static final int MAX_PROVISIONING_AUDIT_EVENTS = 2_048;
    private static final int MAX_PENDING_PROVISIONING_AUDIT_EVENTS = 256;
    private static final String PROVISIONING_BACKLOG_FULL_MESSAGE =
            "NPC provisioning recovery backlog is full; no new provider operation was started. "
                    + "Resolve pending NPC assignments before retrying.";
    private final NpcSurfaceProviderRegistry providers;
    private final NpcBindingRepository repository;
    private final NpcContentCatalog content;
    private final NpcBindingStore store;

    public NpcBindingLifecycleService(
            NpcSurfaceProviderRegistry providers,
            NpcBindingRepository repository,
            NpcContentCatalog content) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.content = Objects.requireNonNull(content, "content");
        this.store = normalize(Objects.requireNonNull(repository.read(), "repository returned null"));
        canonicalizeStoredProfileIds();
        repository.write(store);
    }

    public synchronized NpcProviderResult bind(NpcBinding binding) {
        binding = canonicalProfileIdentity(Objects.requireNonNull(binding, "binding"));
        if (hasPendingAuditIntent(binding.bindingId())
                && !hasMatchingPendingAssignment(binding)) {
            return NpcProviderResult.unknown("binding has an unresolved provisioning intent");
        }
        if (store.pendingRebinds.containsKey(binding.bindingId())) {
            return NpcProviderResult.unknown("binding has an unresolved rebind transaction");
        }
        if (findProfile(binding) == null) {
            return NpcProviderResult.rejected(
                    "unknown-profile", "NPC profile is unknown or retired: " + binding.profileId().value());
        }
        NpcBinding current = store.bindings.get(binding.bindingId());
        if (current != null) {
            if (!current.equals(binding)) {
                return NpcProviderResult.rejected(
                        "binding-owned", "logical binding exists; use explicit rebind");
            }
            return bindWithRecoveryIntent(binding);
        }
        return bindWithRecoveryIntent(binding);
    }

    private NpcProviderResult bindWithRecoveryIntent(NpcBinding binding) {
        // Persist the exact candidate before the provider can mutate ownership.
        // If this write fails, no external bind has started; if the response is
        // lost later, restart still has the candidate needed to reconcile it.
        markPending(binding, NpcProviderOperation.BIND, true);
        NpcProviderResult result = providers.bind(binding);
        if (result.status() == NpcProviderResult.Status.ACCEPTED) {
            NpcBinding previousBinding = store.bindings.put(binding.bindingId(), binding);
            NpcBindingStore.PendingOperation previousOperation =
                    store.pendingOperations.remove(binding.bindingId());
            try {
                persist();
            } catch (RuntimeException error) {
                if (previousBinding == null) store.bindings.remove(binding.bindingId());
                else store.bindings.put(binding.bindingId(), previousBinding);
                if (previousOperation != null) {
                    store.pendingOperations.put(binding.bindingId(), previousOperation);
                }
                throw error;
            }
        } else if (requiresRecovery(result)) {
            markPending(binding, NpcProviderOperation.BIND, requiresReconciliation(result));
        } else {
            clearPendingOperation(binding.bindingId());
        }
        return result;
    }

    public synchronized NpcProviderResult bindAndPublish(NpcBinding binding) {
        binding = canonicalProfileIdentity(Objects.requireNonNull(binding, "binding"));
        NpcContentProfile profile = findProfile(binding);
        if (profile == null) {
            return NpcProviderResult.rejected(
                    "unknown-profile", "NPC profile is unknown or retired: " + binding.profileId().value());
        }
        NpcSurfaceSnapshot surface;
        try {
            surface = profile.bind(binding);
        } catch (RuntimeException error) {
            return NpcProviderResult.rejected("invalid-profile", error.getMessage());
        }
        NpcProviderResult bound = bind(binding);
        if (bound.status() != NpcProviderResult.Status.ACCEPTED) return bound;
        return publishWithRecoveryIntent(binding, surface);
    }

    public synchronized NpcProviderResult rebind(NpcBinding replacement) {
        replacement = canonicalProfileIdentity(Objects.requireNonNull(replacement, "replacement"));
        if (hasPendingAuditIntent(replacement.bindingId())
                && !hasMatchingPendingAssignment(replacement)) {
            return NpcProviderResult.unknown("binding has an unresolved provisioning intent");
        }
        NpcBindingStore.PendingRebind inProgress = store.pendingRebinds.get(replacement.bindingId());
        if (inProgress != null) {
            if (!inProgress.replacement.equals(replacement)) {
                return NpcProviderResult.unknown("binding has an unresolved rebind transaction");
            }
            return advanceRebind(inProgress);
        }
        NpcBinding current = store.bindings.get(replacement.bindingId());
        if (current == null) return bindAndPublish(replacement);
        if (current.equals(replacement)) return bindAndPublish(replacement);
        try {
            NpcContentProfile profile = findProfile(replacement);
            if (profile == null) {
                return NpcProviderResult.rejected(
                        "unknown-profile", "NPC profile is unknown or retired: " + replacement.profileId().value());
            }
            profile.bind(replacement);
        } catch (RuntimeException error) {
            return NpcProviderResult.rejected("invalid-profile", error.getMessage());
        }

        // The previous assignment remains durable until the replacement has
        // been bound and published. Each provider phase is recoverable after a
        // process restart; UNKNOWN never causes a blind ownership overwrite.
        NpcBindingStore.PendingRebind pending = new NpcBindingStore.PendingRebind(
                current, replacement, NpcRebindPhase.UNBIND);
        store.pendingRebinds.put(replacement.bindingId(), pending);
        persist();
        return advanceRebind(pending);
    }

    private NpcProviderResult advanceRebind(NpcBindingStore.PendingRebind pending) {
        NpcProviderResult result;
        switch (pending.phase) {
            case UNBIND -> {
                result = ensureUnbound(pending.previous);
                if (!isSuccessful(result)) return result;
                pending.phase = NpcRebindPhase.BIND;
                persist();
                return advanceRebind(pending);
            }
            case BIND -> {
                result = ensureBound(pending.replacement);
                if (!isSuccessful(result)) return beginRestore(pending, result);
                pending.phase = NpcRebindPhase.PUBLISH;
                persist();
                return advanceRebind(pending);
            }
            case PUBLISH -> {
                result = ensureBound(pending.replacement);
                if (!isSuccessful(result)) return beginRestore(pending, result);
                result = publish(pending.replacement);
                if (result.status() != NpcProviderResult.Status.ACCEPTED) {
                    return beginRestore(pending, result);
                }
                store.bindings.put(pending.replacement.bindingId(), pending.replacement);
                store.pendingOperations.remove(pending.replacement.bindingId());
                store.pendingRebinds.remove(pending.replacement.bindingId());
                persist();
                return result;
            }
            case RESTORE -> {
                result = ensureUnbound(pending.replacement);
                if (!isSuccessful(result)) return result;
                result = ensureBound(pending.previous);
                if (!isSuccessful(result)) return result;
                result = publish(pending.previous);
                if (result.status() != NpcProviderResult.Status.ACCEPTED) return result;
                store.bindings.put(pending.previous.bindingId(), pending.previous);
                store.pendingOperations.remove(pending.previous.bindingId());
                store.pendingRebinds.remove(pending.previous.bindingId());
                persist();
                return NpcProviderResult.rejected(
                        "rebind-rejected", "replacement failed; previous NPC assignment restored");
            }
            case UNASSIGN -> {
                Optional<NpcBinding> unknownOwner = providers.unknownBinding(pending.previous.bindingId());
                if (unknownOwner.isPresent()) {
                    NpcBinding actualOwner = unknownOwner.get();
                    if (!actualOwner.equals(pending.previous)
                            && !actualOwner.equals(pending.replacement)) {
                        return NpcProviderResult.unknown(
                                "provider has an uncertain mapping outside this unassign transaction");
                    }
                    result = providers.reconcile(actualOwner.bindingId());
                    if (requiresRecovery(result)) return result;
                    Optional<NpcBinding> reconciledOwner = providers.binding(actualOwner.bindingId());
                    if (reconciledOwner.isPresent()) {
                        if (!reconciledOwner.get().equals(actualOwner)) {
                            return NpcProviderResult.unknown(
                                    "provider reconciliation found a different mapping during unassign");
                        }
                        result = ensureUnbound(actualOwner);
                        if (!isSuccessful(result)) return result;
                    }
                }
                result = ensureUnbound(pending.replacement);
                if (!isSuccessful(result)) return result;
                result = ensureUnbound(pending.previous);
                if (!isSuccessful(result)) return result;
                finalizeUnbind(pending.previous.bindingId());
                return NpcProviderResult.accepted("NPC assignment and pending provider mapping removed");
            }
            default -> throw new IllegalStateException("unsupported rebind phase: " + pending.phase);
        }
    }

    private NpcProviderResult beginRestore(
            NpcBindingStore.PendingRebind pending,
            NpcProviderResult failure) {
        pending.phase = NpcRebindPhase.RESTORE;
        persist();
        NpcProviderResult restored = advanceRebind(pending);
        if (restored.status() == NpcProviderResult.Status.REJECTED
                && "rebind-rejected".equals(restored.code())) {
            return new NpcProviderResult(
                    failure.status(), failure.code(), failure.message() + "; previous NPC assignment restored");
        }
        return NpcProviderResult.unknown(
                failure.message() + "; previous NPC assignment restoration is unresolved");
    }

    private NpcProviderResult ensureUnbound(NpcBinding binding) {
        Optional<NpcBinding> owned = providers.binding(binding.bindingId());
        if (owned.isPresent()) {
            if (!owned.get().equals(binding)) {
                return NpcProviderResult.unknown("provider owns the logical binding with another mapping");
            }
            return providers.unbind(binding);
        }
        NpcProviderResult reconciled = providers.reconcile(binding);
        if (requiresRecovery(reconciled)) return reconciled;
        return providers.binding(binding.bindingId()).isPresent()
                ? providers.unbind(binding)
                : NpcProviderResult.accepted("binding is not owned");
    }

    private NpcProviderResult ensureBound(NpcBinding binding) {
        Optional<NpcBinding> owned = providers.binding(binding.bindingId());
        if (owned.isPresent()) {
            return owned.get().equals(binding)
                    ? NpcProviderResult.accepted("binding already owned")
                    : NpcProviderResult.unknown("provider owns the logical binding with another mapping");
        }
        NpcProviderResult reconciled = providers.reconcile(binding);
        if (requiresRecovery(reconciled)) return reconciled;
        Optional<NpcBinding> afterReconciliation = providers.binding(binding.bindingId());
        if (afterReconciliation.isPresent()) {
            return afterReconciliation.get().equals(binding)
                    ? NpcProviderResult.accepted("binding already reconciled")
                    : NpcProviderResult.unknown("provider reconciled a different mapping");
        }
        // Reconciliation proved this provider does not own the mapping, so
        // starting the bind cannot duplicate an uncertain external mutation.
        return providers.bind(binding);
    }

    private NpcProviderResult publish(NpcBinding binding) {
        NpcContentProfile profile = findProfile(binding);
        if (profile == null) {
            return NpcProviderResult.rejected(
                    "unknown-profile", "NPC profile is unknown or retired: " + binding.profileId().value());
        }
        try {
            return providers.publish(profile.bind(binding));
        } catch (RuntimeException error) {
            return NpcProviderResult.rejected("invalid-profile", error.getMessage());
        }
    }

    private static boolean isSuccessful(NpcProviderResult result) {
        return result.status() == NpcProviderResult.Status.ACCEPTED
                || result.status() == NpcProviderResult.Status.RECONCILED;
    }

    public synchronized NpcProviderResult unbind(String bindingId) {
        String id = Objects.requireNonNull(bindingId, "bindingId");
        NpcBindingStore.PendingRebind rebind = store.pendingRebinds.get(id);
        if (rebind != null) {
            beginRebindUnassign(rebind);
            return advanceRebind(rebind);
        }
        NpcBinding current = store.bindings.get(id);
        if (current == null) {
            NpcBindingStore.PendingOperation pending = store.pendingOperations.get(id);
            if (pending != null) current = pending.binding;
        }
        if (current == null) current = pendingAuditCandidate(id);
        if (current == null) {
            if (hasPendingAuditIntent(id)) {
                return NpcProviderResult.unknown(
                        "binding has an unresolved provisioning intent without a recoverable candidate");
            }
            return NpcProviderResult.rejected("not-bound", "logical binding is not registered");
        }
        NpcProvisioningAuditEntry intent = pendingAuditIntent(id);
        if (intent != null && intent.action() != NpcProvisioningAuditEntry.Action.UNASSIGN) {
            return NpcProviderResult.unknown("binding has a pending operation that must be explicitly unassigned");
        }
        // Persist cancellation before probing or mutating provider state. This
        // replaces a pending first-bind/publish operation without discarding
        // its candidate until ownership has been resolved and, if necessary,
        // removed from the provider.
        beginUnassign(current);
        NpcProviderResult reconciled = providers.reconcile(current);
        if (requiresRecovery(reconciled)) return reconciled;
        Optional<NpcBinding> owned = providers.binding(id);
        if (owned.isEmpty()) {
            finalizeUnbind(id);
            return NpcProviderResult.accepted("NPC assignment cancelled before provider ownership");
        }
        if (!owned.get().equals(current)) {
            return NpcProviderResult.unknown("provider owns a different mapping; assignment remains locked");
        }
        NpcProviderResult result = providers.unbind(current);
        if (result.status() == NpcProviderResult.Status.ACCEPTED
                || (result.status() == NpcProviderResult.Status.REJECTED
                        && "not-bound".equals(result.code()))) {
            finalizeUnbind(id);
            return NpcProviderResult.accepted("NPC assignment removed");
        }
        if (requiresRecovery(result)) {
            markPending(current, NpcProviderOperation.UNBIND, true);
            return result;
        }
        return NpcProviderResult.unknown(
                result.message() + "; assignment remains locked until provider ownership is resolved");
    }

    /** Re-publishes the current canonical profile without changing assignment or gameplay state. */
    public synchronized NpcProviderResult reproject(String bindingId) {
        String id = Objects.requireNonNull(bindingId, "bindingId");
        if (store.pendingOperations.containsKey(id)) {
            return NpcProviderResult.unknown("binding has an unresolved provider operation");
        }
        if (store.pendingRebinds.containsKey(id)) {
            return NpcProviderResult.unknown("binding has an unresolved rebind transaction");
        }
        if (hasPendingAuditIntent(id)) {
            return NpcProviderResult.unknown("binding has an unresolved provisioning audit intent");
        }
        if (activeRecoveryBindingCount() >= MAX_PENDING_PROVISIONING_AUDIT_EVENTS) {
            return NpcProviderResult.rejected("recovery-backlog-full", PROVISIONING_BACKLOG_FULL_MESSAGE);
        }
        NpcBinding binding = store.bindings.get(id);
        if (binding == null) {
            return NpcProviderResult.rejected("not-bound", "logical binding is not registered");
        }
        NpcContentProfile profile = findProfile(binding);
        if (profile == null) {
            return NpcProviderResult.rejected(
                    "unknown-profile", "NPC profile is unknown or retired: " + binding.profileId().value());
        }
        NpcSurfaceSnapshot surface;
        try {
            surface = profile.bind(binding);
        } catch (RuntimeException error) {
            return NpcProviderResult.rejected("invalid-profile", error.getMessage());
        }
        return publishWithRecoveryIntent(binding, surface);
    }

    private NpcProviderResult publishWithRecoveryIntent(
            NpcBinding binding,
            NpcSurfaceSnapshot surface) {
        // Reproject and initial publication can both time out after applying a
        // change. The durable marker must precede the call, not depend on a
        // successful write after an ambiguous provider response.
        markPending(binding, NpcProviderOperation.PUBLISH, true);
        NpcProviderResult result = providers.publish(surface);
        if (requiresRecovery(result)) {
            markPending(binding, NpcProviderOperation.PUBLISH, requiresReconciliation(result));
        } else {
            clearPendingOperation(binding.bindingId());
        }
        return result;
    }

    /** Records a structured provisioning event in the same durable SavedData aggregate. */
    public synchronized void recordProvisioningAudit(NpcProvisioningAuditEntry entry) {
        Objects.requireNonNull(entry, "entry");
        appendProvisioningAudit(entry);
    }

    /** Atomically compares the latest assignment event and records a durable operation intent. */
    public synchronized boolean recordProvisioningAuditIfRevision(
            NpcProvisioningAuditEntry entry,
            String expectedRevision) {
        return recordProvisioningAuditIfRevision(entry, expectedRevision, null);
    }

    /** Atomically records a pending assignment plus its complete desired binding before provider calls. */
    public synchronized boolean recordProvisioningAuditIfRevision(
            NpcProvisioningAuditEntry entry,
            String expectedRevision,
            NpcBinding requestedBinding) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        if ((entry.action() != NpcProvisioningAuditEntry.Action.ASSIGN
                        && entry.action() != NpcProvisioningAuditEntry.Action.UNASSIGN)
                || entry.outcome() != NpcProvisioningAuditEntry.Outcome.PENDING) {
            throw new IllegalArgumentException("revision-guarded audit entry must be a pending assignment mutation");
        }
        String requestedProfileId = entry.action() == NpcProvisioningAuditEntry.Action.UNASSIGN
                ? entry.oldProfileId() : entry.newProfileId();
        if (entry.action() == NpcProvisioningAuditEntry.Action.UNASSIGN && requestedBinding == null) {
            throw new IllegalArgumentException("pending unassign intent requires its exact binding candidate");
        }
        if (requestedBinding != null
                && (!requestedBinding.bindingId().equals(entry.bindingId())
                        || !requestedBinding.providerId().value().equals(entry.providerId())
                        || !requestedBinding.hostEntityUuid().equals(entry.providerInstanceId())
                        || !requestedBinding.profileId().value().equals(requestedProfileId))) {
            throw new IllegalArgumentException("requested binding does not match the provisioning intent");
        }
        if (!assignmentRevision(entry.providerInstanceId()).equals(expectedRevision)) {
            return false;
        }
        long nextRevision = Math.addExact(
                store.assignmentRevisions.getOrDefault(entry.providerInstanceId(), 0L), 1L);
        Long previousRevision = store.assignmentRevisions.get(entry.providerInstanceId());
        List<NpcProvisioningAuditEntry> previousAudit = new ArrayList<>(store.provisioningAudit);
        Map<String, NpcBinding> previousCandidates = new java.util.LinkedHashMap<>(
                store.pendingProvisioningCandidates);
        try {
            store.assignmentRevisions.put(entry.providerInstanceId(), nextRevision);
            if (entry.action() == NpcProvisioningAuditEntry.Action.UNASSIGN) {
                // A new unassign supersedes unresolved bind intent(s) for the
                // same host and coalesces an older retry. Move exact recovery
                // data onto the newest durable intent before provider access.
                cancelPendingAssignmentIntents(entry.bindingId());
                supersedePendingUnassignIntents(entry);
            }
            if (requestedBinding != null) {
                store.pendingProvisioningCandidates.put(entry.eventId(), requestedBinding);
            }
            appendProvisioningAuditWithoutPersist(entry);
            persist();
        } catch (RuntimeException error) {
            if (previousRevision == null) store.assignmentRevisions.remove(entry.providerInstanceId());
            else store.assignmentRevisions.put(entry.providerInstanceId(), previousRevision);
            store.provisioningAudit.clear();
            store.provisioningAudit.addAll(previousAudit);
            store.pendingProvisioningCandidates.clear();
            store.pendingProvisioningCandidates.putAll(previousCandidates);
            throw error;
        }
        return true;
    }

    /** Replaces a durable operation intent with its observed terminal result. */
    public synchronized void completeProvisioningAudit(NpcProvisioningAuditEntry completed) {
        Objects.requireNonNull(completed, "completed");
        if (completed.outcome() == NpcProvisioningAuditEntry.Outcome.PENDING) {
            throw new IllegalArgumentException("completed audit entry cannot remain pending");
        }
        for (int index = store.provisioningAudit.size() - 1; index >= 0; index--) {
            NpcProvisioningAuditEntry intent = store.provisioningAudit.get(index);
            if (intent.eventId().equals(completed.eventId())) {
                if (intent.action() != completed.action()
                        || !intent.bindingId().equals(completed.bindingId())
                        || !intent.providerId().equals(completed.providerId())
                        || !intent.providerInstanceId().equals(completed.providerInstanceId())
                        || !intent.actorId().equals(completed.actorId())
                        || !intent.oldProfileId().equals(completed.oldProfileId())
                        || !intent.newProfileId().equals(completed.newProfileId())) {
                    throw new IllegalArgumentException("completed audit metadata does not match its intent");
                }
                NpcProvisioningAuditEntry previous = intent;
                NpcBinding previousCandidate = store.pendingProvisioningCandidates.remove(completed.eventId());
                store.provisioningAudit.set(index, completed);
                try {
                    persist();
                } catch (RuntimeException error) {
                    store.provisioningAudit.set(index, previous);
                    if (previousCandidate != null) {
                        store.pendingProvisioningCandidates.put(completed.eventId(), previousCandidate);
                    }
                    throw error;
                }
                return;
            }
        }
        throw new IllegalStateException("provisioning audit intent was not retained: " + completed.eventId());
    }

    /** Monotonic persisted host-assignment generation shared across all provider mappings. */
    public synchronized String assignmentRevision(String providerInstanceId) {
        Objects.requireNonNull(providerInstanceId, "providerInstanceId");
        return Long.toString(store.assignmentRevisions.getOrDefault(providerInstanceId, 0L));
    }

    private void appendProvisioningAudit(NpcProvisioningAuditEntry entry) {
        appendProvisioningAuditWithoutPersist(entry);
        persist();
    }

    private void appendProvisioningAuditWithoutPersist(NpcProvisioningAuditEntry entry) {
        if (entry.outcome() == NpcProvisioningAuditEntry.Outcome.PENDING) {
            long pendingAuditCount = store.provisioningAudit.stream()
                    .filter(existing -> existing.outcome() == NpcProvisioningAuditEntry.Outcome.PENDING)
                    .count();
            boolean newRecoveryBinding = !entry.bindingId().isBlank()
                    && !hasActiveRecovery(entry.bindingId());
            if (pendingAuditCount >= MAX_PENDING_PROVISIONING_AUDIT_EVENTS
                    || (newRecoveryBinding
                            && activeRecoveryBindingCount() >= MAX_PENDING_PROVISIONING_AUDIT_EVENTS)) {
                throw new ProvisioningAuditCapacityException();
            }
        }
        store.provisioningAudit.add(entry);
        trimProvisioningAudit(store);
    }

    private boolean hasPendingAuditIntent(String bindingId) {
        return store.provisioningAudit.stream()
                .anyMatch(event -> event.outcome() == NpcProvisioningAuditEntry.Outcome.PENDING
                        && event.bindingId().equals(bindingId));
    }

    private NpcProvisioningAuditEntry pendingAuditIntent(String bindingId) {
        for (int index = store.provisioningAudit.size() - 1; index >= 0; index--) {
            NpcProvisioningAuditEntry event = store.provisioningAudit.get(index);
            if (event.outcome() == NpcProvisioningAuditEntry.Outcome.PENDING
                    && event.bindingId().equals(bindingId)) return event;
        }
        return null;
    }

    private NpcBinding pendingAuditCandidate(String bindingId) {
        for (int index = store.provisioningAudit.size() - 1; index >= 0; index--) {
            NpcProvisioningAuditEntry event = store.provisioningAudit.get(index);
            if (event.outcome() == NpcProvisioningAuditEntry.Outcome.PENDING
                    && event.bindingId().equals(bindingId)) {
                NpcBinding candidate = store.pendingProvisioningCandidates.get(event.eventId());
                if (candidate != null) return candidate;
            }
        }
        return store.pendingProvisioningCandidates.values().stream()
                .filter(candidate -> candidate.bindingId().equals(bindingId))
                .findFirst().orElse(null);
    }

    private boolean hasMatchingPendingAssignment(NpcBinding binding) {
        NpcProvisioningAuditEntry intent = pendingAuditIntent(binding.bindingId());
        return intent != null
                && intent.action() == NpcProvisioningAuditEntry.Action.ASSIGN
                && binding.equals(store.pendingProvisioningCandidates.get(intent.eventId()));
    }

    /** True while any durable operation for the host has not reached a terminal audit result. */
    public synchronized boolean hasPendingProvisioningIntentForHost(String hostEntityUuid) {
        Objects.requireNonNull(hostEntityUuid, "hostEntityUuid");
        return store.provisioningAudit.stream()
                .anyMatch(event -> event.outcome() == NpcProvisioningAuditEntry.Outcome.PENDING
                        && event.providerInstanceId().equals(hostEntityUuid));
    }

    private boolean hasActiveRecovery(String bindingId) {
        return store.pendingOperations.containsKey(bindingId)
                || store.pendingRebinds.containsKey(bindingId)
                || hasPendingAuditIntent(bindingId);
    }

    private int activeRecoveryBindingCount() {
        java.util.LinkedHashSet<String> bindingIds = new java.util.LinkedHashSet<>();
        store.provisioningAudit.stream()
                .filter(event -> event.outcome() == NpcProvisioningAuditEntry.Outcome.PENDING)
                .map(NpcProvisioningAuditEntry::bindingId)
                .filter(bindingId -> !bindingId.isBlank())
                .forEach(bindingIds::add);
        bindingIds.addAll(store.pendingOperations.keySet());
        bindingIds.addAll(store.pendingRebinds.keySet());
        return bindingIds.size();
    }

    public synchronized List<NpcProvisioningAuditEntry> provisioningAudit() {
        return List.copyOf(store.provisioningAudit);
    }

    public synchronized Inspection inspect(String bindingId) {
        String id = Objects.requireNonNull(bindingId, "bindingId");
        NpcBinding binding = store.bindings.get(id);
        NpcBindingStore.PendingOperation pending = store.pendingOperations.get(id);
        NpcBindingStore.PendingRebind rebind = store.pendingRebinds.get(id);
        if (rebind != null) {
            return new Inspection(id, State.UNKNOWN, rebind.previous, phaseOperation(rebind.phase));
        }
        if (pending != null) {
            // A first bind can be pending before it has ever entered bindings.
            // Surface the durable candidate so provisioning/status can block
            // cross-provider duplicates and show the unresolved assignment.
            if (binding == null) binding = pending.binding;
            return new Inspection(id, State.UNKNOWN, binding, pending.operation);
        }
        NpcProvisioningAuditEntry intent = pendingAuditIntent(id);
        if (intent != null) {
            if (binding == null) binding = store.pendingProvisioningCandidates.get(intent.eventId());
            if (binding == null) binding = store.bindings.get(id);
            return new Inspection(id, State.UNKNOWN, binding, auditOperation(intent.action()));
        }
        if (binding == null) {
            NpcBinding candidate = pendingAuditCandidate(id);
            if (candidate != null) return new Inspection(id, State.UNKNOWN, candidate, null);
            return new Inspection(id, State.UNBOUND, null, null);
        }
        if (findProfile(binding) == null) return new Inspection(id, State.UNKNOWN, binding, null);
        return new Inspection(id, State.BOUND, binding, null);
    }

    public synchronized Map<String, Inspection> inspections() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(store.bindings.keySet());
        ids.addAll(store.pendingOperations.keySet());
        ids.addAll(store.pendingRebinds.keySet());
        store.pendingProvisioningCandidates.values().stream()
                .map(NpcBinding::bindingId)
                .forEach(ids::add);
        store.provisioningAudit.stream()
                .filter(event -> event.outcome() == NpcProvisioningAuditEntry.Outcome.PENDING)
                .map(NpcProvisioningAuditEntry::bindingId)
                .filter(id -> !id.isBlank())
                .forEach(ids::add);
        java.util.LinkedHashMap<String, Inspection> result = new java.util.LinkedHashMap<>();
        for (String id : ids) result.put(id, inspect(id));
        return Map.copyOf(result);
    }

    /** Replays durable bindings after server/provider startup. */
    public synchronized RecoveryReport recover() {
        List<RecoveryItem> items = new ArrayList<>();
        seedInterruptedProvisioningIntents();
        java.util.LinkedHashSet<String> rebindIds = new java.util.LinkedHashSet<>(store.pendingRebinds.keySet());
        for (String id : rebindIds) {
            NpcBindingStore.PendingRebind pending = store.pendingRebinds.get(id);
            if (pending == null) continue;
            items.add(new RecoveryItem(id, advanceRebind(pending)));
        }

        java.util.LinkedHashSet<String> recoveryIds = new java.util.LinkedHashSet<>(store.bindings.keySet());
        recoveryIds.addAll(store.pendingOperations.keySet());
        recoveryIds.removeAll(rebindIds);
        for (String id : recoveryIds) {
            NpcBinding binding = store.bindings.get(id);
            NpcBindingStore.PendingOperation pending = store.pendingOperations.get(id);
            if (binding == null && pending != null) binding = pending.binding;
            if (binding == null) continue;

            if (pending != null && pending.operation == NpcProviderOperation.UNBIND) {
                // A cold registry cannot know whether an interrupted unbind
                // already removed provider state. Reconcile before retrying or
                // deleting the durable assignment.
                NpcProviderResult reconciled = providers.reconcile(binding);
                if (requiresRecovery(reconciled)) {
                    items.add(new RecoveryItem(id, reconciled));
                    continue;
                }
                if (providers.binding(id).isEmpty()) {
                    store.bindings.remove(id);
                    store.pendingOperations.remove(id);
                    persist();
                    items.add(new RecoveryItem(id,
                            NpcProviderResult.accepted("provider confirms the binding is already unassigned")));
                    continue;
                }
                NpcProviderResult removed = providers.unbind(binding);
                if (removed.status() == NpcProviderResult.Status.ACCEPTED
                        || (removed.status() == NpcProviderResult.Status.REJECTED
                                && "not-bound".equals(removed.code()))) {
                    store.bindings.remove(id);
                    store.pendingOperations.remove(id);
                    persist();
                    items.add(new RecoveryItem(id, NpcProviderResult.accepted("binding unassigned")));
                } else {
                    if (requiresRecovery(removed)) {
                        markPending(binding, NpcProviderOperation.UNBIND, requiresReconciliation(removed));
                    }
                    items.add(new RecoveryItem(id, removed));
                }
                continue;
            }

            NpcContentProfile profile = findProfile(binding);
            if (profile == null) {
                items.add(new RecoveryItem(id, NpcProviderResult.rejected(
                        "unknown-profile", "NPC profile is unknown or retired: " + binding.profileId().value())));
                continue;
            }

            if (pending != null && pending.requiresReconciliation) {
                NpcProviderResult reconciled = providers.reconcile(binding);
                if (requiresRecovery(reconciled)) {
                    items.add(new RecoveryItem(id, reconciled));
                    continue;
                }
            }

            NpcProviderResult bound;
            Optional<NpcBinding> owned = providers.binding(id);
            if (owned.isPresent()) {
                bound = owned.get().equals(binding)
                        ? NpcProviderResult.accepted("binding already reconciled")
                        : NpcProviderResult.unknown("provider owns the logical binding with a different mapping");
            } else {
                bound = bindWithRecoveryIntent(binding);
            }
            if (bound.status() != NpcProviderResult.Status.ACCEPTED) {
                items.add(new RecoveryItem(id, bound));
                continue;
            }

            boolean bindingWasMissing = !store.bindings.containsKey(id);
            if (bindingWasMissing) {
                store.bindings.put(id, binding);
                try {
                    persist();
                } catch (RuntimeException error) {
                    store.bindings.remove(id);
                    throw error;
                }
            }
            NpcProviderResult published;
            NpcSurfaceSnapshot surface;
            try {
                surface = profile.bind(binding);
            } catch (RuntimeException error) {
                items.add(new RecoveryItem(id,
                        NpcProviderResult.rejected("invalid-profile", error.getMessage())));
                continue;
            }
            published = publishWithRecoveryIntent(binding, surface);
            items.add(new RecoveryItem(id, published));
        }
        resolveProvisioningAuditIntents(items);
        return new RecoveryReport(List.copyOf(items));
    }

    public synchronized Map<String, NpcBinding> bindings() {
        return Map.copyOf(store.bindings);
    }

    private void markPending(
            NpcBinding binding,
            NpcProviderOperation operation,
            boolean requiresReconciliation) {
        NpcBindingStore.PendingOperation previous = store.pendingOperations.get(binding.bindingId());
        if (previous != null
                && previous.binding.equals(binding)
                && previous.operation == operation
                && previous.requiresReconciliation == requiresReconciliation) {
            return;
        }
        store.pendingOperations.put(binding.bindingId(), new NpcBindingStore.PendingOperation(
                binding, operation, requiresReconciliation));
        try {
            persist();
        } catch (RuntimeException error) {
            if (previous == null) store.pendingOperations.remove(binding.bindingId());
            else store.pendingOperations.put(binding.bindingId(), previous);
            throw error;
        }
    }

    private void clearPendingOperation(String bindingId) {
        NpcBindingStore.PendingOperation previous = store.pendingOperations.remove(bindingId);
        if (previous == null) return;
        try {
            persist();
        } catch (RuntimeException error) {
            store.pendingOperations.put(bindingId, previous);
            throw error;
        }
    }

    private void finalizeUnbind(String bindingId) {
        NpcBinding previousBinding = store.bindings.remove(bindingId);
        NpcBindingStore.PendingOperation previousOperation = store.pendingOperations.remove(bindingId);
        NpcBindingStore.PendingRebind previousRebind = store.pendingRebinds.remove(bindingId);
        List<NpcProvisioningAuditEntry> previousAudit = new ArrayList<>(store.provisioningAudit);
        Map<String, NpcBinding> previousCandidates = new java.util.LinkedHashMap<>(
                store.pendingProvisioningCandidates);
        cancelPendingAssignmentIntents(bindingId);
        try {
            persist();
        } catch (RuntimeException error) {
            if (previousBinding != null) store.bindings.put(bindingId, previousBinding);
            if (previousOperation != null) store.pendingOperations.put(bindingId, previousOperation);
            if (previousRebind != null) store.pendingRebinds.put(bindingId, previousRebind);
            store.provisioningAudit.clear();
            store.provisioningAudit.addAll(previousAudit);
            store.pendingProvisioningCandidates.clear();
            store.pendingProvisioningCandidates.putAll(previousCandidates);
            throw error;
        }
    }

    private void beginUnassign(NpcBinding binding) {
        String id = binding.bindingId();
        NpcBindingStore.PendingOperation previousOperation = store.pendingOperations.get(id);
        List<NpcProvisioningAuditEntry> previousAudit = new ArrayList<>(store.provisioningAudit);
        Map<String, NpcBinding> previousCandidates = new java.util.LinkedHashMap<>(
                store.pendingProvisioningCandidates);
        store.pendingOperations.put(id, new NpcBindingStore.PendingOperation(
                binding, NpcProviderOperation.UNBIND, true));
        cancelPendingAssignmentIntents(id);
        try {
            persist();
        } catch (RuntimeException error) {
            if (previousOperation == null) store.pendingOperations.remove(id);
            else store.pendingOperations.put(id, previousOperation);
            store.provisioningAudit.clear();
            store.provisioningAudit.addAll(previousAudit);
            store.pendingProvisioningCandidates.clear();
            store.pendingProvisioningCandidates.putAll(previousCandidates);
            throw error;
        }
    }

    private void beginRebindUnassign(NpcBindingStore.PendingRebind rebind) {
        NpcRebindPhase previousPhase = rebind.phase;
        List<NpcProvisioningAuditEntry> previousAudit = new ArrayList<>(store.provisioningAudit);
        Map<String, NpcBinding> previousCandidates = new java.util.LinkedHashMap<>(
                store.pendingProvisioningCandidates);
        rebind.phase = NpcRebindPhase.UNASSIGN;
        cancelPendingAssignmentIntents(rebind.previous.bindingId());
        try {
            persist();
        } catch (RuntimeException error) {
            rebind.phase = previousPhase;
            store.provisioningAudit.clear();
            store.provisioningAudit.addAll(previousAudit);
            store.pendingProvisioningCandidates.clear();
            store.pendingProvisioningCandidates.putAll(previousCandidates);
            throw error;
        }
    }

    private void cancelPendingAssignmentIntents(String bindingId) {
        for (int index = 0; index < store.provisioningAudit.size(); index++) {
            NpcProvisioningAuditEntry intent = store.provisioningAudit.get(index);
            if (!intent.bindingId().equals(bindingId)
                    || intent.action() != NpcProvisioningAuditEntry.Action.ASSIGN
                    || intent.outcome() != NpcProvisioningAuditEntry.Outcome.PENDING) {
                continue;
            }
            store.provisioningAudit.set(index, new NpcProvisioningAuditEntry(
                    intent.eventId(), intent.bindingId(), intent.action(), intent.providerId(),
                    intent.providerInstanceId(), intent.actorId(), intent.oldProfileId(), intent.newProfileId(),
                    NpcProvisioningAuditEntry.Outcome.REJECTED, "assignment-cancelled",
                    "Administrator cancelled the assignment before projection completed.",
                    intent.occurredAtEpochMillis()));
            store.pendingProvisioningCandidates.remove(intent.eventId());
        }
    }

    private void supersedePendingUnassignIntents(NpcProvisioningAuditEntry replacement) {
        for (int index = 0; index < store.provisioningAudit.size(); index++) {
            NpcProvisioningAuditEntry intent = store.provisioningAudit.get(index);
            if (!intent.bindingId().equals(replacement.bindingId())
                    || intent.action() != NpcProvisioningAuditEntry.Action.UNASSIGN
                    || intent.outcome() != NpcProvisioningAuditEntry.Outcome.PENDING) {
                continue;
            }
            store.provisioningAudit.set(index, new NpcProvisioningAuditEntry(
                    intent.eventId(), intent.bindingId(), intent.action(), intent.providerId(),
                    intent.providerInstanceId(), intent.actorId(), intent.oldProfileId(), intent.newProfileId(),
                    NpcProvisioningAuditEntry.Outcome.UNKNOWN, "unassign-retried",
                    "A newer durable unassign intent superseded this unresolved retry.",
                    intent.occurredAtEpochMillis()));
            store.pendingProvisioningCandidates.remove(intent.eventId());
        }
    }

    private void seedInterruptedProvisioningIntents() {
        List<NpcProvisioningAuditEntry> pendingIntents = store.provisioningAudit.stream()
                .filter(event -> event.outcome() == NpcProvisioningAuditEntry.Outcome.PENDING)
                .toList();
        java.util.Set<String> unassignIds = pendingIntents.stream()
                .filter(event -> event.action() == NpcProvisioningAuditEntry.Action.UNASSIGN)
                .map(NpcProvisioningAuditEntry::bindingId)
                .filter(id -> !id.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        // A later durable unassign intent supersedes an interrupted assignment
        // intent. Seed cancellations first so restart can never replay the
        // older bind after an administrator already chose to unassign it.
        for (NpcProvisioningAuditEntry event : pendingIntents) {
            if (event.action() != NpcProvisioningAuditEntry.Action.UNASSIGN
                    || event.bindingId().isBlank()) continue;
            NpcBindingStore.PendingRebind rebind = store.pendingRebinds.get(event.bindingId());
            if (rebind != null) {
                if (rebind.phase != NpcRebindPhase.UNASSIGN) beginRebindUnassign(rebind);
                continue;
            }
            NpcBinding binding = store.bindings.get(event.bindingId());
            if (binding == null) {
                NpcBindingStore.PendingOperation pending = store.pendingOperations.get(event.bindingId());
                if (pending != null) binding = pending.binding;
            }
            if (binding == null) {
                for (NpcProvisioningAuditEntry assignment : pendingIntents) {
                    if (assignment.action() == NpcProvisioningAuditEntry.Action.ASSIGN
                            && assignment.bindingId().equals(event.bindingId())) {
                        binding = store.pendingProvisioningCandidates.get(assignment.eventId());
                        if (binding != null) break;
                    }
                }
            }
            if (binding == null) binding = store.pendingProvisioningCandidates.get(event.eventId());
            if (binding != null
                    && binding.providerId().value().equals(event.providerId())
                    && binding.hostEntityUuid().equals(event.providerInstanceId())) {
                beginUnassign(binding);
            }
        }

        for (NpcProvisioningAuditEntry event : pendingIntents) {
            if (event.action() != NpcProvisioningAuditEntry.Action.ASSIGN
                    || event.bindingId().isBlank()
                    || unassignIds.contains(event.bindingId())
                    || store.pendingRebinds.containsKey(event.bindingId())
                    || store.pendingOperations.containsKey(event.bindingId())) continue;
            NpcBinding candidate = store.pendingProvisioningCandidates.get(event.eventId());
            if (candidate == null
                    || !candidate.bindingId().equals(event.bindingId())
                    || !candidate.providerId().value().equals(event.providerId())
                    || !candidate.hostEntityUuid().equals(event.providerInstanceId())) {
                continue;
            }
            NpcBinding binding = store.bindings.get(event.bindingId());
            if (binding == null) {
                store.pendingOperations.put(event.bindingId(), new NpcBindingStore.PendingOperation(
                        candidate, NpcProviderOperation.BIND, true));
                persist();
            } else if (!binding.equals(candidate)) {
                store.pendingRebinds.put(event.bindingId(), new NpcBindingStore.PendingRebind(
                        binding, candidate, NpcRebindPhase.UNBIND));
                persist();
            }
        }
    }

    private void resolveProvisioningAuditIntents(List<RecoveryItem> recoveryItems) {
        Map<String, NpcProviderResult> recoveredResults = new java.util.LinkedHashMap<>();
        for (RecoveryItem item : recoveryItems) recoveredResults.put(item.bindingId(), item.result());
        List<NpcProvisioningAuditEntry> previous = new ArrayList<>(store.provisioningAudit);
        Map<String, NpcBinding> previousCandidates = new java.util.LinkedHashMap<>(
                store.pendingProvisioningCandidates);
        boolean changed = false;
        for (int index = 0; index < store.provisioningAudit.size(); index++) {
            NpcProvisioningAuditEntry intent = store.provisioningAudit.get(index);
            if (intent.outcome() != NpcProvisioningAuditEntry.Outcome.PENDING
                    || intent.bindingId().isBlank()
                    || intent.action() == NpcProvisioningAuditEntry.Action.REPROJECT
                    || store.pendingOperations.containsKey(intent.bindingId())
                    || store.pendingRebinds.containsKey(intent.bindingId())) {
                continue;
            }
            NpcBinding current = store.bindings.get(intent.bindingId());
            NpcProviderResult providerResult = recoveredResults.get(intent.bindingId());
            boolean providerAccepted = providerResult != null
                    && (providerResult.status() == NpcProviderResult.Status.ACCEPTED
                            || providerResult.status() == NpcProviderResult.Status.RECONCILED);
            boolean providerRejected = providerResult != null
                    && providerResult.status() == NpcProviderResult.Status.REJECTED;
            boolean accepted;
            if (intent.action() == NpcProvisioningAuditEntry.Action.ASSIGN) {
                boolean matchesRequested = current != null
                        && current.providerId().value().equals(intent.providerId())
                        && current.hostEntityUuid().equals(intent.providerInstanceId())
                        && current.profileId().value().equals(intent.newProfileId());
                // A matching durable record alone does not prove that the
                // provider accepted the restored projection.
                if (!providerAccepted && !providerRejected) continue;
                accepted = providerAccepted && matchesRequested;
            } else if (intent.action() == NpcProvisioningAuditEntry.Action.UNASSIGN) {
                if (current != null && !providerAccepted && !providerRejected) continue;
                if (current == null && providerResult != null && !providerAccepted && !providerRejected) continue;
                accepted = current == null && (providerResult == null || providerAccepted);
            } else {
                continue;
            }
            NpcProvisioningAuditEntry completed = new NpcProvisioningAuditEntry(
                    intent.eventId(), intent.bindingId(), intent.action(), intent.providerId(),
                    intent.providerInstanceId(), intent.actorId(), intent.oldProfileId(), intent.newProfileId(),
                    accepted ? NpcProvisioningAuditEntry.Outcome.ACCEPTED
                            : NpcProvisioningAuditEntry.Outcome.REJECTED,
                    accepted ? "ok" : providerRejected
                            ? providerResult.code() : "recovered-operation-rejected",
                    accepted ? "" : providerRejected ? providerResult.message()
                            : "The requested assignment was not the durable final state after recovery.",
                    intent.occurredAtEpochMillis());
            store.provisioningAudit.set(index, completed);
            store.pendingProvisioningCandidates.remove(intent.eventId());
            changed = true;
        }
        if (!changed) return;
        try {
            persist();
        } catch (RuntimeException error) {
            store.provisioningAudit.clear();
            store.provisioningAudit.addAll(previous);
            store.pendingProvisioningCandidates.clear();
            store.pendingProvisioningCandidates.putAll(previousCandidates);
            throw error;
        }
    }

    private void persist() {
        repository.write(store);
    }

    private void canonicalizeStoredProfileIds() {
        store.bindings.replaceAll((ignored, binding) -> canonicalProfileIdentity(binding));
        for (NpcBindingStore.PendingOperation pending : store.pendingOperations.values()) {
            pending.binding = canonicalProfileIdentity(pending.binding);
        }
        for (NpcBindingStore.PendingRebind pending : store.pendingRebinds.values()) {
            pending.previous = canonicalProfileIdentity(pending.previous);
            pending.replacement = canonicalProfileIdentity(pending.replacement);
        }
        store.pendingProvisioningCandidates.replaceAll(
                (ignored, binding) -> canonicalProfileIdentity(binding));
    }

    private NpcBinding canonicalProfileIdentity(NpcBinding binding) {
        NpcContentProfile profile = findProfile(binding);
        if (profile == null) return binding;
        if (profile.profileId().equals(binding.profileId())
                && profile.contentId().equals(binding.contentProfileId())
                && profile.schemaVersion() == binding.schemaVersion()) {
            return binding;
        }
        return new NpcBinding(
                binding.bindingId(),
                binding.providerId(),
                binding.hostEntityUuid(),
                binding.externalNpcId(),
                binding.roleId(),
                binding.stationId(),
                profile.contentId(),
                profile.profileId(),
                profile.schemaVersion(),
                binding.assignedBy(),
                binding.assignedAtEpochMillis(),
                binding.hostLocation());
    }

    private NpcContentProfile findProfile(NpcBinding binding) {
        try {
            return content.require(binding.profileId());
        } catch (IllegalArgumentException unknownPublicProfile) {
            if (!binding.profileId().equals(NpcProfileId.fromContentId(binding.contentProfileId()))) {
                return null;
            }
            return content.snapshot().get(binding.contentProfileId());
        }
    }

    private static boolean requiresRecovery(NpcProviderResult result) {
        return result.status() == NpcProviderResult.Status.UNKNOWN
                || result.status() == NpcProviderResult.Status.UNAVAILABLE;
    }

    private static boolean requiresReconciliation(NpcProviderResult result) {
        return result.status() == NpcProviderResult.Status.UNKNOWN;
    }

    private static NpcProviderOperation phaseOperation(NpcRebindPhase phase) {
        return switch (phase) {
            case UNBIND, RESTORE, UNASSIGN -> NpcProviderOperation.UNBIND;
            case BIND -> NpcProviderOperation.BIND;
            case PUBLISH -> NpcProviderOperation.PUBLISH;
        };
    }

    private static NpcProviderOperation auditOperation(NpcProvisioningAuditEntry.Action action) {
        return switch (action) {
            case ASSIGN, MIGRATE -> NpcProviderOperation.BIND;
            case REPROJECT -> NpcProviderOperation.PUBLISH;
            case UNASSIGN -> NpcProviderOperation.UNBIND;
        };
    }

    private static NpcBindingStore normalize(NpcBindingStore value) {
        if (value.schemaVersion < 1 || value.schemaVersion > NpcBindingStore.CURRENT_SCHEMA_VERSION) {
            throw new IllegalStateException("unsupported NPC binding schema: " + value.schemaVersion);
        }
        if (value.bindings == null) value.bindings = new java.util.LinkedHashMap<>();
        if (value.pendingOperations == null) {
            value.pendingOperations = new java.util.LinkedHashMap<>();
        }
        if (value.pendingRebinds == null) value.pendingRebinds = new java.util.LinkedHashMap<>();
        if (value.assignmentRevisions == null) value.assignmentRevisions = new java.util.LinkedHashMap<>();
        if (value.provisioningAudit == null) value.provisioningAudit = new ArrayList<>();
        if (value.pendingProvisioningCandidates == null) {
            value.pendingProvisioningCandidates = new java.util.LinkedHashMap<>();
        }
        value.bindings.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        value.pendingOperations.entrySet().removeIf(
                entry -> entry.getKey() == null || entry.getValue() == null || entry.getValue().binding == null
                        || entry.getValue().operation == null);
        for (NpcBindingStore.PendingOperation pending : value.pendingOperations.values()) {
            // Before requiresReconciliation existed, persisted pending entries
            // always represented an uncertain provider outcome.
            if (pending.requiresReconciliation == null) pending.requiresReconciliation = true;
        }
        value.pendingRebinds.entrySet().removeIf(entry -> entry.getKey() == null
                || entry.getValue() == null || entry.getValue().previous == null
                || entry.getValue().replacement == null || entry.getValue().phase == null);
        value.pendingRebinds.entrySet().removeIf(entry ->
                !entry.getKey().equals(entry.getValue().previous.bindingId())
                        || !entry.getKey().equals(entry.getValue().replacement.bindingId()));
        value.pendingProvisioningCandidates.entrySet().removeIf(entry ->
                entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null);
        value.assignmentRevisions.entrySet().removeIf(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue() < 0L);
        value.provisioningAudit.removeIf(Objects::isNull);
        trimProvisioningAudit(value);
        value.schemaVersion = NpcBindingStore.CURRENT_SCHEMA_VERSION;
        return value;
    }

    private static void trimProvisioningAudit(NpcBindingStore value) {
        while (value.provisioningAudit.size() > MAX_PROVISIONING_AUDIT_EVENTS) {
            int oldestTerminal = -1;
            for (int index = 0; index < value.provisioningAudit.size(); index++) {
                if (value.provisioningAudit.get(index).outcome()
                        != NpcProvisioningAuditEntry.Outcome.PENDING) {
                    oldestTerminal = index;
                    break;
                }
            }
            // New pending intents are capped at ingestion. Preserve any
            // over-cap legacy recovery records rather than losing their
            // provider reconciliation input during load.
            if (oldestTerminal < 0) return;
            NpcProvisioningAuditEntry removed = value.provisioningAudit.remove(oldestTerminal);
            value.pendingProvisioningCandidates.remove(removed.eventId());
        }
    }

    public static final class ProvisioningAuditCapacityException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private ProvisioningAuditCapacityException() {
            super(PROVISIONING_BACKLOG_FULL_MESSAGE);
        }
    }

    public enum State { UNBOUND, BOUND, UNKNOWN }

    public record Inspection(
            String bindingId,
            State state,
            NpcBinding binding,
            NpcProviderOperation pendingOperation) {}

    public record RecoveryItem(String bindingId, NpcProviderResult result) {}

    public record RecoveryReport(List<RecoveryItem> items) {
        public RecoveryReport {
            items = List.copyOf(items);
        }

        public Optional<RecoveryItem> forBinding(String bindingId) {
            return items.stream().filter(item -> item.bindingId().equals(bindingId)).findFirst();
        }
    }
}
