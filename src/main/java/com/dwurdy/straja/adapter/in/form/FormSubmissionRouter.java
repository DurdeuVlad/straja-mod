package com.dwurdy.straja.adapter.in.form;

import com.dwurdy.straja.domain.model.FormField;

import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;

import static com.dwurdy.straja.adapter.in.StrajaText.refusal;
import com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase;
import com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase;
import com.dwurdy.straja.application.port.in.FineRoleplayUseCase;
import com.dwurdy.straja.application.port.in.FormSessionUseCase;
import com.dwurdy.straja.application.port.in.GuardRecruitmentUseCase;
import com.dwurdy.straja.application.port.in.MissionRoleplayUseCase;
import net.minecraft.server.level.ServerPlayer;

/**
 * Routes consumed form submissions to the owning inbound use case. The
 * authenticated server player is wrapped in a player gateway; the trusted
 * record binding arrives inside the submission, never from the client.
 */
public final class FormSubmissionRouter {
    private final GuardRecruitmentUseCase guards;
    private final MissionRoleplayUseCase missions;
    private final ComplaintRoleplayUseCase complaints;
    private final FineRoleplayUseCase fines;
    private final ArchiveRoleplayUseCase archive;
    private final com.dwurdy.straja.application.port.in.ReportUseCase reports;
    private final com.dwurdy.straja.application.port.in.AudienceUseCase audiences;
    private final com.dwurdy.straja.application.port.in.AdminRoleplayUseCase admin;
    private final com.dwurdy.straja.application.port.in.AdminToolsUseCase adminTools;
    private final com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase custody;
    private final com.dwurdy.straja.application.port.in.RoleplayExpansionUseCase expansion;
    private final com.dwurdy.straja.application.port.in.PromotionV2UseCase v2Promotions;
    private final com.dwurdy.straja.application.port.in.JoinRequestUseCase v2JoinRequests;

    public FormSubmissionRouter(GuardRecruitmentUseCase guards, MissionRoleplayUseCase missions,
            ComplaintRoleplayUseCase complaints, FineRoleplayUseCase fines,
            ArchiveRoleplayUseCase archive,
            com.dwurdy.straja.application.port.in.ReportUseCase reports,
            com.dwurdy.straja.application.port.in.AudienceUseCase audiences,
            com.dwurdy.straja.application.port.in.AdminRoleplayUseCase admin,
            com.dwurdy.straja.application.port.in.AdminToolsUseCase adminTools,
            com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase custody,
            com.dwurdy.straja.application.port.in.RoleplayExpansionUseCase expansion,
            com.dwurdy.straja.application.port.in.PromotionV2UseCase v2Promotions,
            com.dwurdy.straja.application.port.in.JoinRequestUseCase v2JoinRequests) {
        this.guards = guards;
        this.missions = missions;
        this.complaints = complaints;
        this.fines = fines;
        this.archive = archive;
        this.reports = reports;
        this.audiences = audiences;
        this.admin = admin;
        this.adminTools = adminTools;
        this.custody = custody;
        this.expansion = expansion;
        this.v2Promotions = v2Promotions;
        this.v2JoinRequests = v2JoinRequests;
    }

