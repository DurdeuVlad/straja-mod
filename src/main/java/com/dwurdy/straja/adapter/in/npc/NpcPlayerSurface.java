package com.dwurdy.straja.adapter.in.npc;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Minecraft-free role plan shared by NPC routing and its player guidance. */
final class NpcPlayerSurface {
    static final String NPC_ACTION_COMMAND = "/straja npc-action ";
    private static final Pattern RECORD_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final Set<String> PARAMETERIZED_ACTIONS = Set.of(
            "mission-join", "mission-accept", "mission-decline", "mission-report", "mission-fail",
            "mission-complete", "mission-reward", "mission-reward-recover", "mission-template-issue",
            "custody-accept", "custody-refuse", "custody-release",
            "complaint-claim", "complaint-join", "complaint-leave", "complaint-report",
            "complaint-review", "complaint-confirm", "complaint-withdraw",
            "fine-pay", "fine-refuse", "fine-task-accept", "fine-task-complete", "fine-task-arrest",
            "fine-task-reward", "fine-appeal", "fine-appeal-review",
            "archive-folder-read", "archive-folder-issue", "archive-sheet-new",
            "archive-sheet-read", "archive-sheet-edit", "archive-recipients",
            "archive-sheet-submit", "archive-sheet-sign", "archive-sheet-copy",
            "archive-sheet-envelope", "archive-sheet-issue", "archive-sheet-revoke",
            "report-review", "audience-review",
            "faq",
            "admin-dossier", "admin-promote", "admin-demote", "admin-suspend",
            "admin-fire", "admin-reinstate",
            "tool-npc-assign", "tool-npc-rename", "tool-npc-skin",
            "tool-npc-remove", "tool-npc-remove-confirm", "tool-npc-record",
            "tool-survey-stamp",
            "armory-buy", "armory-reserve",
            "duty-checkpoint");

    enum RoleRoute { RECEPTIONIST, SECRETARY, JAILER, ARCHIVIST, TRAINER, RECRUITER, ARMORER, UNKNOWN }

    record ChatAction(String label, String actionId) {
        /** Command shape used by the surface; the actual token is player-bound. */
        String command() {
            return NPC_ACTION_COMMAND + "<token>";
        }

        String command(String token) {
            return NPC_ACTION_COMMAND + token;
        }
    }

    record RoleSurface(RoleRoute route, String title, String guidance, List<ChatAction> actions) {
        String visibleText() {
            return title + " " + guidance + " "
                    + String.join(" ", actions.stream().map(ChatAction::label).toList());
        }
    }

    record ActionRef(String operation, String recordId) {}

    record InteractionPlan(RoleSurface surface, boolean executesRoleAction) {}

    private NpcPlayerSurface() {}

    static RoleRoute routeFor(String roleId) {
        return switch (roleId == null ? "" : roleId) {
            case NpcRoles.RECEPTIONIST -> RoleRoute.RECEPTIONIST;
            case NpcRoles.SECRETARY -> RoleRoute.SECRETARY;
            case NpcRoles.JAILER -> RoleRoute.JAILER;
            case NpcRoles.ARCHIVIST -> RoleRoute.ARCHIVIST;
            // Recruiter remains a persisted/CLI alias for old worlds, but the
            // physical player-facing role is now the Instructor.
            case NpcRoles.TRAINER, NpcRoles.RECRUITER -> RoleRoute.TRAINER;
            case NpcRoles.ARMORER -> RoleRoute.ARMORER;
            default -> RoleRoute.UNKNOWN;
        };
    }

