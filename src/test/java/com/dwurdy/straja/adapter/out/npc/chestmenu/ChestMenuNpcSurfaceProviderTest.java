package com.dwurdy.straja.adapter.out.npc.chestmenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.application.service.NpcSurfaceActionService;
import com.dwurdy.straja.application.service.NpcSurfaceProviderRegistry;
import com.dwurdy.straja.domain.model.NpcActionRequest;
import com.dwurdy.straja.domain.model.NpcActionResult;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The desk-menu provider passes the same bind/publish/unbind/reconcile
 * contract as every {@code NpcSurfaceProvider}, mints real action tokens
 * through {@code NpcSurfaceActionService} at submit time, and fails closed
 * whenever it is disabled or the surface is gone. The headless suite stops
 * at {@code openFor}/{@code openBoundSurface} — those need a live
 * {@code ServerPlayer}; the pure contract is exercised end to end here.
 */
class ChestMenuNpcSurfaceProviderTest {
    private static final NpcContentId PROFILE = NpcContentId.of("straja.reception.admission");

    /** Registry + action service + provider wired exactly like the bootstrap. */
    private record Wired(
            NpcSurfaceProviderRegistry registry,
            NpcSurfaceActionService service,
            ChestMenuNpcSurfaceProvider provider) {}

    private static Wired wired(
            AtomicBoolean enabled,
            ChestMenuNpcSurfaceProvider.SurfaceResolver resolver,
            AtomicReference<NpcActionRequest> seen) {
        var registry = new NpcSurfaceProviderRegistry();
        var service = new NpcSurfaceActionService(
                registry,
                (candidate, binding) -> true,
                request -> {
                    if (seen != null) seen.set(request);
                    return new NpcActionResult(
                            NpcActionResult.Status.ACCEPTED, "dispatched", "action dispatched");
                });
        var provider = new ChestMenuNpcSurfaceProvider(
                service, service, resolver, message -> {}, enabled::get);
        registry.register(provider);
        return new Wired(registry, service, provider);
    }

    private static Wired wired(AtomicBoolean enabled) {
        return wired(enabled, (playerId, binding, published) -> published, null);
    }

    private static NpcBinding binding(NpcProviderId providerId) {
        return new NpcBinding("straja.bound.desk", providerId,
                UUID.randomUUID().toString(), "", "receptionist", "hq", PROFILE, 1);
    }

    private static NpcSurfaceSnapshot surface(NpcBinding binding) {
        return new NpcSurfaceSnapshot(
                binding,
                PROFILE,
                "Straja reception",
                "Admission desk.",
                List.of(
                        NpcSurfaceAction.enabled(
                                NpcContentId.of("application-submit"), "Submit application"),
                        NpcSurfaceAction.disabled(
                                NpcContentId.of("complaint-submit"), "File complaint", "closed")),
                List.of(new NpcSurfaceSnapshot.DialogueNode(
                        NpcContentId.of("straja.reception.introduction"),
                        "State your purpose.", List.of())),
                List.of(),
                Set.of(NpcCapability.DIALOGUE, NpcCapability.GUI,
                        NpcCapability.ACTION_INPUT, NpcCapability.QUEST_JOURNAL),
                Set.of());
    }

    @Test
    void providerIdIsDeskMenuAndCapabilitiesStayHonest() {
        var provider = wired(new AtomicBoolean(true)).provider();
        assertEquals(NpcProviderId.DESK_MENU, provider.providerId());
        assertTrue(provider.capabilities().containsAll(
                Set.of(NpcCapability.GUI, NpcCapability.ACTION_INPUT)));
        assertFalse(provider.capabilities().contains(NpcCapability.DIALOGUE),
                "dialogue stays the owning provider's job");
        assertFalse(provider.capabilities().contains(NpcCapability.QUEST_JOURNAL));
    }

    @Test
    void supportsEveryAuthoredSurfaceLikeTheDebugMirror() {
        var provider = wired(new AtomicBoolean(true)).provider();
        assertTrue(provider.supports(Set.of(NpcCapability.DIALOGUE, NpcCapability.GUI,
                        NpcCapability.ACTION_INPUT, NpcCapability.QUEST_JOURNAL)),
                "unrenderable rows degrade out instead of rejecting the surface");
    }

    @Test
    void availabilityFollowsTheEnabledFlag() {
        var enabled = new AtomicBoolean(false);
        var provider = wired(enabled).provider();
        assertFalse(provider.available());
        enabled.set(true);
        assertTrue(provider.available());
    }

    @Test
    void bindFailsClosedOnWrongProviderInvalidHostAndDuplicateHost() {
        var provider = wired(new AtomicBoolean(true)).provider();
        assertEquals(NpcProviderResult.Status.REJECTED,
                provider.bind(binding(NpcProviderId.CUSTOM_NPCS)).status());
        var badHost = new NpcBinding("straja.bound.desk", NpcProviderId.DESK_MENU,
                "not-a-uuid", "", "receptionist", "hq", PROFILE, 1);
        assertEquals(NpcProviderResult.Status.REJECTED, provider.bind(badHost).status());
        var binding = binding(NpcProviderId.DESK_MENU);
        assertEquals(NpcProviderResult.Status.ACCEPTED, provider.bind(binding).status());
        var sameHost = new NpcBinding("straja.bound.other", NpcProviderId.DESK_MENU,
                binding.hostEntityUuid(), "", "receptionist", "hq", PROFILE, 1);
        assertEquals(NpcProviderResult.Status.REJECTED, provider.bind(sameHost).status());
        assertTrue(provider.ownsHost(binding.hostEntityUuid()));
        assertSame(binding, provider.bindingForHost(binding.hostEntityUuid()));
    }

