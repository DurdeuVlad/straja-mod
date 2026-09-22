package com.dwurdy.straja.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.adapter.out.npc.content.NpcContentProfileJsonLoader;
import com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase;
import com.dwurdy.straja.application.service.NpcArchiveSurfaceService;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcArchiveSurfaceTest {
    @Test
    void archivistProjectsAuthorizedArchiveActionsAndBoundedForms() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(
                "data/straja/npc/straja.archivist.archive.json");
        var profile = new NpcContentProfileJsonLoader().load(
                new InputStreamReader(resource, StandardCharsets.UTF_8));
        var binding = new NpcBinding(
                "straja.test.archivist", NpcProviderId.CUSTOM_NPCS,
                UUID.randomUUID().toString(), "", "archivist", "hq", profile.profileId(), 1);

        var surface = new NpcArchiveSurfaceService().resolve(
                profile.bind(binding),
                List.of(
                        new ArchiveRoleplayUseCase.AvailableAction(
                                ArchiveRoleplayUseCase.Action.LIST, ""),
                        new ArchiveRoleplayUseCase.AvailableAction(
                                ArchiveRoleplayUseCase.Action.CREATE_FOLDER, ""),
                        new ArchiveRoleplayUseCase.AvailableAction(
                                ArchiveRoleplayUseCase.Action.READ_FOLDER, "D-1"),
                        new ArchiveRoleplayUseCase.AvailableAction(
                                ArchiveRoleplayUseCase.Action.EDIT_SHEET, "A-1"),
                        new ArchiveRoleplayUseCase.AvailableAction(
                                ArchiveRoleplayUseCase.Action.SIGN_SHEET, "A-1"),
                        new ArchiveRoleplayUseCase.AvailableAction(
                                ArchiveRoleplayUseCase.Action.READ_SHEET, "bad:key")),
                new ArchiveRoleplayUseCase.Limits(200, 2400, 160, 240, 80));

        assertTrue(action(surface, "archive-list").enabled());
        assertTrue(action(surface, "archive-folder-create").enabled());
        assertEquals(2, action(surface, "archive-folder-create").inputs().size());
        assertTrue(action(surface, "archive-folder-read:D-1").enabled());
        assertTrue(action(surface, "archive-sheet-edit:A-1").enabled());
        assertEquals(1, action(surface, "archive-sheet-sign:A-1").inputs().size());
        assertFalse(surface.actions().stream()
                .anyMatch(candidate -> candidate.actionId().value().contains("bad:key")));
        assertEquals(NpcSurfaceSnapshot.QuestState.ACTIVE,
                surface.quests().stream()
                        .filter(entry -> entry.questId().equals(NpcContentId.of("archive-records")))
                        .findFirst().orElseThrow().state());
        assertEquals(surface.actions().size(), surface.dialogue().getFirst().choices().size());
        assertTrue(surface.actions().stream().flatMap(action -> action.inputs().stream())
                .allMatch(input -> input.maxLength() <= 512));
    }

    @Test
    void unavailableAuthoredActionsAreLocked() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(
                "data/straja/npc/straja.archivist.archive.json");
        var profile = new NpcContentProfileJsonLoader().load(
                new InputStreamReader(resource, StandardCharsets.UTF_8));
        var binding = new NpcBinding(
                "straja.test.archivist", NpcProviderId.CUSTOM_NPCS,
                UUID.randomUUID().toString(), "", "archivist", "hq", profile.profileId(), 1);

        var surface = new NpcArchiveSurfaceService().resolve(
                profile.bind(binding),
                List.of(new ArchiveRoleplayUseCase.AvailableAction(
                        ArchiveRoleplayUseCase.Action.LIST, "")),
                new ArchiveRoleplayUseCase.Limits(200, 2400, 160, 240, 80));

        assertTrue(action(surface, "archive-list").enabled());
        assertFalse(action(surface, "archive-folder-create").enabled());
    }

    @Test
    void malformedOnlyArchiveEntriesDoNotClaimAnActiveQuest() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(
                "data/straja/npc/straja.archivist.archive.json");
        var profile = new NpcContentProfileJsonLoader().load(
                new InputStreamReader(resource, StandardCharsets.UTF_8));
        var binding = new NpcBinding(
                "straja.test.archivist", NpcProviderId.CUSTOM_NPCS,
                UUID.randomUUID().toString(), "", "archivist", "hq", profile.profileId(), 1);

        var surface = new NpcArchiveSurfaceService().resolve(
                profile.bind(binding),
                List.of(new ArchiveRoleplayUseCase.AvailableAction(
                        ArchiveRoleplayUseCase.Action.READ_SHEET, "bad:key")),
                new ArchiveRoleplayUseCase.Limits(200, 2400, 160, 240, 80));

        assertEquals(NpcSurfaceSnapshot.QuestState.LOCKED,
                surface.quests().getFirst().state());
    }

    private static NpcSurfaceAction action(NpcSurfaceSnapshot surface, String id) {
        return surface.actions().stream()
                .filter(candidate -> candidate.actionId().equals(NpcContentId.of(id)))
                .findFirst()
                .orElseThrow();
    }
}