    static RoleSurface surfaceFor(String roleId) {
        return switch (routeFor(roleId)) {
            case RECEPTIONIST -> new RoleSurface(
                    RoleRoute.RECEPTIONIST,
                    "Recepție",
                    "Depui aici cererea de admitere, alegi regulamentul Străjii, verifici situația, amenzile, camera sau facțiunea nativă; examenul și instruirea se fac la Instructor.",
                    List.of(
                            new ChatAction("Depune cererea", "application-submit"),
                            new ChatAction("Regulament", "rules"),
                            new ChatAction("Stare Străjer", "guard-status"),
                            new ChatAction("Amenzile mele", "fine-list"),
                            new ChatAction("Stare cameră", "room-status"),
                            new ChatAction("Declară facțiunea nativă", "faction-declare"),
                            faqEntry(RoleRoute.RECEPTIONIST)));
            case TRAINER -> new RoleSurface(
                    RoleRoute.TRAINER,
                    "Instructor",
                    "Aici se fac recrutarea și instruirea: examen de admitere, module teoretice, puncte de serviciu și avansări; manualul se ridică tot de la mine.",
                    List.of(
                            new ChatAction("Progres și puncte", "training-progress"),
                            new ChatAction("Manual de instruire", "training-manual"),
                            faqEntry(RoleRoute.TRAINER)));
            case RECRUITER -> surfaceFor(NpcRoles.TRAINER);
            case SECRETARY -> new RoleSurface(
                    RoleRoute.SECRETARY,
                    "Secretariat",
                    "Ține o carte în mâna principală ca să primești o copie; îți arăt și misiunile vizibile, Carnetul de Ordine și acțiunile de serviciu disponibile.",
                    List.of(
                            new ChatAction("Misiunile mele", "mission-list"),
                            new ChatAction("Carnet de ordine", "mission-carnet"),
                            new ChatAction("Stare serviciu", "guard-status"),
                            faqEntry(RoleRoute.SECRETARY)));
            case JAILER -> new RoleSurface(
                    RoleRoute.JAILER,
                    "Temniță",
                    "Verifică situația custodiei și a sentinței tale; recuperarea după leșin respectă cooldown-ul persistat.",
                    List.of(
                            new ChatAction("Stare custodie", "cuffs-status"),
                            new ChatAction("Stare sentință", "prison-status"),
                            new ChatAction("Stare leșin", "downed-status"),
                            new ChatAction("Primește cătușe", "cuffs-item"),
                            faqEntry(RoleRoute.JAILER)));
            case ARCHIVIST -> new RoleSurface(
                    RoleRoute.ARCHIVIST,
                    "Arhivă",
                    "Îți arăt dosarele pe care ai voie să le citești.",
                    List.of(new ChatAction("Vezi dosarele", "archive-list"),
                            faqEntry(RoleRoute.ARCHIVIST)));
            case ARMORER -> new RoleSurface(
                    RoleRoute.ARMORER,
                    "Armurier",
                    "Vând echipament de rang pe monede și rezerve pe puncte de rechiziție; ofertele depind de rangul tău.",
                    List.of(
                            new ChatAction("Vezi ofertele", "armory-status"),
                            new ChatAction("Kit de serviciu", "duty-kit"),
                            faqEntry(RoleRoute.ARMORER)));
            case UNKNOWN -> new RoleSurface(
                    RoleRoute.UNKNOWN,
                    "Straja",
                    "Acest NPC nu are un rol Straja configurat.",
                    List.of());
        };
    }

    /** Pure interaction contract: opening an NPC only presents its surface. */
    static InteractionPlan interactionPlan(String roleId) {
        return new InteractionPlan(surfaceFor(roleId), false);
    }

    static RoleSurface withAdditionalActions(RoleSurface surface, List<ChatAction> additional) {
        if (additional == null || additional.isEmpty()) return surface;
        var actions = new java.util.ArrayList<>(surface.actions());
        actions.addAll(additional);
        return new RoleSurface(surface.route(), surface.title(), surface.guidance(), List.copyOf(actions));
    }

    static ChatAction faqEntry(RoleRoute origin) {
        return NpcFaqSurface.entry(origin);
    }

    /** Builds an action only for a record ID that can be carried safely by a chat token. */
    static ChatAction recordAction(String label, String operation, String recordId) {
        return new ChatAction(label, parameterizedActionId(operation, recordId).orElseThrow(
                () -> new IllegalArgumentException("Invalid NPC record action: " + operation + ":" + recordId)));
    }

    static Optional<String> parameterizedActionId(String operation, String recordId) {
        String id = recordId == null ? "" : recordId.trim();
        if (!PARAMETERIZED_ACTIONS.contains(operation) || !RECORD_ID.matcher(id).matches()) {
            return Optional.empty();
        }
        return Optional.of(operation + ":" + id);
    }

