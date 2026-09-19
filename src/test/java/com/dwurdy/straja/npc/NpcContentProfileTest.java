package com.dwurdy.straja.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.adapter.out.npc.content.NpcContentProfileJsonLoader;
import com.dwurdy.straja.adapter.out.npc.customnpcs.CustomNpcsNpcSurfaceProvider;
import com.dwurdy.straja.application.port.in.NpcSurfaceActionTokenIssuer;
import com.dwurdy.straja.application.port.in.NpcSurfaceActionUseCase;
import com.dwurdy.straja.domain.model.NpcActionResult;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProviderId;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcContentProfileTest {
    @Test
    void packagedProfileLoadsAsCanonicalProviderNeutralContent() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(
                "data/straja/npc/straja.reception.admission.json");
        var profile = new NpcContentProfileJsonLoader().load(
                new InputStreamReader(resource, StandardCharsets.UTF_8));

        assertEquals(NpcContentId.of("straja.reception.admission"), profile.profileId());
        assertEquals(4, profile.actions().size());
        assertEquals(1, profile.dialogue().size());
        assertEquals(2, profile.quests().size());
        assertTrue(profile.requiredCapabilities().contains(
                com.dwurdy.straja.domain.model.NpcCapability.GUI));
    }

    @Test
    void instructorProfileCarriesBoundedGuiInputsWithoutProviderTypes() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(
                "data/straja/npc/straja.instructor.admission.json");
        var profile = new NpcContentProfileJsonLoader().load(
                new InputStreamReader(resource, StandardCharsets.UTF_8));

        var answer = profile.actions().stream()
                .filter(action -> action.actionId().equals(NpcContentId.of("quiz-answer")))
                .findFirst()
                .orElseThrow();
        assertEquals(2, answer.inputs().size());
        assertEquals(false, answer.inputs().getFirst().visible());
        assertEquals(120, answer.inputs().getLast().maxLength());
    }

    @Test
    void armorerProfileIsProviderNeutralAndQuestCapable() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(
                "data/straja/npc/straja.armorer.orders.json");
        var profile = new NpcContentProfileJsonLoader().load(
                new InputStreamReader(resource, StandardCharsets.UTF_8));

        assertEquals(NpcContentId.of("straja.armorer.orders"), profile.profileId());
        assertEquals(1, profile.actions().size());
        assertEquals(NpcContentId.of("duty-kit"), profile.actions().getFirst().actionId());
        assertEquals(1, profile.quests().size());
        assertTrue(profile.requiredCapabilities().contains(
                com.dwurdy.straja.domain.model.NpcCapability.QUEST_JOURNAL));
    }

    @Test
    void secretaryProfileDefinesTheProviderNeutralWorkflowShell() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(
                "data/straja/npc/straja.secretary.workflows.json");
        var profile = new NpcContentProfileJsonLoader().load(
                new InputStreamReader(resource, StandardCharsets.UTF_8));

        assertEquals(NpcContentId.of("straja.secretary.workflows"), profile.profileId());
        assertTrue(profile.actions().stream().anyMatch(action ->
                action.actionId().equals(NpcContentId.of("report-submit"))));
        assertTrue(profile.requiredCapabilities().contains(
                com.dwurdy.straja.domain.model.NpcCapability.ACTION_INPUT));
        assertEquals(NpcContentId.of("secretary-bureaucracy"), profile.quests().getFirst().questId());
    }

    @Test
    void malformedProfileCannotReferenceAnUnknownAction() {
        assertThrows(IllegalArgumentException.class, () -> new com.dwurdy.straja.domain.model.NpcContentProfile(
                NpcContentId.of("straja.invalid"),
                1,
                "Invalid",
                "Invalid content",
                List.of(),
                List.of(new com.dwurdy.straja.domain.model.NpcSurfaceSnapshot.DialogueNode(
                        NpcContentId.of("straja.invalid.node"),
                        "Text",
                        List.of(new com.dwurdy.straja.domain.model.NpcSurfaceSnapshot.Choice(
                                NpcContentId.of("missing"), "Missing", true)))),
                List.of(),
                Set.of(),
                Set.of()));
    }

    @Test
    void optionalProviderFailsClosedWhenCustomNpcsIsNotLoaded() {
        NpcSurfaceActionUseCase actions = request -> new NpcActionResult(
                NpcActionResult.Status.ACCEPTED, "ok", "accepted");
        NpcSurfaceActionTokenIssuer tokens = (playerId, binding, actionId) -> "token";
        CustomNpcsNpcSurfaceProvider provider = new CustomNpcsNpcSurfaceProvider(
                actions, tokens, ignored -> {});

        assertEquals(NpcProviderId.CUSTOM_NPCS, provider.providerId());
        if (!provider.available()) {
            NpcBinding binding = new NpcBinding(
                    "straja.reception.desk",
                    NpcProviderId.CUSTOM_NPCS,
                    UUID.randomUUID().toString(),
                    "receptionist-1",
                    "receptionist",
                    "hq",
                    NpcContentId.of("straja.reception.admission"),
                    1);
            assertEquals(com.dwurdy.straja.domain.model.NpcProviderResult.Status.UNAVAILABLE,
                    provider.bind(binding).status());
        }
    }
}
