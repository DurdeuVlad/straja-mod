package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.NpcBindingRepository;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcBindingStore;
import com.dwurdy.straja.domain.model.NpcProviderOperation;
import com.dwurdy.straja.domain.model.NpcProviderResult;
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
        repository.write(store);
    }

    public synchronized NpcProviderResult bind(NpcBinding binding) {
        Objects.requireNonNull(binding, "binding");
        NpcBinding current = store.bindings.get(binding.bindingId());
        if (current != null) {
            if (!current.equals(binding)) {
                return NpcProviderResult.rejected(
                        "binding-owned", "logical binding exists; use explicit rebind");
            }
            NpcProviderResult existing = providers.bind(binding);
            if (requiresRecovery(existing)) {
                markPending(binding, NpcProviderOperation.BIND);
            }
            return existing;
        }
        NpcProviderResult result = providers.bind(binding);
        if (result.status() == NpcProviderResult.Status.ACCEPTED) {
            store.bindings.put(binding.bindingId(), binding);
            store.pendingOperations.remove(binding.bindingId());
            persist();
        } else if (requiresRecovery(result)) {
            markPending(binding, NpcProviderOperation.BIND);
        }
        return result;
    }

    public synchronized NpcProviderResult bindAndPublish(NpcBinding binding) {
        NpcProviderResult bound = bind(binding);
        if (bound.status() != NpcProviderResult.Status.ACCEPTED) return bound;
        NpcSurfaceSnapshot surface;
        try {
            surface = content.require(binding.surfaceProfileId()).bind(binding);
        } catch (RuntimeException error) {
            return NpcProviderResult.rejected("invalid-profile", error.getMessage());
        }
        NpcProviderResult published = providers.publish(surface);
        if (requiresRecovery(published)) {
            markPending(binding, NpcProviderOperation.PUBLISH);
        }
        return published;
    }

    public synchronized NpcProviderResult rebind(NpcBinding replacement) {
        Objects.requireNonNull(replacement, "replacement");
        NpcBinding current = store.bindings.get(replacement.bindingId());
        if (current == null) return bindAndPublish(replacement);
        if (current.equals(replacement)) return bindAndPublish(replacement);
        NpcProviderResult removed = unbind(current.bindingId());
        if (removed.status() != NpcProviderResult.Status.ACCEPTED) return removed;
        return bindAndPublish(replacement);
    }

    public synchronized NpcProviderResult unbind(String bindingId) {
        NpcBinding current = store.bindings.get(Objects.requireNonNull(bindingId, "bindingId"));
        if (current == null) {
            return NpcProviderResult.rejected("not-bound", "logical binding is not registered");
        }
        NpcProviderResult result = providers.unbind(current);
        if (result.status() == NpcProviderResult.Status.ACCEPTED) {
            store.bindings.remove(bindingId);
            store.pendingOperations.remove(bindingId);
            persist();
        } else if (requiresRecovery(result)) {
            markPending(current, NpcProviderOperation.UNBIND);
        }
        return result;
    }

    public synchronized Inspection inspect(String bindingId) {
        String id = Objects.requireNonNull(bindingId, "bindingId");
        NpcBinding binding = store.bindings.get(id);
        NpcBindingStore.PendingOperation pending = store.pendingOperations.get(id);
        if (binding == null && pending == null) return new Inspection(id, State.UNBOUND, null, null);
        if (pending != null) return new Inspection(id, State.UNKNOWN, binding, pending.operation);
        return new Inspection(id, State.BOUND, binding, null);
    }

    /** Replays durable bindings after server/provider startup. */
    public synchronized RecoveryReport recover() {
        List<RecoveryItem> items = new ArrayList<>();
        for (NpcBinding binding : List.copyOf(store.bindings.values())) {
            NpcBindingStore.PendingOperation pending = store.pendingOperations.get(binding.bindingId());
            if (pending != null && pending.operation == NpcProviderOperation.UNBIND) {
                if (providers.unknownOperation(binding.bindingId()).isPresent()) {
                    NpcProviderResult reconciled = providers.reconcile(binding.bindingId());
                    if (reconciled.status() == NpcProviderResult.Status.UNKNOWN) {
                        items.add(new RecoveryItem(binding.bindingId(), reconciled));
                        continue;
                    }
                }
                NpcProviderResult removed = providers.unbind(binding);
                if (removed.status() == NpcProviderResult.Status.ACCEPTED) {
                    store.bindings.remove(binding.bindingId());
                    store.pendingOperations.remove(binding.bindingId());
                    items.add(new RecoveryItem(binding.bindingId(), removed));
                } else {
                    items.add(new RecoveryItem(binding.bindingId(), removed));
                }
                continue;
            }
            NpcProviderResult bound;
            if (providers.unknownOperation(binding.bindingId()).isPresent()) {
                NpcProviderResult reconciled = providers.reconcile(binding.bindingId());
                if (reconciled.status() == NpcProviderResult.Status.UNKNOWN) {
                    items.add(new RecoveryItem(binding.bindingId(), reconciled));
                    continue;
                }
                bound = providers.binding(binding.bindingId()).isPresent()
                        ? NpcProviderResult.accepted("binding already reconciled")
                        : providers.bind(binding);
            } else {
                bound = providers.bind(binding);
            }
            if (bound.status() != NpcProviderResult.Status.ACCEPTED) {
                markPending(binding, NpcProviderOperation.BIND);
                items.add(new RecoveryItem(binding.bindingId(), bound));
                continue;
            }
            NpcProviderResult published = providers.publish(content.require(
                    binding.surfaceProfileId()).bind(binding));
            if (published.status() == NpcProviderResult.Status.ACCEPTED) {
                store.pendingOperations.remove(binding.bindingId());
                persist();
            } else {
                markPending(binding, NpcProviderOperation.PUBLISH);
            }
            items.add(new RecoveryItem(binding.bindingId(), published));
        }
        return new RecoveryReport(List.copyOf(items));
    }

    public synchronized Map<String, NpcBinding> bindings() {
        return Map.copyOf(store.bindings);
    }

    private void markPending(NpcBinding binding, NpcProviderOperation operation) {
        store.pendingOperations.put(
                binding.bindingId(), new NpcBindingStore.PendingOperation(binding, operation));
        persist();
    }

    private void persist() {
        repository.write(store);
    }

    private static boolean requiresRecovery(NpcProviderResult result) {
        return result.status() == NpcProviderResult.Status.UNKNOWN
                || result.status() == NpcProviderResult.Status.UNAVAILABLE;
    }

    private static NpcBindingStore normalize(NpcBindingStore value) {
        if (value.schemaVersion < 1 || value.schemaVersion > NpcBindingStore.CURRENT_SCHEMA_VERSION) {
            throw new IllegalStateException("unsupported NPC binding schema: " + value.schemaVersion);
        }
        if (value.bindings == null) value.bindings = new java.util.LinkedHashMap<>();
        if (value.pendingOperations == null) {
            value.pendingOperations = new java.util.LinkedHashMap<>();
        }
        value.bindings.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        value.pendingOperations.entrySet().removeIf(
                entry -> entry.getKey() == null || entry.getValue() == null || entry.getValue().binding == null
                        || entry.getValue().operation == null);
        return value;
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
