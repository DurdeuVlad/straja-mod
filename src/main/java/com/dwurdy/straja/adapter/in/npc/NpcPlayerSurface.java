package com.dwurdy.straja.adapter.in.npc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Minecraft-free role plan shared by NPC routing and its player guidance.
 * Public since the desk-menu adapter reads the same surface model.
 */
public final class NpcPlayerSurface {
    static final String NPC_ACTION_COMMAND = "/straja npc-action ";
    private static final Pattern RECORD_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final Set<String> PARAMETERIZED_ACTIONS = Set.of(
            "mission-join", "mission-accept", "mission-decline", "mission-report", "mission-fail",
            "mission-complete", "mission-reward", "mission-reward-recover", "mission-template-issue",
            "mission-invite", "v2-mission-claim",
            "custody-accept", "custody-refuse", "custody-release",
            "complaint-claim", "complaint-join", "complaint-leave", "complaint-report",
            "complaint-review", "complaint-confirm", "complaint-withdraw",
            "fine-pay", "fine-refuse", "fine-task-accept", "fine-task-complete", "fine-task-arrest",
            "fine-task-reward", "fine-appeal", "fine-appeal-review",
            "archive-folder-read", "archive-folder-issue", "archive-sheet-new",
            "archive-sheet-read", "archive-sheet-edit", "archive-recipients",
            "archive-sheet-submit", "archive-sheet-sign", "archive-sheet-copy",
            "archive-sheet-envelope", "archive-sheet-issue", "archive-sheet-revoke",
            "more", "main",
            "report-review", "audience-review",
            "petition-read", "petition-review",
            "join-read", "join-review",
            "incident-accept", "incident-join", "incident-leave", "incident-resolve",
            "dispatch-accept", "dispatch-decline",
            "bolo-cancel", "evidence-deposit", "evidence-return", "evidence-transfer",
            "evidence-destroy",
            "faq",
            "admin-dossier", "admin-promote", "admin-demote", "admin-suspend",
            "admin-fire", "admin-reinstate",
            "tool-npc-assign", "tool-npc-rename", "tool-npc-skin",
            "tool-npc-remove", "tool-npc-remove-confirm", "tool-npc-record",
            "tool-survey-stamp",
            "armory-buy", "armory-reserve",
            "duty-checkpoint",
            "trades-enroll",
            "delegation-mode", "delegation-window", "delegation-revoke",
            "parole-review", "parole-open", "parole-decide-paroled",
            "parole-decide-denied", "parole-decide-conditional",
            "parole-object", "parole-advocate-answer",
            "manhunt-calloff", "transfer-join", "transfer-pickup",
            "transfer-cancel", "bounty-cancel");

    public enum RoleRoute { RECEPTIONIST, SECRETARY, JAILER, ARCHIVIST, TRAINER, RECRUITER, ARMORER, INSPECTOR, COMMANDER, UNKNOWN }

    public record ChatAction(String label, String actionId, String labelKey) {
        ChatAction(String label, String actionId) {
            this(label, actionId, null);
        }

        /** Command shape used by the surface; the actual token is player-bound. */
        String command() {
            return NPC_ACTION_COMMAND + "<token>";
        }

        String command(String token) {
            return NPC_ACTION_COMMAND + token;
        }
    }

    public record RoleSurface(RoleRoute route, String title, String guidance, List<ChatAction> actions) {
        String visibleText() {
            return title + " " + guidance + " "
                    + String.join(" ", actions.stream().map(ChatAction::label).toList());
        }
    }

    /**
     * Ordered surface result: {@code surface()} is the primary page (never more
     * than {@link #PRIMARY_CAP} actions, including the «Mai multe…» link),
     * {@code overflow()} holds the remaining reachable actions in order.
     */
    public record OrderedSurface(RoleSurface surface, List<ChatAction> overflow) {
        /** Every reachable content action in display order, navigation links excluded. */
        public List<ChatAction> contentActions() {
            var all = new java.util.ArrayList<ChatAction>();
            surface.actions().stream()
                    .filter(action -> !action.actionId().startsWith("more:"))
                    .forEach(all::add);
            all.addAll(overflow);
            return List.copyOf(all);
        }
    }

    /** Max actions on the primary surface page, including the «Mai multe…» link. */
    static final int PRIMARY_CAP = 6;
    private static final String FAQ_SPEC = "@faq";

