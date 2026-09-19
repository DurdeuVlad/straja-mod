package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.in.GuardRecruitmentUseCase;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import com.dwurdy.straja.domain.model.Rank;
import java.util.List;
import java.util.Objects;

/**
 * Projects the authoritative instructor/recruiter progression view onto the
 * provider-neutral NPC surface. It does not create a career decision; rank,
 * training, manual delivery, and promotion remain owned by GuardService.
 */
public final class NpcCareerSurfaceService {
    public NpcSurfaceSnapshot resolve(
            NpcSurfaceSnapshot published,
            GuardState state,
            GuardRecruitmentUseCase.TrainingView training) {
        Objects.requireNonNull(published, "published");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(training, "training");
        List<NpcSurfaceAction> actions = published.actions().stream()
                .map(action -> switch (action.actionId().value()) {
                    case "training-progress" -> stateful(action, careerEligible(state),
                            careerEligible(state) ? "" : "Career progression is available after admission.");
                    case "training-manual" -> stateful(action, manualEligible(state),
                            manualEligible(state) ? "" : "The manual is unavailable in your current status.");
                    case "training-promote" -> stateful(action,
                            careerEligible(state) && training.canPromote(), promotionReason(state, training));
                    default -> action;
                })
                .toList();
        String body = bounded(published.body() + "\n\n" + progressionText(state, training), 8_192);
        return new NpcSurfaceSnapshot(
                published.binding(), published.profileId(), published.title(), body,
                actions, choices(published.dialogue(), actions),
                quests(published.quests(), state),
                published.requiredCapabilities(), published.optionalCapabilities());
    }

    private static boolean careerEligible(GuardState state) {
        return !state.fired && !state.suspended && !state.resigned
                && !state.resignationPending
                && (state.invited || "APPLIED".equals(state.applicationState)
                || state.rank >= Rank.STAGIAR.level());
    }

    private static boolean manualEligible(GuardState state) {
        return !state.fired && !state.suspended && !state.resigned
                && (state.invited || state.rank >= Rank.STAGIAR.level());
    }

    private static String progressionText(
            GuardState state, GuardRecruitmentUseCase.TrainingView training) {
        StringBuilder text = new StringBuilder("Career status: ")
                .append(Rank.of(training.rank()).displayName())
                .append("; service blocks: ").append(training.serviceBlocks()).append('.');
        if (training.pendingModules() > 0) {
            text.append(" Pending training modules: ").append(training.pendingModules()).append('.');
        }
        if (training.nextRank() != null && training.requiredBlocks() != null) {
            text.append(" Next rank: ").append(Rank.of(training.nextRank()).displayName())
                    .append(" after ").append(training.requiredBlocks()).append(" service blocks.");
        } else if (state.rank >= Rank.STAGIAR.level() && state.rank < Rank.INSPECTOR.level()) {
            text.append(" The next advancement requires commissioner review.");
        } else if (state.rank < Rank.STAGIAR.level()) {
            text.append(" Complete admission before career progression becomes available.");
        } else {
            text.append(" You have reached the configured military rank ceiling.");
        }
        return text.toString();
    }

    private static String promotionReason(
            GuardState state, GuardRecruitmentUseCase.TrainingView training) {
        if (!careerEligible(state)) return "Career progression is available after admission.";
        if (training.nextRank() == null) return "The next advancement requires commissioner review.";
        if (training.pendingModules() > 0) {
            return "Complete the remaining training modules first.";
        }
        if (training.requiredBlocks() != null && training.serviceBlocks() < training.requiredBlocks()) {
            return "Earn " + (training.requiredBlocks() - training.serviceBlocks())
                    + " more service blocks first.";
        }
        return "Promotion is not available in the current authoritative state.";
    }

    private static NpcSurfaceAction stateful(
            NpcSurfaceAction action, boolean enabled, String reason) {
        return new NpcSurfaceAction(action.actionId(), action.label(), enabled,
                enabled ? "" : reason, action.inputs());
    }

    private static List<NpcSurfaceSnapshot.DialogueNode> choices(
            List<NpcSurfaceSnapshot.DialogueNode> nodes,
            List<NpcSurfaceAction> actions) {
        return nodes.stream().map(node -> new NpcSurfaceSnapshot.DialogueNode(
                node.nodeId(), node.text(), node.choices().stream().map(choice -> {
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
            GuardState state) {
        return entries.stream()
                .map(entry -> "career-progression".equals(entry.questId().value())
                        ? new NpcSurfaceSnapshot.QuestEntry(
                                entry.questId(), entry.title(), careerQuestState(state))
                        : entry)
                .toList();
    }

    private static NpcSurfaceSnapshot.QuestState careerQuestState(GuardState state) {
        if (state.rank >= Rank.INSPECTOR.level()) return NpcSurfaceSnapshot.QuestState.COMPLETED;
        if (careerEligible(state)) return NpcSurfaceSnapshot.QuestState.ACTIVE;
        if (state.invited || "APPLIED".equals(state.applicationState)) {
            return NpcSurfaceSnapshot.QuestState.AVAILABLE;
        }
        return NpcSurfaceSnapshot.QuestState.LOCKED;
    }

    private static String bounded(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }
}
