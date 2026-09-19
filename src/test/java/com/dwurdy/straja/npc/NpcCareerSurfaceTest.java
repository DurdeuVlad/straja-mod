package com.dwurdy.straja.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.application.port.in.GuardRecruitmentUseCase;
import com.dwurdy.straja.application.service.NpcCareerSurfaceService;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcCareerSurfaceTest {
    private static final NpcContentId PROFILE = NpcContentId.of("straja.instructor.admission");

    @Test
    void civilianSurfaceFailsClosedAndKeepsCareerQuestLocked() {
        GuardState state = new GuardState();
        NpcSurfaceSnapshot surface = new NpcCareerSurfaceService().resolve(
                published(), state, training(0, 0, 2, 0, true, false, 0));

        assertFalse(action(surface, "training-progress").enabled());
        assertFalse(action(surface, "training-promote").enabled());
        assertEquals(NpcSurfaceSnapshot.QuestState.LOCKED, quest(surface, "career-progression").state());
    }

    @Test
    void eligibleGuardSeesProgressionStateAndPromotionWhenAuthoritativeViewAllowsIt() {
        GuardState state = new GuardState();
        state.rank = 1;
        state.invited = true;
        NpcSurfaceSnapshot surface = new NpcCareerSurfaceService().resolve(
                published(), state, training(1, 20, 2, 20, true, false, 0));

        assertTrue(action(surface, "training-progress").enabled());
        assertTrue(action(surface, "training-promote").enabled());
        assertTrue(surface.body().contains("Next rank: Străjer"));
        assertEquals(NpcSurfaceSnapshot.QuestState.ACTIVE, quest(surface, "career-progression").state());
        assertTrue(surface.dialogue().getFirst().choices().stream()
                .anyMatch(choice -> choice.actionId().value().equals("training-promote") && choice.enabled()));
    }

    @Test
    void pendingModulesDisablePromotionWithAPlayerFacingReason() {
        GuardState state = new GuardState();
        state.rank = 1;
        NpcSurfaceSnapshot surface = new NpcCareerSurfaceService().resolve(
                published(), state, training(1, 20, 2, 20, false, false, 2));

        assertFalse(action(surface, "training-promote").enabled());
        assertEquals("Complete the remaining training modules first.",
                action(surface, "training-promote").disabledReason());
        assertTrue(surface.body().contains("Pending training modules: 2"));
    }

    @Test
    void resignationPendingFailsClosedForCareerProgression() {
        GuardState state = new GuardState();
        state.rank = 2;
        state.resignationPending = true;
        NpcSurfaceSnapshot surface = new NpcCareerSurfaceService().resolve(
                published(), state, training(2, 20, 3, 20, true, false, 0));

        assertFalse(action(surface, "training-progress").enabled());
        assertFalse(action(surface, "training-promote").enabled());
        assertEquals(NpcSurfaceSnapshot.QuestState.LOCKED, quest(surface, "career-progression").state());
    }

    private static NpcSurfaceAction action(NpcSurfaceSnapshot surface, String id) {
        return surface.actions().stream()
                .filter(action -> action.actionId().value().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static NpcSurfaceSnapshot.QuestEntry quest(NpcSurfaceSnapshot surface, String id) {
        return surface.quests().stream()
                .filter(quest -> quest.questId().value().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static GuardRecruitmentUseCase.TrainingView training(
            int rank, long blocks, Integer nextRank, Integer required,
            boolean canPromote, boolean hasManual, int pendingModules) {
        return new GuardRecruitmentUseCase.TrainingView(
                rank, blocks, nextRank, required, canPromote, hasManual, pendingModules);
    }

    private static NpcSurfaceSnapshot published() {
        NpcBinding binding = new NpcBinding(
                "straja.test.instructor", NpcProviderId.CUSTOM_NPCS,
                UUID.randomUUID().toString(), "", "trainer", "hq", PROFILE, 1);
        List<NpcSurfaceAction> actions = List.of(
                NpcSurfaceAction.enabled(NpcContentId.of("recruit"), "Open exam"),
                NpcSurfaceAction.enabled(NpcContentId.of("training-progress"), "Progress"),
                NpcSurfaceAction.enabled(NpcContentId.of("training-promote"), "Promote"),
                NpcSurfaceAction.enabled(NpcContentId.of("training-manual"), "Manual"));
        return new NpcSurfaceSnapshot(
                binding, PROFILE, "Instructor", "Training desk.", actions,
                List.of(new NpcSurfaceSnapshot.DialogueNode(
                        NpcContentId.of("straja.instructor.introduction"), "Progress", actions.stream()
                                .map(action -> new NpcSurfaceSnapshot.Choice(
                                        action.actionId(), action.label(), action.enabled()))
                                .toList())),
                List.of(
                        new NpcSurfaceSnapshot.QuestEntry(
                                NpcContentId.of("training-basic"), "Admission", NpcSurfaceSnapshot.QuestState.LOCKED),
                        new NpcSurfaceSnapshot.QuestEntry(
                                NpcContentId.of("career-progression"), "Career progression", NpcSurfaceSnapshot.QuestState.LOCKED)),
                Set.of(NpcCapability.DIALOGUE, NpcCapability.GUI,
                        NpcCapability.ACTION_INPUT, NpcCapability.QUEST_JOURNAL), Set.of());
    }
}