    /** Maps the trusted duty view to clickable actions; pure and state-free. */
    static List<ChatAction> dutyActions(
            com.dwurdy.straja.application.port.in.GuardDutyUseCase.DutyView view) {
        var actions = new java.util.ArrayList<ChatAction>();
        if (view == null) return List.of();
        if (view.canStart()) {
            actions.add(new ChatAction("Începe serviciul", "duty-start"));
        }
        if (view.checkpointId() != null && !view.checkpointId().isEmpty()) {
            parameterizedActionId("duty-checkpoint", view.checkpointId())
                    .ifPresent(id -> actions.add(new ChatAction("Activează checkpoint", id)));
        }
        if (view.canStop()) {
            actions.add(new ChatAction("Încheie serviciul", "duty-stop"));
        }
        if (view.canClaimSalary()) {
            actions.add(new ChatAction("Ridică salariul", "duty-salary"));
        }
        if (view.canViewCoins()) {
            actions.add(new ChatAction("Monede", "duty-coins"));
        }
        if (view.canClaimFood()) {
            actions.add(new ChatAction("Hrană de serviciu", "duty-food"));
        }
        if (view.canClaimKit()) {
            actions.add(new ChatAction("Kit de serviciu", "duty-kit"));
        }
        if (view.canBeginResignation()) {
            actions.add(new ChatAction("Începe demisia", "resignation-start"));
        }
        if (view.canConfirmResignation()) {
            actions.add(new ChatAction("Confirmă demisia", "resignation-confirm"));
        }
        if (view.canCancelResignation()) {
            actions.add(new ChatAction("Anulează demisia", "resignation-cancel"));
        }
        if (view.canRejoin()) {
            actions.add(new ChatAction("Reîntoarcere în Strajă", "rejoin"));
        }
        return List.copyOf(actions);
    }

    /** Maps the armory offer list to per-item buy actions; pure and state-free. */
    static List<ChatAction> armoryActions(
            List<com.dwurdy.straja.application.port.in.ArmoryUseCase.Offer> offers) {
        if (offers == null || offers.isEmpty()) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        for (var offer : offers) {
            if (offer == null || offer.key() == null) continue;
            String label = offer.reserve()
                    ? offer.count() + "× " + offer.itemId() + " — " + offer.cost() + " pct rechiziție"
                    : offer.count() + "× " + offer.itemId() + " — " + offer.cost() + " monede";
            parameterizedActionId(offer.reserve() ? "armory-reserve" : "armory-buy", offer.key())
                    .ifPresent(id -> actions.add(new ChatAction(label, id)));
        }
        return List.copyOf(actions);
    }

    /** Maps the trusted trainer projection to clickable actions; pure and state-free. */
    static List<ChatAction> trainingActions(
            com.dwurdy.straja.application.port.in.GuardRecruitmentUseCase.TrainingView view) {
        if (view == null) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        if (view.pendingModules() > 0) {
            actions.add(new ChatAction("Răspunde la modul (formular)", "quiz-answer"));
        }
        if (view.canPromote()) {
            actions.add(new ChatAction("Cere avansarea", "training-promote"));
        }
        return List.copyOf(actions);
    }

    /** Maps the trusted mission projection to clickable actions; pure and state-free. */
    static List<ChatAction> missionActions(
            List<com.dwurdy.straja.application.port.in.MissionRoleplayUseCase.AvailableAction> available) {
        if (available == null || available.isEmpty()) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        for (var entry : available) {
            if (entry == null || entry.action() == null) continue;
            String id = entry.missionId() == null ? "" : entry.missionId();
            switch (entry.action()) {
                case GET_CARNET -> actions.add(new ChatAction("Carnet de ordine", "mission-carnet"));
                case DRAFT_WRITE -> actions.add(new ChatAction("Scrie ordin nou", "mission-draft-write"));
                case DRAFT_STATUS -> actions.add(new ChatAction("Stare ordin", "mission-draft-status"));
                case DRAFT_SCOPE -> actions.add(new ChatAction("Participanți ordin", "mission-draft-scope"));
                case DRAFT_SIGN -> actions.add(new ChatAction("Semnează ordinul", "mission-draft-sign"));
                case DRAFT_PACKAGE -> actions.add(new ChatAction("Sigilează ordinul", "mission-draft-package"));
                case TEMPLATE_LIST -> actions.add(new ChatAction("Șabloane de misiune", "mission-template-list"));
                case ADJUST_BUDGET -> actions.add(new ChatAction("Ajustează bugetul ordinului", "mission-budget-adjust"));
                case ISSUE_TEMPLATE -> parameterizedActionId("mission-template-issue", id)
                        .ifPresent(a -> actions.add(new ChatAction("Ordin din șablonul " + id, a)));
                case JOIN -> parameterizedActionId("mission-join", id)
                        .ifPresent(a -> actions.add(new ChatAction("Intră în misiunea #" + id, a)));
                case ACCEPT -> parameterizedActionId("mission-accept", id)
                        .ifPresent(a -> actions.add(new ChatAction("Acceptă misiunea #" + id, a)));
                case DECLINE -> parameterizedActionId("mission-decline", id)
                        .ifPresent(a -> actions.add(new ChatAction("Refuză misiunea #" + id, a)));
                case REPORT -> parameterizedActionId("mission-report", id)
                        .ifPresent(a -> actions.add(new ChatAction("Raport #" + id, a)));
                case FAIL -> parameterizedActionId("mission-fail", id)
                        .ifPresent(a -> actions.add(new ChatAction("Renunță la misiunea #" + id, a)));
                case COMPLETE -> parameterizedActionId("mission-complete", id)
                        .ifPresent(a -> actions.add(new ChatAction("Închide misiunea #" + id, a)));
                case CLAIM_REWARD -> parameterizedActionId("mission-reward", id)
                        .ifPresent(a -> actions.add(new ChatAction("Ridică recompensa #" + id, a)));
                case RECOVER_REWARD -> parameterizedActionId("mission-reward-recover", id)
                        .ifPresent(a -> actions.add(new ChatAction("Recuperează recompensa #" + id, a)));
            }
        }
        return List.copyOf(actions);
    }