    @Test
    void bindAndPublishFailClosedWhileDisabled() {
        var provider = wired(new AtomicBoolean(false)).provider();
        var binding = binding(NpcProviderId.DESK_MENU);
        assertEquals(NpcProviderResult.Status.UNAVAILABLE,
                provider.bind(binding).status());
        assertEquals(NpcProviderResult.Status.UNAVAILABLE,
                provider.publish(surface(binding)).status(),
                "a disabled provider publishes nothing");
    }

    @Test
    void publishRequiresTheExactOwnedMappingAndDropsItOnUnbind() {
        var wired = wired(new AtomicBoolean(true));
        var binding = binding(NpcProviderId.DESK_MENU);
        // Publishing before bind fails through the real registry flow too.
        assertEquals(NpcProviderResult.Status.REJECTED,
                wired.registry().publish(surface(binding)).status());
        assertEquals(NpcProviderResult.Status.ACCEPTED, wired.registry().bind(binding).status());
        assertEquals(NpcProviderResult.Status.ACCEPTED,
                wired.registry().publish(surface(binding)).status());
        assertEquals(NpcProviderResult.Status.ACCEPTED,
                wired.provider().unbind(binding).status());
        assertFalse(wired.provider().ownsHost(binding.hostEntityUuid()));
        assertNull(wired.provider().bindingForHost(binding.hostEntityUuid()));
        assertEquals(NpcProviderResult.Status.REJECTED,
                wired.provider().unbind(binding).status(), "double unbind rejects");
    }

    @Test
    void reconcileConfirmsOwnedMappingsAndRejectsTheRest() {
        var provider = wired(new AtomicBoolean(true)).provider();
        var binding = binding(NpcProviderId.DESK_MENU);
        assertEquals(NpcProviderResult.Status.REJECTED, provider.reconcile(binding).status());
        provider.bind(binding);
        assertEquals(NpcProviderResult.Status.ACCEPTED, provider.reconcile(binding).status());
        var drifted = new NpcBinding(binding.bindingId(), binding.providerId(),
                UUID.randomUUID().toString(), "", "receptionist", "hq", PROFILE, 1);
        assertEquals(NpcProviderResult.Status.REJECTED, provider.reconcile(drifted).status());
    }

    @Test
    void resolveProjectsThroughTheResolverAndDiesWithTheSurface() {
        var resolved = new AtomicReference<NpcSurfaceSnapshot>();
        var wired = wired(new AtomicBoolean(true),
                (playerId, binding, published) -> {
                    resolved.set(published);
                    return published;
                },
                null);
        var provider = wired.provider();
        var binding = binding(NpcProviderId.DESK_MENU);
        UUID player = UUID.randomUUID();
        assertNull(provider.resolve(player, binding), "nothing published yet");
        provider.bind(binding);
        provider.publish(surface(binding));
        var projected = provider.resolve(player, binding);
        assertSame(resolved.get(), projected,
                "the player-projected surface is returned, not the raw publish");
        provider.unbind(binding);
        assertNull(provider.resolve(player, binding), "unbind retracts the surface");
    }

    @Test
    void submitMintsAndSubmitsAProviderTokenThroughTheActionService() {
        var seen = new AtomicReference<NpcActionRequest>();
        var wired = wired(new AtomicBoolean(true),
                (playerId, binding, published) -> published, seen);
        var binding = binding(NpcProviderId.DESK_MENU);
        assertEquals(NpcProviderResult.Status.ACCEPTED, wired.registry().bind(binding).status());
        assertEquals(NpcProviderResult.Status.ACCEPTED,
                wired.registry().publish(surface(binding)).status());
        UUID player = UUID.randomUUID();

        var result = wired.provider().submit(player, binding, "application-submit");

        assertEquals(NpcActionResult.Status.ACCEPTED, result.status());
        var request = seen.get();
        assertNotNull(request, "the submit must reach the action service");
        assertEquals(NpcProviderId.DESK_MENU, request.providerId());
        assertEquals(binding.bindingId(), request.bindingId());
        assertEquals(player, request.playerId());
        assertEquals(NpcContentId.of("application-submit"), request.actionId());
        assertNotNull(request.interactionToken(), "a fresh provider token is minted per click");
        assertFalse(request.interactionToken().isBlank());
        assertEquals(Map.of(), request.input());
    }

    @Test
    void submitFailsClosedWithoutASurfaceOrOnADisabledOrUnknownAction() {
        var wired = wired(new AtomicBoolean(true));
        var provider = wired.provider();
        var binding = binding(NpcProviderId.DESK_MENU);
        UUID player = UUID.randomUUID();
        wired.registry().bind(binding);
        assertEquals(NpcActionResult.Status.UNAVAILABLE,
                provider.submit(player, binding, "application-submit").status(),
                "no published surface -> fail closed");
        wired.registry().publish(surface(binding));
        assertEquals(NpcActionResult.Status.UNAVAILABLE,
                provider.submit(player, binding, "complaint-submit").status(),
                "a disabled snapshot action cannot mint a token");
        assertEquals(NpcActionResult.Status.UNAVAILABLE,
                provider.submit(player, binding, "not-a-real-action").status());
    }

    @Test
    void submitSurvivesResolverFailuresAsUnavailable() {
        var wired = wired(new AtomicBoolean(true),
                (playerId, binding, published) -> {
                    throw new IllegalStateException("resolver exploded");
                },
                null);
        var binding = binding(NpcProviderId.DESK_MENU);
        wired.registry().bind(binding);
        wired.registry().publish(surface(binding));
        assertEquals(NpcActionResult.Status.UNAVAILABLE,
                wired.provider().submit(UUID.randomUUID(), binding, "application-submit").status(),
                "a resolver failure must not mint a half-valid token");
    }
}