    /**
     * Friendly Romanian item names for desk rows (armory offers, evidence
     * pieces). Registry ids not listed here degrade to a prettified path —
     * see {@link #itemLabel} — so configurable offers never print raw ids.
     */
    private static final Map<String, String> ITEM_LABELS = Map.ofEntries(
            Map.entry("straja:baton", "baston de serviciu"),
            Map.entry("straja:whip", "bici de serviciu"),
            Map.entry("straja:cuffs", "cătușe"),
            Map.entry("straja:cuff_key", "cheie de cătușe"),
            Map.entry("straja:guard_uniform", "uniformă de străjer"),
            Map.entry("straja:iou_note", "bon de favoare"),
            Map.entry("straja:evidence_bag", "geantă de dovezi"),
            Map.entry("straja:confiscation_receipt", "chitanță de confiscare"),
            Map.entry("straja:rope", "frânghie"),
            Map.entry("straja:head_sack", "sac de cap"),
            Map.entry("straja:lockpick", "speraclă"),
            Map.entry("straja:crowbar", "rangă"),
            Map.entry("straja:bolt_cutters", "foarfecă"),
            Map.entry("straja:alarm_whistle", "fluier de alarmă"),
            Map.entry("minecraft:iron_sword", "sabie de fier"),
            Map.entry("minecraft:diamond_sword", "sabie de diamant"),
            Map.entry("minecraft:diamond_chestplate", "platoșă de diamant"),
            Map.entry("minecraft:shield", "scut"));

    /**
     * Registry id → player-facing noun: the mapped Romanian name, else the
     * id's path with underscores spaced. A name (no namespace) passes
     * through unchanged.
     */
    static String itemLabel(String itemId) {
        if (itemId == null || itemId.isBlank()) return "obiect";
        String known = ITEM_LABELS.get(itemId.trim());
        if (known != null) return known;
        String path = itemId.trim();
        int separator = path.indexOf(':');
        if (separator >= 0 && separator < path.length() - 1) {
            path = path.substring(separator + 1);
        }
        return path.replace('_', ' ');
    }

    /** Ordering phases derived from the player's Straja state. */
    enum SurfacePhase { CIVIL, APPLICANT, MEMBER }

    public record ActionRef(String operation, String recordId) {}

    record InteractionPlan(RoleSurface surface, boolean executesRoleAction) {}

    private NpcPlayerSurface() {}

    public static RoleRoute routeFor(String roleId) {
        return switch (roleId == null ? "" : roleId) {
            case NpcRoles.RECEPTIONIST -> RoleRoute.RECEPTIONIST;
            case NpcRoles.SECRETARY -> RoleRoute.SECRETARY;
            case NpcRoles.JAILER -> RoleRoute.JAILER;
            case NpcRoles.ARCHIVIST -> RoleRoute.ARCHIVIST;
            // Recruiter remains a persisted/CLI alias for old worlds, but the
            // physical player-facing role is now the Instructor.
            case NpcRoles.TRAINER, NpcRoles.RECRUITER -> RoleRoute.TRAINER;
            case NpcRoles.ARMORER -> RoleRoute.ARMORER;
            case NpcRoles.INSPECTOR -> RoleRoute.INSPECTOR;
            case NpcRoles.COMMANDER -> RoleRoute.COMMANDER;
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
                            new ChatAction("Meseriași — înscriere", "trades-list"),
                            new ChatAction("Meseriași — avansare", "trades-promote"),
                            new ChatAction("Regulament", "rules"),
                            new ChatAction("Stare Străjer", "guard-status"),
                            new ChatAction("Amenzile mele", "fine-list"),
                            new ChatAction("Emite buletinul meu", "identity-request"),
                            new ChatAction("Verifică buletinele", "identity-list"),
                            new ChatAction("Fișa mea V2", "v2-personnel-status"),
                            new ChatAction("Promovarea mea V2", "v2-promotion-status"),
                            new ChatAction("Actele mele V2", "v2-document-status"),
                            new ChatAction("Raportează incident", "incident-report"),
                            new ChatAction("Reputația mea", "reputation-self"),
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
                            new ChatAction("Promovarea mea V2", "v2-promotion-status"),
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
                            new ChatAction("Dosarul meu V2", "v2-personnel-status"),
                            new ChatAction("Campanii active", "v2-campaign-status"),
                            new ChatAction("Muncă profesională", "v2-professional-work"),
                            new ChatAction("Incidente active", "incident-list"),
                            new ChatAction("Dispecerat", "dispatch-board"),
                            new ChatAction("Roster Straja", "duty-roster"),
                            new ChatAction("BOLO-uri", "bolo-list"),
                            new ChatAction("Emite BOLO", "bolo-create"),
                            faqEntry(RoleRoute.SECRETARY)));
            case JAILER -> new RoleSurface(
                    RoleRoute.JAILER,
                    "Temniță",
                    "Verifică situația custodiei și a sentinței tale; recuperarea după leșin respectă cooldown-ul persistat.",
                    List.of(
                            new ChatAction("Stare custodie", "cuffs-status"),
                            new ChatAction("Stare sentință", "prison-status"),
                            new ChatAction("Predă deținutul Temnicerului", "arrest-handoff"),
                            new ChatAction("Dosarul meu de arest", "arrest-record"),
                            new ChatAction("Stare leșin", "downed-status"),
                            new ChatAction("Primește cătușe", "cuffs-item"),
                            faqEntry(RoleRoute.JAILER)));
            case ARCHIVIST -> new RoleSurface(
                    RoleRoute.ARCHIVIST,
                    "Arhivă",
                    "Îți arăt dosarele pe care ai voie să le citești.",
                    List.of(new ChatAction("Vezi dosarele", "archive-list"),
                            new ChatAction("Actele mele V2", "v2-document-status"),
                            new ChatAction("Probe în custodie", "evidence-list"),
                            new ChatAction("Probe pe dosar", "evidence-case-view"),
                            faqEntry(RoleRoute.ARCHIVIST)));
            case ARMORER -> new RoleSurface(
                    RoleRoute.ARMORER,
                    "Armurier",
                    "Vând echipament de rang pe monede și rezerve pe puncte de rechiziție; ofertele depind de rangul tău.",
                    List.of(
                            new ChatAction("Vezi ofertele", "armory-status"),
                            new ChatAction("Registrul meu de echipament", "v2-equipment-status"),
                            new ChatAction("Muncă profesională", "v2-professional-work"),
                            new ChatAction("Kit de serviciu", "duty-kit"),
                            faqEntry(RoleRoute.ARMORER)));
            case INSPECTOR -> new RoleSurface(
                    RoleRoute.INSPECTOR,
                    "Inspector de artefacte",
                    "Verific marcile de serie ale documentelor și artefactelor pe care le porți. Prezintă-te la control.",
                    List.of(
                            new ChatAction("Prezint documentele la control", "inspect-documents"),
                            faqEntry(RoleRoute.INSPECTOR)));
            case COMMANDER -> new RoleSurface(
                    RoleRoute.COMMANDER,
                    "Comandament",
                    "Comanda unitatea militară a garnizoanei: înscriere în unitate, operații planificate, muster și istoric — trupa militară și ofițerii servesc aici.",
                    List.of(
                            new ChatAction("Stare unitate", "army-status"),
                            new ChatAction("Istoric operații", "army-history"),
                            faqEntry(RoleRoute.COMMANDER)));
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

