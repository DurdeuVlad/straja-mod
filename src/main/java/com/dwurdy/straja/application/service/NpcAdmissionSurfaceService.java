package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.in.GuardRecruitmentUseCase;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import com.dwurdy.straja.domain.model.Rank;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the player-specific admission projection without giving a provider
 * ownership of recruitment state. Providers receive the resulting snapshot;
 * Straja still owns every eligibility, question, and completion decision.
 */
public final class NpcAdmissionSurfaceService {
    public NpcSurfaceSnapshot resolve(
            NpcSurfaceSnapshot published,
            GuardState state,
            Optional<GuardRecruitmentUseCase.QuizPrompt> prompt) {
        Objects.requireNonNull(published, "published");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(prompt, "prompt");
        return switch (published.binding().roleId()) {
            case "receptionist" -> receptionist(published, state);
            case "trainer", "recruiter" -> instructor(published, state, prompt);
            default -> published;
        };
    }

    private static NpcSurfaceSnapshot receptionist(
            NpcSurfaceSnapshot published, GuardState state) {
        boolean canApply = state.rank < Rank.STAGIAR.level()
                && !state.quizPassed
                && !state.invited
                && !state.fired
                && !state.suspended
                && !state.resigned
                && !state.resignationPending
                && !"APPLIED".equals(state.applicationState);
        String status = applicationStatus(state);
        List<NpcSurfaceAction> actions = published.actions().stream()
                .map(action -> switch (action.actionId().value()) {
                    case "application-submit" -> stateful(action, canApply,
                            canApply ? "" : applicationDisabledReason(state));
                    case "training-progress" -> stateful(action, true, "");
                    default -> action;
                })
                .toList();
        return replace(published,
                published.title(),
                published.body() + "\n\nYour admission status: " + status,
                actions,
                choices(published.dialogue(), actions),
                quests(published.quests(), admissionQuestState(state)));
    }

    private static NpcSurfaceSnapshot instructor(
            NpcSurfaceSnapshot published,
            GuardState state,
            Optional<GuardRecruitmentUseCase.QuizPrompt> prompt) {
        boolean eligible = !state.fired && !state.suspended && !state.resigned
                && (state.invited || "APPLIED".equals(state.applicationState));
        List<NpcSurfaceAction> actions = published.actions().stream()
                .map(action -> switch (action.actionId().value()) {
                    case "recruit" -> stateful(action, eligible && state.rank < Rank.STAGIAR.level(),
                            eligible ? "" : "Submit an application at reception first.");
                    case "quiz-answer" -> quizAction(action, prompt);
                    case "training-progress", "training-manual" -> stateful(action, eligible, 
                            eligible ? "" : "The instructor can help after an application is recorded.");
                    default -> action;
                })
                .toList();
        String question = prompt.map(value -> "\n\nCurrent question:\n" + value.question()).orElse(
                state.quizPassed ? "\n\nAdmission complete. Continue with the available training modules."
                        : "\n\nNo question is currently available.");
        return replace(published,
                published.title(),
                published.body() + question,
                actions,
                choices(published.dialogue(), actions),
                quests(published.quests(), admissionQuestState(state)));
    }

    private static NpcSurfaceAction quizAction(
            NpcSurfaceAction action,
            Optional<GuardRecruitmentUseCase.QuizPrompt> prompt) {
        if (prompt.isEmpty()) {
            return stateful(action, false, "No question is currently available.");
        }
        String questionId = prompt.get().questionId();
        List<NpcSurfaceAction.InputField> inputs = action.inputs().stream()
                .map(field -> "question-id".equals(field.key())
                        ? new NpcSurfaceAction.InputField(
                                field.key(), field.label(), field.maxLength(), true, false, questionId)
                        : field)
                .toList();
        return new NpcSurfaceAction(action.actionId(), action.label(), true, "", inputs);
    }

    private static NpcSurfaceAction stateful(
            NpcSurfaceAction action, boolean enabled, String disabledReason) {
        return new NpcSurfaceAction(
                action.actionId(), action.label(), enabled, enabled ? "" : disabledReason, action.inputs());
    }

    private static List<NpcSurfaceSnapshot.DialogueNode> choices(
            List<NpcSurfaceSnapshot.DialogueNode> nodes,
            List<NpcSurfaceAction> actions) {
        return nodes.stream().map(node -> new NpcSurfaceSnapshot.DialogueNode(
                node.nodeId(),
                node.text(),
                node.choices().stream().map(choice -> {
                    boolean enabled = actions.stream()
                            .filter(action -> action.actionId().equals(choice.actionId()))
                            .findFirst()
                            .map(NpcSurfaceAction::enabled)
                            .orElse(false);
                    return new NpcSurfaceSnapshot.Choice(choice.actionId(), choice.label(), enabled);
                }).toList())).toList();
    }

    private static List<NpcSurfaceSnapshot.QuestEntry> quests(
            List<NpcSurfaceSnapshot.QuestEntry> entries,
            NpcSurfaceSnapshot.QuestState state) {
        return entries.stream()
                .map(entry -> new NpcSurfaceSnapshot.QuestEntry(entry.questId(), entry.title(), state))
                .toList();
    }

    private static NpcSurfaceSnapshot replace(
            NpcSurfaceSnapshot published,
            String title,
            String body,
            List<NpcSurfaceAction> actions,
            List<NpcSurfaceSnapshot.DialogueNode> dialogue,
            List<NpcSurfaceSnapshot.QuestEntry> quests) {
        return new NpcSurfaceSnapshot(
                published.binding(), published.profileId(), title, body, actions, dialogue, quests,
                published.requiredCapabilities(), published.optionalCapabilities());
    }

    private static NpcSurfaceSnapshot.QuestState admissionQuestState(GuardState state) {
        if (state.quizPassed || state.rank >= Rank.STAGIAR.level()) {
            return NpcSurfaceSnapshot.QuestState.COMPLETED;
        }
        if (state.invited || "APPLIED".equals(state.applicationState)) {
            return NpcSurfaceSnapshot.QuestState.ACTIVE;
        }
        return NpcSurfaceSnapshot.QuestState.AVAILABLE;
    }

    private static String applicationStatus(GuardState state) {
        if (state.fired) return "Fired — speak with the commissioner.";
        if (state.suspended) return "Suspended — the admission desk is unavailable.";
        if (state.quizPassed || state.rank >= Rank.STAGIAR.level()) return "Authorized — report to the instructor.";
        if (state.invited) return "Invited — report to the instructor for the exam.";
        if ("APPLIED".equals(state.applicationState)) return "Application recorded — report to the instructor.";
        return "No application recorded.";
    }

    private static String applicationDisabledReason(GuardState state) {
        if (state.invited) return "An invitation already exists; report to the instructor.";
        if ("APPLIED".equals(state.applicationState)) return "Your application is already recorded.";
        if (state.quizPassed || state.rank >= Rank.STAGIAR.level()) return "You are already admitted.";
        if (state.fired) return "Fired players must speak with the commissioner.";
        if (state.suspended) return "Suspended players cannot submit an application.";
        if (state.resigned || state.resignationPending) return "Your resignation status prevents a new application.";
        return "The admission desk cannot accept this application.";
    }
}
