package com.dwurdy.straja.adapter.in.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.dwurdy.straja.adapter.in.form.FormSessionBridge;
import com.dwurdy.straja.application.port.in.FormSessionUseCase;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;

/**
 * Registry of Straja NPC roles. Role assignment is explicit and persistent on
 * the entity; this class maps role IDs to behavior.
 */
public final class NpcRoles {
    public static final String RECEPTIONIST = "receptionist";
    public static final String SECRETARY = "secretary";
    public static final String JAILER = "jailer";
    public static final String ARCHIVIST = "archivist";
    public static final String TRAINER = "trainer";
    public static final String RECRUITER = "recruiter";
    public static final String ARMORER = "armorer";

    private static final Set<String> KNOWN =
            Set.of(RECEPTIONIST, SECRETARY, JAILER, ARCHIVIST, TRAINER, RECRUITER, ARMORER);

    private NpcRoles() {}

    public static boolean isKnown(String roleId) {
        return KNOWN.contains(roleId);
    }

    public static Set<String> knownRoles() {
        return KNOWN;
    }

    /** Handles the implicit Secretary book action before the normal dialog. */
    public static boolean trySecretaryBookCopy(
            String roleId, com.dwurdy.straja.application.port.out.PlayerGateway player) {
        if (!SECRETARY.equals(roleId) || player == null) return false;
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null) return false;
        return runtime.secretaryRoleplay().copyHeldBook(player)
                != com.dwurdy.straja.application.port.out.PlayerGateway.BookCopyResult.NOT_A_BOOK;
    }

    public static void interact(String roleId, Player player, ServerLevel level) {
        NpcPlayerSurface.InteractionPlan plan = NpcPlayerSurface.interactionPlan(roleId);
        NpcPlayerSurface.RoleSurface surface = plan.surface();
        surface = NpcPlayerSurface.withAdditionalActions(surface, stateAwareActions(roleId, player, level));
        sendGuidance(player, surface);
    }

    /** The NPC interaction itself is deliberately guidance-only; buttons dispatch separately. */
    static Component guidanceComponent(String roleId) {
        return guidanceComponent(NpcPlayerSurface.surfaceFor(roleId), null);
    }

    private static Component guidanceComponent(NpcPlayerSurface.RoleSurface surface, Player player) {
        MutableComponent message = Component.literal("[Straja] " + surface.title() + ": " + surface.guidance());
        for (NpcPlayerSurface.ChatAction action : surface.actions()) {
            MutableComponent label = Component.literal("[" + action.label() + "]").withStyle(style -> style
                    .withColor(ChatFormatting.AQUA)
                    .withUnderlined(true)
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.literal("Apasă pentru a deschide."))));
            if (player != null) {
                label.withStyle(style -> style.withClickEvent(new ClickEvent(
                        ClickEvent.Action.RUN_COMMAND,
                        NpcInteractionService.issueActionCommand(player, action))));
            }
            message.append(Component.literal(" ")).append(label);
        }
        return message;
    }

    private static void sendGuidance(Player player, NpcPlayerSurface.RoleSurface surface) {
        player.sendSystemMessage(guidanceComponent(surface, player));
    }

    public static boolean performAction(String actionId, Player player, ServerLevel level) {
        if (actionId == null || player == null || level == null) return false;
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null) return false;
        var gw = gateway(player, level);
        var parameterized = NpcPlayerSurface.parseActionId(actionId);
        if (parameterized.isPresent()) {
            return performParameterizedAction(parameterized.get(), player, level, runtime, gw);
        }
        switch (actionId) {
            case "rules" -> runtime.guardDuty().showRules(gw);
            case "guard-status" -> runtime.guardDuty().showStatus(gw);
            case "application-submit" -> runtime.guardRecruitment().applyForStraja(gw);
            case "recruit" -> runtime.guardRecruitment().recruit(gw);
            case "quiz-answer" -> openQuizForm(player, level, runtime, gw);
            case "training-progress" -> runtime.guardRecruitment().showProgress(gw);
            case "training-promote" -> runtime.guardRecruitment().requestPromotion(gw);
            case "training-manual" -> runtime.guardRecruitment().giveManual(gw);
            case "faction-declare" -> openFactionForm(player, runtime, gw);
            case "incident-report" -> openIncidentForm(player, runtime);
            case "bolo-create" -> openBoloForm(player, runtime);
            case "incident-list" -> tellIncidents(runtime, gw);
            case "duty-roster" -> tellRoster(runtime, gw);
            case "bolo-list" -> tellBolos(runtime, gw);
            case "reputation-self" -> tellReputation(runtime, gw);
            case "arrest-record" -> tellArrestRecord(runtime, gw);
            case "arrest-record-admin" -> tellArrestRecords(runtime, gw);
            case "evidence-list" -> tellEvidence(runtime, gw);
            case "evidence-case-view" -> openEvidenceCaseViewForm(player);
            case "arrest-handoff" -> openArrestHandoffForm(player);
            case "reputation-view" -> openReputationViewForm(player);
            case "reputation-correction" -> openReputationCorrectionForm(player);
            case "duty-start" -> runtime.guardDuty().startDuty(gw);
            case "duty-stop" -> runtime.guardDuty().stopDuty(gw);
            case "duty-salary" -> runtime.guardDuty().salary(gw);
            case "duty-coins" -> runtime.guardDuty().coins(gw);
            case "duty-food" -> runtime.guardDuty().food(gw);
            case "duty-kit" -> runtime.guardDuty().kit(gw);
            case "armory-status" -> {
                var offers = runtime.armory().offers(gw);
                if (offers.isEmpty()) {
                    gw.tell("Armurierul nu are articole pentru tine acum.");
                } else {
                    gw.tell("Stoc armurier: " + offers.stream()
                            .filter(o -> !o.reserve())
                            .map(o -> o.count() + "× " + o.itemId() + " = " + o.cost() + " monede")
                            .collect(java.util.stream.Collectors.joining("; ")));
                    gw.tell("Rezerve (puncte de rechiziție): " + offers.stream()
                            .filter(com.dwurdy.straja.application.port.in.ArmoryUseCase.Offer::reserve)
                            .map(o -> o.count() + "× " + o.itemId() + " = " + o.cost() + " pct")
                            .collect(java.util.stream.Collectors.joining("; ")));
                }
            }
            case "resignation-start" -> runtime.guardDuty().beginResignation(gw);
            case "resignation-confirm" -> runtime.guardDuty().confirmResignation(gw);
            case "resignation-cancel" -> runtime.guardDuty().cancelResignation(gw);
            case "rejoin" -> runtime.guardDuty().rejoin(gw);
            case "mission-list" -> runtime.missionRoleplay().list(gw);
            case "mission-carnet" -> runtime.missionRoleplay().giveCarnet(gw);
            case "mission-draft-status" -> runtime.missionRoleplay().draftStatus(gw);
            case "mission-draft-sign" -> runtime.missionRoleplay().draftSign(gw);
            case "mission-draft-package" -> runtime.missionRoleplay().draftPackage(gw);
            case "mission-draft-write" -> openMissionForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DRAFT_WRITE, "");
            case "mission-draft-scope" -> openMissionForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DRAFT_SCOPE, "");
            case "mission-template-list" -> runtime.missionRoleplay().templateList(gw);
            case "mission-budget-adjust" -> openMissionForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.ADJUST_BUDGET, "");
            case "cuffs-status" -> runtime.custodyRoleplay().cuffStatus(gw);
            case "prison-status" -> runtime.prisonRoleplay().status(gw);
            case "downed-status" -> runtime.custodyRoleplay().downedStatus(gw);
            case "custody-remove-head-sack" -> runtime.custodyRoleplay().removeHeadSack(gw);
            case "custody-wake-downed" -> runtime.custodyRoleplay().wakeDowned(gw, "jailer_npc");
            case "cuffs-item" -> runtime.custodyRoleplay().giveCuffs(gw);
            case "fine-list" -> runtime.fineRoleplay().listFines(gw);
            case "identity-request" -> runtime.identityCards().request(gw);
            case "identity-list" -> runtime.identityCards().list(gw);
            case "fine-task-list" -> runtime.fineRoleplay().listTasks(gw);
            case "fine-draft-status" -> gw.tell(runtime.fineRoleplay().draftText(gw));
            case "fine-appeal-list" -> runtime.fineRoleplay().listAppeals(gw);
            case "fine-draft-write" -> openFineForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.FineRoleplayUseCase.Action.DRAFT_WRITE, "");
            case "fine-warrant" -> openFineForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.FineRoleplayUseCase.Action.HEARING_WARRANT, "");
            case "complaint-list" -> runtime.complaintRoleplay().list(gw);
            case "complaint-submit" -> openComplaintForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase.Action.SUBMIT, "");
            case "room-status" -> runtime.roomRoleplay().status(gw);
            case "room-release" -> runtime.roomRoleplay().releaseFor(gw);
            case "archive-list" -> runtime.archiveRoleplay().listFolders(gw);
            case "archive-folder-create" -> openArchiveForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.CREATE_FOLDER, "");
            case "report-submit" -> openReportSubmitForm(player, runtime, gw);
            case "report-status" -> runtime.reportRoleplay().status(gw);
            case "report-review-list" -> runtime.reportRoleplay().listForReview(gw);
            case "audience-request" -> openAudienceRequestForm(player, runtime, gw);
            case "audience-status" -> runtime.audienceRoleplay().status(gw);
            case "audience-review-list" -> runtime.audienceRoleplay().listForReview(gw);
            case "admin-personnel" -> runtime.adminRoleplay().personnel(gw);
            case "admin-roster" -> runtime.adminRoleplay().activeRoster(gw);
            case "admin-authorize" -> openAdminForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.AUTHORIZE, "");
            case "admin-policies" -> runtime.adminRoleplay().policyList(gw);
            case "admin-policy-set" -> openAdminForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.POLICY_SET, "");
            case "admin-emergency-status" -> runtime.adminRoleplay().emergencyStatus(gw);
            case "admin-emergency-alert" -> openAdminForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.EMERGENCY_ALERT, "");
            case "admin-emergency-start" -> openAdminForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.EMERGENCY_START, "");
            case "admin-emergency-end" -> runtime.adminRoleplay().emergencyEnd(gw);
            case "tool-cell-confirm" -> runtime.adminTools().cellConfirm(gw);
            case "tool-survey-all" -> runtime.adminTools().surveyStampAllMissing(gw);
            default -> { return false; }
        }
        return true;
    }

    private static boolean performParameterizedAction(NpcPlayerSurface.ActionRef action,
                                                       Player player, ServerLevel level,
                                                       com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                                       com.dwurdy.straja.application.port.out.PlayerGateway gw) {
        String operation = action.operation();
        String id = action.recordId();
        if (!isStillValidForPlayer(operation, id, runtime, gw)) {
            player.sendSystemMessage(Component.literal("[Straja] Acțiunea NPC nu mai este disponibilă: dosarul sau cererea s-a schimbat."));
            return false;
        }
        switch (operation) {
            case "faq" -> performFaq(id, player, runtime, gw);
            case "duty-checkpoint" -> runtime.guardDuty().checkpoint(gw, id);
            case "mission-join" -> runtime.missionRoleplay().join(gw, id);
            case "mission-accept" -> runtime.missionRoleplay().accept(gw, id);
            case "mission-decline" -> runtime.missionRoleplay().decline(gw, id);
            case "mission-complete" -> runtime.missionRoleplay().complete(gw, id);
            case "mission-reward" -> runtime.missionRoleplay().claimReward(gw, id);
            case "mission-reward-recover" -> runtime.missionRoleplay().recoverReward(gw, id);
            case "mission-report" -> openMissionForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.REPORT, id);
            case "mission-fail" -> openMissionForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.FAIL, id);
            case "mission-template-issue" -> runtime.missionRoleplay().draftFromTemplate(gw, id);
            case "complaint-report" -> openComplaintForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase.Action.REPORT, id);
            case "complaint-review" -> openComplaintForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase.Action.REVIEW, id);
            case "complaint-withdraw" -> openComplaintForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase.Action.WITHDRAW, id);
            case "custody-accept" -> runtime.custodyRoleplay().accept(gw, id);
            case "custody-refuse" -> runtime.custodyRoleplay().refuse(gw, id);
            case "custody-release" -> runtime.custodyRoleplay().releaseById(gw, id);
            case "complaint-claim" -> runtime.complaintRoleplay().claim(gw, id);
            case "complaint-join" -> runtime.complaintRoleplay().join(gw, id);
            case "complaint-leave" -> runtime.complaintRoleplay().leave(gw, id);
            case "complaint-confirm" -> runtime.complaintRoleplay().confirm(gw, id);
            case "fine-pay" -> runtime.fineRoleplay().pay(gw, id);
            case "fine-refuse" -> runtime.fineRoleplay().refusePayment(gw, id);
            case "fine-appeal" -> openFineForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.FineRoleplayUseCase.Action.APPEAL, id);
            case "fine-appeal-review" -> openFineForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.FineRoleplayUseCase.Action.REVIEW_APPEAL, id);
            case "fine-task-accept" -> runtime.fineRoleplay().acceptTask(gw, id);
            case "fine-task-complete" -> runtime.fineRoleplay().completeTask(gw, id);
            case "fine-task-arrest" -> runtime.fineRoleplay().arrest(gw, id, null);
            case "fine-task-reward" -> runtime.fineRoleplay().claimTaskReward(gw, id);
            case "archive-folder-read" -> runtime.archiveRoleplay().readFolder(gw, id);
            case "archive-sheet-read" -> runtime.archiveRoleplay().readSheet(gw, id);
            case "archive-sheet-submit" -> runtime.archiveRoleplay().submitSheet(gw, id);
            case "archive-sheet-revoke" -> runtime.archiveRoleplay().revokeSheet(gw, id);
            case "archive-folder-issue" -> openArchiveForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.ISSUE_FOLDER, id);
            case "archive-sheet-new" -> openArchiveForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.NEW_SHEET, id);
            case "archive-sheet-edit" -> openArchiveForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.EDIT_SHEET, id);
            case "archive-recipients" -> openArchiveForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.SET_RECIPIENTS, id);
            case "archive-sheet-sign" -> openArchiveForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.SIGN_SHEET, id);
            case "archive-sheet-copy" -> openArchiveForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.COPY_SHEET, id);
            case "archive-sheet-envelope" -> openArchiveForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.PACK_ENVELOPE, id);
            case "archive-sheet-issue" -> openArchiveForm(player, runtime, gw,
                    com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.ISSUE_DOCUMENT, id);
            case "report-review" -> openReportReviewForm(player, runtime, gw, id);
            case "audience-review" -> openAudienceReviewForm(player, runtime, gw, id);
            case "admin-dossier" -> runtime.adminRoleplay().dossier(gw, id);
            case "admin-promote" -> runtime.adminRoleplay().promote(gw, id);
            case "admin-demote" -> runtime.adminRoleplay().demote(gw, id);
            case "admin-suspend" -> runtime.adminRoleplay().suspend(gw, id);
            case "admin-fire" -> runtime.adminRoleplay().fire(gw, id);
            case "admin-reinstate" -> runtime.adminRoleplay().reinstate(gw, id);
            case "tool-npc-assign" -> {
                // recordId = "<entityUuid>-<role>"; entity UUIDs are fixed-width.
                String npcUuid = id.length() > 37 ? id.substring(0, 36) : "";
                String role = id.length() > 37 ? id.substring(37) : "";
                runtime.adminTools().npcAssign(gw, npcUuid, role);
                var npc = loadedNpc(player, npcUuid);
                if (npc != null) {
                    // Reflect the persisted record, not the raw token input —
                    // an unknown role must not reach the live entity.
                    var registration = runtime.npcRegistry().registration(npcUuid);
                    if (registration != null && registration.role() != null
                            && !registration.role().isEmpty()) {
                        npc.setRoleId(registration.role());
                    }
                }
            }
            case "tool-npc-rename" -> openToolForm(player, runtime, id, true);
            case "tool-npc-skin" -> openToolForm(player, runtime, id, false);
            case "tool-npc-remove" -> sendNpcRemoveConfirm(player, runtime, id);
            case "tool-npc-remove-confirm" -> {
                runtime.adminTools().npcRemove(gw, id);
                var npc = loadedNpc(player, id);
                if (npc != null) npc.discard();
            }
            case "tool-npc-record" -> runtime.adminTools().npcShowRecord(gw, id);
            case "tool-survey-stamp" -> runtime.adminTools().surveyStamp(gw, id);
            case "incident-accept" -> runtime.expansionRoleplay().acceptIncident(gw, id);
            case "incident-join" -> runtime.expansionRoleplay().joinIncident(gw, id);
            case "incident-leave" -> runtime.expansionRoleplay().leaveIncident(gw, id);
            case "incident-resolve" -> openIncidentResolveForm(player, runtime, id);
            case "bolo-cancel" -> runtime.expansionRoleplay().cancelBolo(gw, id);
            case "evidence-deposit" -> runtime.expansionRoleplay().depositEvidence(gw, id);
            case "evidence-return" -> runtime.expansionRoleplay().returnEvidence(gw, id);
            case "evidence-transfer" -> openEvidenceTransferForm(player, id);
            case "evidence-destroy" -> openEvidenceDestroyForm(player, id);
            case "armory-buy" -> runtime.armory().buy(gw, id);
            case "armory-reserve" -> runtime.armory().buyReserve(gw, id);
            default -> { return false; }
        }
        return true;
    }

    private static void performFaq(String target, Player player,
                                    com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                    com.dwurdy.straja.application.port.out.PlayerGateway gw) {
        var parsed = NpcFaqSurface.parseTarget(target);
        if (parsed.isEmpty()) {
            gw.tell("[Straja] Întrebarea FAQ nu mai este disponibilă.");
            return;
        }
        var state = runtime.playerQueries().readState(gw);
        int rank = state == null ? com.dwurdy.straja.domain.model.Rank.CIVIL.level() : state.rank;
        var context = NpcFaqSurface.Context.from(state, runtime.playerQueries().isCommissioner(gw),
                runtime.policies().rankName(rank));
        var targetRef = parsed.get();
        if (!NpcFaqSurface.targetAvailable(targetRef, context)) {
            gw.tell("[FAQ] Ramura nu mai este disponibilă pentru statutul tău.");
            sendGuidance(player, NpcFaqSurface.root(targetRef.origin(), context));
            return;
        }
        switch (targetRef.kind()) {
            case "root" -> sendGuidance(player, NpcFaqSurface.root(targetRef.origin(), context));
            case "menu" -> sendGuidance(player,
                    NpcFaqSurface.menu(targetRef.origin(), context, targetRef.topic()));
            case "answer" -> {
                gw.tell("[FAQ] " + NpcFaqSurface.answer(
                        targetRef.origin(), targetRef.topic(), targetRef.question(), context));
                sendGuidance(player,
                        NpcFaqSurface.menu(targetRef.origin(), context, targetRef.topic()));
            }
            default -> gw.tell("[Straja] Ramură FAQ necunoscută.");
        }
    }

    /** The loaded Straja NPC entity across all server levels, or null. */
    private static StrajaNpcEntity loadedNpc(Player player, String entityUuid) {
        try {
            var server = player.getServer();
            if (server == null) return null;
            var uuid = java.util.UUID.fromString(entityUuid);
            for (var lvl : server.getAllLevels()) {
                if (lvl.getEntity(uuid) instanceof StrajaNpcEntity npc) return npc;
            }
        } catch (IllegalArgumentException ignored) {}
        return null;
    }

    /** Wand text actions (rename / skin) run through the native form surface. */
    private static boolean openToolForm(Player player,
                                        com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                        String entityUuid, boolean name) {
        if (!runtime.adminTools().npcStillRegistered(entityUuid)) {
            player.sendSystemMessage(Component.literal("[Straja] NPC-ul nu mai este înregistrat."));
            return true;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                name ? FormSessionUseCase.Action.TOOL_NPC_NAME
                        : FormSessionUseCase.Action.TOOL_NPC_SKIN,
                entityUuid,
                name ? "Redenumește NPC" : "Skin nou",
                name ? "Noul nume afișat al NPC-ului." : "ID-ul skin-ului pentru acest NPC.",
                java.util.List.of(new FormSessionUseCase.Field(
                        name ? "name" : "skin", name ? "Nume" : "Skin", 80, false))));
        return true;
    }

    /** Removal is destructive — the first click mints a second confirm token. */
    private static boolean sendNpcRemoveConfirm(Player player,
                                                com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                                String entityUuid) {
        if (!runtime.adminTools().npcStillRegistered(entityUuid)) {
            player.sendSystemMessage(Component.literal("[Straja] NPC-ul nu mai este înregistrat."));
            return true;
        }
        String token = NpcInteractionService.issueActionToken(player.getUUID(),
                "tool-npc-remove-confirm:" + entityUuid);
        // A bound foreign entity is never discarded — detach drops only the
        // registry record; a loaded StrajaNpcEntity is deleted with it.
        boolean nativeEntity = loadedNpc(player, entityUuid) != null;
        MutableComponent confirm = Component.literal(
                nativeEntity ? "[Confirmă eliminarea]" : "[Confirmă detașarea]").withStyle(style -> style
                .withColor(ChatFormatting.RED)
                .withUnderlined(true)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal(nativeEntity
                                ? "Acțiunea este permanentă."
                                : "Entitatea rămâne în lume; doar rolul Straja este retras.")))
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/straja npc-action " + token)));
        player.sendSystemMessage(Component.literal(nativeEntity
                ? "[Straja] Eliminarea este permanentă. "
                : "[Straja] Detașarea retrage doar rolul Straja. ").append(confirm));
        return true;
    }

    /**
     * Renders an admin-tool menu as one chat line of clickable buttons, each
     * bound to a fresh short-lived one-use token for this player.
     */
    public static void sendToolMenu(ServerPlayer player,
                                    com.dwurdy.straja.application.port.in.AdminToolsUseCase.Menu menu) {
        if (player == null || menu == null) return;
        MutableComponent message = Component.literal(menu.title());
        for (var action : menu.actions()) {
            message.append(Component.literal(" "))
                    .append(toolButton(player, action.label(), action.actionId()));
        }
        player.sendSystemMessage(message);
    }

    /** A single clickable row sent on its own line (e.g. the cell confirm). */
    public static void sendToolPrompt(ServerPlayer player, String text, String label, String actionId) {
        if (player == null) return;
        player.sendSystemMessage(Component.literal(text).append(Component.literal(" "))
                .append(toolButton(player, label, actionId)));
    }

    private static MutableComponent toolButton(ServerPlayer player, String label, String actionId) {
        String token = NpcInteractionService.issueToolActionToken(player.getUUID(), actionId);
        return Component.literal("[" + label + "]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Apasă pentru a executa.")))
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/straja npc-action " + token)));
    }

    private static boolean openIncidentForm(Player player,
                                            com.dwurdy.straja.bootstrap.StrajaRuntime runtime) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.INCIDENT_REPORT, "",
                "Raport de incident", "Descrie pe scurt ce s-a întâmplat și unde.",
                java.util.List.of(
                        new FormSessionUseCase.Field("category", "Categorie", 80, false),
                        new FormSessionUseCase.Field("description", "Descriere",
                                runtime.policies().incidentMaxDescriptionLength, true))));
        return true;
    }

    private static boolean openIncidentResolveForm(Player player,
                                                   com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                                   String incidentId) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.INCIDENT_RESOLVE, incidentId,
                "Încheiere incident", "Alege rezultatul și notează ce s-a întâmplat.",
                java.util.List.of(
                        new FormSessionUseCase.Field("resolution",
                                "Cod (RESOLVED/TRANSFERRED/FALSE_REPORT/NO_ACTION)", 32, false),
                        new FormSessionUseCase.Field("notes", "Note",
                                runtime.policies().incidentMaxDescriptionLength, true))));
        return true;
    }

    private static boolean openBoloForm(Player player,
                                        com.dwurdy.straja.bootstrap.StrajaRuntime runtime) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.BOLO_CREATE, "",
                "Emite BOLO", "BOLO-ul este alertă de informare; nu este mandat de arest.",
                java.util.List.of(
                        new FormSessionUseCase.Field("subject", "Jucător online", 64, false),
                        new FormSessionUseCase.Field("reason", "Motiv", runtime.policies().boloMaxReasonLength, true),
                        new FormSessionUseCase.Field("notes", "Note", runtime.policies().incidentMaxDescriptionLength, true),
                        new FormSessionUseCase.Field("authority", "INFORMATION_ONLY", 32, false),
                        new FormSessionUseCase.Field("incidentId", "Incident legat (opțional)", 80, false))));
        return true;
    }

    private static boolean openReputationViewForm(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.REPUTATION_VIEW, "",
                "Istoric reputație", "Disponibil doar Comisarului; indică un jucător online.",
                java.util.List.of(new FormSessionUseCase.Field(
                        "subject", "Jucător", 64, false))));
        return true;
    }

    private static boolean openReputationCorrectionForm(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.REPUTATION_CORRECTION, "",
                "Corecție reputație", "Doar Comisarul poate modifica reputația; motivul este obligatoriu.",
                java.util.List.of(
                        new FormSessionUseCase.Field("subject", "Jucător online", 64, false),
                        new FormSessionUseCase.Field("delta", "Modificare", 8, false),
                        new FormSessionUseCase.Field("reason", "Motiv", 240, true))));
        return true;
    }

    public static boolean openEvidenceForm(
            Player player,
            com.dwurdy.straja.application.port.in.RoleplayExpansionUseCase.SearchView search,
            com.dwurdy.straja.bootstrap.StrajaRuntime runtime) {
        if (!(player instanceof ServerPlayer serverPlayer) || search == null) return false;
        String slots = search.slots().stream()
                .map(entry -> entry.slot() + ": " + entry.item().id() + " x" + entry.item().count())
                .collect(java.util.stream.Collectors.joining("; "));
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.EVIDENCE_CONFISCATE, search.token(),
                "Percheziție: " + search.targetName(),
                "Sloturi disponibile: " + slots,
                java.util.List.of(
                        new FormSessionUseCase.Field("slot", "Slot", 3, false),
                        new FormSessionUseCase.Field("amount", "Cantitate exactă", 4, false),
                        new FormSessionUseCase.Field("reason", "Motiv",
                                runtime.policies().evidenceMaxReasonLength, true),
                        new FormSessionUseCase.Field("incidentId",
                                "Incident legat (opțional)", 80, false))));
        return true;
    }

    private static boolean openEvidenceTransferForm(Player player, String evidenceId) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.EVIDENCE_TRANSFER, evidenceId,
                "Transferă proba " + evidenceId,
                "Indică numele custodelui autorizat online și motivul transferului.",
                java.util.List.of(
                        new FormSessionUseCase.Field("custodian", "Custode", 64, false),
                        new FormSessionUseCase.Field("reason", "Motiv", 200, true))));
        return true;
    }

    private static boolean openEvidenceDestroyForm(Player player, String evidenceId) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.EVIDENCE_DESTROY, evidenceId,
                "Distruge proba " + evidenceId,
                "Acțiunea este definitivă și disponibilă doar Comisarului. Motivul este obligatoriu.",
                java.util.List.of(new FormSessionUseCase.Field("reason", "Motiv", 200, true))));
        return true;
    }

    private static boolean openEvidenceCaseViewForm(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.EVIDENCE_CASE_VIEW, "",
                "Probe pe dosar", "Introdu ID-ul dosarului de arest.",
                java.util.List.of(new FormSessionUseCase.Field("caseId", "Dosar", 80, false))));
        return true;
    }

    private static boolean openArrestHandoffForm(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.ARREST_HANDOFF, "",
                "Predare la Temnicer", "Completează hand-off-ul unei sentințe active.",
                java.util.List.of(
                        new FormSessionUseCase.Field("detainee", "Deținut online", 64, false),
                        new FormSessionUseCase.Field("sentenceId", "ID sentință", 80, false),
                        new FormSessionUseCase.Field("notes", "Note", 240, true))));
        return true;
    }

    private static void tellIncidents(com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                      com.dwurdy.straja.application.port.out.PlayerGateway player) {
        var incidents = runtime.expansionRoleplay().activeIncidents(player);
        if (incidents.isEmpty()) {
            player.tell("Nu există incidente active pentru rosterul tău.");
            return;
        }
        for (var incident : incidents) {
            player.tell(incident.id() + " [" + incident.priority() + "] "
                    + incident.title() + " — " + incident.location()
                    + " — " + incident.status());
        }
    }

    private static void tellRoster(com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                   com.dwurdy.straja.application.port.out.PlayerGateway player) {
        var roster = runtime.expansionRoleplay().dutyRoster(player);
        if (roster.isEmpty()) {
            player.tell("Rosterul de serviciu este gol sau nu ai acces.");
            return;
        }
        for (var entry : roster) {
            player.tell(entry.callsign() + " — " + entry.name() + " — "
                    + entry.rank() + " — " + entry.operationalState());
        }
    }

    private static void tellBolos(com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                  com.dwurdy.straja.application.port.out.PlayerGateway player) {
        var bolos = runtime.expansionRoleplay().activeBolos(player);
        if (bolos.isEmpty()) {
            player.tell("Nu există BOLO-uri active sau nu ai acces.");
            return;
        }
        for (var bolo : bolos) {
            player.tell(bolo.id() + " — " + bolo.subject() + " — " + bolo.reason()
                    + " — " + bolo.authority()
                    + (bolo.arrestAuthority() ? " [task autoritativ]" : " [INFORMARE]"));
        }
    }

    private static void tellReputation(com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                       com.dwurdy.straja.application.port.out.PlayerGateway player) {
        var view = runtime.expansionRoleplay().reputation(player, player);
        if (view == null) return;
        player.tell("Reputația ta: " + view.score() + " — " + view.band()
                + ". Reputația nu este autoritate de arest.");
    }

    private static void tellArrestRecord(com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                         com.dwurdy.straja.application.port.out.PlayerGateway player) {
        var records = runtime.arrestRecords().recordsForDetainee(player);
        if (records.isEmpty()) {
            player.tell("Nu există un dosar de arest pentru tine.");
            return;
        }
        for (var record : records) {
            player.tell(record.id + " [" + record.status + "] motiv: "
                    + record.detentionReason + " — sentință " + record.sentenceDays + " zile");
        }
    }

    private static void tellArrestRecords(com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                          com.dwurdy.straja.application.port.out.PlayerGateway player) {
        var records = runtime.arrestRecords().recordsFor(player);
        if (records.isEmpty()) {
            player.tell("Nu există dosare de arest vizibile pentru tine.");
            return;
        }
        for (var record : records) {
            player.tell(record.id + " [" + record.status + "] " + record.detaineeName
                    + " — motiv: " + record.detentionReason
                    + " — probe: " + String.join(", ", record.evidenceIds));
        }
    }

    private static void tellEvidence(com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                     com.dwurdy.straja.application.port.out.PlayerGateway player) {
        var records = runtime.evidence().recordsFor(player);
        if (records.isEmpty()) {
            player.tell("Nu există probe vizibile pentru tine.");
            return;
        }
        for (var record : records) {
            player.tell(record.id + " — " + record.itemId + " x" + record.amount
                    + " — " + record.status + " — " + record.currentLocation
                    + (record.caseId == null || record.caseId.isBlank() ? "" : " — dosar " + record.caseId));
            if (record.custodyHistory != null) {
                for (var event : record.custodyHistory) {
                    player.tell("  lanț: " + event.previousStatus + " → " + event.newStatus
                            + " / " + event.reason + " / " + event.actorName);
                }
            }
        }
    }

    private static boolean openQuizForm(Player player, ServerLevel level,
                                        com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                        com.dwurdy.straja.application.port.out.PlayerGateway gw) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        var prompt = runtime.guardRecruitment().currentQuizPrompt(gw);
        if (prompt.isEmpty()) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.QUIZ_ANSWER,
                prompt.get().questionId(),
                prompt.get().title(),
                prompt.get().question(),
                java.util.List.of(new FormSessionUseCase.Field(
                        "answer", "Răspuns", prompt.get().maxLength(), false))));
        return true;
    }

    private static boolean openFactionForm(Player player,
                                           com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                           com.dwurdy.straja.application.port.out.PlayerGateway gw) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        int maxLength = runtime.guardRecruitment().nativeFactionMaxLength();
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.FACTION_DECLARE, "",
                "Facțiune nativă", "Declară facțiunea din care provii. "
                        + "În timpul turei acționezi ca Străjer al Castelului. Scrie \"niciuna\" pentru a șterge.",
                java.util.List.of(new FormSessionUseCase.Field(
                        "faction", "Facțiune", maxLength, false))));
        return true;
    }

    private static boolean openMissionForm(Player player,
                                           com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                           com.dwurdy.straja.application.port.out.PlayerGateway gw,
                                           com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action required,
                                           String recordId) {
        String expected = recordId == null ? "" : recordId;
        boolean stillAvailable = runtime.missionRoleplay().availableActions(gw).stream()
                .anyMatch(a -> a.action() == required && expected.equals(a.missionId()));
        if (!stillAvailable) {
            player.sendSystemMessage(Component.literal(
                    "[Straja] Acțiunea nu mai este disponibilă; starea misiunii s-a schimbat."));
            return true;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionUseCase.Request request = switch (required) {
            case REPORT -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.MISSION_REPORT, expected,
                    "Raport de misiune", "Descrie rezultatul misiunii.",
                    java.util.List.of(new FormSessionUseCase.Field("report", "Raport", 2000, true)));
            case FAIL -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.MISSION_FAIL, expected,
                    "Încheiere voluntară", "Explică motivul renunțării.",
                    java.util.List.of(new FormSessionUseCase.Field("reason", "Motiv", 240, true)));
            case DRAFT_WRITE -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.MISSION_DRAFT_WRITE, "",
                    "Ordin nou", "Completează ordinul de misiune.",
                    java.util.List.of(
                            new FormSessionUseCase.Field("minutes", "Timp (minute)", 5, false),
                            new FormSessionUseCase.Field("start", "Începere", 16, false),
                            new FormSessionUseCase.Field("reward", "Recompensă", 10, false),
                            new FormSessionUseCase.Field("objective", "Obiectiv", 2000, true)));
            case DRAFT_SCOPE -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.MISSION_DRAFT_SCOPE, "",
                    "Participanți ordin", "Configurează rangul minim și numărul maxim de participanți.",
                    java.util.List.of(
                            new FormSessionUseCase.Field("minimumRank", "Rang minim", 16, false),
                            new FormSessionUseCase.Field("maxAssignees", "Max participanți", 3, false)));
            case ADJUST_BUDGET -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.MISSION_BUDGET_ADJUST, "",
                    "Ajustare buget", "Ore estimate și risc recalculează recompensa. "
                            + "Completează „Recompensă” doar pentru o depășire motivată — "
                            + "peste marja permisă motivul este obligatoriu și se înregistrează.",
                    java.util.List.of(
                            new FormSessionUseCase.Field("hours", "Ore estimate", 6, false),
                            new FormSessionUseCase.Field("risk", "Risc (multiplicator)", 6, false),
                            new FormSessionUseCase.Field("reward", "Recompensă (gol = calculată)", 10, false),
                            new FormSessionUseCase.Field("reason", "Motiv depășire", 240, false)));
            default -> null;
        };
        if (request == null) return true;
        FormSessionBridge.open(serverPlayer, request);
        return true;
    }

    private static boolean openComplaintForm(Player player,
                                             com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                             com.dwurdy.straja.application.port.out.PlayerGateway gw,
                                             com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase.Action required,
                                             String recordId) {
        String expected = recordId == null ? "" : recordId;
        boolean stillAvailable = runtime.complaintRoleplay().availableActions(gw).stream()
                .anyMatch(a -> a.action() == required && expected.equals(a.complaintId()));
        if (!stillAvailable) {
            player.sendSystemMessage(Component.literal(
                    "[Straja] Acțiunea nu mai este disponibilă; starea dosarului s-a schimbat."));
            return true;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        var limits = runtime.complaintRoleplay().limits();
        FormSessionUseCase.Request request = switch (required) {
            case SUBMIT -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.COMPLAINT_SUBMIT, "",
                    "Plângere nouă", "Completează datele plângerii.",
                    java.util.List.of(
                            new FormSessionUseCase.Field("accused", "Acuzat", 80, false),
                            new FormSessionUseCase.Field("category", "Categorie", 80, false),
                            new FormSessionUseCase.Field("description", "Descriere",
                                    limits.description(), true)));
            case REPORT -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.COMPLAINT_REPORT, expected,
                    "Raport de investigație", "Descrie constatările dosarului.",
                    java.util.List.of(new FormSessionUseCase.Field("report", "Raport",
                            limits.evidence(), true)));
            case WITHDRAW -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.COMPLAINT_WITHDRAW, expected,
                    "Retragere plângere", "Explică motivul retragerii.",
                    java.util.List.of(new FormSessionUseCase.Field("reason", "Motiv",
                            limits.withdrawalReason(), true)));
            case REVIEW -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.COMPLAINT_REVIEW, expected,
                    "Verificare dosar", "Alege approve, return sau dismiss și recompensa.",
                    java.util.List.of(
                            new FormSessionUseCase.Field("decision", "Decizie", 16, false),
                            new FormSessionUseCase.Field("reward", "Recompensă", 10, false)));
            default -> null;
        };
        if (request == null) return true;
        FormSessionBridge.open(serverPlayer, request);
        return true;
    }

    private static boolean openFineForm(Player player,
                                        com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                        com.dwurdy.straja.application.port.out.PlayerGateway gw,
                                        com.dwurdy.straja.application.port.in.FineRoleplayUseCase.Action required,
                                        String recordId) {
        String expected = recordId == null ? "" : recordId;
        boolean stillAvailable = runtime.fineRoleplay().availableActions(gw).stream()
                .anyMatch(a -> a.action() == required && expected.equals(a.recordId()));
        if (!stillAvailable) {
            player.sendSystemMessage(Component.literal(
                    "[Straja] Acțiunea nu mai este disponibilă; starea amenzii s-a schimbat."));
            return true;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        var limits = runtime.fineRoleplay().limits();
        FormSessionUseCase.Request request = switch (required) {
            case DRAFT_WRITE -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.FINE_DRAFT, "",
                    "Amendă nouă", "Completează datele amenzii.",
                    java.util.List.of(
                            new FormSessionUseCase.Field("target", "Cetățean", 80, false),
                            new FormSessionUseCase.Field("amount", "Sumă", 10, false),
                            new FormSessionUseCase.Field("law", "Lege", limits.law(), false),
                            new FormSessionUseCase.Field("description", "Descriere",
                                    limits.description(), true)));
            case APPEAL -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.FINE_APPEAL, expected,
                    "Contestație", "Explică motivul contestației.",
                    java.util.List.of(new FormSessionUseCase.Field("reason", "Motiv",
                            limits.appealReason(), true)));
            case REVIEW_APPEAL -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.FINE_APPEAL_REVIEW, expected,
                    "Decizie contestație", "Alege uphold, reduce sau void.",
                    java.util.List.of(
                            new FormSessionUseCase.Field("decision", "Decizie", 16, false),
                            new FormSessionUseCase.Field("reducedAmount", "Sumă redusă", 10, false),
                            new FormSessionUseCase.Field("reason", "Motiv",
                                    limits.reviewReason(), true)));
            case HEARING_WARRANT -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.FINE_WARRANT, "",
                    "Mandat de audiere", "Completează ținta și detaliile mandatului.",
                    java.util.List.of(
                            new FormSessionUseCase.Field("target", "Țintă", 80, false),
                            new FormSessionUseCase.Field("details", "Detalii",
                                    limits.warrantReason(), true)));
            default -> null;
        };
        if (request == null) return true;
        FormSessionBridge.open(serverPlayer, request);
        return true;
    }

    private static boolean openReportSubmitForm(Player player,
                                                com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                                com.dwurdy.straja.application.port.out.PlayerGateway gw) {
        boolean stillAvailable = runtime.reportRoleplay().availableActions(gw).stream()
                .anyMatch(a -> a.action()
                        == com.dwurdy.straja.application.port.in.ReportUseCase.Action.SUBMIT);
        if (!stillAvailable) {
            player.sendSystemMessage(Component.literal(
                    "[Straja] Doar membrii Străjii depun rapoarte de activitate."));
            return true;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.REPORT_SUBMIT, "",
                "Raport de activitate", "Completează raportul pentru perioada curentă.",
                java.util.List.of(
                        new FormSessionUseCase.Field("activity", "Activitate", 2000, true),
                        new FormSessionUseCase.Field("missions", "Misiuni", 2000, true),
                        new FormSessionUseCase.Field("incidents", "Incidente", 2000, true),
                        new FormSessionUseCase.Field("notes", "Note pentru Comisar", 1000, true))));
        return true;
    }

    private static boolean openAudienceRequestForm(Player player,
                                                   com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                                   com.dwurdy.straja.application.port.out.PlayerGateway gw) {
        boolean stillAvailable = runtime.audienceRoleplay().availableActions(gw).stream()
                .anyMatch(a -> a.action()
                        == com.dwurdy.straja.application.port.in.AudienceUseCase.Action.REQUEST);
        if (!stillAvailable) {
            player.sendSystemMessage(Component.literal(
                    "[Straja] Doar membrii Străjii pot cere audiență la Comisar."));
            return true;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.AUDIENCE_REQUEST, "",
                "Cerere de audiență", "Scrie motivul pentru care ceri audiență la Comisar.",
                java.util.List.of(new FormSessionUseCase.Field(
                        "reason", "Motiv", 500, true))));
        return true;
    }

    private static boolean openAudienceReviewForm(Player player,
                                                  com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                                  com.dwurdy.straja.application.port.out.PlayerGateway gw,
                                                  String recordId) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.AUDIENCE_REVIEW, recordId,
                "Decide cererea " + recordId, "Decizie: resolve sau dismiss (notă opțională).",
                java.util.List.of(
                        new FormSessionUseCase.Field("decision", "Decizie", 16, false),
                        new FormSessionUseCase.Field("note", "Notă pentru solicitant", 500, true))));
        return true;
    }

    private static boolean openAdminForm(Player player,
                                         com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                         com.dwurdy.straja.application.port.out.PlayerGateway gw,
                                         com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action required,
                                         String recordId) {
        boolean stillAvailable = runtime.adminRoleplay().availableActions(gw).stream()
                .anyMatch(a -> a.action() == required);
        if (!stillAvailable) {
            player.sendSystemMessage(Component.literal(
                    "[Straja] Doar Comisarul poate folosi interfața administrativă."));
            return true;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionUseCase.Request request = switch (required) {
            case AUTHORIZE -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.ADMIN_AUTHORIZE, "",
                    "Autorizare directă", "Numele jucătorului și rangul (1=Stagiar … 4=Inspector).",
                    java.util.List.of(
                            new FormSessionUseCase.Field("name", "Nume jucător", 32, false),
                            new FormSessionUseCase.Field("rank", "Rang (1-4)", 2, false)));
            case POLICY_SET -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.ADMIN_POLICY_SET, "",
                    "Modifică o regulă", "Cheia din lista de reguli și noua valoare.",
                    java.util.List.of(
                            new FormSessionUseCase.Field("key", "Cheie", 80, false),
                            new FormSessionUseCase.Field("value", "Valoare", 200, false)));
            case EMERGENCY_ALERT -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.ADMIN_EMERGENCY_ALERT, "",
                    "Alertă de urgență", "Mesajul ajunge la toți membrii online și la cei care se conectează cât e activă.",
                    java.util.List.of(new FormSessionUseCase.Field("message", "Mesaj", 240, true)));
            case EMERGENCY_START -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.ADMIN_EMERGENCY_START, "",
                    "Stare de urgență", "Multiplicator de plată și runde obligatorii (gol = valorile configurate).",
                    java.util.List.of(
                            new FormSessionUseCase.Field("multiplier", "Multiplicator plată", 6, false),
                            new FormSessionUseCase.Field("rounds", "Runde obligatorii", 4, false),
                            new FormSessionUseCase.Field("reason", "Motiv", 240, false)));
            default -> null;
        };
        if (request == null) return true;
        FormSessionBridge.open(serverPlayer, request);
        return true;
    }

    private static boolean openReportReviewForm(Player player,
                                                com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                                com.dwurdy.straja.application.port.out.PlayerGateway gw,
                                                String recordId) {
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        FormSessionBridge.open(serverPlayer, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.REPORT_REVIEW, recordId,
                "Verifică raportul " + recordId,
                "Decizie: accept, return (cu notă) sau call (audiență).",
                java.util.List.of(
                        new FormSessionUseCase.Field("decision", "Decizie", 16, false),
                        new FormSessionUseCase.Field("note", "Notă pentru autor", 500, true))));
        return true;
    }

    private static boolean openArchiveForm(Player player,
                                           com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                           com.dwurdy.straja.application.port.out.PlayerGateway gw,
                                           com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action required,
                                           String recordId) {
        String expected = recordId == null ? "" : recordId;
        boolean stillAvailable = runtime.archiveRoleplay().availableActions(gw).stream()
                .anyMatch(a -> a.action() == required && expected.equals(a.recordId()));
        if (!stillAvailable) {
            player.sendSystemMessage(Component.literal(
                    "[Straja] Acțiunea nu mai este disponibilă; starea arhivei s-a schimbat."));
            return true;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) return true;
        var limits = runtime.archiveRoleplay().limits();
        FormSessionUseCase.Request request = switch (required) {
            case CREATE_FOLDER -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.ARCHIVE_FOLDER_CREATE, "",
                    "Dosar nou", "Completează datele dosarului.",
                    java.util.List.of(
                            new FormSessionUseCase.Field("title", "Titlu", limits.title(), false),
                            new FormSessionUseCase.Field("department", "Departament", 80, false)));
            case ISSUE_FOLDER -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.ARCHIVE_FOLDER_ISSUE, expected,
                    "Emitere dosar", "Indică jucătorul căruia îi este emis dosarul.",
                    java.util.List.of(new FormSessionUseCase.Field("target", "Țintă",
                            limits.target(), false)));
            case NEW_SHEET -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.ARCHIVE_SHEET_NEW, expected,
                    "Foaie nouă", "Completează tipul și titlul foii.",
                    java.util.List.of(
                            new FormSessionUseCase.Field("type", "Tip", 32, false),
                            new FormSessionUseCase.Field("title", "Titlu", limits.title(), false)));
            case EDIT_SHEET -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.ARCHIVE_SHEET_EDIT, expected,
                    "Editare foaie", "Scrie conținutul actului.",
                    java.util.List.of(new FormSessionUseCase.Field("content", "Conținut",
                            limits.content(), true)));
            case SET_RECIPIENTS -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.ARCHIVE_RECIPIENTS, expected,
                    "Destinatari", "Listează destinatarii separați prin virgulă.",
                    java.util.List.of(new FormSessionUseCase.Field("recipients", "Destinatari",
                            limits.recipients(), true)));
            case SIGN_SHEET -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.ARCHIVE_SIGN, expected,
                    "Semnare act", "Notează motivul semnăturii.",
                    java.util.List.of(new FormSessionUseCase.Field("reason", "Motiv",
                            limits.signatureReason(), false)));
            case COPY_SHEET -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.ARCHIVE_COPY, expected,
                    "Copie indigo", "Alege numărul de copii și destinatarii.",
                    java.util.List.of(
                            new FormSessionUseCase.Field("count", "Număr copii", 10, false),
                            new FormSessionUseCase.Field("targets", "Destinatari",
                                    limits.recipients(), true)));
            case PACK_ENVELOPE -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.ARCHIVE_ENVELOPE, expected,
                    "Plic oficial", "Indică destinatarul plicului.",
                    java.util.List.of(new FormSessionUseCase.Field("target", "Țintă",
                            limits.target(), false)));
            case ISSUE_DOCUMENT -> new FormSessionUseCase.Request(
                    FormSessionUseCase.Action.ARCHIVE_DOCUMENT_ISSUE, expected,
                    "Emitere act", "Indică jucătorul căruia îi este emis actul.",
                    java.util.List.of(new FormSessionUseCase.Field("target", "Țintă",
                            limits.target(), false)));
            default -> null;
        };
        if (request == null) return true;
        FormSessionBridge.open(serverPlayer, request);
        return true;
    }

    /**
     * Revalidates the action against the player's fresh available-actions
     * projection before dispatch, so a stale button reports "no longer
     * available" instead of reaching the service. Services still re-check
     * authoritatively at mutation time.
     */
    private static boolean isStillValidForPlayer(String operation, String id,
                                                 com.dwurdy.straja.bootstrap.StrajaRuntime runtime,
                                                 com.dwurdy.straja.application.port.out.PlayerGateway player) {
        return switch (operation) {
            case "faq" -> true;
            case "duty-checkpoint" -> {
                var view = runtime.guardDuty().dutyView(player);
                yield view != null && id.equals(view.checkpointId());
            }
            case "mission-join", "mission-accept", "mission-decline", "mission-complete",
                    "mission-report", "mission-fail", "mission-reward", "mission-reward-recover",
                    "mission-template-issue" -> {
                var expected = missionActionFor(operation);
                yield expected != null && runtime.missionRoleplay().availableActions(player)
                        .stream().anyMatch(a -> a.action() == expected && id.equals(a.missionId()));
            }
            case "custody-accept", "custody-refuse", "custody-release" -> {
                var expected = custodyActionFor(operation);
                yield expected != null && runtime.custodyRoleplay().availableActions(player)
                        .stream().anyMatch(a -> a.action() == expected && id.equals(a.recordId()));
            }
            case "complaint-claim", "complaint-join", "complaint-leave", "complaint-report",
                    "complaint-review", "complaint-confirm", "complaint-withdraw" -> {
                var expected = complaintActionFor(operation);
                yield expected != null && runtime.complaintRoleplay().availableActions(player)
                        .stream().anyMatch(a -> a.action() == expected && id.equals(a.complaintId()));
            }
            case "fine-pay", "fine-refuse", "fine-appeal", "fine-appeal-review",
                    "fine-task-accept", "fine-task-complete", "fine-task-arrest",
                    "fine-task-reward" -> {
                var expected = fineActionFor(operation);
                yield expected != null && runtime.fineRoleplay().availableActions(player)
                        .stream().anyMatch(a -> a.action() == expected && id.equals(a.recordId()));
            }
            case "archive-folder-read", "archive-folder-issue", "archive-sheet-new",
                    "archive-sheet-read", "archive-sheet-edit", "archive-recipients",
                    "archive-sheet-submit", "archive-sheet-sign", "archive-sheet-copy",
                    "archive-sheet-envelope", "archive-sheet-issue", "archive-sheet-revoke" -> {
                var expected = archiveActionFor(operation);
                yield expected != null && runtime.archiveRoleplay().availableActions(player)
                        .stream().anyMatch(a -> a.action() == expected && id.equals(a.recordId()));
            }
            case "report-review" -> runtime.reportRoleplay().availableActions(player)
                    .stream().anyMatch(a -> a.action()
                            == com.dwurdy.straja.application.port.in.ReportUseCase.Action.REVIEW
                            && id.equals(a.reportId()));
            case "audience-review" -> runtime.audienceRoleplay().availableActions(player)
                    .stream().anyMatch(a -> a.action()
                            == com.dwurdy.straja.application.port.in.AudienceUseCase.Action.REVIEW
                            && id.equals(a.requestId()));
            case "incident-accept", "incident-join", "incident-leave", "incident-resolve" ->
                    runtime.expansionRoleplay().activeIncidents(player).stream()
                            .anyMatch(incident -> id.equals(incident.id()));
            case "bolo-cancel" -> runtime.expansionRoleplay().activeBolos(player).stream()
                    .anyMatch(bolo -> id.equals(bolo.id()));
            case "evidence-deposit", "evidence-return", "evidence-transfer", "evidence-destroy" -> runtime.evidence().recordsFor(player).stream()
                    .anyMatch(record -> id.equals(record.id));
            case "admin-dossier", "admin-promote", "admin-demote", "admin-suspend",
                    "admin-fire", "admin-reinstate" -> {
                var expected = adminActionFor(operation);
                yield expected != null && runtime.adminRoleplay().isStillValid(player, expected, id);
            }
            case "tool-npc-assign" ->
                    // A first-time bind has no record yet — the button stays
                    // valid while the clicker is still an authorized holder;
                    // npcAssign re-gates and validates the role anyway.
                    id.length() > 37 && runtime.adminTools().isToolHolder(player);
            case "tool-npc-rename", "tool-npc-skin", "tool-npc-remove",
                    "tool-npc-remove-confirm", "tool-npc-record" ->
                    runtime.adminTools().npcStillRegistered(id);
            case "tool-survey-stamp" -> runtime.adminTools().surveyPending(player);
            case "armory-buy" -> runtime.armory().offers(player).stream()
                    .anyMatch(o -> !o.reserve() && id.equals(o.key()));
            case "armory-reserve" -> runtime.armory().offers(player).stream()
                    .anyMatch(o -> o.reserve() && id.equals(o.key()));
            default -> false;
        };
    }

    private static com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action
            adminActionFor(String operation) {
        return switch (operation) {
            case "admin-dossier" -> com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.DOSSIER;
            case "admin-promote" -> com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.PROMOTE;
            case "admin-demote" -> com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.DEMOTE;
            case "admin-suspend" -> com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.SUSPEND;
            case "admin-fire" -> com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.FIRE;
            case "admin-reinstate" -> com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.REINSTATE;
            default -> null;
        };
    }

    private static com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action
            missionActionFor(String operation) {
        return switch (operation) {
            case "mission-join" -> com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.JOIN;
            case "mission-accept" -> com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.ACCEPT;
            case "mission-decline" -> com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DECLINE;
            case "mission-complete" -> com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.COMPLETE;
            case "mission-report" -> com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.REPORT;
            case "mission-fail" -> com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.FAIL;
            case "mission-reward" -> com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.CLAIM_REWARD;
            case "mission-reward-recover" -> com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.RECOVER_REWARD;
            case "mission-template-issue" -> com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.ISSUE_TEMPLATE;
            default -> null;
        };
    }

    private static com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase.Action
            custodyActionFor(String operation) {
        return switch (operation) {
            case "custody-accept" -> com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase.Action.ACCEPT_REQUEST;
            case "custody-refuse" -> com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase.Action.REFUSE_REQUEST;
            case "custody-release" -> com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase.Action.RELEASE_TARGET;
            default -> null;
        };
    }

    private static com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase.Action
            complaintActionFor(String operation) {
        return switch (operation) {
            case "complaint-claim" -> com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase.Action.CLAIM;
            case "complaint-join" -> com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase.Action.JOIN;
            case "complaint-leave" -> com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase.Action.LEAVE;
            case "complaint-report" -> com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase.Action.REPORT;
            case "complaint-review" -> com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase.Action.REVIEW;
            case "complaint-confirm" -> com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase.Action.CONFIRM;
            case "complaint-withdraw" -> com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase.Action.WITHDRAW;
            default -> null;
        };
    }

    private static com.dwurdy.straja.application.port.in.FineRoleplayUseCase.Action
            fineActionFor(String operation) {
        return switch (operation) {
            case "fine-pay" -> com.dwurdy.straja.application.port.in.FineRoleplayUseCase.Action.PAY;
            case "fine-refuse" -> com.dwurdy.straja.application.port.in.FineRoleplayUseCase.Action.REFUSE;
            case "fine-appeal" -> com.dwurdy.straja.application.port.in.FineRoleplayUseCase.Action.APPEAL;
            case "fine-appeal-review" -> com.dwurdy.straja.application.port.in.FineRoleplayUseCase.Action.REVIEW_APPEAL;
            case "fine-task-accept" -> com.dwurdy.straja.application.port.in.FineRoleplayUseCase.Action.ACCEPT_TASK;
            case "fine-task-complete" -> com.dwurdy.straja.application.port.in.FineRoleplayUseCase.Action.COMPLETE_TASK;
            case "fine-task-arrest" -> com.dwurdy.straja.application.port.in.FineRoleplayUseCase.Action.ARREST_TASK;
            case "fine-task-reward" -> com.dwurdy.straja.application.port.in.FineRoleplayUseCase.Action.CLAIM_TASK_REWARD;
            default -> null;
        };
    }

    private static com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action
            archiveActionFor(String operation) {
        return switch (operation) {
            case "archive-folder-read" -> com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.READ_FOLDER;
            case "archive-folder-issue" -> com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.ISSUE_FOLDER;
            case "archive-sheet-new" -> com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.NEW_SHEET;
            case "archive-sheet-read" -> com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.READ_SHEET;
            case "archive-sheet-edit" -> com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.EDIT_SHEET;
            case "archive-recipients" -> com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.SET_RECIPIENTS;
            case "archive-sheet-submit" -> com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.SUBMIT_SHEET;
            case "archive-sheet-sign" -> com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.SIGN_SHEET;
            case "archive-sheet-copy" -> com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.COPY_SHEET;
            case "archive-sheet-envelope" -> com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.PACK_ENVELOPE;
            case "archive-sheet-issue" -> com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.ISSUE_DOCUMENT;
            case "archive-sheet-revoke" -> com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.REVOKE_SHEET;
            default -> null;
        };
    }

    private static List<NpcPlayerSurface.ChatAction> stateAwareActions(
            String roleId, Player player, ServerLevel level) {
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null) return List.of();
        var gateway = gateway(player, level);
        var actions = new ArrayList<NpcPlayerSurface.ChatAction>();
        switch (NpcPlayerSurface.routeFor(roleId)) {
            case SECRETARY -> {
                actions.addAll(NpcPlayerSurface.incidentActions(
                        runtime.expansionRoleplay().activeIncidents(gateway)));
                actions.addAll(NpcPlayerSurface.boloActions(
                        runtime.expansionRoleplay().activeBolos(gateway)));
                actions.addAll(NpcPlayerSurface.dutyActions(
                        runtime.guardDuty().dutyView(gateway)));
                actions.addAll(NpcPlayerSurface.missionActions(
                        runtime.missionRoleplay().availableActions(gateway)));
                actions.addAll(NpcPlayerSurface.complaintActions(
                        runtime.complaintRoleplay().availableActions(gateway),
                        NpcPlayerSurface.RoleRoute.SECRETARY));
                actions.addAll(NpcPlayerSurface.fineActions(
                        runtime.fineRoleplay().availableActions(gateway),
                        NpcPlayerSurface.RoleRoute.SECRETARY));
                actions.addAll(NpcPlayerSurface.reportActions(
                        runtime.reportRoleplay().availableActions(gateway),
                        NpcPlayerSurface.RoleRoute.SECRETARY));
                actions.addAll(NpcPlayerSurface.audienceActions(
                        runtime.audienceRoleplay().availableActions(gateway),
                        NpcPlayerSurface.RoleRoute.SECRETARY));
                actions.addAll(NpcPlayerSurface.adminActions(
                        runtime.adminRoleplay().availableActions(gateway)));
                if (runtime.playerQueries().isCommissioner(gateway)) {
                    actions.add(new NpcPlayerSurface.ChatAction("Istoric reputație", "reputation-view"));
                    actions.add(new NpcPlayerSurface.ChatAction("Corectează reputația", "reputation-correction"));
                    actions.add(new NpcPlayerSurface.ChatAction("Dosare de arest", "arrest-record-admin"));
                    actions.add(new NpcPlayerSurface.ChatAction("Probe și lanțul custodiei", "evidence-list"));
                }
            }
            case JAILER -> actions.addAll(NpcPlayerSurface.custodyActions(
                    runtime.custodyRoleplay().availableActions(gateway)));
            case ARCHIVIST -> {
                actions.addAll(NpcPlayerSurface.archiveActions(
                        runtime.archiveRoleplay().availableActions(gateway)));
                actions.addAll(NpcPlayerSurface.evidenceActions(
                        runtime.evidence().recordsFor(gateway)));
            }
            case TRAINER -> {
                var guardState = runtime.playerQueries().readState(gateway);
                // The physical Instructor also owns admission. Keep the
                // recruiter role alias working, but never spawn or require a
                // second admission NPC.
                if (guardState != null && !guardState.fired && !guardState.suspended && !guardState.resigned
                        && guardState.rank < com.dwurdy.straja.domain.model.Rank.STAGIAR.level()
                        && (guardState.invited || "APPLIED".equals(guardState.applicationState))) {
                    actions.add(new NpcPlayerSurface.ChatAction("Examen de admitere", "recruit"));
                    actions.add(new NpcPlayerSurface.ChatAction(
                            "Răspunde la examen (formular)", "quiz-answer"));
                }
                actions.addAll(NpcPlayerSurface.trainingActions(
                        runtime.guardRecruitment().trainingView(gateway)));
            }
            case ARMORER -> actions.addAll(NpcPlayerSurface.armoryActions(
                    runtime.armory().offers(gateway)));
            case RECEPTIONIST -> {
                actions.addAll(NpcPlayerSurface.complaintActions(
                        runtime.complaintRoleplay().availableActions(gateway),
                        NpcPlayerSurface.RoleRoute.RECEPTIONIST));
                actions.addAll(NpcPlayerSurface.fineActions(
                        runtime.fineRoleplay().availableActions(gateway),
                        NpcPlayerSurface.RoleRoute.RECEPTIONIST));
                actions.addAll(NpcPlayerSurface.roomActions(
                        runtime.roomRoleplay().canRelease(gateway)));
            }
            default -> {}
        }
        return actions;
    }

    private static com.dwurdy.straja.application.port.out.PlayerGateway gateway(
            Player player, ServerLevel level) {
        return new com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway(
                level.getServer(), player.getUUID());
    }

    public static boolean onHurt(String roleId, StrajaNpcEntity npc, DamageSource source, float amount) {
        // Generic NPCs stay immune. The jailer takes real damage when
        // jailerMayTakeDamage allows it so LivingDamage/Death events fire.
        return !(JAILER.equals(roleId) && jailerMayTakeDamage(npc, source));
    }

    /**
     * True when the source should damage the jailer. Damage must pass for
     * {@code LivingDamageEvent.Post}/{@code LivingDeathEvent} to reach the
     * civic assault service; when {@code jailerGuardImmunity} is on, on-duty
     * guards deal no damage (matching the service-level activeGuard filter).
     */
    public static boolean jailerMayTakeDamage(StrajaNpcEntity npc, DamageSource source) {
        if (!JAILER.equals(npc.getRoleId())) return false;
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null || !(npc.level() instanceof ServerLevel level)) return false;
        boolean onDutyGuard = false;
        if (source.getEntity() instanceof Player player) {
            onDutyGuard = runtime.playerQueries().isOnDutyGuard(gateway(player, level));
        }
        return runtime.policies().jailerDamageAllowed(onDutyGuard);
    }
}
