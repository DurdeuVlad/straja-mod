package com.dwurdy.straja.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.adapter.out.npc.content.NpcContentProfileJsonLoader;
import com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase;
import com.dwurdy.straja.application.port.in.FineRoleplayUseCase;
import com.dwurdy.straja.application.service.NpcCivicSurfaceService;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcContentProfile;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcCivicSurfaceTest {
    @Test
    void receptionistProjectsCurrentComplaintAndFineActionsIntoNativeForms() throws Exception {
        NpcContentProfile profile = new NpcContentProfileJsonLoader().load(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream(
                        "data/straja/npc/straja.reception.admission.json"),
                StandardCharsets.UTF_8));
        NpcBinding binding = new NpcBinding(
                "straja.test.reception", NpcProviderId.CUSTOM_NPCS,
                UUID.randomUUID().toString(), "", "receptionist", "hq", profile.profileId(), 1);

        NpcSurfaceSnapshot surface = new NpcCivicSurfaceService().resolve(
                profile.bind(binding),
                List.of(
                        new ComplaintRoleplayUseCase.AvailableAction(
                                ComplaintRoleplayUseCase.Action.SUBMIT, ""),
                        new ComplaintRoleplayUseCase.AvailableAction(
                                ComplaintRoleplayUseCase.Action.WITHDRAW, "C-1"),
                        new ComplaintRoleplayUseCase.AvailableAction(
                                ComplaintRoleplayUseCase.Action.CONFIRM, "bad:key")),
                List.of(
                        new FineRoleplayUseCase.AvailableAction(
                                FineRoleplayUseCase.Action.PAY, "F-1"),
                        new FineRoleplayUseCase.AvailableAction(
                                FineRoleplayUseCase.Action.APPEAL, "F-1"),
                        new FineRoleplayUseCase.AvailableAction(
                                FineRoleplayUseCase.Action.REVIEW_APPEAL, "bad:key")),
                new ComplaintRoleplayUseCase.Limits(180, 240, 120),
                new FineRoleplayUseCase.Limits(80, 240, 180, 160, 120));

        assertTrue(action(surface, "complaint-submit").enabled());
        assertEquals(3, action(surface, "complaint-submit").inputs().size());
        assertTrue(action(surface, "complaint-withdraw:C-1").enabled());
        assertTrue(action(surface, "fine-pay:F-1").enabled());
        assertTrue(action(surface, "fine-appeal:F-1").enabled());
        assertFalse(surface.actions().stream()
                .anyMatch(candidate -> candidate.actionId().value().contains("bad:key")));
        assertEquals(NpcSurfaceSnapshot.QuestState.ACTIVE,
                surface.quests().stream()
                        .filter(entry -> entry.questId().equals(NpcContentId.of("civic-accountability")))
                        .findFirst().orElseThrow().state());
        assertEquals(surface.actions().size(), surface.dialogue().getFirst().choices().size());
        assertTrue(surface.actions().stream().flatMap(action -> action.inputs().stream())
                .allMatch(input -> input.maxLength() <= 512));
    }

    private static NpcSurfaceAction action(NpcSurfaceSnapshot surface, String id) {
        return surface.actions().stream()
                .filter(candidate -> candidate.actionId().equals(NpcContentId.of(id)))
                .findFirst()
                .orElseThrow();
    }
}
