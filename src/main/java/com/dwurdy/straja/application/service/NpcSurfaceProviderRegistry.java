package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.NpcSurfaceProvider;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderOperation;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Registry for explicitly configured NPC presentation providers. */
public final class NpcSurfaceProviderRegistry {
    private final Map<NpcProviderId, NpcSurfaceProvider> providers = new LinkedHashMap<>();
    private final Map<String, NpcBinding> bindings = new LinkedHashMap<>();
    private final Map<String, NpcSurfaceSnapshot> surfaces = new LinkedHashMap<>();
    private final Map<String, UnknownBinding> unknownBindings = new LinkedHashMap<>();

    public synchronized void register(NpcSurfaceProvider provider) {
        Objects.requireNonNull(provider, "provider");
        NpcProviderId providerId = Objects.requireNonNull(provider.providerId(), "provider.providerId()");
        Set<NpcCapability> capabilities = Set.copyOf(
                Objects.requireNonNull(provider.capabilities(), "provider.capabilities()"));
        if (!providerId.equals(provider.providerId())) {
            throw new IllegalArgumentException("provider id changed during registration");
        }
        if (providers.putIfAbsent(providerId, provider) != null) {
            throw new IllegalArgumentException("provider already registered: " + providerId.value());
        }
    }

    public synchronized Optional<NpcSurfaceProvider> find(NpcProviderId providerId) {
        return Optional.ofNullable(providers.get(Objects.requireNonNull(providerId, "providerId")));
    }

    /**
     * Atomically claims a logical binding for one provider. Provider adapters
     * must be reached through this method so two providers cannot claim the
     * same logical NPC concurrently.
     */
    public synchronized NpcProviderResult bind(NpcBinding binding) {
        Objects.requireNonNull(binding, "binding");
        if (unknownBindings.containsKey(binding.bindingId())) {
            return NpcProviderResult.unknown("binding requires provider-state reconciliation");
        }
        NpcBinding current = bindings.get(binding.bindingId());
        if (current != null) {
            return current.equals(binding)
                    ? NpcProviderResult.accepted("binding already owned")
                    : NpcProviderResult.rejected("binding-owned", "logical binding is owned by another mapping");
        }
        NpcSurfaceProvider provider = find(binding.providerId()).orElse(null);
        if (provider == null) {
            return NpcProviderResult.unavailable("configured NPC provider is unavailable");
        }
        NpcProviderResult result = safely(() -> provider.bind(binding));
        if (result.status() == NpcProviderResult.Status.ACCEPTED) {
            bindings.put(binding.bindingId(), binding);
        } else if (result.status() == NpcProviderResult.Status.UNKNOWN) {
            unknownBindings.put(binding.bindingId(),
                    new UnknownBinding(binding, NpcProviderOperation.BIND, null));
        }
        return result;
    }

    public synchronized NpcProviderResult unbind(NpcBinding binding) {
        Objects.requireNonNull(binding, "binding");
        if (unknownBindings.containsKey(binding.bindingId())) {
            return NpcProviderResult.unknown("binding requires provider-state reconciliation");
        }
        NpcBinding current = bindings.get(binding.bindingId());
        if (current == null) {
            return NpcProviderResult.rejected("not-bound", "logical binding is not owned");
        }
        if (!current.equals(binding)) {
            return NpcProviderResult.rejected("wrong-binding", "binding mapping does not match the owner");
        }
        NpcSurfaceProvider provider = find(binding.providerId()).orElse(null);
        if (provider == null) {
            return NpcProviderResult.unavailable("bound NPC provider is unavailable");
        }
        NpcProviderResult result = safely(() -> provider.unbind(binding));
        if (result.status() == NpcProviderResult.Status.ACCEPTED) {
            bindings.remove(binding.bindingId());
            surfaces.remove(binding.bindingId());
        } else if (result.status() == NpcProviderResult.Status.UNKNOWN) {
            unknownBindings.put(binding.bindingId(),
                    new UnknownBinding(binding, NpcProviderOperation.UNBIND, null));
        }
        return result;
    }

    public synchronized NpcProviderResult publish(NpcSurfaceSnapshot surface) {
        Objects.requireNonNull(surface, "surface");
        if (unknownBindings.containsKey(surface.binding().bindingId())) {
            return NpcProviderResult.unknown("binding requires provider-state reconciliation");
        }
        NpcBinding current = bindings.get(surface.binding().bindingId());
        if (current == null) {
            return NpcProviderResult.rejected("not-bound", "surface binding is not owned");
        }
        if (!current.equals(surface.binding())) {
            return NpcProviderResult.rejected("wrong-binding", "surface mapping does not match the owner");
        }
        NpcSurfaceProvider provider = find(current.providerId()).orElse(null);
        if (provider == null) {
            return NpcProviderResult.unavailable("bound NPC provider is unavailable");
        }
        if (!provider.capabilities().containsAll(surface.requiredCapabilities())) {
            return NpcProviderResult.rejected(
                    "unsupported-capability", "provider lacks a required surface capability");
        }
        NpcProviderResult result = safely(() -> provider.publish(surface));
        if (result.status() == NpcProviderResult.Status.ACCEPTED) {
            surfaces.put(surface.binding().bindingId(), surface);
        } else if (result.status() == NpcProviderResult.Status.UNKNOWN) {
            unknownBindings.put(surface.binding().bindingId(),
                    new UnknownBinding(surface.binding(), NpcProviderOperation.PUBLISH, surface));
        }
        return result;
    }