    public void submit(ServerPlayer player, FormSessionUseCase.Submission submission) {
        if (player == null || submission == null) return;
        var gateway = new MinecraftPlayerGateway(player.getServer(), player.getUUID());
        var values = submission.values();
        switch (submission.action()) {
            case QUIZ_ANSWER ->
                    guards.answerQuiz(gateway, submission.recordId(), values.get(FormField.ANSWER));
            case FACTION_DECLARE ->
                    guards.declareNativeFaction(gateway, values.get(FormField.FACTION));
            case MISSION_REPORT ->
                    missions.report(gateway, submission.recordId(), values.get(FormField.REPORT));
            case MISSION_FAIL ->
                    missions.fail(gateway, submission.recordId(), values.get(FormField.REASON));
            case MISSION_DRAFT_WRITE -> {
                Integer minutes = parseInt(values.get(FormField.MINUTES));
                Integer reward = parseInt(values.get(FormField.REWARD));
                if (minutes == null || reward == null) {
                    player.sendSystemMessage(refusal("straja.form.order_invalid", "straja.remedy.fix_retry"));
                    return;
                }
                Integer xp = parseInt(values.get(FormField.XP));
                missions.draftWrite(gateway, minutes, values.get(FormField.START), reward,
                        xp == null ? -1 : xp, values.get(FormField.OBJECTIVE));
            }
            case MISSION_INVITE ->
                    missions.inviteByName(gateway, submission.recordId(), values.get(FormField.SUBJECT));
            case MISSION_DRAFT_SCOPE -> {
                Integer maxAssignees = parseInt(values.get(FormField.MAX_ASSIGNEES));
                if (maxAssignees == null) {
                    player.sendSystemMessage(refusal("straja.form.order_invalid", "straja.remedy.fix_retry"));
                    return;
                }
                String openRaw = values.get(FormField.OPEN);
                missions.draftScope(gateway, values.get(FormField.MINIMUM_RANK), maxAssignees,
                        openRaw == null || openRaw.isBlank() ? null : truthy(openRaw));
            }
            case MISSION_BUDGET_ADJUST ->
                    missions.draftAdjust(gateway, values.get(FormField.HOURS), values.get(FormField.RISK),
                            values.get(FormField.REWARD), values.get(FormField.REASON));
            case COMPLAINT_SUBMIT ->
                    complaints.submit(gateway, values.get(FormField.ACCUSED), values.get(FormField.CATEGORY),
                            values.get(FormField.DESCRIPTION));
            case COMPLAINT_REPORT ->
                    complaints.report(gateway, submission.recordId(), values.get(FormField.REPORT));
            case COMPLAINT_WITHDRAW ->
                    complaints.withdraw(gateway, submission.recordId(), values.get(FormField.REASON));
            case COMPLAINT_REVIEW -> {
                Integer reward = parseInt(values.get(FormField.REWARD));
                if (reward == null) {
                    player.sendSystemMessage(refusal("straja.form.verify_invalid", "straja.remedy.fix_retry"));
                    return;
                }
                complaints.review(gateway, submission.recordId(), values.get(FormField.DECISION), reward);
            }
            case FINE_DRAFT -> {
                Integer amount = parseInt(values.get(FormField.AMOUNT));
                if (amount == null) {
                    player.sendSystemMessage(refusal("straja.form.fine_invalid", "straja.remedy.fix_retry"));
                    return;
                }
                fines.writeDraft(gateway, values.get(FormField.TARGET), amount,
                        values.get(FormField.LAW), values.get(FormField.DESCRIPTION));
            }
            case FINE_APPEAL ->
                    fines.appeal(gateway, submission.recordId(), values.get(FormField.REASON));
            case FINE_APPEAL_REVIEW -> {
                String decision = values.get(FormField.DECISION);
                Integer reduced = parseInt(values.get(FormField.REDUCED_AMOUNT));
                boolean reduce = com.dwurdy.straja.domain.model.DecisionWords.is(
                        decision, com.dwurdy.straja.domain.model.DecisionWords.FINE_REDUCE);
                if (reduce && reduced == null) {
                    player.sendSystemMessage(refusal("straja.form.appeal_invalid", "straja.remedy.fix_retry"));
                    return;
                }
                fines.reviewAppeal(gateway, submission.recordId(), decision,
                        reduce ? reduced : Integer.valueOf(0), values.get(FormField.REASON));
            }
            case FINE_WARRANT ->
                    fines.issueHearingWarrant(gateway, values.get(FormField.TARGET), values.get(FormField.DETAILS));
            case ARCHIVE_FOLDER_CREATE ->
                    archive.createFolder(gateway, values.get(FormField.TITLE), values.get(FormField.DEPARTMENT));
            case ARCHIVE_FOLDER_ISSUE ->
                    archive.issueFolder(gateway, submission.recordId(), values.get(FormField.TARGET));
            case ARCHIVE_SHEET_NEW ->
                    archive.newSheet(gateway, submission.recordId(), values.get(FormField.TYPE),
                            values.get(FormField.TITLE));
            case ARCHIVE_SHEET_EDIT ->
                    archive.editSheet(gateway, submission.recordId(), values.get(FormField.CONTENT));
            case ARCHIVE_RECIPIENTS ->
                    archive.setRecipients(gateway, submission.recordId(), values.get(FormField.RECIPIENTS));
            case ARCHIVE_SIGN ->
                    archive.signSheet(gateway, submission.recordId(), values.get(FormField.REASON));
            case ARCHIVE_COPY -> {
                Integer count = parseInt(values.get(FormField.COUNT));
                if (count == null) {
                    player.sendSystemMessage(refusal("straja.form.copy_invalid", "straja.remedy.fix_retry"));
                    return;
                }
                archive.copySheet(gateway, submission.recordId(), count, values.get(FormField.TARGETS));
            }
            case ARCHIVE_ENVELOPE ->
                    archive.packEnvelope(gateway, submission.recordId(), values.get(FormField.TARGET));
            case ARCHIVE_DOCUMENT_ISSUE ->
                    archive.issueDocument(gateway, submission.recordId(), values.get(FormField.TARGET));
            case REPORT_SUBMIT ->
                    reports.submit(gateway, values.get(FormField.ACTIVITY), values.get(FormField.MISSIONS),
                            values.get(FormField.INCIDENTS), values.get(FormField.NOTES));
            case REPORT_REVIEW ->
                    reports.review(gateway, submission.recordId(), values.get(FormField.DECISION),
                            values.get(FormField.NOTE));
            case AUDIENCE_REQUEST ->
                    audiences.request(gateway, values.get(FormField.REASON));
            case AUDIENCE_REVIEW ->
                    audiences.resolve(gateway, submission.recordId(), values.get(FormField.DECISION),
                            values.get(FormField.NOTE));
            case PETITION_REQUEST ->
                    guards.requestPetition(gateway, values.get(FormField.REASON));
            case PETITION_REVIEW -> {
                // recordId carries "<appId>_<version>" from the review form —
                // the version the reviewer actually read. Provider surfaces
                // pass the bare id, which resolves the current version.
                var ref = com.dwurdy.straja.domain.model.ReviewRef.parse(submission.recordId());
                String appId = ref.id();
                Long expected = ref.version();
                var application = v2Promotions.find(appId);
                if (application == null || application.status
                        != com.dwurdy.straja.domain.model.PromotionStatus.READY_FOR_APPROVAL) {
                    player.sendSystemMessage(refusal("straja.petition.gone", "straja.remedy.retry"));
                    return;
                }
                if (expected != null && expected.longValue() != application.version) {
                    player.sendSystemMessage(refusal("straja.petition.gone", "straja.remedy.retry"));
                    return;
                }
                boolean approve = com.dwurdy.straja.domain.model.DecisionWords.is(
                        values.get(FormField.DECISION), com.dwurdy.straja.domain.model.DecisionWords.APPROVE);
                boolean deny = com.dwurdy.straja.domain.model.DecisionWords.is(
                        values.get(FormField.DECISION), com.dwurdy.straja.domain.model.DecisionWords.DENY);
                if (!approve && !deny) {
                    player.sendSystemMessage(refusal("straja.petition.bad_decision", "straja.remedy.fix_retry"));
                    return;
                }
                String note = values.getOrDefault(FormField.NOTE, "");
                try {
                    if (approve) {
                        v2Promotions.approve(gateway.uuid().toString(),
                                application.applicationId, application.version);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "Cererea " + application.applicationId + " a fost aprobată."));
                    } else {
                        v2Promotions.reject(gateway.uuid().toString(),
                                application.applicationId, note);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "Cererea " + application.applicationId + " a fost respinsă."));
                    }
                } catch (IllegalStateException denied) {
                    player.sendSystemMessage(refusal("straja.petition.denied", "straja.remedy.retry"));
                    return;
                }
                // Tell the subject immediately when they are online; the
                // login sweep in deliverOutcome covers offline members.
                try {
                    var subject = java.util.UUID.fromString(application.subjectUuid);
                    if (player.getServer().getPlayerList().getPlayer(subject) != null) {
                        v2Promotions.deliverOutcome(
                                new MinecraftPlayerGateway(player.getServer(), subject));
                    }
                } catch (IllegalArgumentException ignored) {}
            }
            case JOIN_REQUEST -> {
                // recordId carries the profession chosen on the trades list —
                // the service revalidates it against the configured set.
                try {
                    var request = v2JoinRequests.submit(gateway.uuid().toString(),
                            submission.recordId(), values.get(FormField.REASON));
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "straja.join.submitted", request.requestId));
                } catch (RuntimeException refused) {
                    player.sendSystemMessage(refusal("straja.join.refused", "straja.remedy.retry"));
                }
            }
            case JOIN_REVIEW -> {
                // recordId carries "<requestId>_<version>" — the version the
                // reviewer actually read; a changed petition fails closed.
                var ref = com.dwurdy.straja.domain.model.ReviewRef.parse(submission.recordId());
                String requestId = ref.id();
                Long expected = ref.version();
                var request = v2JoinRequests.find(requestId);
                if (request == null || request.status
                        != com.dwurdy.straja.domain.model.JoinRequestStatus.PENDING) {
                    player.sendSystemMessage(refusal("straja.join.gone", "straja.remedy.retry"));
                    return;
                }
                if (expected != null && expected.longValue() != request.version) {
                    player.sendSystemMessage(refusal("straja.join.gone", "straja.remedy.retry"));
                    return;
                }
                boolean approve = com.dwurdy.straja.domain.model.DecisionWords.is(
                        values.get(FormField.DECISION), com.dwurdy.straja.domain.model.DecisionWords.APPROVE);
                boolean deny = com.dwurdy.straja.domain.model.DecisionWords.is(
                        values.get(FormField.DECISION), com.dwurdy.straja.domain.model.DecisionWords.DENY);
                if (!approve && !deny) {
                    player.sendSystemMessage(refusal("straja.petition.bad_decision", "straja.remedy.fix_retry"));
                    return;
                }
                String note = values.getOrDefault(FormField.NOTE, "");
                try {
                    if (approve) {
                        v2JoinRequests.approve(gateway.uuid().toString(),
                                request.requestId, request.version);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "Cererea " + request.requestId + " a fost aprobată."));
                    } else {
                        v2JoinRequests.reject(gateway.uuid().toString(),
                                request.requestId, request.version, note);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                "Cererea " + request.requestId + " a fost respinsă."));
                    }
                } catch (IllegalStateException denied) {
                    player.sendSystemMessage(refusal("straja.petition.denied", "straja.remedy.retry"));
                    return;
                }
                // Tell the subject immediately when they are online; the
                // login sweep in deliverOutcome covers offline members.
                try {
                    var subject = java.util.UUID.fromString(request.subjectUuid);
                    if (player.getServer().getPlayerList().getPlayer(subject) != null) {
                        v2JoinRequests.deliverOutcome(
                                new MinecraftPlayerGateway(player.getServer(), subject));
                    }
                } catch (IllegalArgumentException ignored) {}
            }
            case ADMIN_AUTHORIZE -> {
                String rankOrGrade = values.get(FormField.RANK);
                if (rankOrGrade == null || rankOrGrade.isBlank()) {
                    player.sendSystemMessage(refusal("straja.form.rank_missing", "straja.remedy.fix_retry"));
                    return;
                }
                admin.authorize(gateway, values.get(FormField.NAME), rankOrGrade);
            }
            case ADMIN_POLICY_SET ->
                    admin.policySet(gateway, values.get(FormField.KEY), values.get(FormField.VALUE));
            case ADMIN_EMERGENCY_ALERT ->
                    admin.emergencyAlert(gateway, values.get(FormField.MESSAGE));
            case ADMIN_EMERGENCY_START -> {
                Double multiplier = parseDouble(values.get(FormField.MULTIPLIER));
                Integer rounds = parseInt(values.get(FormField.ROUNDS));
                admin.emergencyStart(gateway, multiplier, rounds, values.get(FormField.REASON));
            }
            case TOOL_NPC_NAME -> {
                adminTools.npcRename(gateway, submission.recordId(), values.get(FormField.NAME));
                reflectNpc(player, submission.recordId(), true);
            }
            case TOOL_NPC_SKIN -> {
                adminTools.npcSetSkin(gateway, submission.recordId(), values.get(FormField.SKIN));
                reflectNpc(player, submission.recordId(), false);
            }
            case GIVE_UP -> {
                if (!custody.giveUp(gateway, true)) {
                    player.sendSystemMessage(refusal(
                            "straja.give_up.stale", "straja.remedy.retry"));
                }
            }
            case INCIDENT_REPORT ->
                    expansion.reportIncident(gateway, values.get(FormField.CATEGORY), values.get(FormField.DESCRIPTION));
            case BOLO_CREATE ->
                    expansion.createBolo(gateway, values.get(FormField.SUBJECT), values.get(FormField.REASON),
                            values.get(FormField.NOTES), values.get(FormField.AUTHORITY),
                            values.get(FormField.INCIDENT_ID));
            case INCIDENT_RESOLVE ->
                    expansion.resolveIncident(gateway, submission.recordId(),
                            values.get(FormField.RESOLUTION), values.get(FormField.NOTES));
            case EVIDENCE_CONFISCATE -> {
                Integer slot = parseInt(values.get(FormField.SLOT));
                Integer amount = parseInt(values.get(FormField.AMOUNT));
                if (slot == null || amount == null) {
                    player.sendSystemMessage(refusal("straja.form.slot_not_numbers", "straja.remedy.fix_retry"));
                    return;
                }
                expansion.confiscate(gateway, submission.recordId(), slot, amount,
                        values.get(FormField.REASON), values.get(FormField.INCIDENT_ID));
            }
            case EVIDENCE_TRANSFER ->
                    expansion.transferEvidence(gateway, submission.recordId(),
                            values.get(FormField.CUSTODIAN), values.get(FormField.REASON));
            case EVIDENCE_DESTROY ->
                    expansion.destroyEvidence(gateway, submission.recordId(), values.get(FormField.REASON));
            case EVIDENCE_CASE_VIEW -> {
                var records = com.dwurdy.straja.bootstrap.StrajaRuntime.get()
                        .evidence().recordsForCase(gateway, values.get(FormField.CASE_ID));
                if (records.isEmpty()) gateway.refuse("straja.form.dossier_evidence", "straja.remedy.archivist");
                else for (var evidence : records) {
                    gateway.tell(evidence.id + " — " + evidence.itemId + " x" + evidence.amount
                            + " — " + evidence.status);
                }
            }
            case ARREST_HANDOFF ->
                    expansion.finalizeArrest(gateway, values.get(FormField.DETAINEE),
                            values.get(FormField.SENTENCE_ID), values.get(FormField.NOTES));
            case REPUTATION_VIEW -> {
                var history = expansion.reputationHistory(gateway, values.get(FormField.SUBJECT));
                if (history.isEmpty()) {
                    gateway.refuse("straja.form.subject_history", "straja.remedy.retry");
                } else {
                    for (var event : history) {
                        gateway.tell(event.id() + " " + event.source() + " delta=" + event.delta()
                                + " scor=" + event.scoreBefore() + "→" + event.scoreAfter()
                                + " — " + event.reason());
                    }
                }
            }
            case REPUTATION_CORRECTION -> {
                Integer delta = parseInt(values.get(FormField.DELTA));
                if (delta == null) {
                    player.sendSystemMessage(refusal("straja.form.reputation_not_number", "straja.remedy.fix_retry"));
                    return;
                }
                expansion.correctReputation(gateway, values.get(FormField.SUBJECT), delta, values.get(FormField.REASON));
            }
            default -> {}
        }
    }

    /** Reflects a persisted registry change onto the live entity when loaded. */
    private static void reflectNpc(ServerPlayer player, String entityUuid, boolean name) {
        try {
            var uuid = java.util.UUID.fromString(entityUuid);
            for (var level : player.getServer().getAllLevels()) {
                if (level.getEntity(uuid) instanceof com.dwurdy.straja.adapter.in.npc.StrajaNpcEntity npc) {
                    var registration = com.dwurdy.straja.bootstrap.StrajaRuntime.get()
                            .npcRegistry().registration(entityUuid);
                    if (registration == null) return;
                    if (name) {
                        // A null custom name clears the nameplate — an empty
                        // literal would leave a blank name floating.
                        String display = registration.displayName();
                        npc.setCustomName(display == null || display.isEmpty() ? null
                                : net.minecraft.network.chat.Component.literal(display));
                    } else {
                        // setSkin maps null back to the "default" skin.
                        npc.setSkin(registration.skin());
                    }
                    return;
                }
            }
        } catch (IllegalArgumentException ignored) {}
    }

    private static boolean truthy(String raw) {
        return switch (raw == null ? "" : raw.trim().toLowerCase()) {
            case "da", "yes", "true", "1", "y" -> true;
            default -> false;
        };
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.valueOf(Integer.parseInt(raw == null ? "" : raw.trim(), 10));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String raw) {
        try {
            return Double.valueOf(Double.parseDouble(raw == null ? "" : raw.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