    /** Maps the trusted complaint projection to clickable actions; pure and state-free. */
    static List<ChatAction> complaintActions(
            List<com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase.AvailableAction> available,
            RoleRoute role) {
        if (available == null || available.isEmpty()) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        for (var entry : available) {
            if (entry == null || entry.action() == null) continue;
            String id = entry.complaintId() == null ? "" : entry.complaintId();
            switch (entry.action()) {
                case SUBMIT -> {
                    if (role == RoleRoute.RECEPTIONIST) {
                        actions.add(new ChatAction("Depune plângere", "complaint-submit"));
                    }
                }
                case LIST -> {
                    if (role == RoleRoute.SECRETARY) {
                        actions.add(new ChatAction("Registrul de plângeri", "complaint-list"));
                    }
                }
                case CLAIM -> {
                    if (role == RoleRoute.SECRETARY) {
                        parameterizedActionId("complaint-claim", id)
                                .ifPresent(a -> actions.add(new ChatAction("Preia dosarul " + id, a)));
                    }
                }
                case JOIN -> {
                    if (role == RoleRoute.SECRETARY) {
                        parameterizedActionId("complaint-join", id)
                                .ifPresent(a -> actions.add(new ChatAction("Raportează-te pe dosarul " + id, a)));
                    }
                }
                case LEAVE -> {
                    if (role == RoleRoute.SECRETARY) {
                        parameterizedActionId("complaint-leave", id)
                                .ifPresent(a -> actions.add(new ChatAction("Ieși din dosarul " + id, a)));
                    }
                }
                case REPORT -> {
                    if (role == RoleRoute.SECRETARY) {
                        parameterizedActionId("complaint-report", id)
                                .ifPresent(a -> actions.add(new ChatAction("Depune raportul dosarului " + id, a)));
                    }
                }
                case CONFIRM -> {
                    if (role == RoleRoute.RECEPTIONIST) {
                        parameterizedActionId("complaint-confirm", id)
                                .ifPresent(a -> actions.add(new ChatAction("Confirmă soluționarea " + id, a)));
                    }
                }
                case WITHDRAW -> {
                    if (role == RoleRoute.RECEPTIONIST) {
                        parameterizedActionId("complaint-withdraw", id)
                                .ifPresent(a -> actions.add(new ChatAction("Retrage plângerea " + id, a)));
                    }
                }
                case REVIEW -> {
                    if (role == RoleRoute.SECRETARY) {
                        parameterizedActionId("complaint-review", id)
                                .ifPresent(a -> actions.add(new ChatAction("Verifică dosarul " + id, a)));
                    }
                }
            }
        }
        return List.copyOf(actions);
    }