    public synchronized Optional<NpcBinding> binding(String bindingId) {
        if (unknownBindings.containsKey(Objects.requireNonNull(bindingId, "bindingId"))) {
            return Optional.empty();
        }
        return Optional.ofNullable(bindings.get(Objects.requireNonNull(bindingId, "bindingId")));
    }

    public synchronized Optional<NpcSurfaceSnapshot> surface(String bindingId) {
        if (unknownBindings.containsKey(Objects.requireNonNull(bindingId, "bindingId"))) {
            return Optional.empty();
        }
        return Optional.ofNullable(surfaces.get(Objects.requireNonNull(bindingId, "bindingId")));
    }

    public synchronized Optional<NpcBinding> unknownBinding(String bindingId) {
        return Optional.ofNullable(unknownBindings.get(Objects.requireNonNull(bindingId, "bindingId")))
                .map(UnknownBinding::binding);
    }

    public synchronized Optional<NpcProviderOperation> unknownOperation(String bindingId) {
        return Optional.ofNullable(unknownBindings.get(Objects.requireNonNull(bindingId, "bindingId")))
                .map(UnknownBinding::operation);
    }

    /**
     * Resolves a provider operation that may have mutated external state before
     * failing. Until this succeeds, the logical binding fails closed.
     */
    public synchronized NpcProviderResult reconcile(String bindingId) {
        UnknownBinding unknown = unknownBindings.get(Objects.requireNonNull(bindingId, "bindingId"));
        if (unknown == null) {
            return NpcProviderResult.rejected("not-unknown", "binding has no pending reconciliation");
        }
        NpcSurfaceProvider provider = find(unknown.binding().providerId()).orElse(null);
        if (provider == null) {
            return NpcProviderResult.unknown("bound NPC provider is unavailable");
        }
        NpcProviderResult result = safely(() -> provider.reconcile(unknown.binding()));
        if (result.status() == NpcProviderResult.Status.ACCEPTED) {
            bindings.put(unknown.binding().bindingId(), unknown.binding());
            // A publish, bind, or unbind exception means the cached surface
            // cannot be trusted. The caller must publish a fresh projection.
            surfaces.remove(unknown.binding().bindingId());
            unknownBindings.remove(unknown.binding().bindingId());
            return NpcProviderResult.reconciled(
                    "provider-owned after reconciling " + unknown.operation().name().toLowerCase());
        }
        if (result.status() == NpcProviderResult.Status.REJECTED) {
            bindings.remove(unknown.binding().bindingId());
            surfaces.remove(unknown.binding().bindingId());
            unknownBindings.remove(unknown.binding().bindingId());
            return NpcProviderResult.reconciled(
                    "provider-unowned after reconciling " + unknown.operation().name().toLowerCase());
        }
        return result;
    }

    public synchronized boolean owns(String bindingId, NpcProviderId providerId) {
        return binding(bindingId).map(binding -> binding.providerId().equals(providerId)).orElse(false);
    }

    public synchronized NpcSurfaceProvider require(
            NpcProviderId providerId, Set<NpcCapability> requiredCapabilities) {
        NpcSurfaceProvider provider = find(providerId)
                .orElseThrow(() -> new IllegalStateException(
                        "NPC provider is unavailable: " + providerId.value()));
        Set<NpcCapability> required = Set.copyOf(
                Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        if (!provider.capabilities().containsAll(required)) {
            throw new IllegalStateException(
                    "NPC provider lacks required capabilities: " + providerId.value());
        }
        return provider;
    }

    public synchronized Map<NpcProviderId, NpcSurfaceProvider> snapshot() {
        return Map.copyOf(providers);
    }

    private static NpcProviderResult safely(java.util.function.Supplier<NpcProviderResult> operation) {
        try {
            return Objects.requireNonNull(operation.get(), "provider returned null result");
        } catch (RuntimeException exception) {
            return NpcProviderResult.unknown("NPC provider operation outcome is unknown");
        }
    }

    private record UnknownBinding(
            NpcBinding binding,
            NpcProviderOperation operation,
            NpcSurfaceSnapshot attemptedSurface) {}
}
