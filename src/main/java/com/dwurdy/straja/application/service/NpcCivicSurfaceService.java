package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase;
import com.dwurdy.straja.application.port.in.FineRoleplayUseCase;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Projects receptionist civic actions onto the same provider-neutral surface
 * used for admission. Complaint and fine services remain the authority for
 * identity, rank, deadlines, payment, and persisted legal state.
 */
public final class NpcCivicSurfaceService {
    private static final int MAX_ACTIONS = 64;
    private static final int MAX_INPUT_LENGTH = 512;
    private static final Pattern SAFE_RECORD_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    public NpcSurfaceSnapshot resolve(
            NpcSurfaceSnapshot published,
            List<ComplaintRoleplayUseCase.AvailableAction> complaints,
            List<FineRoleplayUseCase.AvailableAction> fines,
            ComplaintRoleplayUseCase.Limits complaintLimits,
            FineRoleplayUseCase.Limits fineLimits) {
        Objects.requireNonNull(published, "published");
        complaints = List.copyOf(Objects.requireNonNull(complaints, "complaints"));
        fines = List.copyOf(Objects.requireNonNull(fines, "fines"));
        Objects.requireNonNull(complaintLimits, "complaintLimits");
        Objects.requireNonNull(fineLimits, "fineLimits");

        Map<NpcContentId, NpcSurfaceAction> actions = new LinkedHashMap<>();
        for (NpcSurfaceAction action : published.actions()) {
            actions.put(action.actionId(), authored(action, complaints, complaintLimits));
        }
        for (ComplaintRoleplayUseCase.AvailableAction entry : complaints) {
            if (entry == null || entry.action() == null) continue;
            addComplaint(actions, entry, complaintLimits);
        }
        for (FineRoleplayUseCase.AvailableAction entry : fines) {
            if (entry == null || entry.action() == null) continue;
            addFine(actions, entry, fineLimits);
        }

        List<NpcSurfaceAction> result = List.copyOf(actions.values());
        boolean civicAvailable = !complaints.isEmpty() || !fines.isEmpty();
        List<NpcSurfaceSnapshot.QuestEntry> quests = published.quests().stream()
                .map(entry -> entry.questId().equals(NpcContentId.of("civic-accountability"))
                        ? new NpcSurfaceSnapshot.QuestEntry(
                                entry.questId(), entry.title(),
                                civicAvailable
                                        ? NpcSurfaceSnapshot.QuestState.ACTIVE
                                        : NpcSurfaceSnapshot.QuestState.LOCKED)
                        : entry)
                .toList();
        String body = bounded(published.body() + "\n\nCivic desk: " +
                (civicAvailable ? "current complaint and fine actions are available." :
                        "no civic action is currently available."), 8_192);
        return new NpcSurfaceSnapshot(
                published.binding(), published.profileId(), published.title(), body, result,
                published.dialogue().stream()
                        .map(node -> new NpcSurfaceSnapshot.DialogueNode(
                                node.nodeId(), node.text(), choices(result)))
                        .toList(),
                quests, published.requiredCapabilities(), published.optionalCapabilities());
    }

    private static NpcSurfaceAction authored(
            NpcSurfaceAction action,
            List<ComplaintRoleplayUseCase.AvailableAction> complaints,
            ComplaintRoleplayUseCase.Limits limits) {
        if (!"complaint-submit".equals(action.actionId().value())) return action;
        boolean enabled = complaints.stream().anyMatch(entry -> entry != null
                && entry.action() == ComplaintRoleplayUseCase.Action.SUBMIT);
        return new NpcSurfaceAction(
                action.actionId(), action.label(), enabled,
                enabled ? "" : "Complaint submission is not available at this desk.",
                List.of(
                        field("accused", "Accused player", 64, true),
                        field("category", "Category", 64, true),
                        field("description", "Description", limits.description(), true)));
    }

    private static void addComplaint(
            Map<NpcContentId, NpcSurfaceAction> actions,
            ComplaintRoleplayUseCase.AvailableAction entry,
            ComplaintRoleplayUseCase.Limits limits) {
        String id = entry.complaintId();
        switch (entry.action()) {
            case SUBMIT -> add(actions, "complaint-submit", "Submit complaint", List.of(
                    field("accused", "Accused player", 64, true),
                    field("category", "Category", 64, true),
                    field("description", "Description", limits.description(), true)));
            case CONFIRM -> add(actions, parameterized("complaint-confirm", id),
                    "Confirm complaint " + id, List.of());
            case WITHDRAW -> add(actions, parameterized("complaint-withdraw", id),
                    "Withdraw complaint " + id,
                    List.of(field("reason", "Reason", limits.withdrawalReason(), true)));
            case LIST, CLAIM, JOIN, LEAVE, REPORT, REVIEW -> { }
        }
    }

    private static void addFine(
            Map<NpcContentId, NpcSurfaceAction> actions,
            FineRoleplayUseCase.AvailableAction entry,
            FineRoleplayUseCase.Limits limits) {
        String id = entry.recordId();
        switch (entry.action()) {
            case PAY -> add(actions, parameterized("fine-pay", id), "Pay fine " + id, List.of());
            case REFUSE -> add(actions, parameterized("fine-refuse", id), "Refuse fine " + id, List.of());
            case APPEAL -> add(actions, parameterized("fine-appeal", id), "Appeal fine " + id,
                    List.of(field("reason", "Reason", limits.appealReason(), true)));
            case LIST_APPEALS -> add(actions, "fine-appeal-list", "View pending appeals", List.of());
            case REVIEW_APPEAL -> add(actions, parameterized("fine-appeal-review", id),
                    "Review appeal " + id,
                    List.of(field("decision", "Decision", 16, true),
                            field("reduced-amount", "Reduced amount", 12, false),
                            field("reason", "Reason", limits.reviewReason(), true)));
            case DRAFT_WRITE, DRAFT_STATUS, LIST_TASKS, ACCEPT_TASK, COMPLETE_TASK,
                    ARREST_TASK, CLAIM_TASK_REWARD, HEARING_WARRANT -> { }
        }
    }

    private static void add(
            Map<NpcContentId, NpcSurfaceAction> actions,
            String id,
            String label,
            List<NpcSurfaceAction.InputField> fields) {
        if (id == null || id.isBlank() || actions.size() >= MAX_ACTIONS) return;
        try {
            NpcContentId contentId = NpcContentId.of(id);
            NpcSurfaceAction current = actions.get(contentId);
            if (current == null || !current.enabled()) {
                List<NpcSurfaceAction.InputField> effectiveFields = fields.isEmpty() && current != null
                        ? current.inputs() : fields;
                actions.put(contentId, new NpcSurfaceAction(
                        contentId, bounded(label, 256), true, "", effectiveFields));
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed persisted record IDs never become provider actions.
        }
    }

    private static String parameterized(String operation, String recordId) {
        return recordId != null && SAFE_RECORD_ID.matcher(recordId).matches()
                ? operation + ":" + recordId : "";
    }

    private static NpcSurfaceAction.InputField field(
            String key, String label, int maxLength, boolean required) {
        return new NpcSurfaceAction.InputField(
                key, label, Math.min(MAX_INPUT_LENGTH, Math.max(1, maxLength)), required);
    }

    private static List<NpcSurfaceSnapshot.Choice> choices(List<NpcSurfaceAction> actions) {
        return actions.stream()
                .map(action -> new NpcSurfaceSnapshot.Choice(
                        action.actionId(), action.label(), action.enabled()))
                .toList();
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum - 3) + "...";
    }
}