    /**
     * Orders a role's surface around the player's current state: the next
     * meaningful actions lead, «Am o întrebare» stays within the first three
     * positions, and everything else stays reachable under «Mai multe…».
     * Pure and deterministic — same inputs always produce the same order.
     */
    static OrderedSurface orderedFor(String roleId, List<ChatAction> dynamicActions,
                                     NpcFaqSurface.Context context) {
        RoleSurface base = surfaceFor(roleId);
        var present = new java.util.LinkedHashMap<String, ChatAction>();
        base.actions().forEach(action -> present.putIfAbsent(action.actionId(), action));
        var extras = dynamicActions == null ? List.<ChatAction>of() : dynamicActions;
        extras.forEach(action -> present.putIfAbsent(action.actionId(), action));

        SurfacePhase phase = phaseFor(context);
        String faqId = faqEntry(base.route()).actionId();
        var seen = new java.util.HashSet<String>();
        var eligible = new java.util.ArrayList<ChatAction>();
        java.util.function.Consumer<ChatAction> push = action -> {
            if (action != null && seen.add(action.actionId())) eligible.add(action);
        };
        // Open-window rows — a pending consent, muster, objection or exam —
        // always lead: burying them under «Mai multe…» costs the player the
        // window. Members and the jailer desk put every live row first
        // (custody is detention-driven, not membership-driven).
        extras.stream().filter(NpcPlayerSurface::isUrgent).forEach(push);
        boolean extrasLead = phase == SurfacePhase.MEMBER
                || base.route() == RoleRoute.JAILER;
        if (extrasLead) {
            extras.stream().filter(action -> !isUrgent(action)).forEach(push);
        }
        String routeKey = NpcFaqSurface.roleKey(base.route());
        for (String specId : orderSpec(base.route(), phase)) {
            String actionId = FAQ_SPEC.equals(specId) ? faqId : specId;
            push.accept(present.get(actionId));
        }
        if (!extrasLead) {
            extras.stream().filter(action -> !isUrgent(action)).forEach(push);
        }

        // Actions that do not fit the current state stay reachable, but only
        // under «Mai multe…» — nothing is removed outright.
        var deferred = new java.util.ArrayList<ChatAction>();
        present.values().forEach(action -> {
            if (!seen.contains(action.actionId())) deferred.add(action);
        });

        int faqIndex = indexOf(eligible, faqId);
        if (faqIndex > 2) eligible.add(2, eligible.remove(faqIndex));

        boolean needsMore = !deferred.isEmpty() || eligible.size() > PRIMARY_CAP;
        int contentCap = needsMore ? PRIMARY_CAP - 1 : PRIMARY_CAP;
        var primary = new java.util.ArrayList<>(
                eligible.subList(0, Math.min(contentCap, eligible.size())));
        var overflow = new java.util.ArrayList<ChatAction>();
        if (eligible.size() > contentCap) {
            overflow.addAll(eligible.subList(contentCap, eligible.size()));
        }
        overflow.addAll(deferred);
        if (!overflow.isEmpty()) {
            primary.add(new ChatAction("Mai multe…", "more:" + routeKey, "straja.ui.more"));
        }
        var surface = new RoleSurface(base.route(), base.title(), base.guidance(), List.copyOf(primary));
        return new OrderedSurface(surface, List.copyOf(overflow));
    }

