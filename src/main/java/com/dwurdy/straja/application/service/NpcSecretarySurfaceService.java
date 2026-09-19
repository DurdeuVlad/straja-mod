package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.in.AudienceUseCase;
import com.dwurdy.straja.application.port.in.AdminRoleplayUseCase;
import com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase;
import com.dwurdy.straja.application.port.in.FineRoleplayUseCase;
import com.dwurdy.straja.application.port.in.GuardDutyUseCase;
import com.dwurdy.straja.application.port.in.MissionRoleplayUseCase;
import com.dwurdy.straja.application.port.in.ReportUseCase;
import com.dwurdy.straja.application.port.in.RoleplayExpansionUseCase;
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
 * Projects secretary-authorized workflow projections onto the provider-neutral
 * NPC surface. It never evaluates rank or mutates documents; the inbound
 * roleplay ports remain the authority for availability and submission.
 */
public final class NpcSecretarySurfaceService {
    private static final int MAX_ACTIONS = 64;
    private static final int MAX_INPUT_LENGTH = 512;
    private static final Pattern SAFE_RECORD_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    public NpcSurfaceSnapshot resolve(NpcSurfaceSnapshot published, Inputs inputs) {
        Objects.requireNonNull(published, "published");
        Objects.requireNonNull(inputs, "inputs");
        Map<NpcContentId, NpcSurfaceAction> actions = new LinkedHashMap<>();
        Set<String> available = inputs.availableActionIds();
        for (NpcSurfaceAction action : published.actions()) {
            actions.put(action.actionId(), staticAction(action, available, inputs));
        }
        for (MissionRoleplayUseCase.AvailableAction entry : inputs.missions()) {
            if (entry == null || entry.action() == null) continue;
            addMission(actions, entry);
        }
        for (ComplaintRoleplayUseCase.AvailableAction entry : inputs.complaints()) {
            if (entry == null || entry.action() == null) continue;
            addComplaint(actions, entry, inputs.complaintLimits());
        }
        for (FineRoleplayUseCase.AvailableAction entry : inputs.fines()) {
            if (entry == null || entry.action() == null) continue;
            addFine(actions, entry, inputs.fineLimits());
        }
        for (ReportUseCase.AvailableAction entry : inputs.reports()) {
            if (entry == null || entry.action() == null) continue;
            addReport(actions, entry);
        }
        for (AudienceUseCase.AvailableAction entry : inputs.audiences()) {
            if (entry == null || entry.action() == null) continue;
            addAudience(actions, entry);
        }
        for (RoleplayExpansionUseCase.IncidentView incident : inputs.incidents()) {
            addIncident(actions, incident);
        }
        for (RoleplayExpansionUseCase.BoloView bolo : inputs.bolos()) {
            addBolo(actions, bolo);
        }
        for (AdminRoleplayUseCase.AvailableAction entry : inputs.administration()) {
            if (entry == null || entry.action() == null) continue;
            addAdministration(actions, entry);
        }
        addDuty(actions, inputs.duty());
        List<NpcSurfaceAction> result = List.copyOf(actions.values());
        List<NpcSurfaceSnapshot.DialogueNode> dialogue = published.dialogue().stream()
                .map(node -> new NpcSurfaceSnapshot.DialogueNode(
                        node.nodeId(), node.text(), choices(result)))
                .toList();
        String body = bounded(published.body() + "\n\nAvailable workflows: " + result.size()
                + ". Duty active: " + (inputs.duty() != null && inputs.duty().activeGuard()) + ".", 8_192);
        List<NpcSurfaceSnapshot.QuestEntry> quests = published.quests().stream()
                .map(entry -> new NpcSurfaceSnapshot.QuestEntry(
                        entry.questId(), entry.title(), NpcSurfaceSnapshot.QuestState.ACTIVE))
                .toList();
        return new NpcSurfaceSnapshot(
                published.binding(), published.profileId(), published.title(), body,
                result, dialogue, quests,
                published.requiredCapabilities(), published.optionalCapabilities());
    }