    /** Maps the trusted fine projection to clickable actions; pure and state-free. */
    static List<ChatAction> fineActions(
            List<com.dwurdy.straja.application.port.in.FineRoleplayUseCase.AvailableAction> available,
            RoleRoute role) {
        if (available == null || available.isEmpty()) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        for (var entry : available) {
            if (entry == null || entry.action() == null) continue;
            String id = entry.recordId() == null ? "" : entry.recordId();
            switch (entry.action()) {
                case DRAFT_WRITE -> {
                    if (role == RoleRoute.SECRETARY) {
                        actions.add(new ChatAction("Scrie amendă nouă", "fine-draft-write"));
                    }
                }
                case DRAFT_STATUS -> {
                    if (role == RoleRoute.SECRETARY) {
                        actions.add(new ChatAction("Formular de amendă", "fine-draft-status"));
                    }
                }
                case PAY -> {
                    if (role == RoleRoute.RECEPTIONIST) {
                        parameterizedActionId("fine-pay", id)
                                .ifPresent(a -> actions.add(new ChatAction("Plătește amenda " + id, a)));
                    }
                }
                case REFUSE -> {
                    if (role == RoleRoute.RECEPTIONIST) {
                        parameterizedActionId("fine-refuse", id)
                                .ifPresent(a -> actions.add(new ChatAction("Refuză plata " + id, a)));
                    }
                }
                case APPEAL -> {
                    if (role == RoleRoute.RECEPTIONIST) {
                        parameterizedActionId("fine-appeal", id)
                                .ifPresent(a -> actions.add(new ChatAction("Contestează amenda " + id, a)));
                    }
                }
                case LIST_APPEALS -> {
                    if (role == RoleRoute.RECEPTIONIST) {
                        actions.add(new ChatAction("Contestații în așteptare", "fine-appeal-list"));
                    }
                }
                case REVIEW_APPEAL -> {
                    if (role == RoleRoute.RECEPTIONIST) {
                        parameterizedActionId("fine-appeal-review", id)
                                .ifPresent(a -> actions.add(new ChatAction("Decide contestația " + id, a)));
                    }
                }
                case LIST_TASKS -> {
                    if (role == RoleRoute.SECRETARY) {
                        actions.add(new ChatAction("Misiuni de amenzi", "fine-task-list"));
                    }
                }
                case ACCEPT_TASK -> {
                    if (role == RoleRoute.SECRETARY) {
                        parameterizedActionId("fine-task-accept", id)
                                .ifPresent(a -> actions.add(new ChatAction("Preia misiunea " + id, a)));
                    }
                }
                case COMPLETE_TASK -> {
                    if (role == RoleRoute.SECRETARY) {
                        parameterizedActionId("fine-task-complete", id)
                                .ifPresent(a -> actions.add(new ChatAction("Finalizează misiunea " + id, a)));
                    }
                }
                case ARREST_TASK -> {
                    if (role == RoleRoute.SECRETARY) {
                        parameterizedActionId("fine-task-arrest", id)
                                .ifPresent(a -> actions.add(new ChatAction("Execută arestarea " + id, a)));
                    }
                }
                case CLAIM_TASK_REWARD -> {
                    if (role == RoleRoute.SECRETARY) {
                        parameterizedActionId("fine-task-reward", id)
                                .ifPresent(a -> actions.add(new ChatAction("Ridică recompensa " + id, a)));
                    }
                }
                case HEARING_WARRANT -> {
                    if (role == RoleRoute.SECRETARY) {
                        actions.add(new ChatAction("Mandat de audiere", "fine-warrant"));
                    }
                }
            }
        }
        return List.copyOf(actions);
    }

    /** Maps the trusted custody projection to clickable actions; pure and state-free. */
    static List<ChatAction> custodyActions(
            List<com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase.AvailableAction> available) {
        if (available == null || available.isEmpty()) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        var releaseTargets = new java.util.HashSet<String>();
        for (var entry : available) {
            if (entry == null || entry.action() == null) continue;
            String id = entry.recordId() == null ? "" : entry.recordId();
            switch (entry.action()) {
                case ACCEPT_REQUEST -> parameterizedActionId("custody-accept", id)
                        .ifPresent(a -> actions.add(new ChatAction("Acceptă cererea " + id, a)));
                case REFUSE_REQUEST -> parameterizedActionId("custody-refuse", id)
                        .ifPresent(a -> actions.add(new ChatAction("Refuză cererea " + id, a)));
                case RELEASE_TARGET -> {
                    if (releaseTargets.add(id)) {
                        parameterizedActionId("custody-release", id)
                                .ifPresent(a -> actions.add(new ChatAction("Eliberează " + id, a)));
                    }
                }
                case REMOVE_HEAD_SACK -> actions.add(
                        new ChatAction("Dă jos Sacul de Captiv", "custody-remove-head-sack"));
                case WAKE_DOWNED -> actions.add(
                        new ChatAction("Trezește-te", "custody-wake-downed"));
                case GIVE_CUFFS -> actions.add(
                        new ChatAction("Primește cătușe", "cuffs-item"));
            }
        }
        return List.copyOf(actions);
    }