    static ChatAction backLink(String roleKey) {
        return new ChatAction("« Înapoi", "main:" + roleKey, "straja.ui.back");
    }

    static String roleIdFor(RoleRoute route) {
        return switch (route) {
            case RECEPTIONIST -> NpcRoles.RECEPTIONIST;
            case SECRETARY -> NpcRoles.SECRETARY;
            case JAILER -> NpcRoles.JAILER;
            case ARCHIVIST -> NpcRoles.ARCHIVIST;
            case TRAINER, RECRUITER -> NpcRoles.TRAINER;
            case ARMORER -> NpcRoles.ARMORER;
            case INSPECTOR -> NpcRoles.INSPECTOR;
            case COMMANDER -> NpcRoles.COMMANDER;
            case UNKNOWN -> "";
        };
    }

    private static SurfacePhase phaseFor(NpcFaqSurface.Context context) {
        if (context == null) return SurfacePhase.CIVIL;
        if (context.commissioner() || context.activeMember()) return SurfacePhase.MEMBER;
        if (context.memberRecord()) return SurfacePhase.APPLICANT;
        return SurfacePhase.CIVIL;
    }

    /**
     * Rows whose window is open right now: consent prompts, musters, exam and
     * objection windows, the current-shift checkpoint. They outrank both the
     * static order and the «Mai multe…» overflow in every phase.
     */
    private static final List<String> URGENT_PREFIXES = List.of(
            "custody-accept:", "custody-refuse:", "custody-release:",
            "parole-object:", "parole-advocate-answer:",
            "complaint-claim:", "complaint-join:", "complaint-leave:",
            "complaint-confirm:",
            "fine-pay:", "fine-refuse:", "fine-task-accept:",
            "mission-accept:", "mission-decline:", "mission-join:",
            "v2-mission-claim:",
            "incident-accept:", "incident-join:", "incident-leave:",
            "incident-resolve:",
            "dispatch-accept:", "dispatch-decline:",
            "duty-checkpoint:",
            "army-signup", "army-report", "army-leave-confirm",
            "recruit", "quiz-answer",
            "bribe-accept", "bribe-decline", "trade-accept", "trade-decline");

    static boolean isUrgent(ChatAction action) {
        if (action == null || action.actionId() == null) return false;
        for (String prefix : URGENT_PREFIXES) {
            if (action.actionId().startsWith(prefix)) return true;
        }
        return false;
    }

