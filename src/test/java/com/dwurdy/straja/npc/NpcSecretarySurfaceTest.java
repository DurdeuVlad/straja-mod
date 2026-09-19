package com.dwurdy.straja.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.application.port.in.AudienceUseCase;
import com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase;
import com.dwurdy.straja.application.port.in.FineRoleplayUseCase;
import com.dwurdy.straja.application.port.in.GuardDutyUseCase;
import com.dwurdy.straja.application.port.in.MissionRoleplayUseCase;
import com.dwurdy.straja.application.port.in.ReportUseCase;
import com.dwurdy.straja.application.service.NpcSecretarySurfaceService;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcContentProfile;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcSecretarySurfaceTest {
    @Test
    void projectsCurrentSecretaryWorkflowsIntoBoundedNativeGuiActions() throws Exception {
        NpcContentProfile profile = new com.dwurdy.straja.adapter.out.npc.content.NpcContentProfileJsonLoader()
                .load(new InputStreamReader(
                        getClass().getClassLoader().getResourceAsStream(
                                "data/straja/npc/straja.secretary.workflows.json"),
                        StandardCharsets.UTF_8));
        NpcBinding binding = new NpcBinding(
                "straja.test.secretary", NpcProviderId.CUSTOM_NPCS,
                UUID.randomUUID().toString(), "", "secretary", "hq", profile.profileId(), 1);

        NpcSurfaceSnapshot surface = new NpcSecretarySurfaceService().resolve(
                profile.bind(binding),
                new NpcSecretarySurfaceService.Inputs(
                        List.of(
                                new MissionRoleplayUseCase.AvailableAction(
                                        MissionRoleplayUseCase.Action.DRAFT_WRITE, ""),
                                new MissionRoleplayUseCase.AvailableAction(
                                        MissionRoleplayUseCase.Action.REPORT, "M-1"),
                                new MissionRoleplayUseCase.AvailableAction(
                                        MissionRoleplayUseCase.Action.REPORT, "bad:key")),
                        List.of(new ComplaintRoleplayUseCase.AvailableAction(
                                ComplaintRoleplayUseCase.Action.LIST, "")),
                        List.of(new FineRoleplayUseCase.AvailableAction(
                                FineRoleplayUseCase.Action.DRAFT_WRITE, "")),
                        List.of(
                                new ReportUseCase.AvailableAction(ReportUseCase.Action.SUBMIT, ""),
                                new ReportUseCase.AvailableAction(ReportUseCase.Action.REVIEW, "R-1")),
                        List.of(
                                new AudienceUseCase.AvailableAction(AudienceUseCase.Action.REQUEST, ""),
                                new AudienceUseCase.AvailableAction(AudienceUseCase.Action.REVIEW, "A-1")),
                        List.of(new com.dwurdy.straja.application.port.in.RoleplayExpansionUseCase.IncidentView(
                                "I-1", "HIGH", "OPEN", "Missing patrol", "Details", "Gate", "", "", 0, 0)),
                        List.of(new com.dwurdy.straja.application.port.in.RoleplayExpansionUseCase.BoloView(
                                "B-1", "Suspect", "Reason", "Commissioner", true, 0, "I-1")),
                        List.of(new com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.AvailableAction(
                                com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.PROMOTE,
                                "member-1", "Member One")),
                        new GuardDutyUseCase.DutyView(
                                false, true, "checkpoint_1", false, false, true,
                                false, false, false, false, false, false),
                        new ComplaintRoleplayUseCase.Limits(120, 240, 180),
                        new FineRoleplayUseCase.Limits(80, 240, 200, 160, 180)));

        assertTrue(action(surface, "mission-draft-write").enabled());
        assertEquals(4, action(surface, "mission-draft-write").inputs().size());
        assertTrue(action(surface, "mission-report:M-1").enabled());
        assertTrue(action(surface, "complaint-list").enabled());
        assertTrue(action(surface, "fine-draft-write").enabled());
        assertTrue(action(surface, "report-submit").enabled());
        assertTrue(action(surface, "audience-request").enabled());
        assertTrue(action(surface, "incident-accept:I-1").enabled());
        assertTrue(action(surface, "incident-resolve:I-1").enabled());
        assertTrue(action(surface, "bolo-cancel:B-1").enabled());
        assertTrue(action(surface, "admin-promote:member-1").enabled());
        assertTrue(action(surface, "duty-start").enabled());
        assertFalse(surface.actions().stream()
                .anyMatch(candidate -> candidate.actionId().value().contains("bad:key")));
        assertTrue(surface.actions().size() <= 64);
        assertTrue(surface.actions().stream()
                .flatMap(candidate -> candidate.inputs().stream())
                .allMatch(input -> input.maxLength() <= 512));
        assertEquals(surface.actions().size(), surface.dialogue().getFirst().choices().size());
        assertEquals(NpcSurfaceSnapshot.QuestState.ACTIVE, surface.quests().getFirst().state());
    }

    private static NpcSurfaceAction action(NpcSurfaceSnapshot surface, String id) {
        return surface.actions().stream()
                .filter(candidate -> candidate.actionId().equals(NpcContentId.of(id)))
                .findFirst()
                .orElseThrow();
    }
}