    /** Room release appears only while the player holds a room or waitlist spot. */
    static List<ChatAction> roomActions(boolean canRelease) {
        return canRelease
                ? List.of(new ChatAction("Eliberează camera / ieși din așteptare", "room-release"))
                : List.of();
    }

    /** Maps the trusted archive projection to clickable actions; pure and state-free. */
    static List<ChatAction> archiveActions(
            List<com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.AvailableAction> available) {
        if (available == null || available.isEmpty()) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        for (var entry : available) {
            if (entry == null || entry.action() == null) continue;
            String id = entry.recordId() == null ? "" : entry.recordId();
            switch (entry.action()) {
                case LIST -> actions.add(new ChatAction("Vezi dosarele", "archive-list"));
                case CREATE_FOLDER -> actions.add(
                        new ChatAction("Dosar nou", "archive-folder-create"));
                case READ_FOLDER -> parameterizedActionId("archive-folder-read", id)
                        .ifPresent(a -> actions.add(new ChatAction("Citește dosarul " + id, a)));
                case ISSUE_FOLDER -> parameterizedActionId("archive-folder-issue", id)
                        .ifPresent(a -> actions.add(new ChatAction("Emite dosarul " + id, a)));
                case NEW_SHEET -> parameterizedActionId("archive-sheet-new", id)
                        .ifPresent(a -> actions.add(new ChatAction("Foaie nouă în " + id, a)));
                case READ_SHEET -> parameterizedActionId("archive-sheet-read", id)
                        .ifPresent(a -> actions.add(new ChatAction("Citește foaia " + id, a)));
                case EDIT_SHEET -> parameterizedActionId("archive-sheet-edit", id)
                        .ifPresent(a -> actions.add(new ChatAction("Editează foaia " + id, a)));
                case SET_RECIPIENTS -> parameterizedActionId("archive-recipients", id)
                        .ifPresent(a -> actions.add(new ChatAction("Destinatari " + id, a)));
                case SUBMIT_SHEET -> parameterizedActionId("archive-sheet-submit", id)
                        .ifPresent(a -> actions.add(new ChatAction("Trimite la semnat " + id, a)));
                case SIGN_SHEET -> parameterizedActionId("archive-sheet-sign", id)
                        .ifPresent(a -> actions.add(new ChatAction("Semnează actul " + id, a)));
                case COPY_SHEET -> parameterizedActionId("archive-sheet-copy", id)
                        .ifPresent(a -> actions.add(new ChatAction("Copie indigo " + id, a)));
                case PACK_ENVELOPE -> parameterizedActionId("archive-sheet-envelope", id)
                        .ifPresent(a -> actions.add(new ChatAction("Plic oficial " + id, a)));
                case ISSUE_DOCUMENT -> parameterizedActionId("archive-sheet-issue", id)
                        .ifPresent(a -> actions.add(new ChatAction("Emite actul " + id, a)));
                case REVOKE_SHEET -> parameterizedActionId("archive-sheet-revoke", id)
                        .ifPresent(a -> actions.add(new ChatAction("Revocă actul " + id, a)));
            }
        }
        return List.copyOf(actions);
    }

    /** Maps the trusted report projection to clickable actions; pure and state-free. */
    static List<ChatAction> reportActions(
            List<com.dwurdy.straja.application.port.in.ReportUseCase.AvailableAction> available,
            RoleRoute role) {
        if (available == null || available.isEmpty() || role != RoleRoute.SECRETARY) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        for (var entry : available) {
            if (entry == null || entry.action() == null) continue;
            String id = entry.reportId() == null ? "" : entry.reportId();
            switch (entry.action()) {
                case SUBMIT -> actions.add(new ChatAction("Depune raport de activitate", "report-submit"));
                case STATUS -> actions.add(new ChatAction("Raportul meu", "report-status"));
                case REVIEW_LIST -> actions.add(new ChatAction("Rapoarte în așteptare", "report-review-list"));
                case REVIEW -> parameterizedActionId("report-review", id)
                        .ifPresent(a -> actions.add(new ChatAction("Verifică raportul " + id, a)));
            }
        }
        return List.copyOf(actions);
    }

