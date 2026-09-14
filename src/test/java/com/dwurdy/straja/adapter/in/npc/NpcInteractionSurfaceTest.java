package com.dwurdy.straja.adapter.in.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase;
import com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase;
import com.dwurdy.straja.application.port.in.FineRoleplayUseCase;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NpcInteractionSurfaceTest {
    static Stream<Arguments> roleRoutes() {
        return Stream.of(
                Arguments.of(NpcRoles.RECEPTIONIST, NpcPlayerSurface.RoleRoute.RECEPTIONIST),
                Arguments.of(NpcRoles.SECRETARY, NpcPlayerSurface.RoleRoute.SECRETARY),
                Arguments.of(NpcRoles.JAILER, NpcPlayerSurface.RoleRoute.JAILER),
                Arguments.of(NpcRoles.ARCHIVIST, NpcPlayerSurface.RoleRoute.ARCHIVIST),
                Arguments.of(NpcRoles.TRAINER, NpcPlayerSurface.RoleRoute.TRAINER));
    }

    @ParameterizedTest
    @MethodSource("roleRoutes")
    void routesEachKnownRoleToItsPlayerSurface(String roleId, NpcPlayerSurface.RoleRoute expected) {
        assertEquals(expected, NpcInteractionService.routeFor(roleId));
        assertEquals(expected, NpcPlayerSurface.surfaceFor(roleId).route());
    }

    @Test
    void unknownAndNullRolesUseTheUnknownRoute() {
        assertEquals(NpcPlayerSurface.RoleRoute.UNKNOWN, NpcInteractionService.routeFor(null));
        assertEquals(NpcPlayerSurface.RoleRoute.UNKNOWN, NpcInteractionService.routeFor("not-a-role"));
    }

    @ParameterizedTest
    @MethodSource("roleRoutes")
    void ordinaryNpcGuidanceContainsNoTypedGameplayCommands(
            String roleId, NpcPlayerSurface.RoleRoute ignored) {
        var surface = NpcPlayerSurface.surfaceFor(roleId);

        assertFalse(surface.visibleText().contains("/straja"),
                () -> roleId + " guidance must not tell players to type /straja");
        assertTrue(surface.actions().stream().allMatch(action ->
                        action.command().startsWith("/straja ")),
                "role actions remain clickable command integrations, not typed guidance");
    }

    @ParameterizedTest
    @MethodSource("roleRoutes")
    void clickableNpcActionsTargetTheProvenanceCheckedAdapter(
            String roleId, NpcPlayerSurface.RoleRoute ignored) {
        var surface = NpcPlayerSurface.surfaceFor(roleId);

        assertTrue(surface.actions().stream().allMatch(action ->
                        action.command("test-token").equals("/straja npc-action test-token")),
                () -> roleId + " must not click a permission-2 gameplay root");
    }

    @Test
    void npcActionTokensAreBoundToThePlayerAndConsumedOnce() {
        UUID player = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();
        String token = NpcInteractionService.issueActionToken(player, "rules");

        assertEquals("rules", NpcInteractionService.consumeActionToken(token, player));
        assertNull(NpcInteractionService.consumeActionToken(token, player));

        String otherToken = NpcInteractionService.issueActionToken(player, "rules");
        assertNull(NpcInteractionService.consumeActionToken(otherToken, otherPlayer));
        assertEquals("rules", NpcInteractionService.consumeActionToken(otherToken, player));
    }

    @Test
    void tokenPurgeKeepsLiveTokensUsable() {
        UUID player = UUID.randomUUID();
        String token = NpcInteractionService.issueActionToken(player, "rules");
        NpcInteractionService.purgeExpiredTokens();
        assertEquals("rules", NpcInteractionService.consumeActionToken(token, player),
                "purging must only drop expired tokens");
        assertNull(NpcInteractionService.consumeActionToken(token, player));
    }

    @Test
    void expiredTokensAreRejectedAndPurged() {
        UUID player = UUID.randomUUID();
        String token = NpcInteractionService.issueActionToken(player, "rules", 0L);
        assertNull(NpcInteractionService.consumeActionToken(token, player),
                "an expired token must never resolve an action");
        assertNull(NpcInteractionService.consumeActionToken(token, player),
                "the expired token stays dead on replay");
    }

    @Test
    void unknownAndMalformedTokensResolveNothing() {
        UUID player = UUID.randomUUID();
        assertNull(NpcInteractionService.consumeActionToken(null, player));
        assertNull(NpcInteractionService.consumeActionToken("not-a-token", player));
        assertNull(NpcInteractionService.consumeActionToken("", player));
        assertNull(NpcInteractionService.consumeActionToken("abc", null));
    }

    @Test
    void parameterizedActionIdsCarryValidatedOperationAndRecordProvenance() {
        var action = NpcPlayerSurface.recordAction("Acceptă #7", "mission-accept", " 7 ");

        assertEquals("mission-accept:7", action.actionId());
        var parsed = NpcPlayerSurface.parseActionId(action.actionId()).orElseThrow();
        assertEquals("mission-accept", parsed.operation());
        assertEquals("7", parsed.recordId());
        assertTrue(NpcPlayerSurface.parameterizedActionId("mission-accept", "M:7").isEmpty());
        assertTrue(NpcPlayerSurface.parameterizedActionId("mission-accept", "").isEmpty());
        assertTrue(NpcPlayerSurface.parameterizedActionId("not-an-action", "7").isEmpty());
    }

    @Test
    void npcInteractionPlanIsGuidanceOnlyAndDoesNotExecuteRoleActions() {
        var plan = NpcPlayerSurface.interactionPlan(NpcRoles.RECEPTIONIST);

        assertFalse(plan.executesRoleAction());
        assertFalse(plan.surface().actions().isEmpty());
    }

    @Test
    void applicationAtReceptionistExamAtRecruiterTrainingAtInstructor() {
        var receptionist = NpcPlayerSurface.surfaceFor(NpcRoles.RECEPTIONIST);
        var receptionistIds = receptionist.actions().stream()
                .map(NpcPlayerSurface.ChatAction::actionId).toList();
        assertTrue(receptionistIds.contains("application-submit"),
                "the receptionist records the application (§5)");
        assertTrue(receptionistIds.stream()
                        .noneMatch(a -> a.equals("recruit") || a.equals("quiz-answer")),
                "the exam moved to the recruiter");

        var recruiter = NpcPlayerSurface.surfaceFor(NpcRoles.RECRUITER);
        assertEquals(NpcPlayerSurface.RoleRoute.RECRUITER, recruiter.route());
        var recruiterIds = recruiter.actions().stream()
                .map(NpcPlayerSurface.ChatAction::actionId).toList();
        assertTrue(recruiterIds.containsAll(java.util.List.of("recruit", "quiz-answer")),
                "the recruiter runs the admission exam");

        var trainer = NpcPlayerSurface.surfaceFor(NpcRoles.TRAINER);
        var trainerIds = trainer.actions().stream().map(NpcPlayerSurface.ChatAction::actionId).toList();
        assertTrue(trainerIds.containsAll(java.util.List.of("training-progress", "training-manual")));
        assertTrue(trainerIds.stream().noneMatch(a -> a.equals("recruit") || a.equals("quiz-answer")),
                "the trainer no longer offers the entry exam");
    }

    @Test
    void trainingActionsEmitPromotionOnlyWhenEligible() {
        var eligible = NpcPlayerSurface.trainingActions(
                new com.dwurdy.straja.application.port.in.GuardRecruitmentUseCase.TrainingView(
                        1, 60, 2, 60, true, false, 0));
        assertEquals(java.util.List.of("training-promote"),
                eligible.stream().map(NpcPlayerSurface.ChatAction::actionId).toList());

        var blocked = NpcPlayerSurface.trainingActions(
                new com.dwurdy.straja.application.port.in.GuardRecruitmentUseCase.TrainingView(
                        1, 30, 2, 60, false, false, 0));
        assertTrue(blocked.isEmpty());
        assertTrue(NpcPlayerSurface.trainingActions(null).isEmpty());
    }

    @Test
    void trainingActionsMintModuleQuizOnlyWithPendingModules() {
        var training = NpcPlayerSurface.trainingActions(
                new com.dwurdy.straja.application.port.in.GuardRecruitmentUseCase.TrainingView(
                        2, 30, null, null, false, true, 2));
        assertEquals(java.util.List.of("quiz-answer"),
                training.stream().map(NpcPlayerSurface.ChatAction::actionId).toList());
    }

    private static com.dwurdy.straja.application.port.in.GuardDutyUseCase.DutyView view(
            boolean activeGuard, boolean canStart, String checkpointId, boolean canStop,
            boolean canClaimSalary, boolean canViewCoins, boolean canClaimFood,
            boolean canClaimKit, boolean canRequestRegear, boolean canBeginResignation,
            boolean canConfirmResignation, boolean canCancelResignation, boolean canRejoin) {
        return new com.dwurdy.straja.application.port.in.GuardDutyUseCase.DutyView(
                activeGuard, canStart, checkpointId, canStop, canClaimSalary, canViewCoins,
                canClaimFood, canClaimKit, canRequestRegear, canBeginResignation,
                canConfirmResignation, canCancelResignation, canRejoin);
    }

    @Test
    void dutyActionsMapperEmitsEveryEnabledAction() {
        var actions = NpcPlayerSurface.dutyActions(
                view(true, true, "checkpoint_2", true, true, true, true, true, true,
                        true, true, true, true));
        var ids = actions.stream().map(NpcPlayerSurface.ChatAction::actionId).toList();
        assertEquals(java.util.List.of(
                "duty-start", "duty-checkpoint:checkpoint_2", "duty-stop", "duty-salary",
                "duty-coins", "duty-food", "duty-kit", "duty-regear",
                "resignation-start", "resignation-confirm", "resignation-cancel", "rejoin"), ids);
    }

    @Test
    void dutyActionsMapperOmitsDisabledActions() {
        var actions = NpcPlayerSurface.dutyActions(
                view(true, false, "", false, false, true, false, false, false,
                        false, false, false, false));
        var ids = actions.stream().map(NpcPlayerSurface.ChatAction::actionId).toList();
        assertEquals(java.util.List.of("duty-coins"), ids);
        var civil = NpcPlayerSurface.dutyActions(
                view(false, false, "", false, false, false, false, false, false,
                        false, false, false, false));
        assertTrue(civil.isEmpty());
    }

    @Test
    void dutyCheckpointActionParsesAndRejectsMalformed() {
        var actions = NpcPlayerSurface.dutyActions(
                view(true, false, "checkpoint_1", false, false, false, false, false,
                        false, false, false, false, false));
        var parsed = NpcPlayerSurface.parseActionId(
                actions.get(0).actionId()).orElseThrow();
        assertEquals("duty-checkpoint", parsed.operation());
        assertEquals("checkpoint_1", parsed.recordId());
        assertTrue(NpcPlayerSurface.parseActionId("duty-checkpoint:bad id").isEmpty());
        assertTrue(NpcPlayerSurface.parseActionId("duty-checkpoint:").isEmpty());
        assertTrue(NpcPlayerSurface.parameterizedActionId("duty-checkpoint", "").isEmpty());
        var none = NpcPlayerSurface.dutyActions(
                view(true, true, "", false, false, false, false, false, false,
                        false, false, false, false));
        assertTrue(none.stream().noneMatch(a -> a.actionId().startsWith("duty-checkpoint")),
                "an empty checkpoint id must not emit a parameterized action");
    }

    private static com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.AvailableAction
            missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action action,
                          String missionId) {
        return new com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.AvailableAction(
                action, missionId);
    }

    @Test
    void missionActionsMapperEmitsEveryAvailableAction() {
        var actions = NpcPlayerSurface.missionActions(java.util.List.of(
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.GET_CARNET, ""),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DRAFT_WRITE, ""),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DRAFT_STATUS, ""),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DRAFT_SCOPE, ""),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DRAFT_SIGN, ""),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DRAFT_PACKAGE, ""),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.JOIN, "M-7"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.ACCEPT, "M-7"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DECLINE, "M-7"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.REPORT, "M-7"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.FAIL, "M-7"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.COMPLETE, "M-7"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.CLAIM_REWARD, "M-7"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.RECOVER_REWARD, "M-7")));
        var ids = actions.stream().map(NpcPlayerSurface.ChatAction::actionId).toList();
        assertEquals(java.util.List.of(
                "mission-carnet", "mission-draft-write", "mission-draft-status",
                "mission-draft-scope", "mission-draft-sign", "mission-draft-package",
                "mission-join:M-7", "mission-accept:M-7", "mission-decline:M-7",
                "mission-report:M-7", "mission-fail:M-7", "mission-complete:M-7",
                "mission-reward:M-7", "mission-reward-recover:M-7"), ids);
    }

    @Test
    void missionActionsRejectEmptyOrMalformedRecordIds() {
        var actions = NpcPlayerSurface.missionActions(java.util.List.of(
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.JOIN, ""),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.ACCEPT, "bad id"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.REPORT, "M-7")));
        var ids = actions.stream().map(NpcPlayerSurface.ChatAction::actionId).toList();
        assertEquals(java.util.List.of("mission-report:M-7"), ids);
        var parsed = NpcPlayerSurface.parseActionId("mission-report:M-7").orElseThrow();
        assertEquals("mission-report", parsed.operation());
        assertEquals("M-7", parsed.recordId());
        assertTrue(NpcPlayerSurface.missionActions(null).isEmpty());
        assertTrue(NpcPlayerSurface.missionActions(java.util.List.of()).isEmpty());
    }

    @Test
    void missionActionsContainNoTypedCommands() {
        var actions = NpcPlayerSurface.missionActions(java.util.List.of(
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.GET_CARNET, ""),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DRAFT_WRITE, ""),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DRAFT_STATUS, ""),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DRAFT_SCOPE, ""),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DRAFT_SIGN, ""),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DRAFT_PACKAGE, ""),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.JOIN, "M-7"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.ACCEPT, "M-7"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.DECLINE, "M-7"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.REPORT, "M-7"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.FAIL, "M-7"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.COMPLETE, "M-7"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.CLAIM_REWARD, "M-7"),
                missionAction(com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.Action.RECOVER_REWARD, "M-7")));
        for (var action : actions) {
            assertFalse(action.label().contains("/straja"),
                    () -> action.label() + " must not reference typed commands");
        }
    }

    private static ComplaintRoleplayUseCase.AvailableAction
            complaintAction(ComplaintRoleplayUseCase.Action action, String complaintId) {
        return new ComplaintRoleplayUseCase.AvailableAction(action, complaintId);
    }

    private static java.util.List<ComplaintRoleplayUseCase.AvailableAction>
            allComplaintActions(String id) {
        return java.util.List.of(
                complaintAction(ComplaintRoleplayUseCase.Action.SUBMIT, ""),
                complaintAction(ComplaintRoleplayUseCase.Action.LIST, ""),
                complaintAction(ComplaintRoleplayUseCase.Action.CLAIM, id),
                complaintAction(ComplaintRoleplayUseCase.Action.JOIN, id),
                complaintAction(ComplaintRoleplayUseCase.Action.LEAVE, id),
                complaintAction(ComplaintRoleplayUseCase.Action.REPORT, id),
                complaintAction(ComplaintRoleplayUseCase.Action.CONFIRM, id),
                complaintAction(ComplaintRoleplayUseCase.Action.WITHDRAW, id),
                complaintAction(ComplaintRoleplayUseCase.Action.REVIEW, id));
    }

    @Test
    void complaintActionsMapperEmitsRoleAwareIds() {
        var available = allComplaintActions("C-3");
        var receptionist = NpcPlayerSurface.complaintActions(
                available, NpcPlayerSurface.RoleRoute.RECEPTIONIST);
        assertEquals(java.util.List.of("complaint-submit", "complaint-confirm:C-3",
                "complaint-withdraw:C-3"),
                receptionist.stream().map(NpcPlayerSurface.ChatAction::actionId).toList());
        var secretary = NpcPlayerSurface.complaintActions(
                available, NpcPlayerSurface.RoleRoute.SECRETARY);
        assertEquals(java.util.List.of("complaint-list", "complaint-claim:C-3",
                "complaint-join:C-3", "complaint-leave:C-3", "complaint-report:C-3",
                "complaint-review:C-3"),
                secretary.stream().map(NpcPlayerSurface.ChatAction::actionId).toList());
    }

    @Test
    void complaintActionsRejectMalformedIdsAndContainNoTypedCommands() {
        var actions = NpcPlayerSurface.complaintActions(java.util.List.of(
                complaintAction(ComplaintRoleplayUseCase.Action.CLAIM, "bad id"),
                complaintAction(ComplaintRoleplayUseCase.Action.CLAIM, ""),
                complaintAction(ComplaintRoleplayUseCase.Action.REPORT, "C-3")),
                NpcPlayerSurface.RoleRoute.SECRETARY);
        assertEquals(java.util.List.of("complaint-report:C-3"),
                actions.stream().map(NpcPlayerSurface.ChatAction::actionId).toList());
        for (var route : NpcPlayerSurface.RoleRoute.values()) {
            for (var action : NpcPlayerSurface.complaintActions(allComplaintActions("C-3"), route)) {
                assertFalse(action.label().contains("/straja"),
                        () -> action.label() + " must not reference typed commands");
            }
        }
        assertTrue(NpcPlayerSurface.complaintActions(null, NpcPlayerSurface.RoleRoute.UNKNOWN).isEmpty());
    }

    private static FineRoleplayUseCase.AvailableAction
            fineAction(FineRoleplayUseCase.Action action, String recordId) {
        return new FineRoleplayUseCase.AvailableAction(action, recordId);
    }

    private static java.util.List<FineRoleplayUseCase.AvailableAction>
            allFineActions(String fineId, String taskId) {
        return java.util.List.of(
                fineAction(FineRoleplayUseCase.Action.DRAFT_WRITE, ""),
                fineAction(FineRoleplayUseCase.Action.DRAFT_STATUS, ""),
                fineAction(FineRoleplayUseCase.Action.PAY, fineId),
                fineAction(FineRoleplayUseCase.Action.REFUSE, taskId),
                fineAction(FineRoleplayUseCase.Action.APPEAL, fineId),
                fineAction(FineRoleplayUseCase.Action.LIST_APPEALS, ""),
                fineAction(FineRoleplayUseCase.Action.REVIEW_APPEAL, fineId),
                fineAction(FineRoleplayUseCase.Action.LIST_TASKS, ""),
                fineAction(FineRoleplayUseCase.Action.ACCEPT_TASK, taskId),
                fineAction(FineRoleplayUseCase.Action.COMPLETE_TASK, taskId),
                fineAction(FineRoleplayUseCase.Action.ARREST_TASK, taskId),
                fineAction(FineRoleplayUseCase.Action.CLAIM_TASK_REWARD, taskId),
                fineAction(FineRoleplayUseCase.Action.HEARING_WARRANT, ""));
    }

    @Test
    void fineActionsMapperEmitsRoleAwareIds() {
        var available = allFineActions("F-3", "T-7");
        var receptionist = NpcPlayerSurface.fineActions(
                available, NpcPlayerSurface.RoleRoute.RECEPTIONIST);
        assertEquals(java.util.List.of("fine-pay:F-3", "fine-refuse:T-7", "fine-appeal:F-3",
                "fine-appeal-list", "fine-appeal-review:F-3"),
                receptionist.stream().map(NpcPlayerSurface.ChatAction::actionId).toList());
        var secretary = NpcPlayerSurface.fineActions(
                available, NpcPlayerSurface.RoleRoute.SECRETARY);
        assertEquals(java.util.List.of("fine-draft-write", "fine-draft-status", "fine-task-list",
                "fine-task-accept:T-7", "fine-task-complete:T-7", "fine-task-arrest:T-7",
                "fine-task-reward:T-7", "fine-warrant"),
                secretary.stream().map(NpcPlayerSurface.ChatAction::actionId).toList());
    }

    @Test
    void fineActionsRejectMalformedIdsAndContainNoTypedCommands() {
        var actions = NpcPlayerSurface.fineActions(java.util.List.of(
                fineAction(FineRoleplayUseCase.Action.PAY, "bad id"),
                fineAction(FineRoleplayUseCase.Action.APPEAL, ""),
                fineAction(FineRoleplayUseCase.Action.APPEAL, "F-3")),
                NpcPlayerSurface.RoleRoute.RECEPTIONIST);
        assertEquals(java.util.List.of("fine-appeal:F-3"),
                actions.stream().map(NpcPlayerSurface.ChatAction::actionId).toList());
        for (var route : NpcPlayerSurface.RoleRoute.values()) {
            for (var action : NpcPlayerSurface.fineActions(allFineActions("F-3", "T-7"), route)) {
                assertFalse(action.label().contains("/straja"),
                        () -> action.label() + " must not reference typed commands");
            }
        }
        assertTrue(NpcPlayerSurface.fineActions(null, NpcPlayerSurface.RoleRoute.UNKNOWN).isEmpty());
    }

    private static CustodyRoleplayUseCase.AvailableAction
            custodyAction(CustodyRoleplayUseCase.Action action, String recordId) {
        return new CustodyRoleplayUseCase.AvailableAction(action, recordId);
    }

    @Test
    void custodyActionsMapperEmitsExactIds() {
        String targetUuid = UUID.randomUUID().toString();
        var available = java.util.List.of(
                custodyAction(CustodyRoleplayUseCase.Action.ACCEPT_REQUEST, "C7"),
                custodyAction(CustodyRoleplayUseCase.Action.REFUSE_REQUEST, "C7"),
                custodyAction(CustodyRoleplayUseCase.Action.RELEASE_TARGET, targetUuid),
                custodyAction(CustodyRoleplayUseCase.Action.RELEASE_TARGET, targetUuid),
                custodyAction(CustodyRoleplayUseCase.Action.REMOVE_HEAD_SACK, ""),
                custodyAction(CustodyRoleplayUseCase.Action.WAKE_DOWNED, ""),
                custodyAction(CustodyRoleplayUseCase.Action.GIVE_CUFFS, ""));
        var actions = NpcPlayerSurface.custodyActions(available);
        assertEquals(java.util.List.of(
                        "custody-accept:C7", "custody-refuse:C7",
                        "custody-release:" + targetUuid,
                        "custody-remove-head-sack", "custody-wake-downed", "cuffs-item"),
                actions.stream().map(NpcPlayerSurface.ChatAction::actionId).toList());
    }

    @Test
    void custodyActionsRejectMalformedIdsAndContainNoTypedCommands() {
        var actions = NpcPlayerSurface.custodyActions(java.util.List.of(
                custodyAction(CustodyRoleplayUseCase.Action.ACCEPT_REQUEST, "bad id"),
                custodyAction(CustodyRoleplayUseCase.Action.RELEASE_TARGET, "bad id"),
                custodyAction(CustodyRoleplayUseCase.Action.RELEASE_TARGET, ""),
                custodyAction(CustodyRoleplayUseCase.Action.REFUSE_REQUEST, "C-9")));
        assertEquals(java.util.List.of("custody-refuse:C-9"),
                actions.stream().map(NpcPlayerSurface.ChatAction::actionId).toList());
        for (var action : actions) {
            assertFalse(action.label().contains("/straja"),
                    () -> action.label() + " must not reference typed commands");
            assertFalse(action.actionId().startsWith("emergency")
                            || action.actionId().startsWith("special"),
                    () -> action.actionId() + " must not be an admin action");
        }
        assertTrue(NpcPlayerSurface.custodyActions(null).isEmpty());
        assertTrue(NpcPlayerSurface.custodyActions(java.util.Arrays.asList(
                        new CustodyRoleplayUseCase.AvailableAction(null, "C1"),
                        null)).isEmpty());
    }

    private static com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.AvailableAction
            archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action action,
                          String recordId) {
        return new com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.AvailableAction(
                action, recordId);
    }

    @Test
    void archiveActionsMapperEmitsExactIds() {
        var actions = NpcPlayerSurface.archiveActions(java.util.List.of(
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.LIST, ""),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.CREATE_FOLDER, ""),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.READ_FOLDER, "D-1"),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.ISSUE_FOLDER, "D-1"),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.NEW_SHEET, "D-1"),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.READ_SHEET, "A-1"),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.EDIT_SHEET, "A-1"),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.SET_RECIPIENTS, "A-1"),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.SUBMIT_SHEET, "A-1"),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.SIGN_SHEET, "A-1"),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.COPY_SHEET, "A-1"),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.PACK_ENVELOPE, "A-1"),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.ISSUE_DOCUMENT, "A-1"),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.REVOKE_SHEET, "A-1")));
        assertEquals(java.util.List.of(
                        "archive-list", "archive-folder-create",
                        "archive-folder-read:D-1", "archive-folder-issue:D-1",
                        "archive-sheet-new:D-1", "archive-sheet-read:A-1",
                        "archive-sheet-edit:A-1", "archive-recipients:A-1",
                        "archive-sheet-submit:A-1", "archive-sheet-sign:A-1",
                        "archive-sheet-copy:A-1", "archive-sheet-envelope:A-1",
                        "archive-sheet-issue:A-1", "archive-sheet-revoke:A-1"),
                actions.stream().map(NpcPlayerSurface.ChatAction::actionId).toList());
    }

    @Test
    void archiveActionsRejectMalformedIdsAndContainNoTypedCommands() {
        var actions = NpcPlayerSurface.archiveActions(java.util.List.of(
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.READ_SHEET, "bad id"),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.SIGN_SHEET, ""),
                archiveAction(com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.REVOKE_SHEET, "A-9")));
        assertEquals(java.util.List.of("archive-sheet-revoke:A-9"),
                actions.stream().map(NpcPlayerSurface.ChatAction::actionId).toList());
        for (var action : actions) {
            assertFalse(action.label().contains("/straja"),
                    () -> action.label() + " must not reference typed commands");
            assertFalse(action.actionId().startsWith("archive-role"),
                    () -> action.actionId() + " must not be an admin role grant");
        }
        assertTrue(NpcPlayerSurface.archiveActions(null).isEmpty());
        assertTrue(NpcPlayerSurface.archiveActions(java.util.Arrays.asList(
                        new com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase
                                .AvailableAction(null, "A-1"), null)).isEmpty());
    }

    private static com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.AvailableAction
            adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action action,
                        String memberId, String memberName) {
        return new com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.AvailableAction(
                action, memberId, memberName);
    }

    @Test
    void adminActionsMapperEmitsExactIds() {
        String member = UUID.randomUUID().toString();
        var actions = NpcPlayerSurface.adminActions(java.util.List.of(
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.PERSONNEL, "", ""),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.ROSTER_ACTIVE, "", ""),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.AUTHORIZE, "", ""),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.DOSSIER, member, "g1"),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.PROMOTE, member, "g1"),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.DEMOTE, member, "g1"),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.SUSPEND, member, "g1"),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.FIRE, member, "g1"),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.REINSTATE, member, "g1"),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.POLICIES, "", ""),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.POLICY_SET, "", ""),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.EMERGENCY_STATUS, "", ""),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.EMERGENCY_ALERT, "", ""),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.EMERGENCY_START, "", ""),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.EMERGENCY_END, "", "")));
        assertEquals(java.util.List.of(
                        "admin-personnel", "admin-roster", "admin-authorize",
                        "admin-dossier:" + member, "admin-promote:" + member,
                        "admin-demote:" + member, "admin-suspend:" + member,
                        "admin-fire:" + member, "admin-reinstate:" + member,
                        "admin-policies", "admin-policy-set",
                        "admin-emergency-status", "admin-emergency-alert",
                        "admin-emergency-start", "admin-emergency-end"),
                actions.stream().map(NpcPlayerSurface.ChatAction::actionId).toList());
    }

    @Test
    void adminActionsRejectMalformedIdsAndContainNoTypedCommands() {
        var actions = NpcPlayerSurface.adminActions(java.util.Arrays.asList(
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.DOSSIER, "bad id", "x"),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.FIRE, "", "x"),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.PROMOTE, null, "x"),
                adminAction(com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.Action.PERSONNEL, "", ""),
                adminAction(null, "A-1", "x"),
                null));
        assertEquals(java.util.List.of("admin-personnel"),
                actions.stream().map(NpcPlayerSurface.ChatAction::actionId).toList(),
                "member-scoped actions with unusable ids must be dropped");
        for (var action : actions) {
            assertFalse(action.label().contains("/straja"),
                    () -> action.label() + " must not reference typed commands");
        }
        assertTrue(NpcPlayerSurface.adminActions(null).isEmpty());
        assertTrue(NpcPlayerSurface.adminActions(java.util.List.of()).isEmpty());
    }

    @Test
    void dutyActionsContainNoTypedCommandsOrAdminActions() {
        var actions = NpcPlayerSurface.dutyActions(
                view(true, true, "checkpoint_2", true, true, true, true, true, true,
                        true, true, true, true));
        for (var action : actions) {
            assertFalse(action.label().contains("/straja"),
                    () -> action.label() + " must not reference typed commands");
            assertFalse(action.actionId().startsWith("special")
                            || action.actionId().startsWith("promote")
                            || action.actionId().startsWith("demote")
                            || action.actionId().startsWith("suspend")
                            || action.actionId().startsWith("fire"),
                    () -> action.actionId() + " must not be an admin/special-duty action");
        }
    }

}
