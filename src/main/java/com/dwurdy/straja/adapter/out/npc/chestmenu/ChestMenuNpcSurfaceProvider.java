package com.dwurdy.straja.adapter.out.npc.chestmenu;

import com.dwurdy.straja.application.port.in.NpcSurfaceActionTokenIssuer;
import com.dwurdy.straja.application.port.in.NpcSurfaceActionUseCase;
import com.dwurdy.straja.application.port.out.DeskSurfaceBridge;
import com.dwurdy.straja.application.port.out.NpcSurfaceProvider;
import com.dwurdy.straja.domain.model.NpcActionRequest;
import com.dwurdy.straja.domain.model.NpcActionResult;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Vanilla chest-menu provider for canonically bound NPC hosts.
 *
 * <p>Renders only {@link NpcSurfaceSnapshot#actions()}: enabled rows become
 * desk buttons paged at 45, disabled rows are omitted, and dialogue or
 * quest entries simply never render — the binding still owns them, the
 * provider that owns dialogue (CustomNPCs) keeps it. Each click re-resolves
 * the published snapshot, mints a player-bound token through the
 * five-minute-capable {@link NpcSurfaceActionTokenIssuer} exact-surface
 * path, and submits through {@code NpcSurfaceActionService} — never the
 * 15-second chat tokens. An action carrying input fields dispatches
 * through {@code NpcRoles.performAction} instead, which opens the native
 * Straja form screen: a chest grid cannot collect fields.</p>
 *
 * <p>Like every provider adapter this owns no Straja progression,
 * permissions, rewards, or gameplay state — the action service keeps
 * ownership, freshness, proximity, and replay authority. Host entities
 * stay ordinary vanilla/registry entities (villagers included): the
 * provider claims the right-click, never the entity.</p>
 */
public final class ChestMenuNpcSurfaceProvider implements NpcSurfaceProvider, DeskSurfaceBridge {
    private static final Set<NpcCapability> CAPABILITIES = Set.copyOf(EnumSet.of(
            NpcCapability.GUI,
            NpcCapability.ACTION_INPUT));

    private final NpcSurfaceActionUseCase actions;
    private final NpcSurfaceActionTokenIssuer tokenIssuer;
    private final SurfaceResolver surfaceResolver;
    private final Consumer<String> diagnostics;
    private final BooleanSupplier enabled;
    private final Map<String, NpcBinding> bindings = new ConcurrentHashMap<>();
    private final Map<String, NpcSurfaceSnapshot> surfaces = new ConcurrentHashMap<>();

    public ChestMenuNpcSurfaceProvider(
            NpcSurfaceActionUseCase actions,
            NpcSurfaceActionTokenIssuer tokenIssuer,
            SurfaceResolver surfaceResolver,
            Consumer<String> diagnostics,
            BooleanSupplier enabled) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.tokenIssuer = Objects.requireNonNull(tokenIssuer, "tokenIssuer");
        this.surfaceResolver = Objects.requireNonNull(surfaceResolver, "surfaceResolver");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    @Override
    public NpcProviderId providerId() {
        return NpcProviderId.DESK_MENU;
    }

    @Override
    public Set<NpcCapability> capabilities() {
        return CAPABILITIES;
    }

    /**
     * The desk renders every canonical surface's ACTION rows; it does not
     * claim to implement dialogue, quests, or rich text — those rows are
     * simply never shown, exactly like the debug mirror degrades them.
     */
    @Override
    public boolean supports(Set<NpcCapability> requiredCapabilities) {
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
        return true;
    }

    /** True while desk menus and the bound-provider flag are both enabled. */
    @Override
    public boolean available() {
        try {
            return enabled.getAsBoolean();
        } catch (RuntimeException configNotLoaded) {
            return false;
        }
    }

    @Override
    public NpcProviderResult bind(NpcBinding binding) {
        Objects.requireNonNull(binding, "binding");
        if (!providerId().equals(binding.providerId())) {
            return NpcProviderResult.rejected("wrong-provider", "binding belongs to another provider");
        }
        if (!available()) {
            return NpcProviderResult.unavailable("desk-menu provider is disabled");
        }
        if (!isUuid(binding.hostEntityUuid())) {
            return NpcProviderResult.rejected("invalid-host-identity", "hostEntityUuid must be a UUID");
        }
        for (NpcBinding current : bindings.values()) {
            if (current.hostEntityUuid().equals(binding.hostEntityUuid())
                    && !current.bindingId().equals(binding.bindingId())) {
                return NpcProviderResult.rejected(
                        "host-already-bound", "one host entity cannot own two logical bindings");
            }
        }
        NpcBinding existing = bindings.putIfAbsent(binding.bindingId(), binding);
        if (existing != null) {
            return existing.equals(binding)
                    ? NpcProviderResult.accepted("desk-menu binding already owned")
                    : NpcProviderResult.rejected("already-bound", "binding mapping differs");
        }
        return NpcProviderResult.accepted("desk-menu binding created");
    }

    @Override
    public NpcProviderResult unbind(NpcBinding binding) {
        Objects.requireNonNull(binding, "binding");
        if (!bindings.remove(binding.bindingId(), binding)) {
            return NpcProviderResult.rejected("not-bound", "desk-menu binding is not owned");
        }
        surfaces.remove(binding.bindingId());
        return NpcProviderResult.accepted("desk-menu binding removed");
    }

    @Override
    public NpcProviderResult publish(NpcSurfaceSnapshot surface) {
        Objects.requireNonNull(surface, "surface");
        NpcBinding binding = surface.binding();
        if (!providerId().equals(binding.providerId())) {
            return NpcProviderResult.rejected("wrong-provider", "surface belongs to another provider");
        }
        if (!available()) {
            return NpcProviderResult.unavailable("desk-menu provider is disabled");
        }
        if (!supports(surface.requiredCapabilities())) {
            return NpcProviderResult.rejected(
                    "unsupported-capability", "desk-menu provider lacks a required surface capability");
        }
        if (!binding.equals(bindings.get(binding.bindingId()))) {
            return NpcProviderResult.rejected("not-bound", "surface binding is not registered");
        }
        surfaces.put(binding.bindingId(), surface);
        return NpcProviderResult.accepted("desk-menu surface published");
    }

    @Override
    public NpcProviderResult reconcile(NpcBinding binding) {
        Objects.requireNonNull(binding, "binding");
        NpcBinding current = bindings.get(binding.bindingId());
        if (current == null) {
            return NpcProviderResult.rejected("not-bound", "desk-menu does not own the binding");
        }
        if (!current.equals(binding)) {
            return NpcProviderResult.rejected("wrong-binding", "desk-menu binding mapping differs");
        }
        return NpcProviderResult.accepted("desk-menu provider confirms in-process ownership");
    }

    /**
     * Fail-closed gate the Minecraft-facing caller runs before opening the
     * chest grid: the binding must be live-owned by this provider and still
     * carry a published Straja surface, so a stale host click never falls
     * through into the host entity's own interaction. Kept Minecraft-free so
     * the whole provider contract loads on the headless test classpath; the
     * actual menu open lives in the inbound menu adapter.
     */
    public boolean readyFor(NpcBinding binding) {
        Objects.requireNonNull(binding, "binding");
        return available()
                && providerId().equals(binding.providerId())
                && binding.equals(bindings.get(binding.bindingId()))
                && surfaces.get(binding.bindingId()) != null;
    }

    /** The binding currently mapped to this host entity, or null. */
    public NpcBinding bindingForHost(String hostEntityUuid) {
        if (hostEntityUuid == null) return null;
        return bindings.values().stream()
                .filter(binding -> binding.hostEntityUuid().equals(hostEntityUuid))
                .findFirst().orElse(null);
    }

    /** True when a live binding maps this host entity to this provider. */
    public boolean ownsHost(String hostEntityUuid) {
        return bindingForHost(hostEntityUuid) != null;
    }

    @Override
    public NpcSurfaceSnapshot resolve(UUID playerId, NpcBinding binding) {
        NpcSurfaceSnapshot published = surfaces.get(binding.bindingId());
        if (published == null) return null;
        try {
            return surfaceResolver.resolve(playerId, binding, published);
        } catch (RuntimeException resolveFailed) {
            diagnostics.accept("desk-menu surface resolve failed: "
                    + resolveFailed.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Mints a token against the surface actually rendered — the resolver's
     * player projection — then submits. Token issuance owns the enabled and
     * ownership checks; the service owns replay, expiry, and proximity.
     */
    @Override
    public NpcActionResult submit(UUID playerId, NpcBinding binding, String actionId) {
        try {
            NpcContentId id = NpcContentId.of(actionId);
            NpcSurfaceSnapshot resolved = resolve(playerId, binding);
            if (resolved == null) {
                return new NpcActionResult(NpcActionResult.Status.UNAVAILABLE,
                        "surface-unavailable", "NPC surface is no longer available");
            }
            String token = tokenIssuer.issueToken(playerId, binding, id, resolved);
            return actions.submit(new NpcActionRequest(
                    providerId(), binding.bindingId(), playerId, id, token, Map.of()));
        } catch (RuntimeException submitFailed) {
            diagnostics.accept("desk-menu action submit failed: "
                    + submitFailed.getClass().getSimpleName());
            return new NpcActionResult(NpcActionResult.Status.UNAVAILABLE,
                    "bridge-failed", "This Straja action is temporarily unavailable.");
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /** Player projection of a published surface — same seam CustomNPCs uses. */
    @FunctionalInterface
    public interface SurfaceResolver {
        NpcSurfaceSnapshot resolve(
                UUID playerId, NpcBinding binding, NpcSurfaceSnapshot published);
    }
}