    /** Maps the trusted audience projection to clickable actions; pure and state-free. */
    static List<ChatAction> audienceActions(
            List<com.dwurdy.straja.application.port.in.AudienceUseCase.AvailableAction> available,
            RoleRoute role) {
        if (available == null || available.isEmpty() || role != RoleRoute.SECRETARY) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        for (var entry : available) {
            if (entry == null || entry.action() == null) continue;
            String id = entry.requestId() == null ? "" : entry.requestId();
            switch (entry.action()) {
                case REQUEST -> actions.add(new ChatAction("Cere audiență la Comisar", "audience-request"));
                case STATUS -> actions.add(new ChatAction("Cererea mea de audiență", "audience-status"));
                case REVIEW_LIST -> actions.add(new ChatAction("Cereri de audiență", "audience-review-list"));
                case REVIEW -> parameterizedActionId("audience-review", id)
                        .ifPresent(a -> actions.add(new ChatAction("Decide cererea " + id, a)));
            }
        }
        return List.copyOf(actions);
    }

    /** §14 Comisar admin actions on the Secretary surface. */
    static List<ChatAction> adminActions(
            List<com.dwurdy.straja.application.port.in.AdminRoleplayUseCase.AvailableAction> available) {
        if (available == null || available.isEmpty()) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        for (var entry : available) {
            if (entry == null || entry.action() == null) continue;
            String id = entry.memberId() == null ? "" : entry.memberId();
            String name = entry.memberName() == null ? "" : entry.memberName();
            switch (entry.action()) {
                case PERSONNEL -> actions.add(new ChatAction("Personal Straja", "admin-personnel"));
                case ROSTER_ACTIVE -> actions.add(new ChatAction("În serviciu acum", "admin-roster"));
                case AUTHORIZE -> actions.add(new ChatAction("Autorizare directă", "admin-authorize"));
                case DOSSIER -> parameterizedActionId("admin-dossier", id)
                        .ifPresent(a -> actions.add(new ChatAction("Dosar: " + name, a)));
                case PROMOTE -> parameterizedActionId("admin-promote", id)
                        .ifPresent(a -> actions.add(new ChatAction("Promovează " + name, a)));
                case DEMOTE -> parameterizedActionId("admin-demote", id)
                        .ifPresent(a -> actions.add(new ChatAction("Retrogradează " + name, a)));
                case SUSPEND -> parameterizedActionId("admin-suspend", id)
                        .ifPresent(a -> actions.add(new ChatAction("Suspendă " + name, a)));
                case FIRE -> parameterizedActionId("admin-fire", id)
                        .ifPresent(a -> actions.add(new ChatAction("Revoacă " + name, a)));
                case REINSTATE -> parameterizedActionId("admin-reinstate", id)
                        .ifPresent(a -> actions.add(new ChatAction("Reintegrează " + name, a)));
                case POLICIES -> actions.add(new ChatAction("Reguli live (YAML)", "admin-policies"));
                case POLICY_SET -> actions.add(new ChatAction("Modifică o regulă", "admin-policy-set"));
                case EMERGENCY_STATUS -> actions.add(new ChatAction("Stare de urgență", "admin-emergency-status"));
                case EMERGENCY_ALERT -> actions.add(new ChatAction("Alertă de urgență", "admin-emergency-alert"));
                case EMERGENCY_START -> actions.add(new ChatAction("Activează starea de urgență", "admin-emergency-start"));
                case EMERGENCY_END -> actions.add(new ChatAction("Încheie starea de urgență", "admin-emergency-end"));
            }
        }
        return List.copyOf(actions);
    }

    static Optional<ActionRef> parseActionId(String actionId) {
        if (actionId == null) return Optional.empty();
        int separator = actionId.indexOf(':');
        if (separator <= 0 || separator == actionId.length() - 1
                || actionId.indexOf(':', separator + 1) >= 0) return Optional.empty();
        String operation = actionId.substring(0, separator);
        String recordId = actionId.substring(separator + 1);
        return parameterizedActionId(operation, recordId).map(ignored -> new ActionRef(operation, recordId));
    }
}
