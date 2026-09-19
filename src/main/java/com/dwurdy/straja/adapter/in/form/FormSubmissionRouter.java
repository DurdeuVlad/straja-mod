package com.dwurdy.straja.adapter.in.form;

import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
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

    public FormSubmissionRouter(GuardRecruitmentUseCase guards, MissionRoleplayUseCase missions,
            ComplaintRoleplayUseCase complaints, FineRoleplayUseCase fines,
            ArchiveRoleplayUseCase archive,
            com.dwurdy.straja.application.port.in.ReportUseCase reports,
            com.dwurdy.straja.application.port.in.AudienceUseCase audiences,
            com.dwurdy.straja.application.port.in.AdminRoleplayUseCase admin,
            com.dwurdy.straja.application.port.in.AdminToolsUseCase adminTools,
            com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase custody,
            com.dwurdy.straja.application.port.in.RoleplayExpansionUseCase expansion) {
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
    }

    public void submit(ServerPlayer player, FormSessionUseCase.Submission submission) {
        if (player == null || submission == null) return;
        var gateway = new MinecraftPlayerGateway(player.getServer(), player.getUUID());
        var values = submission.values();
        switch (submission.action()) {
            case QUIZ_ANSWER ->
                    guards.answerQuiz(gateway, submission.recordId(), values.get("answer"));
            case FACTION_DECLARE ->
                    guards.declareNativeFaction(gateway, values.get("faction"));
            case MISSION_REPORT ->
                    missions.report(gateway, submission.recordId(), values.get("report"));
            case MISSION_FAIL ->
                    missions.fail(gateway, submission.recordId(), values.get("reason"));
            case MISSION_DRAFT_WRITE -> {
                Integer minutes = parseInt(values.get("minutes"));
                Integer reward = parseInt(values.get("reward"));
                if (minutes == null || reward == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component
                            .literal("Datele ordinului nu sunt valide."));
                    return;
                }
                missions.draftWrite(gateway, minutes, values.get("start"), reward, values.get("objective"));
            }
            case MISSION_DRAFT_SCOPE -> {
                Integer maxAssignees = parseInt(firstValue(values, "max-assignees", "maxAssignees"));
                if (maxAssignees == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component
                            .literal("Datele ordinului nu sunt valide."));
                    return;
                }
                missions.draftScope(gateway, firstValue(values, "minimum-rank", "minimumRank"), maxAssignees);
            }
            case MISSION_BUDGET_ADJUST ->
                    missions.draftAdjust(gateway, values.get("hours"), values.get("risk"),
                            values.get("reward"), values.get("reason"));
            case COMPLAINT_SUBMIT ->
                    complaints.submit(gateway, values.get("accused"), values.get("category"),
                            values.get("description"));
            case COMPLAINT_REPORT ->
                    complaints.report(gateway, submission.recordId(), values.get("report"));
            case COMPLAINT_WITHDRAW ->
                    complaints.withdraw(gateway, submission.recordId(), values.get("reason"));
            case COMPLAINT_REVIEW -> {
                Integer reward = parseInt(values.get("reward"));
                if (reward == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component
                            .literal("Datele verificării nu sunt valide."));
                    return;
                }
                complaints.review(gateway, submission.recordId(), values.get("decision"), reward);
            }
            case FINE_DRAFT -> {
                Integer amount = parseInt(values.get("amount"));
                if (amount == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component
                            .literal("Datele amenzii nu sunt valide."));
                    return;
                }
                fines.writeDraft(gateway, values.get("target"), amount,
                        values.get("law"), values.get("description"));
            }
            case FINE_APPEAL ->
                    fines.appeal(gateway, submission.recordId(), values.get("reason"));
            case FINE_APPEAL_REVIEW -> {
                String decision = values.get("decision");
                Integer reduced = parseInt(firstValue(values, "reduced-amount", "reducedAmount"));
                boolean reduce = decision != null && java.util.List.of(
                        "reduce", "redu", "micsoreaza", "micșorează")
                        .contains(decision.toLowerCase());
                if (reduce && reduced == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component
                            .literal("Datele contestației nu sunt valide."));
                    return;
                }
                fines.reviewAppeal(gateway, submission.recordId(), decision,
                        reduce ? reduced : Integer.valueOf(0), values.get("reason"));
            }
            case FINE_WARRANT ->
                    fines.issueHearingWarrant(gateway, values.get("target"), values.get("details"));
            case ARCHIVE_FOLDER_CREATE ->
                    archive.createFolder(gateway, values.get("title"), values.get("department"));
            case ARCHIVE_FOLDER_ISSUE ->
                    archive.issueFolder(gateway, submission.recordId(), values.get("target"));
            case ARCHIVE_SHEET_NEW ->
                    archive.newSheet(gateway, submission.recordId(), values.get("type"),
                            values.get("title"));
            case ARCHIVE_SHEET_EDIT ->
                    archive.editSheet(gateway, submission.recordId(), values.get("content"));
            case ARCHIVE_RECIPIENTS ->
                    archive.setRecipients(gateway, submission.recordId(), values.get("recipients"));
            case ARCHIVE_SIGN ->
                    archive.signSheet(gateway, submission.recordId(), values.get("reason"));
            case ARCHIVE_COPY -> {
                Integer count = parseInt(values.get("count"));
                if (count == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component
                            .literal("Datele copierii nu sunt valide."));
                    return;
                }
                archive.copySheet(gateway, submission.recordId(), count, values.get("targets"));
            }
            case ARCHIVE_ENVELOPE ->
                    archive.packEnvelope(gateway, submission.recordId(), values.get("target"));
            case ARCHIVE_DOCUMENT_ISSUE ->
                    archive.issueDocument(gateway, submission.recordId(), values.get("target"));
            case REPORT_SUBMIT ->
                    reports.submit(gateway, values.get("activity"), values.get("missions"),
                            values.get("incidents"), values.get("notes"));
            case REPORT_REVIEW ->
                    reports.review(gateway, submission.recordId(), values.get("decision"),
                            values.get("note"));
            case AUDIENCE_REQUEST ->
                    audiences.request(gateway, values.get("reason"));
            case AUDIENCE_REVIEW ->
                    audiences.resolve(gateway, submission.recordId(), values.get("decision"),
                            values.get("note"));
            case ADMIN_AUTHORIZE -> {
                Integer rank = parseInt(values.get("rank"));
                if (rank == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component
                            .literal("Rangul trebuie să fie un număr (1-4)."));
                    return;
                }
                admin.authorize(gateway, values.get("name"), rank);
            }
            case ADMIN_POLICY_SET ->
                    admin.policySet(gateway, values.get("key"), values.get("value"));
            case ADMIN_EMERGENCY_ALERT ->
                    admin.emergencyAlert(gateway, values.get("message"));
            case ADMIN_EMERGENCY_START -> {
                Double multiplier = parseDouble(values.get("multiplier"));
                Integer rounds = parseInt(values.get("rounds"));
                admin.emergencyStart(gateway, multiplier, rounds, values.get("reason"));
            }
            case TOOL_NPC_NAME -> {
                adminTools.npcRename(gateway, submission.recordId(), values.get("name"));
                reflectNpc(player, submission.recordId(), true);
            }
            case TOOL_NPC_SKIN -> {
                adminTools.npcSetSkin(gateway, submission.recordId(), values.get("skin"));
                reflectNpc(player, submission.recordId(), false);
            }
            case GIVE_UP -> {
                if (!custody.giveUp(gateway, true)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                            "straja.give_up.stale"));
                }
            }
            case INCIDENT_REPORT ->
                    expansion.reportIncident(gateway, values.get("category"), values.get("description"));
            case BOLO_CREATE ->
                    expansion.createBolo(gateway, values.get("subject"), values.get("reason"),
                            values.get("notes"), values.get("authority"),
                            firstValue(values, "incident-id", "incidentId"));
            case INCIDENT_RESOLVE ->
                    expansion.resolveIncident(gateway, submission.recordId(),
                            values.get("resolution"), values.get("notes"));
            case EVIDENCE_CONFISCATE -> {
                Integer slot = parseInt(values.get("slot"));
                Integer amount = parseInt(values.get("amount"));
                if (slot == null || amount == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "Slotul și cantitatea probei trebuie să fie numere."));
                    return;
                }
                expansion.confiscate(gateway, submission.recordId(), slot, amount,
                        values.get("reason"), values.get("incidentId"));
            }
            case EVIDENCE_TRANSFER ->
                    expansion.transferEvidence(gateway, submission.recordId(),
                            values.get("custodian"), values.get("reason"));
            case EVIDENCE_DESTROY ->
                    expansion.destroyEvidence(gateway, submission.recordId(), values.get("reason"));
            case EVIDENCE_CASE_VIEW -> {
                var records = com.dwurdy.straja.bootstrap.StrajaRuntime.get()
                        .evidence().recordsForCase(gateway, values.get("caseId"));
                if (records.isEmpty()) gateway.tell("Dosarul nu există sau nu are probe vizibile.");
                else for (var evidence : records) {
                    gateway.tell(evidence.id + " — " + evidence.itemId + " x" + evidence.amount
                            + " — " + evidence.status);
                }
            }
            case ARREST_HANDOFF ->
                    expansion.finalizeArrest(gateway, values.get("detainee"),
                            values.get("sentenceId"), values.get("notes"));
            case REPUTATION_VIEW -> {
                var history = expansion.reputationHistory(gateway, values.get("subject"));
                if (history.isEmpty()) {
                    gateway.tell("Nu există istoric sau subiectul nu este online.");
                } else {
                    for (var event : history) {
                        gateway.tell(event.id() + " " + event.source() + " delta=" + event.delta()
                                + " scor=" + event.scoreBefore() + "→" + event.scoreAfter()
                                + " — " + event.reason());
                    }
                }
            }
            case REPUTATION_CORRECTION -> {
                Integer delta = parseInt(values.get("delta"));
                if (delta == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "Modificarea reputației trebuie să fie un număr."));
                    return;
                }
                expansion.correctReputation(gateway, values.get("subject"), delta, values.get("reason"));
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

    private static String firstValue(java.util.Map<String, String> values, String primary, String legacy) {
        String value = values.get(primary);
        return value != null ? value : values.get(legacy);
    }
}