    private static int indexOf(List<ChatAction> actions, String actionId) {
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).actionId().equals(actionId)) return i;
        }
        return -1;
    }

    /**
     * Primary-page eligibility per route and phase. Unlisted actions are still
     * reachable — they drop to «Mai multe…» in declaration order. The "@faq"
     * placeholder resolves to the route's FAQ entry.
     */
    private static List<String> orderSpec(RoleRoute route, SurfacePhase phase) {
        boolean member = phase == SurfacePhase.MEMBER;
        return switch (route) {
            case RECEPTIONIST -> switch (phase) {
                case CIVIL -> List.of("application-submit", "rules", FAQ_SPEC,
                        "trades-list", "fine-list", "identity-request",
                        "trades-promote", "incident-report", "reputation-self");
                case APPLICANT -> List.of("rules", FAQ_SPEC, "trades-list",
                        "fine-list", "identity-request", "trades-promote",
                        "incident-report", "reputation-self");
                case MEMBER -> List.of("guard-status", FAQ_SPEC, "v2-personnel-status",
                        "v2-promotion-status", "v2-document-status", "room-status",
                        "reputation-self", "faction-declare", "identity-request",
                        "identity-list", "fine-list", "rules", "trades-list",
                        "trades-promote", "application-submit", "incident-report");
            };
            case SECRETARY -> member
                    ? List.of("guard-status", "mission-list", "mission-carnet", FAQ_SPEC,
                            "v2-personnel-status", "v2-campaign-status",
                            "v2-professional-work", "duty-roster", "incident-list",
                            "bolo-list", "bolo-create")
                    : List.of("mission-list", FAQ_SPEC, "incident-list");
            case TRAINER -> switch (phase) {
                case CIVIL -> List.of(FAQ_SPEC, "training-manual");
                case APPLICANT -> List.of("training-progress", FAQ_SPEC, "training-manual");
                case MEMBER -> List.of("training-progress", FAQ_SPEC,
                        "v2-promotion-status", "training-manual");
            };
            case RECRUITER -> orderSpec(RoleRoute.TRAINER, phase);
            // Custody actions are driven by detention state, not membership —
            // the same order serves every phase.
            case JAILER -> List.of("cuffs-status", "prison-status", FAQ_SPEC,
                    "arrest-handoff", "arrest-record", "downed-status", "cuffs-item");
            case ARCHIVIST -> member
                    ? List.of("archive-list", "v2-document-status", FAQ_SPEC,
                            "evidence-list", "evidence-case-view")
                    : List.of(FAQ_SPEC, "archive-list");
            case ARMORER -> member
                    ? List.of("armory-status", "v2-equipment-status", FAQ_SPEC,
                            "v2-professional-work", "duty-kit")
                    : List.of(FAQ_SPEC, "armory-status");
            case INSPECTOR -> List.of("inspect-documents", FAQ_SPEC);
            case COMMANDER -> List.of("army-status", FAQ_SPEC, "army-history");
            case UNKNOWN -> List.of();
        };
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
        // FUN-1: standing-order issue ops carry the queue name in the
        // operation ("delegation-order-<action>:<filter>") — dynamic but
        // bounded, so it joins the parameterized space by prefix.
        boolean known = PARAMETERIZED_ACTIONS.contains(operation)
                || (operation != null && operation.startsWith("delegation-order-")
                        && operation.length() <= 64);
        if (!known || !RECORD_ID.matcher(id).matches()) {
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
        if (view.canStartWork()) {
            actions.add(new ChatAction("Începe tura de lucru", "work-start"));
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
                    ? "Rezervă " + offer.count() + "× " + itemLabel(offer.itemId())
                            + " — " + offer.cost() + " puncte de rechiziție"
                    : "Cumpără " + offer.count() + "× " + itemLabel(offer.itemId())
                            + " — " + offer.cost() + " monede";
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
                case INVITE -> parameterizedActionId("mission-invite", id)
                        .ifPresent(a -> actions.add(new ChatAction("Alocă jucător la #" + id, a)));
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

    /**
     * Maps the trusted custody projection to clickable actions; pure and
     * state-free. {@code subjectName} resolves a release target's raw record
     * id (a stored uuid) to a display name so the row reads «Eliberează pe
     * Vlad» instead of a bare identifier; unresolvable ids pass through.
     */
    static List<ChatAction> custodyActions(
            List<com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase.AvailableAction> available,
            java.util.function.Function<String, String> subjectName) {
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
                        String name = subjectName == null ? id : subjectName.apply(id);
                        String label = name == null || name.isBlank() || name.equals(id)
                                ? "Eliberează " + id
                                : "Eliberează pe " + name;
                        parameterizedActionId("custody-release", id)
                                .ifPresent(a -> actions.add(new ChatAction(label, a)));
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

    /**
     * Jailer-desk parole rows: filing verbs for an eligible prisoner, the
     * advocate row once a case is pending, the bail row while a CONDITIONAL
     * offer is open, and the parolee check-in. Officers also get the docket —
     * the institution rows render for whoever the desk belongs to, never by
     * hiding the surface itself.
     */
    static List<ChatAction> paroleActions(
            com.dwurdy.straja.application.port.in.ParoleUseCase.ParoleSurface surface,
            boolean officer) {
        if (surface == null) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        if (surface.prisoner() || surface.parolee()
                || !surface.pendingId().isEmpty() || !surface.conditionalId().isEmpty()) {
            actions.add(new ChatAction("Statutul eliberării", "parole-status"));
        }
        if (surface.prisoner() && surface.eligible()
                && surface.pendingId().isEmpty() && surface.conditionalId().isEmpty()) {
            actions.add(new ChatAction("Cere eliberarea condiționată", "parole-file"));
            actions.add(new ChatAction("Contează sentința", "parole-appeal"));
        }
        if (!surface.pendingId().isEmpty()
                && !"AGREED".equals(surface.advocateStatus())) {
            actions.add(new ChatAction("Desemnează un avocat", "parole-advocate"));
        }
        if (!surface.conditionalId().isEmpty()) {
            actions.add(new ChatAction("Plătește cauțiunea (" + surface.bailAmount()
                    + " credite)", "parole-settle"));
        }
        if (surface.parolee()) {
            actions.add(new ChatAction("Raportează-te (check-in)", "parole-checkin"));
        }
        if (officer) {
            actions.add(new ChatAction("Dosare de eliberare", "parole-docket"));
        }
        return List.copyOf(actions);
    }

    /**
     * Receptionist parole rows: a victim's open objection windows and a
     * pending advocacy ask — the two civil seats the parole loop touches.
     */
    static List<ChatAction> paroleCivilActions(
            List<String> openObjections, String advocacyAsk) {
        var actions = new java.util.ArrayList<ChatAction>();
        if (openObjections != null) {
            for (String hearingId : openObjections) {
                parameterizedActionId("parole-object", hearingId)
                        .ifPresent(a -> actions.add(new ChatAction(
                                "Obiectează la eliberarea " + hearingId, a)));
            }
        }
        if (advocacyAsk != null && !advocacyAsk.isBlank()) {
            parameterizedActionId("parole-advocate-answer", "accept")
                    .ifPresent(a -> actions.add(new ChatAction(
                            "Acceptă avocatura " + advocacyAsk, a)));
            parameterizedActionId("parole-advocate-answer", "decline")
                    .ifPresent(a -> actions.add(new ChatAction(
                            "Refuză avocatura " + advocacyAsk, a)));
        }
        return List.copyOf(actions);
    }

    /**
     * The prisoner's own loop at the jailer desk — the repeated work of a
     * sentence (jobs, camp status, market trades, IOUs, bribe, debt, bail)
     * rides rows instead of typed commands. {@code inCell} mirrors the
     * bribe verb's custody gate; {@code bountied} gates surrender; every
     * click still revalidates inside its service.
     */
    static List<ChatAction> prisonLoopActions(boolean prisoner, boolean inCell,
                                              boolean inCamp, boolean bountied) {
        if (!prisoner) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        actions.add(new ChatAction("Munca ta — cote și plata", "job-status"));
        if (inCamp) {
            actions.add(new ChatAction("Statutul taberei de muncă", "camp-status"));
        }
        actions.add(new ChatAction("Propune un schimb", "trade-offer"));
        actions.add(new ChatAction("Semnează o cambie (IOU)", "iou-issue"));
        if (inCell) {
            actions.add(new ChatAction("Oferă mită străjerului", "bribe-offer"));
        }
        actions.add(new ChatAction("Datoriile tale", "debt-status"));
        actions.add(new ChatAction("Plătește din datorie", "debt-pay"));
        actions.add(new ChatAction("Plătește cauțiunea ta", "bail-pay"));
        if (bountied) {
            actions.add(new ChatAction("Predă-te (recompensa e activă)", "surrender"));
        }
        return List.copyOf(actions);
    }

    /**
     * The detention-shift verbs a jailer works with — cell search, the hunt,
     * transfer and bounty boards, posting and inspecting. {@code senior}
     * gates the posting row (Inspector+ / Comisar, same bar the service sets).
     */
    static List<ChatAction> guardCustodyActions(boolean onDuty, boolean senior) {
        if (!onDuty) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        actions.add(new ChatAction("Percheziționează celula", "cell-search"));
        actions.add(new ChatAction("Panou vânători", "manhunt-board"));
        actions.add(new ChatAction("Panou transferuri", "transfer-board"));
        actions.add(new ChatAction("Panou recompense", "bounty-board"));
        if (senior) {
            actions.add(new ChatAction("Pune recompensă", "bounty-post"));
        }
        actions.add(new ChatAction("Verifică documentele unui jucător", "inspect-player"));
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
                        .ifPresent(a -> actions.add(new ChatAction("Destinatari pentru " + id, a)));
                case SUBMIT_SHEET -> parameterizedActionId("archive-sheet-submit", id)
                        .ifPresent(a -> actions.add(new ChatAction("Trimite la semnat " + id, a)));
                case SIGN_SHEET -> parameterizedActionId("archive-sheet-sign", id)
                        .ifPresent(a -> actions.add(new ChatAction("Semnează actul " + id, a)));
                case COPY_SHEET -> parameterizedActionId("archive-sheet-copy", id)
                        .ifPresent(a -> actions.add(new ChatAction("Copie indigo a actului " + id, a)));
                case PACK_ENVELOPE -> parameterizedActionId("archive-sheet-envelope", id)
                        .ifPresent(a -> actions.add(new ChatAction("Plic oficial pentru " + id, a)));
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

    /**
     * Promotion petitions for the chat surface: the member's own "Cere
     * avansarea" plus one read/decide pair per pending petition (reviewers
     * only — callers pass an empty list for anyone else).
     */
    static List<ChatAction> petitionActions(
            java.util.Optional<com.dwurdy.straja.domain.model.CareerGrade> petitionTarget,
            List<com.dwurdy.straja.domain.model.PromotionApplication> petitions,
            java.util.function.Function<com.dwurdy.straja.domain.model.CareerGrade, String> gradeName,
            java.util.function.Function<String, String> subjectName) {
        var actions = new java.util.ArrayList<ChatAction>();
        petitionTarget.ifPresent(target -> actions.add(new ChatAction(
                "Cere avansarea la " + gradeName.apply(target), "petition-request")));
        if (petitions != null) {
            for (var application : petitions) {
                if (application == null) continue;
                String name = subjectName.apply(application.subjectUuid);
                parameterizedActionId("petition-read", application.applicationId)
                        .ifPresent(id -> actions.add(new ChatAction(
                                "Citește " + application.applicationId + " (" + name + ")", id)));
                parameterizedActionId("petition-review", application.applicationId)
                        .ifPresent(id -> actions.add(new ChatAction(
                                "Decide " + application.applicationId + " → "
                                        + gradeName.apply(application.targetGrade), id)));
            }
        }
        return List.copyOf(actions);
    }

    /**
     * Work petitions for the chat surface: one read/decide pair per pending
     * join request (reviewers only — callers pass an empty list for anyone
     * else).
     */
    static List<ChatAction> joinActions(
            List<com.dwurdy.straja.domain.model.JoinRequest> requests,
            java.util.function.Function<String, String> subjectName) {
        if (requests == null || requests.isEmpty()) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        for (var request : requests) {
            if (request == null) continue;
            String name = subjectName.apply(request.subjectUuid);
            parameterizedActionId("join-read", request.requestId)
                    .ifPresent(id -> actions.add(new ChatAction(
                            "Citește cererea " + request.requestId + " (" + name + ")", id)));
            parameterizedActionId("join-review", request.requestId)
                    .ifPresent(id -> actions.add(new ChatAction(
                            "Decide " + request.requestId + " — "
                                    + request.profession.toLowerCase(java.util.Locale.ROOT), id)));
        }
        return List.copyOf(actions);
    }

    static List<ChatAction> incidentActions(
            List<com.dwurdy.straja.application.port.in.RoleplayExpansionUseCase.IncidentView> incidents) {
        if (incidents == null || incidents.isEmpty()) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        for (var incident : incidents) {
            if (incident == null) continue;
            if ("OPEN".equals(incident.status())) {
                parameterizedActionId("incident-accept", incident.id())
                        .ifPresent(id -> actions.add(new ChatAction("Preia " + incident.id(), id)));
            }
            parameterizedActionId("incident-join", incident.id())
                    .ifPresent(id -> actions.add(new ChatAction("Sprijin " + incident.id(), id)));
            parameterizedActionId("incident-resolve", incident.id())
                    .ifPresent(id -> actions.add(new ChatAction("Încheie " + incident.id(), id)));
        }
        return List.copyOf(actions);
    }

    static List<ChatAction> boloActions(
            List<com.dwurdy.straja.application.port.in.RoleplayExpansionUseCase.BoloView> bolos) {
        if (bolos == null || bolos.isEmpty()) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        for (var bolo : bolos) {
            if (bolo == null) continue;
            parameterizedActionId("bolo-cancel", bolo.id())
                    .ifPresent(id -> actions.add(new ChatAction("Anulează " + bolo.id(), id)));
        }
        return List.copyOf(actions);
    }

    static List<ChatAction> evidenceActions(
            List<com.dwurdy.straja.domain.model.EvidenceRecord> records) {
        if (records == null || records.isEmpty()) return List.of();
        var actions = new java.util.ArrayList<ChatAction>();
        for (var record : records) {
            if (record == null) continue;
            boolean inCustody = record.status
                    == com.dwurdy.straja.domain.model.EvidenceStatus.IN_GUARD_CUSTODY;
            boolean deposited = record.status
                    == com.dwurdy.straja.domain.model.EvidenceStatus.DEPOSITED;
            if (inCustody) {
                parameterizedActionId("evidence-deposit", record.id)
                        .ifPresent(id -> actions.add(new ChatAction(
                                "Depune proba " + record.id + evidenceSuffix(record), id)));
            } else if (deposited) {
                parameterizedActionId("evidence-return", record.id)
                        .ifPresent(id -> actions.add(new ChatAction(
                                "Restituie " + record.id + evidenceOwner(record)
                                        + evidenceSuffix(record), id)));
            }
            if (inCustody || deposited) {
                parameterizedActionId("evidence-transfer", record.id)
                        .ifPresent(id -> actions.add(new ChatAction(
                                "Transferă " + record.id + evidenceSuffix(record), id)));
                parameterizedActionId("evidence-destroy", record.id)
                        .ifPresent(id -> actions.add(new ChatAction(
                                "Distruge " + record.id + evidenceSuffix(record), id)));
            }
        }
        return List.copyOf(actions);
    }

    /** « — 3× sabie de fier» so a desk row says what the evidence piece is. */
    private static String evidenceSuffix(
            com.dwurdy.straja.domain.model.EvidenceRecord record) {
        String raw = record.itemName != null && !record.itemName.isBlank()
                ? record.itemName : record.itemId;
        if (raw == null || raw.isBlank()) return "";
        return " — " + (record.amount > 1 ? record.amount + "× " : "")
                + itemLabel(raw);
    }

    /** « către Vlad» names the return row's owner; empty when unknown. */
    private static String evidenceOwner(
            com.dwurdy.straja.domain.model.EvidenceRecord record) {
        return record.sourcePlayerName == null || record.sourcePlayerName.isBlank()
                ? "" : " către " + record.sourcePlayerName;
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

    /**
     * FUN-1 parity: renders the delegation console on the native surface the
     * same way {@code NpcSecretarySurfaceService.addDelegation} projects it
     * for bound NPCs. The service itself gates the view — viewers without
     * delegation authority get an empty {@code SurfaceView}, so this emits
     * nothing for them. Digest rows reuse each queue's native review action
     * (the digest batches the queues; it never invents a parallel form).
     */
    static List<ChatAction> delegationActions(
            com.dwurdy.straja.application.port.in.DelegationUseCase.SurfaceView view) {
        if (view == null || (view.digest().isEmpty() && view.policies().isEmpty())) {
            return List.of();
        }
        var actions = new java.util.ArrayList<ChatAction>();
        actions.add(new ChatAction(
                "Instituția decide — " + view.digest().size()
                        + " în așteptare · jurnal decizii",
                "delegation-ledger"));
        if (view.digest() != null) {
            for (var entry : view.digest()) {
                if (entry == null || entry.reviewActionId() == null) continue;
                String queue = entry.action() == null ? "?" : entry.action().displayName();
                actions.add(new ChatAction(
                        "◈ " + queue + " " + entry.requestId()
                                + " — " + bounded(entry.subjectName(), 30),
                        entry.reviewActionId()));
            }
        }
        if (view.policies() == null) return List.copyOf(actions);
        for (var policy : view.policies()) {
            if (policy == null || policy.action() == null) continue;
            var queue = policy.action();
            parameterizedActionId("delegation-mode", queue.name())
                    .ifPresent(id -> actions.add(new ChatAction(
                            "⚙ " + queue.displayName() + ": "
                                    + delegationModeLabel(policy), id)));
            if (policy.mode() == com.dwurdy.straja.domain.model.DelegationMode.STANDING_ORDER) {
                int options = 0;
                if (view.orderOptions() != null) {
                    for (var option : view.orderOptions()) {
                        if (option == null || !queue.name().equals(option.action())) continue;
                        if (++options > 6) break;
                        parameterizedActionId("delegation-order-"
                                        + queue.name().toLowerCase(java.util.Locale.ROOT),
                                        option.filter())
                                .ifPresent(id -> actions.add(new ChatAction(
                                        "+ ordin: " + bounded(option.label(), 60), id)));
                    }
                }
                if (policy.orders() != null) {
                    for (var order : policy.orders()) {
                        if (order == null || !order.active()) continue;
                        parameterizedActionId("delegation-revoke", order.orderId())
                                .ifPresent(id -> actions.add(new ChatAction(
                                        "− retrage ordin " + order.orderId()
                                                + " (" + order.filter() + ")", id)));
                    }
                }
            }
            if (policy.mode() == com.dwurdy.straja.domain.model.DelegationMode.TIMEOUT) {
                parameterizedActionId("delegation-window", queue.name())
                        .ifPresent(id -> actions.add(new ChatAction(
                                "fereastră " + (policy.timeoutMillis() / 3_600_000L)
                                        + "h — apasă pentru schimbare", id)));
            }
        }
        return List.copyOf(actions);
    }

    private static String delegationModeLabel(
            com.dwurdy.straja.application.port.in.DelegationUseCase.PolicyView policy) {
        return switch (policy.mode()) {
            case MANUAL -> "manual — un om decide";
            case DIGEST -> "digest — se semnează în masă";
            case STANDING_ORDER -> "ordin permanent — se rezolvă singure";
            case TIMEOUT -> "presupus după " + (policy.timeoutMillis() / 3_600_000L) + "h";
        };
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    public static Optional<ActionRef> parseActionId(String actionId) {
        if (actionId == null) return Optional.empty();
        int separator = actionId.indexOf(':');
        if (separator <= 0 || separator == actionId.length() - 1
                || actionId.indexOf(':', separator + 1) >= 0) return Optional.empty();
        String operation = actionId.substring(0, separator);
        String recordId = actionId.substring(separator + 1);
        return parameterizedActionId(operation, recordId).map(ignored -> new ActionRef(operation, recordId));
    }
}