    private static NpcSurfaceAction staticAction(
            NpcSurfaceAction action, Set<String> available, Inputs inputs) {
        String id = action.actionId().value();
        List<NpcSurfaceAction.InputField> fields = switch (id) {
            case "mission-draft-write" -> List.of(
                    field("minutes", "Minutes", 5, true),
                    field("start", "Start time", 32, true),
                    field("reward", "Reward", 12, true),
                    field("objective", "Objective", 512, true));
            case "mission-draft-scope" -> List.of(
                    field("minimum-rank", "Minimum rank", 32, true),
                    field("max-assignees", "Maximum assignees", 5, true));
            case "mission-budget-adjust" -> List.of(
                    field("hours", "Hours", 12, true),
                    field("risk", "Risk", 12, true),
                    field("reward", "Reward", 12, true),
                    field("reason", "Reason", 240, true));
            case "fine-draft-write" -> List.of(
                    field("target", "Target player", 64, true),
                    field("amount", "Amount", 12, true),
                    field("law", "Law", inputs.fineLimits().law(), true),
                    field("description", "Description", inputs.fineLimits().description(), true));
            case "fine-warrant" -> List.of(
                    field("target", "Target player", 64, true),
                    field("details", "Details", inputs.fineLimits().warrantReason(), true));
            case "report-submit" -> List.of(
                    field("activity", "Activity", 512, true),
                    field("missions", "Missions", 512, true),
                    field("incidents", "Incidents", 512, true),
                    field("notes", "Notes", 512, true));
            case "audience-request" -> List.of(field("reason", "Reason", 512, true));
            case "bolo-create" -> List.of(
                    field("subject", "Subject", 64, true),
                    field("reason", "Reason", 240, true),
                    field("notes", "Notes", 500, false),
                    field("authority", "Authority", 120, true),
                    field("incident-id", "Incident ID", 80, false));
            case "incident-resolve" -> List.of(
                    field("resolution", "Resolution", 500, true),
                    field("notes", "Notes", 500, false));
            case "admin-authorize" -> List.of(
                    field("name", "Player name", 64, true),
                    field("rank", "Rank", 2, true));
            case "admin-policy-set" -> List.of(
                    field("key", "Policy key", 128, true),
                    field("value", "Policy value", 512, true));
            case "admin-emergency-alert" -> List.of(field("message", "Alert", 512, true));
            case "admin-emergency-start" -> List.of(
                    field("multiplier", "Multiplier", 16, true),
                    field("rounds", "Rounds", 8, true),
                    field("reason", "Reason", 240, true));
            default -> action.inputs();
        };
        boolean enabled = switch (id) {
            case "mission-list", "mission-carnet", "guard-status", "incident-list",
                    "duty-roster", "bolo-list", "complaint-list", "fine-list" -> true;
            case "report-status" -> available.contains("report-status");
            case "audience-status" -> available.contains("audience-status");
            default -> available.contains(id);
        };
        return new NpcSurfaceAction(action.actionId(), action.label(), enabled,
                enabled ? "" : "This workflow is not available in your current record state.", fields);
    }

    private static void addMission(
            Map<NpcContentId, NpcSurfaceAction> actions,
            MissionRoleplayUseCase.AvailableAction entry) {
        String id = entry.missionId();
        switch (entry.action()) {
            case GET_CARNET -> add(actions, "mission-carnet", "Request order ledger");
            case DRAFT_WRITE -> add(actions, "mission-draft-write", "Write new order");
            case DRAFT_STATUS -> add(actions, "mission-draft-status", "View order status");
            case DRAFT_SCOPE -> add(actions, "mission-draft-scope", "Set order participants");
            case DRAFT_SIGN -> add(actions, "mission-draft-sign", "Sign order");
            case DRAFT_PACKAGE -> add(actions, "mission-draft-package", "Seal order");
            case TEMPLATE_LIST -> add(actions, "mission-template-list", "View mission templates");
            case ADJUST_BUDGET -> add(actions, "mission-budget-adjust", "Adjust order budget");
            case ISSUE_TEMPLATE -> add(actions, parameterized("mission-template-issue", id), "Issue template order");
            case JOIN -> add(actions, parameterized("mission-join", id), "Join mission " + id);
            case ACCEPT -> add(actions, parameterized("mission-accept", id), "Accept mission " + id);
            case DECLINE -> add(actions, parameterized("mission-decline", id), "Decline mission " + id);
            case REPORT -> add(actions, parameterized("mission-report", id), "Submit mission report",
                    List.of(field("report", "Report", 512, true)));
            case FAIL -> add(actions, parameterized("mission-fail", id), "End mission " + id,
                    List.of(field("reason", "Reason", 240, true)));
            case COMPLETE -> add(actions, parameterized("mission-complete", id), "Complete mission " + id);
            case CLAIM_REWARD -> add(actions, parameterized("mission-reward", id), "Claim mission reward");
            case RECOVER_REWARD -> add(actions, parameterized("mission-reward-recover", id), "Recover mission reward");
        }
    }

