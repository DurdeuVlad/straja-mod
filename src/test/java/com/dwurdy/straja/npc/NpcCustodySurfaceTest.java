package com.dwurdy.straja.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.adapter.out.npc.content.NpcContentProfileJsonLoader;
import com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase;
import com.dwurdy.straja.application.service.NpcCustodySurfaceService;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcContentProfile;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import com.dwurdy.straja.domain.model.Sentence;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcCustodySurfaceTest {
    @Test
    void jailerProjectsStateAndValidatedCustodyActions() throws Exception {
        NpcContentProfile profile = new NpcContentProfileJsonLoader().load(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream(
                        "data/straja/npc/straja.jailer.custody.json"),
                StandardCharsets.UTF_8));
        NpcBinding binding = new NpcBinding(
                "straja.test.jailer", NpcProviderId.CUSTOM_NPCS,
                UUID.randomUUID().toString(), "", "jailer", "hq", profile.profileId(), 1);
        Sentence sentence = new Sentence();
        sentence.id = "S-1";
        sentence.status = "ACTIVE";
        sentence.remainingActiveMs = 3_600_000L;

        NpcSurfaceSnapshot surface = new NpcCustodySurfaceService().resolve(
                profile.bind(binding),
                List.of(
                        new CustodyRoleplayUseCase.AvailableAction(
                                CustodyRoleplayUseCase.Action.ACCEPT_REQUEST, "Q-1"),
                        new CustodyRoleplayUseCase.AvailableAction(
                                CustodyRoleplayUseCase.Action.REFUSE_REQUEST, "Q-1"),
                        new CustodyRoleplayUseCase.AvailableAction(
                                CustodyRoleplayUseCase.Action.RELEASE_TARGET, "target-1"),
                        new CustodyRoleplayUseCase.AvailableAction(
                                CustodyRoleplayUseCase.Action.RELEASE_TARGET, "bad:key"),
                        new CustodyRoleplayUseCase.AvailableAction(
                                CustodyRoleplayUseCase.Action.GIVE_CUFFS, "")),
                true, false, false, sentence);

        assertTrue(action(surface, "custody-accept:Q-1").enabled());
        assertTrue(action(surface, "custody-release:target-1").enabled());
        assertFalse(surface.actions().stream()
                .anyMatch(candidate -> candidate.actionId().value().contains("bad:key")));
        assertTrue(action(surface, "cuffs-item").enabled());
        assertFalse(action(surface, "custody-remove-head-sack").enabled());
        assertFalse(action(surface, "custody-wake-downed").enabled());
        assertTrue(surface.body().contains("ACTIVE"));
        assertEquals(NpcSurfaceSnapshot.QuestState.ACTIVE, surface.quests().getFirst().state());
        assertEquals(surface.actions().size(), surface.dialogue().getFirst().choices().size());
    }

    private static NpcSurfaceAction action(NpcSurfaceSnapshot surface, String id) {
        return surface.actions().stream()
                .filter(candidate -> candidate.actionId().equals(NpcContentId.of(id)))
                .findFirst()
                .orElseThrow();
    }
}
