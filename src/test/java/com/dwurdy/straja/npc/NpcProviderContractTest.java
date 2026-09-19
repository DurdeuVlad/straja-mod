package com.dwurdy.straja.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.adapter.out.npc.debug.DebugTextNpcSurfaceProvider;
import com.dwurdy.straja.application.service.NpcSurfaceActionService;
import com.dwurdy.straja.application.port.out.NpcSurfaceProvider;
import com.dwurdy.straja.application.service.NpcSurfaceProviderRegistry;
import com.dwurdy.straja.domain.model.NpcActionRequest;
import com.dwurdy.straja.domain.model.NpcActionResult;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderOperation;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.ArrayList;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class NpcProviderContractTest {
    private static final NpcContentId PROFILE = NpcContentId.of("straja.reception.admission");

    @Test
    void debugProviderUsesTheSameProviderPortAndRendersCanonicalSurface() {
        List<String> output = new ArrayList<>();
        NpcSurfaceProvider provider = new DebugTextNpcSurfaceProvider(output::add);
        NpcBinding binding = binding(NpcProviderId.DEBUG_TEXT);

        assertEquals(NpcProviderResult.Status.ACCEPTED, provider.bind(binding).status());
        assertEquals(NpcProviderResult.Status.ACCEPTED, provider.publish(surface(binding)).status());
        assertEquals(1, output.size());
        assertTrue(output.getFirst().contains("straja.reception.admission"));
        assertTrue(output.getFirst().contains("application-submit"));
        assertTrue(output.getFirst().contains("training-basic"));
    }

    @Test
    void providerRegistryRejectsDuplicatesAndMissingCapabilitiesExplicitly() {
        NpcSurfaceProviderRegistry registry = new NpcSurfaceProviderRegistry();
        DebugTextNpcSurfaceProvider provider = new DebugTextNpcSurfaceProvider(ignored -> {});

        registry.register(provider);
        assertThrows(IllegalArgumentException.class, () -> registry.register(provider));
        assertThrows(IllegalStateException.class, () -> registry.require(
                NpcProviderId.DEBUG_TEXT, Set.of(NpcCapability.PORTRAITS)));
        assertThrows(IllegalStateException.class, () -> registry.require(
                NpcProviderId.CUSTOM_NPCS, Set.of(NpcCapability.GUI)));
    }

    @Test
    void registryOwnsLogicalBindingAtomicallyAndRequiresExactMapping() {
        NpcSurfaceProviderRegistry registry = new NpcSurfaceProviderRegistry();
        DebugTextNpcSurfaceProvider provider = new DebugTextNpcSurfaceProvider(ignored -> {});
        registry.register(provider);
        NpcBinding binding = binding(NpcProviderId.DEBUG_TEXT);
        NpcBinding conflicting = new NpcBinding(
                binding.bindingId(), binding.providerId(), UUID.randomUUID().toString(),
                binding.externalNpcId(), binding.roleId(), binding.stationId(),
                binding.surfaceProfileId(), binding.schemaVersion());

        assertEquals(NpcProviderResult.Status.ACCEPTED, registry.bind(binding).status());
        assertEquals(NpcProviderResult.Status.REJECTED, registry.bind(conflicting).status());
        assertEquals(NpcProviderResult.Status.ACCEPTED, registry.publish(surface(binding)).status());
        assertEquals(NpcProviderResult.Status.REJECTED, registry.publish(surface(conflicting)).status());
    }

    @Test
    void actionServiceBindsTokenToPlayerProviderBindingActionAndProximity() {
        NpcSurfaceProviderRegistry registry = new NpcSurfaceProviderRegistry();
        registry.register(new DebugTextNpcSurfaceProvider(ignored -> {}));
        NpcBinding binding = binding(NpcProviderId.DEBUG_TEXT);
        assertEquals(NpcProviderResult.Status.ACCEPTED, registry.bind(binding).status());
        assertEquals(NpcProviderResult.Status.ACCEPTED, registry.publish(surface(binding)).status());
        UUID player = UUID.randomUUID();
        NpcSurfaceActionService service = new NpcSurfaceActionService(
                registry,
                (candidate, target) -> candidate.equals(player),
                request -> new NpcActionResult(
                        NpcActionResult.Status.ACCEPTED, "dispatched", "action dispatched"),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(15));
        String token = service.issueToken(player, binding, NpcContentId.of("application-submit"));
        NpcActionRequest request = new NpcActionRequest(
                binding.providerId(), binding.bindingId(), player,
                NpcContentId.of("application-submit"), token, Map.of());

        assertEquals(NpcActionResult.Status.ACCEPTED, service.submit(request).status());
        assertEquals(NpcActionResult.Status.ACCEPTED, service.submit(request).status());
    }

    @Test
    void actionServiceRejectsWrongPlayerAndOutOfRangeWithoutConsumingTheToken() {
        NpcSurfaceProviderRegistry registry = new NpcSurfaceProviderRegistry();
        registry.register(new DebugTextNpcSurfaceProvider(ignored -> {}));
        NpcBinding binding = binding(NpcProviderId.DEBUG_TEXT);
        registry.bind(binding);
        registry.publish(surface(binding));
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        AtomicBoolean inRange = new AtomicBoolean(false);
        NpcSurfaceActionService service = new NpcSurfaceActionService(
                registry,
                (candidate, target) -> inRange.get() && candidate.equals(owner),
                request -> new NpcActionResult(
                        NpcActionResult.Status.ACCEPTED, "dispatched", "action dispatched"));
        String token = service.issueToken(owner, binding, NpcContentId.of("application-submit"));
        NpcActionRequest wrongPlayer = new NpcActionRequest(
                binding.providerId(), binding.bindingId(), other,
                NpcContentId.of("application-submit"), token, Map.of());
        assertEquals(NpcActionResult.Status.UNAUTHORIZED, service.submit(wrongPlayer).status());

        NpcActionRequest ownerRequest = new NpcActionRequest(
                binding.providerId(), binding.bindingId(), owner,
                NpcContentId.of("application-submit"), token, Map.of());
        assertEquals(NpcActionResult.Status.UNAUTHORIZED, service.submit(ownerRequest).status());
        inRange.set(true);
        assertEquals(NpcActionResult.Status.ACCEPTED, service.submit(ownerRequest).status());
    }

    @Test
    void failedDispatchLeavesTheTokenRetryableButPreventsConcurrentReplay() {
        NpcSurfaceProviderRegistry registry = new NpcSurfaceProviderRegistry();
        registry.register(new DebugTextNpcSurfaceProvider(ignored -> {}));
        NpcBinding binding = binding(NpcProviderId.DEBUG_TEXT);
        registry.bind(binding);
        registry.publish(surface(binding));
        UUID player = UUID.randomUUID();
        AtomicBoolean dispatchAvailable = new AtomicBoolean(false);
        NpcSurfaceActionService service = new NpcSurfaceActionService(
                registry,
                (candidate, target) -> true,
                request -> dispatchAvailable.get()
                        ? new NpcActionResult(NpcActionResult.Status.ACCEPTED, "dispatched", "action dispatched")
                        : new NpcActionResult(NpcActionResult.Status.UNAVAILABLE, "busy", "try again"));
        String token = service.issueToken(player, binding, NpcContentId.of("application-submit"));
        NpcActionRequest request = new NpcActionRequest(
                binding.providerId(), binding.bindingId(), player,
                NpcContentId.of("application-submit"), token, Map.of());

        assertEquals(NpcActionResult.Status.UNAVAILABLE, service.submit(request).status());
        dispatchAvailable.set(true);
        assertEquals(NpcActionResult.Status.UNAVAILABLE, service.submit(request).status());
        String retryToken = service.issueToken(player, binding, NpcContentId.of("application-submit"));
        NpcActionRequest retry = new NpcActionRequest(
                binding.providerId(), binding.bindingId(), player,
                NpcContentId.of("application-submit"), retryToken, Map.of());
        assertEquals(NpcActionResult.Status.ACCEPTED, service.submit(retry).status());
    }

    @Test
    void providerExceptionCreatesAnUnknownStateInsteadOfAFalseOwnershipReceipt() {
        NpcSurfaceProviderRegistry registry = new NpcSurfaceProviderRegistry();
        NpcProviderId providerId = NpcProviderId.of("throwing");
        registry.register(new NpcSurfaceProvider() {
            @Override
            public NpcProviderId providerId() {
                return providerId;
            }

            @Override
            public Set<NpcCapability> capabilities() {
                return Set.of(NpcCapability.TEXT_MIRROR);
            }

            @Override
            public NpcProviderResult bind(NpcBinding binding) {
                throw new IllegalStateException("external provider mutated before failing");
            }

            @Override
            public NpcProviderResult unbind(NpcBinding binding) {
                return NpcProviderResult.accepted("unused");
            }

            @Override
            public NpcProviderResult publish(NpcSurfaceSnapshot surface) {
                return NpcProviderResult.accepted("unused");
            }

            @Override
            public NpcProviderResult reconcile(NpcBinding binding) {
                return NpcProviderResult.accepted("confirmed after external probe");
            }
        });
        NpcBinding binding = binding(providerId);

        assertEquals(NpcProviderResult.Status.UNKNOWN, registry.bind(binding).status());
        assertTrue(registry.unknownBinding(binding.bindingId()).isPresent());
        assertEquals(NpcProviderOperation.BIND,
                registry.unknownOperation(binding.bindingId()).orElseThrow());
        assertEquals(NpcProviderResult.Status.UNKNOWN, registry.bind(binding).status());
        assertTrue(registry.binding(binding.bindingId()).isEmpty());
        assertEquals(NpcProviderResult.Status.RECONCILED,
                registry.reconcile(binding.bindingId()).status());
        assertTrue(registry.binding(binding.bindingId()).isPresent());
    }

    @Test
    void dialogueChoicesMustReferenceCanonicalSurfaceActions() {
        NpcBinding binding = binding(NpcProviderId.DEBUG_TEXT);
        assertThrows(IllegalArgumentException.class, () -> new NpcSurfaceSnapshot(
                binding,
                PROFILE,
                "Reception",
                "Submit your admission application.",
                List.of(NpcSurfaceAction.enabled(
                        NpcContentId.of("application-submit"), "Submit application")),
                List.of(new NpcSurfaceSnapshot.DialogueNode(
                        NpcContentId.of("straja.reception.introduction"),
                        "Welcome to the Straja.",
                        List.of(new NpcSurfaceSnapshot.Choice(
                                NpcContentId.of("missing-action"), "Missing", true)))),
                List.of(),
                Set.of(NpcCapability.TEXT_MIRROR),
                Set.of()));
    }

    @Test
    void providerCannotPublishAOtherProviderSurfaceOrUnboundSurface() {
        DebugTextNpcSurfaceProvider provider = new DebugTextNpcSurfaceProvider(ignored -> {});
        NpcBinding binding = binding(NpcProviderId.DEBUG_TEXT);
        NpcBinding otherProviderBinding = binding(NpcProviderId.CUSTOM_NPCS);

        assertEquals(NpcProviderResult.Status.REJECTED,
                provider.publish(surface(binding)).status());
        assertEquals(NpcProviderResult.Status.REJECTED,
                provider.publish(surface(otherProviderBinding)).status());
    }

    @Test
    void bindingIdentityAndSurfaceProfileAreStableAndValidated() {
        NpcBinding binding = binding(NpcProviderId.DEBUG_TEXT);
        assertEquals("straja.reception.desk", binding.bindingId());
        assertEquals(PROFILE, binding.surfaceProfileId());
        assertThrows(IllegalArgumentException.class, () -> new NpcBinding(
                "Bad Binding", NpcProviderId.DEBUG_TEXT, "entity", "external",
                "receptionist", "hq", PROFILE, 1));
        assertThrows(IllegalArgumentException.class, () -> new NpcSurfaceSnapshot(
                binding, NpcContentId.of("straja.other.profile"), "Reception", "Welcome",
                List.of(), List.of(), List.of(), Set.of(), Set.of()));
    }

    @Test
    void actionInputIsBoundedBeforeItReachesAnAdapter() {
        assertThrows(IllegalArgumentException.class, () -> new com.dwurdy.straja.domain.model.NpcActionRequest(
                NpcProviderId.DEBUG_TEXT, "straja.reception.desk", UUID.randomUUID(),
                NpcContentId.of("straja.reception.admission"), "token", Map.of("x", "x".repeat(513))));
    }

    private static NpcBinding binding(NpcProviderId providerId) {
        return new NpcBinding(
                "straja.reception.desk",
                providerId,
                UUID.randomUUID().toString(),
                "receptionist-1",
                "receptionist",
                "hq",
                PROFILE,
                1);
    }

    private static NpcSurfaceSnapshot surface(NpcBinding binding) {
        return new NpcSurfaceSnapshot(
                binding,
                PROFILE,
                "Reception",
                "Submit your admission application.",
                List.of(NpcSurfaceAction.enabled(
                        NpcContentId.of("application-submit"), "Submit application")),
                List.of(new NpcSurfaceSnapshot.DialogueNode(
                        NpcContentId.of("straja.reception.introduction"),
                        "Welcome to the Straja.",
                        List.of())),
                List.of(new NpcSurfaceSnapshot.QuestEntry(
                        NpcContentId.of("training-basic"),
                        "Basic training",
                        NpcSurfaceSnapshot.QuestState.AVAILABLE)),
                Set.of(NpcCapability.TEXT_MIRROR),
                Set.of());
    }
}