    private static void addComplaint(
            Map<NpcContentId, NpcSurfaceAction> actions,
            ComplaintRoleplayUseCase.AvailableAction entry,
            ComplaintRoleplayUseCase.Limits limits) {
        String id = entry.complaintId();
        switch (entry.action()) {
            case LIST -> add(actions, "complaint-list", "View complaint register");
            case CLAIM -> add(actions, parameterized("complaint-claim", id), "Claim complaint " + id);
            case JOIN -> add(actions, parameterized("complaint-join", id), "Join complaint " + id);
            case LEAVE -> add(actions, parameterized("complaint-leave", id), "Leave complaint " + id);
            case REPORT -> add(actions, parameterized("complaint-report", id), "Report complaint " + id,
                    List.of(field("report", "Report", limits.evidence(), true)));
            case WITHDRAW -> add(actions, parameterized("complaint-withdraw", id), "Withdraw complaint " + id,
                    List.of(field("reason", "Reason", limits.withdrawalReason(), true)));
            case REVIEW -> add(actions, parameterized("complaint-review", id), "Review complaint " + id,
                    List.of(field("decision", "Decision", 32, true), field("reward", "Reward", 12, true)));
            case SUBMIT, CONFIRM -> { }
        }
    }

    private static void addFine(
            Map<NpcContentId, NpcSurfaceAction> actions,
            FineRoleplayUseCase.AvailableAction entry,
            FineRoleplayUseCase.Limits limits) {
        String id = entry.recordId();
        switch (entry.action()) {
            case DRAFT_WRITE -> add(actions, "fine-draft-write", "Write fine draft");
            case DRAFT_STATUS -> add(actions, "fine-draft-status", "View fine draft");
            case LIST_TASKS -> add(actions, "fine-task-list", "View fine tasks");
            case ACCEPT_TASK -> add(actions, parameterized("fine-task-accept", id), "Accept fine task " + id);
            case COMPLETE_TASK -> add(actions, parameterized("fine-task-complete", id), "Complete fine task " + id);
            case ARREST_TASK -> add(actions, parameterized("fine-task-arrest", id), "Execute fine arrest " + id);
            case CLAIM_TASK_REWARD -> add(actions, parameterized("fine-task-reward", id), "Claim fine reward " + id);
            case HEARING_WARRANT -> add(actions, "fine-warrant", "Issue hearing warrant");
            case PAY, REFUSE, APPEAL, LIST_APPEALS, REVIEW_APPEAL -> { }
        }
    }

    private static void addReport(
            Map<NpcContentId, NpcSurfaceAction> actions,
            ReportUseCase.AvailableAction entry) {
        String id = entry.reportId();
        switch (entry.action()) {
            case SUBMIT -> add(actions, "report-submit", "Submit activity report");
            case STATUS -> add(actions, "report-status", "View activity report");
            case REVIEW_LIST -> add(actions, "report-review-list", "Review pending reports");
            case REVIEW -> add(actions, parameterized("report-review", id), "Review report " + id,
                    List.of(field("decision", "Decision", 16, true), field("note", "Note", 500, false)));
        }
    }

    private static void addAudience(
            Map<NpcContentId, NpcSurfaceAction> actions,
            AudienceUseCase.AvailableAction entry) {
        String id = entry.requestId();
        switch (entry.action()) {
            case REQUEST -> add(actions, "audience-request", "Request commissioner audience");
            case STATUS -> add(actions, "audience-status", "View audience request");
            case REVIEW_LIST -> add(actions, "audience-review-list", "Review audience requests");
            case REVIEW -> add(actions, parameterized("audience-review", id), "Review audience request " + id,
                    List.of(field("decision", "Decision", 16, true), field("note", "Note", 500, false)));
        }
    }

    private static void addIncident(
            Map<NpcContentId, NpcSurfaceAction> actions,
            RoleplayExpansionUseCase.IncidentView incident) {
        if (incident == null) return;
        String id = incident.id();
        if ("OPEN".equals(incident.status())) {
            add(actions, parameterized("incident-accept", id), "Accept incident " + id);
        }
        add(actions, parameterized("incident-join", id), "Join incident " + id);
        add(actions, parameterized("incident-resolve", id), "Resolve incident " + id,
                List.of(field("resolution", "Resolution", 500, true),
                        field("notes", "Notes", 500, false)));
    }

