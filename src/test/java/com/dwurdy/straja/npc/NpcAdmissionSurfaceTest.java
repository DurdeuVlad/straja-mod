package com.dwurdy.straja.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.application.port.in.GuardRecruitmentUseCase;
import com.dwurdy.straja.application.service.NpcAdmissionSurfaceService;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcAdmissionSurfaceTest {
    private static final NpcContentId RECEPTION_PROFILE = NpcContentId.of("straja.reception.admission");
    private static final NpcContentId INSTRUCTOR_PROFILE = NpcContentId.of("straja.instructor.admission");

    @Test
    void receptionistProjectionShowsPersistedApplicationStatusAndDisablesDuplicateApply() {
        NpcBinding binding = binding("receptionist", RECEPTION_PROFILE);
        GuardState state = new GuardState();
        state.applicationState = "APPLIED";

        NpcSurfaceSnapshot surface = new NpcAdmissionSurfaceService().resolve(
                reception(binding),
                state,
                Optional.empty());

        NpcSurfaceAction apply = action(surface, "application-submit");
        assertTrue(surface.body().contains("Application recorded"));
        assertEquals(false, apply.enabled());
        assertEquals(NpcSurfaceSnapshot.QuestState.ACTIVE, surface.quests().getFirst().state());
        assertEquals(false, surface.dialogue().getFirst().choices().getFirst().enabled());
    }

    @Test
    void instructorProjectionBindsTheServerQuestionToAHiddenInputAndShowsQuest() {
        NpcBinding binding = binding("trainer", INSTRUCTOR_PROFILE);
        GuardState state = new GuardState();
        state.applicationState = "APPLIED";
        GuardRecruitmentUseCase.QuizPrompt prompt = new GuardRecruitmentUseCase.QuizPrompt(
                "question-7", "Admission", "What is your duty?", 120);

        NpcSurfaceSnapshot surface = new NpcAdmissionSurfaceService().resolve(
                instructor(binding),
                state,
                Optional.of(prompt));

        NpcSurfaceAction answer = action(surface, "quiz-answer");
        NpcSurfaceAction.InputField questionId = answer.inputs().stream()
                .filter(field -> field.key().equals("question-id"))
                .findFirst()
                .orElseThrow();
        assertTrue(surface.body().contains("What is your duty?"));
        assertTrue(answer.enabled());
        assertEquals("question-7", questionId.initialValue());
        assertEquals(false, questionId.visible());
        assertEquals(NpcSurfaceSnapshot.QuestState.ACTIVE, surface.quests().getFirst().state());
    }

    @Test
    void completedAdmissionReconstructsAsCompletedAndHidesAnswerAction() {
        NpcBinding binding = binding("trainer", INSTRUCTOR_PROFILE);
        GuardState state = new GuardState();
        state.applicationState = "AUTHORIZED";
        state.quizPassed = true;
        state.rank = 1;

        NpcSurfaceSnapshot surface = new NpcAdmissionSurfaceService().resolve(
                instructor(binding), state, Optional.empty());

        assertEquals(false, action(surface, "quiz-answer").enabled());
        assertEquals(NpcSurfaceSnapshot.QuestState.COMPLETED, surface.quests().getFirst().state());
        assertTrue(surface.body().contains("Admission complete"));
    }

    private static NpcSurfaceAction action(NpcSurfaceSnapshot surface, String id) {
        return surface.actions().stream()
                .filter(action -> action.actionId().value().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static NpcSurfaceSnapshot reception(NpcBinding binding) {
        return new NpcSurfaceSnapshot(
                binding, RECEPTION_PROFILE, "Reception", "The reception desk.",
                List.of(
                        NpcSurfaceAction.enabled(NpcContentId.of("application-submit"), "Apply"),
                        NpcSurfaceAction.enabled(NpcContentId.of("training-progress"), "Progress")),
                List.of(new NpcSurfaceSnapshot.DialogueNode(
                        NpcContentId.of("reception"), "State your purpose.", List.of(
                                new NpcSurfaceSnapshot.Choice(
                                        NpcContentId.of("application-submit"), "Apply", true),
                                new NpcSurfaceSnapshot.Choice(
                                        NpcContentId.of("training-progress"), "Progress", true)))),
                List.of(new NpcSurfaceSnapshot.QuestEntry(
                        NpcContentId.of("training-basic"), "Admission", NpcSurfaceSnapshot.QuestState.AVAILABLE)),
                Set.of(NpcCapability.GUI), Set.of());
    }

    private static NpcSurfaceSnapshot instructor(NpcBinding binding) {
        return new NpcSurfaceSnapshot(
                binding, INSTRUCTOR_PROFILE, "Instructor", "The instructor.",
                List.of(
                        NpcSurfaceAction.enabled(NpcContentId.of("recruit"), "Open exam"),
                        new NpcSurfaceAction(
                                NpcContentId.of("quiz-answer"), "Answer", true, "", List.of(
                                        new NpcSurfaceAction.InputField(
                                                "question-id", "Question", 128, true, false, ""),
                                        new NpcSurfaceAction.InputField(
                                                "answer", "Answer", 120, true))),
                        NpcSurfaceAction.enabled(NpcContentId.of("training-progress"), "Progress"),
                        NpcSurfaceAction.enabled(NpcContentId.of("training-manual"), "Manual")),
                List.of(new NpcSurfaceSnapshot.DialogueNode(
                        NpcContentId.of("instructor"), "The exam.", List.of(
                                new NpcSurfaceSnapshot.Choice(
                                        NpcContentId.of("recruit"), "Open exam", true),
                                new NpcSurfaceSnapshot.Choice(
                                        NpcContentId.of("quiz-answer"), "Answer", true)))),
                List.of(new NpcSurfaceSnapshot.QuestEntry(
                        NpcContentId.of("training-basic"), "Admission", NpcSurfaceSnapshot.QuestState.LOCKED)),
                Set.of(NpcCapability.GUI), Set.of());
    }

    private static NpcBinding binding(String role, NpcContentId profile) {
        return new NpcBinding(
                "straja.test." + role,
                NpcProviderId.CUSTOM_NPCS,
                UUID.randomUUID().toString(), "", role, "hq", profile, 1);
    }
}
