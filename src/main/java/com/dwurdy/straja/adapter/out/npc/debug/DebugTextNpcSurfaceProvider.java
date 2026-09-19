package com.dwurdy.straja.adapter.out.npc.debug;

import com.dwurdy.straja.application.port.out.NpcSurfaceProvider;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Text-only diagnostic provider. It is a provider implementation for tests and
 * debugging, not the normal player-facing Straja surface.
 */
public final class DebugTextNpcSurfaceProvider implements NpcSurfaceProvider {
    private static final Set<NpcCapability> CAPABILITIES = Set.copyOf(EnumSet.of(
            NpcCapability.TEXT_MIRROR));

    private final Consumer<String> output;
    private final Map<String, NpcBinding> bindings = new ConcurrentHashMap<>();

    public DebugTextNpcSurfaceProvider(Consumer<String> output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public NpcProviderId providerId() {
        return NpcProviderId.DEBUG_TEXT;
    }

    @Override
    public Set<NpcCapability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public NpcProviderResult bind(NpcBinding binding) {
        Objects.requireNonNull(binding, "binding");
        if (!providerId().equals(binding.providerId())) {
            return NpcProviderResult.rejected("wrong-provider", "binding belongs to another provider");
        }
        if (bindings.putIfAbsent(binding.bindingId(), binding) != null) {
            return NpcProviderResult.rejected("already-bound", "binding is already owned");
        }
        return NpcProviderResult.accepted("debug binding created");
    }

    @Override
    public NpcProviderResult unbind(NpcBinding binding) {
        Objects.requireNonNull(binding, "binding");
        if (!bindings.remove(binding.bindingId(), binding)) {
            return NpcProviderResult.rejected("not-bound", "binding is not owned by debug provider");
        }
        return NpcProviderResult.accepted("debug binding removed");
    }

    @Override
    public NpcProviderResult publish(NpcSurfaceSnapshot surface) {
        Objects.requireNonNull(surface, "surface");
        NpcBinding binding = surface.binding();
        if (!binding.providerId().equals(providerId())) {
            return NpcProviderResult.rejected("wrong-provider", "surface belongs to another provider");
        }
        if (!capabilities().containsAll(surface.requiredCapabilities())) {
            return NpcProviderResult.rejected(
                    "unsupported-capability", "debug provider lacks a required surface capability");
        }
        if (!binding.equals(bindings.get(binding.bindingId()))) {
            return NpcProviderResult.rejected("not-bound", "surface binding is not registered");
        }
        output.accept(format(surface));
        return NpcProviderResult.accepted("debug surface published");
    }

    @Override
    public NpcProviderResult reconcile(NpcBinding binding) {
        Objects.requireNonNull(binding, "binding");
        NpcBinding current = bindings.get(binding.bindingId());
        if (current == null) {
            return NpcProviderResult.rejected("not-bound", "debug provider does not own the binding");
        }
        if (!current.equals(binding)) {
            return NpcProviderResult.rejected("wrong-binding", "debug provider mapping differs");
        }
        return NpcProviderResult.accepted("debug provider confirms binding ownership");
    }

    private static String format(NpcSurfaceSnapshot surface) {
        StringBuilder text = new StringBuilder()
                .append("[NPC DEBUG] ")
                .append(surface.binding().bindingId())
                .append(" profile=")
                .append(surface.profileId().value())
                .append(" — ")
                .append(surface.title())
                .append(System.lineSeparator())
                .append(surface.body());
        for (NpcSurfaceAction action : surface.actions()) {
            text.append(System.lineSeparator())
                    .append("[action ")
                    .append(action.actionId().value())
                    .append("] ")
                    .append(action.label());
            if (!action.enabled()) {
                text.append(" — ").append(action.disabledReason());
            }
        }
        for (NpcSurfaceSnapshot.QuestEntry quest : surface.quests()) {
            text.append(System.lineSeparator())
                    .append("[quest ")
                    .append(quest.questId().value())
                    .append("] ")
                    .append(quest.title())
                    .append(" — ")
                    .append(quest.state());
        }
        return text.toString();
    }
}