    private static void addBolo(
            Map<NpcContentId, NpcSurfaceAction> actions,
            RoleplayExpansionUseCase.BoloView bolo) {
        if (bolo == null) return;
        add(actions, parameterized("bolo-cancel", bolo.id()), "Cancel BOLO " + bolo.id());
    }

    private static void addAdministration(
            Map<NpcContentId, NpcSurfaceAction> actions,
            AdminRoleplayUseCase.AvailableAction entry) {
        String id = entry.memberId();
        String name = bounded(entry.memberName() == null ? id : entry.memberName(), 120);
        switch (entry.action()) {
            case PERSONNEL -> add(actions, "admin-personnel", "View Straja personnel");
            case ROSTER_ACTIVE -> add(actions, "admin-roster", "View active roster");
            case DOSSIER -> add(actions, parameterized("admin-dossier", id), "Open dossier " + name);
            case AUTHORIZE -> add(actions, "admin-authorize", "Authorize member");
            case PROMOTE -> add(actions, parameterized("admin-promote", id), "Promote " + name);
            case DEMOTE -> add(actions, parameterized("admin-demote", id), "Demote " + name);
            case SUSPEND -> add(actions, parameterized("admin-suspend", id), "Suspend " + name);
            case FIRE -> add(actions, parameterized("admin-fire", id), "Revoke " + name);
            case REINSTATE -> add(actions, parameterized("admin-reinstate", id), "Reinstate " + name);
            case POLICIES -> add(actions, "admin-policies", "View live policies");
            case POLICY_SET -> add(actions, "admin-policy-set", "Set live policy");
            case EMERGENCY_STATUS -> add(actions, "admin-emergency-status", "View emergency status");
            case EMERGENCY_ALERT -> add(actions, "admin-emergency-alert", "Send emergency alert");
            case EMERGENCY_START -> add(actions, "admin-emergency-start", "Start emergency state");
            case EMERGENCY_END -> add(actions, "admin-emergency-end", "End emergency state");
        }
    }

    private static void addDuty(
            Map<NpcContentId, NpcSurfaceAction> actions, GuardDutyUseCase.DutyView view) {
        if (view == null) return;
        if (view.canStart()) add(actions, "duty-start", "Start duty");
        if (view.checkpointId() != null && !view.checkpointId().isBlank()) {
            add(actions, parameterized("duty-checkpoint", view.checkpointId()), "Activate checkpoint");
        }
        if (view.canStop()) add(actions, "duty-stop", "End duty");
        if (view.canClaimSalary()) add(actions, "duty-salary", "Claim salary");
        if (view.canViewCoins()) add(actions, "duty-coins", "View coins");
        if (view.canClaimFood()) add(actions, "duty-food", "Claim duty food");
        if (view.canClaimKit()) add(actions, "duty-kit", "Claim duty kit");
        if (view.canBeginResignation()) add(actions, "resignation-start", "Begin resignation");
        if (view.canConfirmResignation()) add(actions, "resignation-confirm", "Confirm resignation");
        if (view.canCancelResignation()) add(actions, "resignation-cancel", "Cancel resignation");
        if (view.canRejoin()) add(actions, "rejoin", "Rejoin Straja");
    }

    private static void add(Map<NpcContentId, NpcSurfaceAction> actions, String id, String label) {
        add(actions, id, label, List.of());
    }

