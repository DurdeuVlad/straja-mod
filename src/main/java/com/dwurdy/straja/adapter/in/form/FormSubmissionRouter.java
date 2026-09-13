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

    public FormSubmissionRouter(GuardRecruitmentUseCase guards, MissionRoleplayUseCase missions,
            ComplaintRoleplayUseCase complaints, FineRoleplayUseCase fines,
            ArchiveRoleplayUseCase archive) {
        this.guards = guards;
        this.missions = missions;
        this.complaints = complaints;
        this.fines = fines;
        this.archive = archive;
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
                Integer maxAssignees = parseInt(values.get("maxAssignees"));
                if (maxAssignees == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component
                            .literal("Datele ordinului nu sunt valide."));
                    return;
                }
                missions.draftScope(gateway, values.get("minimumRank"), maxAssignees);
            }
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
                Integer reduced = parseInt(values.get("reducedAmount"));
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
            default -> {}
        }
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.valueOf(Integer.parseInt(raw == null ? "" : raw.trim(), 10));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