    private static void add(
            Map<NpcContentId, NpcSurfaceAction> actions, String id, String label,
            List<NpcSurfaceAction.InputField> fields) {
        if (id == null || id.isBlank() || actions.size() >= MAX_ACTIONS) return;
        try {
            NpcContentId contentId = NpcContentId.of(id);
            NpcSurfaceAction current = actions.get(contentId);
            if (current == null || !current.enabled()) {
                List<NpcSurfaceAction.InputField> effectiveFields = fields.isEmpty() && current != null
                        ? current.inputs()
                        : fields;
                actions.put(contentId, new NpcSurfaceAction(
                        contentId, bounded(label, 256), true, "", effectiveFields));
            }
        } catch (IllegalArgumentException ignored) {
            // Provider-facing projections drop malformed persisted record IDs.
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

    private static String bounded(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    public record Inputs(
            List<MissionRoleplayUseCase.AvailableAction> missions,
            List<ComplaintRoleplayUseCase.AvailableAction> complaints,
            List<FineRoleplayUseCase.AvailableAction> fines,
            List<ReportUseCase.AvailableAction> reports,
            List<AudienceUseCase.AvailableAction> audiences,
            List<RoleplayExpansionUseCase.IncidentView> incidents,
            List<RoleplayExpansionUseCase.BoloView> bolos,
            List<AdminRoleplayUseCase.AvailableAction> administration,
            GuardDutyUseCase.DutyView duty,
            ComplaintRoleplayUseCase.Limits complaintLimits,
            FineRoleplayUseCase.Limits fineLimits) {
        public Inputs {
            missions = List.copyOf(Objects.requireNonNull(missions, "missions"));
            complaints = List.copyOf(Objects.requireNonNull(complaints, "complaints"));
            fines = List.copyOf(Objects.requireNonNull(fines, "fines"));
            reports = List.copyOf(Objects.requireNonNull(reports, "reports"));
            audiences = List.copyOf(Objects.requireNonNull(audiences, "audiences"));
            incidents = List.copyOf(Objects.requireNonNull(incidents, "incidents"));
            bolos = List.copyOf(Objects.requireNonNull(bolos, "bolos"));
            administration = List.copyOf(Objects.requireNonNull(administration, "administration"));
            Objects.requireNonNull(complaintLimits, "complaintLimits");
            Objects.requireNonNull(fineLimits, "fineLimits");
        }

        private Set<String> availableActionIds() {
            Set<String> ids = new java.util.HashSet<>();
            missions.forEach(entry -> { if (entry != null && entry.action() != null) ids.add(missionId(entry.action())); });
            complaints.forEach(entry -> { if (entry != null && entry.action() != null) ids.add(complaintId(entry.action())); });
            fines.forEach(entry -> { if (entry != null && entry.action() != null) ids.add(fineId(entry.action())); });
            reports.forEach(entry -> { if (entry != null && entry.action() != null) ids.add(reportId(entry.action())); });
            audiences.forEach(entry -> { if (entry != null && entry.action() != null) ids.add(audienceId(entry.action())); });
            administration.forEach(entry -> { if (entry != null && entry.action() != null) ids.add(adminId(entry.action())); });
            return ids;
        }

        private static String missionId(MissionRoleplayUseCase.Action action) {
            return switch (action) {
                case GET_CARNET -> "mission-carnet";
                case DRAFT_WRITE -> "mission-draft-write";
                case DRAFT_STATUS -> "mission-draft-status";
                case DRAFT_SCOPE -> "mission-draft-scope";
                case DRAFT_SIGN -> "mission-draft-sign";
                case DRAFT_PACKAGE -> "mission-draft-package";
                case TEMPLATE_LIST -> "mission-template-list";
                case ADJUST_BUDGET -> "mission-budget-adjust";
                default -> "";
            };
        }

        private static String complaintId(ComplaintRoleplayUseCase.Action action) {
            return action == ComplaintRoleplayUseCase.Action.LIST ? "complaint-list" : "";
        }

        private static String fineId(FineRoleplayUseCase.Action action) {
            return switch (action) {
                case DRAFT_WRITE -> "fine-draft-write";
                case DRAFT_STATUS -> "fine-draft-status";
                case LIST_TASKS -> "fine-task-list";
                case HEARING_WARRANT -> "fine-warrant";
                default -> "";
            };
        }

        private static String reportId(ReportUseCase.Action action) {
            return switch (action) {
                case SUBMIT -> "report-submit";
                case STATUS -> "report-status";
                case REVIEW_LIST -> "report-review-list";
                case REVIEW -> "";
            };
        }

        private static String audienceId(AudienceUseCase.Action action) {
            return switch (action) {
                case REQUEST -> "audience-request";
                case STATUS -> "audience-status";
                case REVIEW_LIST -> "audience-review-list";
                case REVIEW -> "";
            };
        }

        private static String adminId(AdminRoleplayUseCase.Action action) {
            return switch (action) {
                case PERSONNEL -> "admin-personnel";
                case ROSTER_ACTIVE -> "admin-roster";
                case AUTHORIZE -> "admin-authorize";
                case POLICIES -> "admin-policies";
                case POLICY_SET -> "admin-policy-set";
                case EMERGENCY_STATUS -> "admin-emergency-status";
                case EMERGENCY_ALERT -> "admin-emergency-alert";
                case EMERGENCY_START -> "admin-emergency-start";
                case EMERGENCY_END -> "admin-emergency-end";
                default -> "";
            };
        }
    }
}
