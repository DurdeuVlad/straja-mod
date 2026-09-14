package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.GuardDutyUseCase;
import com.dwurdy.straja.application.port.in.GuardRecruitmentUseCase;
import com.dwurdy.straja.application.port.out.CurrencyProvider;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.DomainEvent;
import com.dwurdy.straja.domain.model.DutyEngine;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.InboxMessage;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.PermissionLevel;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.Result;
import com.dwurdy.straja.domain.model.SetupChecklist;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Player-facing use cases: recruitment, quiz, duty lifecycle, salary, food,
 * kit, merit, resignation/rejoin, inbox and admin rank operations.
 * Ports of the reference flows; all authority checks stay server-side.
 */
public class GuardService implements GuardRecruitmentUseCase, GuardDutyUseCase {
    private static final int QUIZ_PROMPT_MAX_LENGTH = 120;
    private static final String STALE_FORM =
            "Formularul nu mai este valid. Deschide din nou quiz-ul la Instructor.";

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private final EquipmentService equipment;
    private final java.util.function.Supplier<String> bootId;

    /** Optional hook for room auto-assignment (wired by M6 RoomService). */
    public interface RankChangeHook { void onPromotedToGuard(PlayerGateway player); }
    private RankChangeHook roomAutoAssign = p -> {};

    /** Status-change listeners (mission cancellation, room release, ...). All registered hooks run. */
    private final java.util.List<java.util.function.BiConsumer<PlayerGateway, String>> statusChangeHooks =
            new java.util.ArrayList<>();
    public void onStatusChange(java.util.function.BiConsumer<PlayerGateway, String> hook) {
        if (hook != null) this.statusChangeHooks.add(hook);
    }

    private void fireStatusChange(PlayerGateway target, String reason) {
        for (var hook : statusChangeHooks) hook.accept(target, reason);
    }

    public GuardService(StrajaContext ctx, PlayerService players, AuditService audit,
                        EquipmentService equipment) {
        this(ctx, players, audit, equipment, () -> "");
    }

    public GuardService(StrajaContext ctx, PlayerService players, AuditService audit,
                        EquipmentService equipment, java.util.function.Supplier<String> bootId) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
        this.equipment = equipment;
        this.bootId = bootId;
    }

    public void onPromotedToGuard(RankChangeHook hook) {
        if (hook != null) this.roomAutoAssign = hook;
    }

    // ------------------------------------------------------------ helpers

    private long now() { return ctx.clock().nowMillis(); }

    /** Hourly wage (§10): the Comisar rate comes from the personnel flag, not
     * rank. §25 hazard pay multiplies every accrual path through this funnel. */
    private int salaryFor(PlayerGateway player, GuardState state) {
        int wage = players.isCommissioner(player)
                ? ctx.policies().salaryCommissionerPerHour
                : ctx.policies().salaryPerHour(state.rank);
        var emergency = ctx.emergency().read();
        if (emergency.active && emergency.payMultiplier > 1.0) {
            wage = (int) Math.round(wage * emergency.payMultiplier);
        }
        return wage;
    }

    /** Sergent+ ranks and the commissioner run shifts at will, without patrol. */
    private boolean freeDutyEligible(PlayerGateway player, GuardState state) {
        return players.isCommissioner(player) || state.rank >= ctx.policies().freeDutyMinRank;
    }

    private boolean canAuthorize(PlayerGateway actor, String action, String targetName, int targetRank) {
        GuardState actorState = players.state(actor);
        return DutyEngine.canAuthorize(players.isCommissioner(actor), actor.name(), actorState.rank,
                targetName, targetRank, action);
    }

    private static boolean operational(GuardState state) {
        return state.rank >= Rank.STAGIAR.level() && !state.suspended && !state.fired
                && !state.resigned && !state.resignationPending;
    }

    private void notifyEvents(PlayerGateway player, List<DomainEvent> events) {
        for (DomainEvent event : events) {
            switch (event.type()) {
                case "salary_accrual" -> player.tell("Ai acumulat +" + event.data().get("amount")
                        + " monede în sold (" + event.data().get("paidMinutes")
                        + " min plătite, " + event.data().get("hourlyWage") + "/h"
                        + (ctx.emergency().read().active ? ", primă de urgență inclusă" : "")
                        + ").");
                case "checkpoint_activated" -> player.tell("Checkpoint atins. Următorul devine disponibil peste "
                        + ctx.policies().checkpointUnlockMinutes + " minute.");
                case "checkpoint_available" -> player.tell("Checkpoint disponibil: " + event.data().get("checkpoint")
                        + ". Ai " + event.data().get("missionMinutes") + " minute.");
                case "patrol_round_complete" -> player.tell("Rundă de patrulare completă (runda "
                        + event.data().get("round") + "). Checkpoint-ul "
                        + event.data().get("checkpoint") + " devine disponibil peste "
                        + ctx.policies().checkpointUnlockMinutes + " minute.");
                case "patrol_complete" -> player.tell("Patrulare încheiată: "
                        + event.data().get("rounds") + " runde complete. Serviciul se încheie.");
                case "duty_ended" -> player.tell("Serviciul s-a încheiat: " + event.data().get("reason")
                        + ". Soldul acumulat a fost păstrat.");
                case "special_started" -> player.tell("Special Duty activ. Cronometrele checkpoint-urilor sunt suspendate.");
                case "patrol_resumed" -> player.tell("Patrula a fost reluată. Ai " + event.data().get("missionMinutes")
                        + " minute pentru " + event.data().get("checkpoint") + ".");
                default -> {}
            }
        }
    }

    private boolean applyCoreResult(PlayerGateway player, GuardState state, Result result) {
        if (!result.ok()) {
            player.tell(coreError(result.code()));
            return false;
        }
        if (!state.duty) restoreDutyFaction(player, state);
        players.save(player.uuid(), state);
        notifyEvents(player, result.events());
        return true;
    }

    // ---------------------------------------------------------------- faction capture/restore (§7)

    /** Moves the guard onto the operational Straja team, remembering their team. */
    private void captureDutyFaction(PlayerGateway player, GuardState state) {
        if (!ctx.policies().captureDutyFaction) return;
        state.dutyCapturedFaction = ctx.factions().teamOf(player);
        state.dutyFactionManaged = true;
        ctx.factions().joinTeam(player, ctx.policies().strajaTeamName);
    }

    /**
     * Restores the captured team. A captured team that no longer exists is
     * flagged loudly instead of guessed; a guard who had no team returns to
     * none.
     */
    private void restoreDutyFaction(PlayerGateway player, GuardState state) {
        if (!state.dutyFactionManaged) return;
        state.dutyFactionManaged = false;
        String captured = state.dutyCapturedFaction;
        state.dutyCapturedFaction = null;
        if (captured == null) {
            ctx.factions().leaveTeam(player);
            return;
        }
        if (ctx.factions().teamExists(captured)) {
            ctx.factions().joinTeam(player, captured);
            return;
        }
        ctx.factions().leaveTeam(player);
        player.tell("Facțiunea ta anterioară \"" + captured + "\" nu mai există; ai rămas fără echipă.");
        audit.record("faction_restore_failed", "system", player.uuid().toString(),
                player.name(), player.uuid().toString(), "FAIL", captured);
    }

    /** Normal (patrol) shifts start and end at the Secretary (§7). */
    private boolean nearSecretary(PlayerGateway player) {
        var secretary = ctx.setup().read().location("secretary");
        if (secretary == null) return true; // unconfigured: fail open, setup checklist flags it
        if (!secretary.dimension.equals(player.dimension())) return false;
        double dx = player.x() - secretary.x;
        double dy = player.y() - secretary.y;
        double dz = player.z() - secretary.z;
        double radius = ctx.policies().secretaryRadiusBlocks;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    public String coreError(String code) {
        return switch (code == null ? "" : code) {
            case "rank_required" -> "Ai nevoie de rangul Stagiar.";
            case "already_on_duty" -> "Ești deja în serviciu.";
            case "suspended" -> "Ești suspendat și nu poți începe serviciul.";
            case "resignation_pending" -> "Demisia este deja în așteptare; nu poți începe un serviciu nou.";
            case "resigned" -> "Ai demisionat și ești în cooldown pentru revenire.";
            case "fired" -> "Ai fost îndepărtat din Strajă și nu poți începe serviciul.";
            case "route_invalid" -> "Traseul trebuie să conțină cel puțin "
                    + ctx.policies().patrolMinCheckpoints + " checkpoint-uri distincte.";
            case "not_normal_duty" -> "Nu ești într-o patrulă normală activă.";
            case "checkpoint_not_active" -> "Checkpoint-ul nu este disponibil încă. Respectă pauza de "
                    + ctx.policies().checkpointUnlockMinutes + " minute.";
            case "wrong_checkpoint" -> "Acesta nu este checkpoint-ul activ.";
            case "normal_duty_required" -> "Ținta trebuie să fie într-o patrulă normală.";
            case "special_duty_required" -> "Ținta nu este în Special Duty.";
            case "already_resigned" -> "Ai deja demisia semnată.";
            case "resignation_not_pending" -> "Nu există o demisie în așteptare.";
            case "resignation_wait" -> "Perioada de așteptare nu a expirat.";
            case "duty_active" -> "Încheie serviciul activ înainte de această operațiune.";
            default -> "Operațiune refuzată: " + code;
        };
    }

    public String prettyTime(Long timestamp) {
        if (timestamp == null) return "—";
        long minutes = Math.max(0, (long) Math.ceil((timestamp - now()) / (double) DutyEngine.MINUTE_MS));
        return minutes + " min";
    }

    // ------------------------------------------------------------ lifecycle

    public boolean invite(PlayerGateway actor, PlayerGateway target) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Doar Comisaru' poate invita recruți.");
            return false;
        }
        GuardState state = players.state(target);
        if (state.rank >= Rank.STAGIAR.level() || state.resigned || state.fired || state.suspended
                || state.duty || state.resignationPending) {
            actor.tell("Invitația se poate acorda doar unui Civil eligibil, fără statut activ sau suspendat.");
            audit.record("invite", actor.name(), actor.uuid().toString(),
                    target.name(), target.uuid().toString(), "REFUSED", "target_not_eligible");
            return false;
        }
        state.invited = true;
        state.fired = false;
        state.suspended = false;
        if (state.rank < Rank.STAGIAR.level()) {
            state.quizIndex = 0;
            state.quizPassed = false;
            state.quizCooldownAt = null;
            state.quizOrder = new java.util.ArrayList<>();
        }
        players.save(target.uuid(), state);
        audit.record("invite", actor.name(), actor.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS", "invited");
        target.tell("Ai fost invitat în Straja Castelului. Prezintă-te la Recrutor pentru examen, apoi la Instructor pentru instruire.");
        actor.tell("Invitația a fost trimisă lui " + target.name() + ".");
        return true;
    }

    /** §6: the admission quiz opens for applicants (Recepție) and invited recruits (Comisar bypass). */
    private boolean admissionEligible(GuardState state) {
        return !state.fired && (state.invited || "APPLIED".equals(state.applicationState));
    }

    private String admissionGate(GuardState state) {
        return state.fired ? coreError("fired")
                : "Depune mai întâi cererea la Recepție, apoi prezintă-te la Recrutor pentru examen.";
    }

    @Override
    public void applyForStraja(PlayerGateway player) {
        GuardState state = players.state(player);
        if (state.fired) {
            player.tell(coreError("fired"));
            return;
        }
        if (state.resigned || state.resignationPending) {
            player.tell("Demisia ta este pe rol sau în cooldown — cererea nu se depune la Recepție. Vorbește cu Comisaru'.");
            return;
        }
        if (state.suspended) {
            player.tell(coreError("suspended"));
            return;
        }
        if (state.rank >= Rank.STAGIAR.level() || state.quizPassed) {
            player.tell("Ești deja " + ctx.policies().rankName(state.rank) + ".");
            return;
        }
        if (state.invited) {
            player.tell("Ai deja o invitație de la Comisar — prezintă-te direct la Recrutor pentru examen.");
            return;
        }
        if ("APPLIED".equals(state.applicationState)) {
            player.tell("Cererea ta este deja înregistrată. Prezintă-te la Recrutor pentru examen.");
            return;
        }
        state.applicationState = "APPLIED";
        state.appliedAt = now();
        state.applicationRecordedBy = player.name();
        players.save(player.uuid(), state);
        audit.record("application", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS", "applied");
        player.tell("Cerere înregistrată la Recepție. Prezintă-te la Recrutor pentru examenul de admitere.");
    }

    @Override
    public void recruit(PlayerGateway player) {
        GuardState state = players.state(player);
        if (!admissionEligible(state)) {
            player.tell(admissionGate(state));
            return;
        }
        if (state.rank >= Rank.STAGIAR.level()) {
            player.tell("Ești deja " + ctx.policies().rankName(state.rank) + ".");
            return;
        }
        var question = ensureQuizOrder(state);
        players.save(player.uuid(), state);
        player.tell("Examenul se susține la Recrutor, pas cu pas. "
                + (question != null ? question.question() : "Revino la Recrutor pentru următoarea întrebare."));
    }

    /**
     * Players natively belong to other factions; while on Straja duty they act
     * as Străjeri, but the native allegiance stays on record. Self-declared at
     * the receptionist; "none"/blank clears it.
     */
    @Override
    public boolean declareNativeFaction(PlayerGateway player, String faction) {
        return applyNativeFaction(player, player, faction);
    }

    @Override
    public int nativeFactionMaxLength() {
        return ctx.policies().nativeFactionMaxLength;
    }

    /** Commissioner-side native-faction record management. */
    public boolean setNativeFactionFor(PlayerGateway actor, PlayerGateway target, String faction) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Doar Comisaru' poate edita facțiunea nativă a altui jucător.");
            return false;
        }
        return applyNativeFaction(actor, target, faction);
    }

    private boolean applyNativeFaction(PlayerGateway actor, PlayerGateway target, String faction) {
        String normalized = faction == null ? "" : faction.trim();
        if (normalized.isEmpty() || normalized.equalsIgnoreCase("none")
                || normalized.equalsIgnoreCase("niciuna") || normalized.equalsIgnoreCase("niciună")) {
            normalized = null;
        } else if (normalized.length() > ctx.policies().nativeFactionMaxLength) {
            actor.tell("Facțiunea nativă poate avea maximum "
                    + ctx.policies().nativeFactionMaxLength + " caractere.");
            return false;
        }
        GuardState state = players.state(target);
        state.nativeFaction = normalized;
        players.save(target.uuid(), state);
        audit.record("native_faction", actor.name(), actor.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS",
                normalized == null ? "cleared" : normalized);
        if (normalized == null) {
            target.tell("Facțiunea nativă a fost ștearsă din registrul Străjii.");
        } else {
            target.tell("Facțiune nativă înregistrată: " + normalized
                    + ". În timpul turei acționezi ca Străjer al Castelului.");
        }
        if (actor != target) {
            actor.tell("Facțiunea nativă a lui " + target.name() + " a fost actualizată.");
        }
        return true;
    }

    /**
     * Commissioner-side specialization management (§2): ranks are the ladder,
     * specializations are independent functions (Instructor, Recrutor, …)
     * recorded on the personnel file.
     */
    public boolean setSpecialization(PlayerGateway actor, PlayerGateway target,
                                     String specialization, boolean grant) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Doar Comisaru' poate acorda sau retrage funcții.");
            return false;
        }
        String name = specialization == null ? "" : specialization.trim();
        if (name.isEmpty() || name.length() > ctx.policies().nativeFactionMaxLength) {
            actor.tell("Funcția trebuie să aibă între 1 și "
                    + ctx.policies().nativeFactionMaxLength + " caractere.");
            return false;
        }
        GuardState state = players.state(target);
        if (state.specializations == null) state.specializations = new java.util.LinkedHashSet<>();
        boolean changed = grant ? state.specializations.add(name) : state.specializations.remove(name);
        if (!changed) {
            actor.tell(target.name() + (grant ? " are deja funcția " : " nu are funcția ") + name + ".");
            return false;
        }
        players.save(target.uuid(), state);
        audit.record("specialization", actor.name(), actor.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS",
                (grant ? "grant:" : "revoke:") + name);
        target.tell(grant ? "Ai primit funcția: " + name + "." : "Funcția " + name + " ți-a fost retrasă.");
        actor.tell(target.name() + (grant ? " — funcția " : " — funcția ") + name
                + (grant ? " acordată." : " retrasă."));
        return true;
    }

    private StrajaPolicies.QuizQuestion ensureQuizOrder(GuardState state) {
        List<StrajaPolicies.QuizQuestion> quiz = ctx.policies().quiz;
        boolean valid = state.quizOrder != null && state.quizOrder.size() == quiz.size()
                && new java.util.HashSet<>(state.quizOrder).size() == quiz.size()
                && state.quizIndex >= 0 && state.quizIndex < quiz.size();
        if (!valid) {
            var order = new java.util.ArrayList<String>();
            for (StrajaPolicies.QuizQuestion q : quiz) order.add(q.id());
            java.util.Collections.shuffle(order);
            state.quizOrder = order;
            state.quizIndex = 0;
        }
        return state.quizIndex < state.quizOrder.size()
                ? quiz.stream().filter(q -> q.id().equals(state.quizOrder.get(state.quizIndex))).findFirst().orElse(null)
                : null;
    }

    /** /straja quiz <answer>: admission quiz below Stagiar, training modules after. */
    public void quiz(PlayerGateway player, String answer) {
        GuardState state = players.state(player);
        if (!admissionEligible(state)) {
            player.tell(admissionGate(state));
            return;
        }
        if (state.resigned) {
            player.tell(coreError("resigned"));
            return;
        }
        if (state.suspended) {
            player.tell(coreError("suspended"));
            return;
        }
        if (state.rank >= Rank.STAGIAR.level() || state.quizPassed) {
            trainingQuiz(player, state, answer);
            return;
        }
        if (state.quizCooldownAt != null && state.quizCooldownAt > now()) {
            player.tell("Mai încearcă peste " + prettyTime(state.quizCooldownAt) + ".");
            return;
        }
        var item = ensureQuizOrder(state);
        if (item == null) {
            promoteToStagiar(player, state);
            return;
        }
        applyInitialAnswer(player, state, item, answer);
    }

    @Override
    public Optional<QuizPrompt> currentQuizPrompt(PlayerGateway player) {
        GuardState state = players.state(player);
        if (!admissionEligible(state)) {
            player.tell(admissionGate(state));
            return Optional.empty();
        }
        if (state.resigned) {
            player.tell(coreError("resigned"));
            return Optional.empty();
        }
        if (state.suspended) {
            player.tell(coreError("suspended"));
            return Optional.empty();
        }
        if (state.rank >= Rank.STAGIAR.level() || state.quizPassed) {
            return currentTrainingPrompt(player, state);
        }
        if (state.quizCooldownAt != null && state.quizCooldownAt > now()) {
            player.tell("Mai încearcă peste " + prettyTime(state.quizCooldownAt) + ".");
            return Optional.empty();
        }
        var item = ensureQuizOrder(state);
        if (item == null) {
            promoteToStagiar(player, state);
            return Optional.empty();
        }
        players.save(player.uuid(), state);
        return Optional.of(new QuizPrompt(item.id(), "Recrutare Straja",
                item.question(), QUIZ_PROMPT_MAX_LENGTH));
    }

    @Override
    public boolean answerQuiz(PlayerGateway player, String expectedQuestionId, String answer) {
        GuardState state = players.state(player);
        if (!admissionEligible(state)) {
            player.tell(admissionGate(state));
            return false;
        }
        if (state.resigned) {
            player.tell(coreError("resigned"));
            return false;
        }
        if (state.suspended) {
            player.tell(coreError("suspended"));
            return false;
        }
        if (state.rank >= Rank.STAGIAR.level() || state.quizPassed) {
            return answerTraining(player, state, expectedQuestionId, answer);
        }
        if (state.quizCooldownAt != null && state.quizCooldownAt > now()) {
            player.tell("Mai încearcă peste " + prettyTime(state.quizCooldownAt) + ".");
            return false;
        }
        var item = ensureQuizOrder(state);
        if (item == null) {
            promoteToStagiar(player, state);
            return false;
        }
        if (expectedQuestionId == null || expectedQuestionId.isBlank()
                || !expectedQuestionId.equals(item.id())) {
            player.tell(STALE_FORM);
            return false;
        }
        applyInitialAnswer(player, state, item, answer);
        return true;
    }

    private void promoteToStagiar(PlayerGateway player, GuardState state) {
        state.quizPassed = true;
        state.rank = Rank.STAGIAR.level();
        state.applicationState = "AUTHORIZED";
        state.invited = true;
        players.save(player.uuid(), state);
        roomAutoAssign.onPromotedToGuard(player);
        grantRankUp(player, state, Rank.STAGIAR.level());
        player.tell("Quiz promovat. Ai devenit Stagiar.");
    }

    /**
     * Rank-up reward: credits {@code promotionBonusHours × salaryPerHour(rank)}
     * to the unpaid-salary balance and attempts the permanent rank kit.
     * The kit is owned gear — old equipment is never taken back; when the
     * inventory cannot take it, {@code kitClaimedRank} stays below the rank so
     * the kit remains claimable.
     */
    private void grantRankUp(PlayerGateway player, GuardState state, int rank) {
        int bonus = Math.max(0, ctx.policies().promotionBonusHours)
                * Math.max(0, ctx.policies().salaryPerHour(rank));
        if (bonus > 0) {
            state.unpaidSalary += bonus;
            players.save(player.uuid(), state);
            player.tell("Bonus de avansare: " + bonus + " monede în sold ("
                    + ctx.policies().promotionBonusHours + "h × "
                    + ctx.policies().salaryPerHour(rank) + "/h).");
        }
        audit.record("rank_grant", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS",
                "rank=" + rank + (bonus > 0 ? ",bonus=" + bonus : ""));
        if (state.kitClaimedRank < rank) equipment.giveKit(player, state);
    }

    private void applyInitialAnswer(PlayerGateway player, GuardState state,
                                    StrajaPolicies.QuizQuestion item, String answer) {
        if (item.accepts(answer)) {
            state.quizIndex += 1;
            state.quizCooldownAt = null;
            if (state.quizIndex >= ctx.policies().quiz.size()) {
                state.quizPassed = true;
                state.rank = Rank.STAGIAR.level();
                state.applicationState = "AUTHORIZED";
                state.invited = true;
                players.save(player.uuid(), state);
                roomAutoAssign.onPromotedToGuard(player);
                grantRankUp(player, state, Rank.STAGIAR.level());
                player.tell("Quiz promovat. Ai devenit Stagiar. Poți începe serviciul; echipamentul este al tău.");
            } else {
                players.save(player.uuid(), state);
                var next = ctx.policies().quiz.stream()
                        .filter(q -> q.id().equals(state.quizOrder.get(state.quizIndex))).findFirst().orElse(null);
                player.tell("Răspuns corect. Întrebarea următoare: " + (next != null ? next.question() : "—"));
            }
        } else {
            state.quizCooldownAt = now() + ctx.policies().quizCooldownMinutes * DutyEngine.MINUTE_MS;
            players.save(player.uuid(), state);
            player.tell("Răspuns greșit. Poți încerca din nou peste " + ctx.policies().quizCooldownMinutes + " minute.");
        }
    }

    private Optional<QuizPrompt> currentTrainingPrompt(PlayerGateway player, GuardState state) {
        if (state.rank < Rank.STAGIAR.level()) {
            player.tell("Quiz-ul de rang se deschide după recrutarea ca Stagiar.");
            return Optional.empty();
        }
        if (state.trainingQuizCooldownAt != null && state.trainingQuizCooldownAt > now()) {
            player.tell("Mai încearcă peste " + prettyTime(state.trainingQuizCooldownAt) + ".");
            return Optional.empty();
        }
        var item = currentTrainingItem(state);
        players.save(player.uuid(), state);
        if (item == null) {
            player.tell("Nu ai încă module de instruire disponibile pentru rangul tău. Consultă Manualul de la Instructor.");
            return Optional.empty();
        }
        return Optional.of(new QuizPrompt(item.id(), "Instruire Straja",
                item.question(), QUIZ_PROMPT_MAX_LENGTH));
    }

    private boolean answerTraining(PlayerGateway player, GuardState state,
                                   String expectedQuestionId, String answer) {
        if (state.rank < Rank.STAGIAR.level()) {
            player.tell("Quiz-ul de rang se deschide după recrutarea ca Stagiar.");
            return false;
        }
        if (state.trainingQuizCooldownAt != null && state.trainingQuizCooldownAt > now()) {
            player.tell("Mai încearcă peste " + prettyTime(state.trainingQuizCooldownAt) + ".");
            return false;
        }
        var item = currentTrainingItem(state);
        players.save(player.uuid(), state);
        if (item == null) {
            player.tell("Nu ai încă module de instruire disponibile pentru rangul tău. Consultă Manualul de la Instructor.");
            return false;
        }
        if (expectedQuestionId == null || expectedQuestionId.isBlank()
                || !expectedQuestionId.equals(item.id())) {
            player.tell(STALE_FORM);
            return false;
        }
        applyTrainingAnswer(player, state, item, answer);
        return true;
    }

    private void trainingQuiz(PlayerGateway player, GuardState state, String answer) {
        if (state.rank < Rank.STAGIAR.level()) {
            player.tell("Quiz-ul de rang se deschide după recrutarea ca Stagiar.");
            return;
        }
        if (state.trainingQuizCooldownAt != null && state.trainingQuizCooldownAt > now()) {
            player.tell("Mai încearcă peste " + prettyTime(state.trainingQuizCooldownAt) + ".");
            return;
        }
        var item = currentTrainingItem(state);
        if (item == null) {
            players.save(player.uuid(), state);
            player.tell("Nu ai încă module de instruire disponibile pentru rangul tău. Consultă Manualul de la Instructor.");
            return;
        }
        if (answer == null || answer.isBlank()) {
            players.save(player.uuid(), state);
            player.tell("Instruire " + item.id() + ": " + item.question());
            return;
        }
        applyTrainingAnswer(player, state, item, answer);
    }

    private void applyTrainingAnswer(PlayerGateway player, GuardState state,
                                     StrajaPolicies.QuizQuestion item, String answer) {
        if (item.accepts(answer)) {
            state.trainingPassed.put(item.id(), true);
            state.trainingQuizId = null;
            state.trainingQuizCooldownAt = null;
            var next = currentTrainingItem(state);
            players.save(player.uuid(), state);
            player.tell("Răspuns corect. " + (next != null ? "Următorul modul: " + next.question()
                    : "Ai terminat instruirea disponibilă pentru rangul tău."));
            audit.record("training_quiz", player.name(), player.uuid().toString(),
                    player.name(), player.uuid().toString(), "SUCCESS", "correct_answer");
        } else {
            state.trainingQuizCooldownAt = now() + ctx.policies().quizCooldownMinutes * DutyEngine.MINUTE_MS;
            players.save(player.uuid(), state);
            player.tell("Răspuns greșit. Modulul rămâne disponibil după cooldown.");
            audit.record("training_quiz", player.name(), player.uuid().toString(),
                    player.name(), player.uuid().toString(), "REFUSED", "wrong_answer");
        }
    }

    private StrajaPolicies.QuizQuestion currentTrainingItem(GuardState state) {
        var available = pendingModules(state);
        if (available.isEmpty()) {
            state.trainingQuizId = null;
            return null;
        }
        var existing = available.stream().filter(q -> q.id().equals(state.trainingQuizId)).findFirst();
        if (existing.isPresent()) return existing.get();
        var selected = available.get(new java.util.Random().nextInt(available.size()));
        state.trainingQuizId = selected.id();
        return selected;
    }

    // ------------------------------------------------------------ trainer: progress, promotion, manual

    @Override
    public TrainingView trainingView(PlayerGateway player) {
        GuardState state = players.state(player);
        return buildTrainingView(player, state);
    }

    private TrainingView buildTrainingView(PlayerGateway player, GuardState state) {
        Integer nextRank = null;
        Integer required = null;
        boolean canPromote = false;
        if (promotableState(state)) {
            int candidate = state.rank + 1;
            if (ctx.policies().promotionServiceBlocks.containsKey(candidate)) {
                nextRank = candidate;
                required = ctx.policies().promotionServiceBlocks.get(candidate);
                canPromote = state.serviceBlocks >= required && pendingModules(state).isEmpty();
            }
        }
        return new TrainingView(state.rank, state.serviceBlocks, nextRank, required, canPromote,
                hasManual(player), state.rank >= Rank.STAGIAR.level() ? pendingModules(state).size() : 0);
    }

    private boolean promotableState(GuardState state) {
        return state.invited && !state.fired && !state.resigned && !state.suspended
                && !state.resignationPending
                && state.rank >= Rank.STAGIAR.level() && state.rank < Rank.INSPECTOR.level();
    }

    private List<StrajaPolicies.QuizQuestion> pendingModules(GuardState state) {
        return ctx.policies().trainingQuiz.stream()
                .filter(q -> q.minRank() <= state.rank && !Boolean.TRUE.equals(state.trainingPassed.get(q.id())))
                .toList();
    }

    private boolean hasManual(PlayerGateway player) {
        String manual = ctx.policies().trainingManualItem;
        return manual != null && !manual.isBlank() && player.inventory().countOf(manual) >= 1;
    }

    @Override
    public void showProgress(PlayerGateway player) {
        GuardState state = players.state(player);
        var view = buildTrainingView(player, state);
        player.tell("Rang: " + ctx.policies().rankName(state.rank)
                + " | puncte de serviciu: " + state.serviceBlocks);
        var pending = pendingModules(state);
        player.tell(pending.isEmpty()
                ? "Instruire teoretică: completă pentru rangul tău."
                : "Instruire teoretică: " + pending.size() + " module rămase la rangul tău.");
        if (view.nextRank() == null) {
            player.tell(promotableState(state)
                    ? "Avansarea la " + ctx.policies().rankName(state.rank + 1) + " este decizia Comisarului."
                    : "Nu există o avansare disponibilă pentru starea ta curentă.");
        } else {
            player.tell("Avansare la " + ctx.policies().rankName(view.nextRank()) + ": necesare "
                    + view.requiredBlocks() + " puncte (îți lipsesc "
                    + Math.max(0, view.requiredBlocks() - state.serviceBlocks) + ").");
        }
    }

    @Override
    public void requestPromotion(PlayerGateway player) {
        GuardState state = players.state(player);
        if (!promotableState(state)) {
            player.tell("Nu ești într-o stare care permite avansarea.");
            return;
        }
        int nextRank = state.rank + 1;
        Integer required = ctx.policies().promotionServiceBlocks.get(nextRank);
        if (required == null) {
            player.tell("Avansarea la " + ctx.policies().rankName(nextRank) + " este decizia Comisarului.");
            return;
        }
        if (state.serviceBlocks < required) {
            player.tell("Mai sunt necesare " + (required - state.serviceBlocks) + " puncte de serviciu.");
            return;
        }
        var pending = pendingModules(state);
        if (!pending.isEmpty()) {
            player.tell("Mai întâi finalizează instruirea: " + pending.size()
                    + " module rămase. Întreabă Instructorul pentru următoarea întrebare.");
            return;
        }
        state.rank = nextRank;
        players.save(player.uuid(), state);
        roomAutoAssign.onPromotedToGuard(player);
        audit.record("promotion", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS",
                "trainer_rank_up:" + nextRank);
        grantRankUp(player, state, nextRank);
        player.tell("Avansare: " + ctx.policies().rankName(nextRank)
                + ". Instructorul te-a confirmat; echipamentul nou este al tău.");
    }

    @Override
    public void giveManual(PlayerGateway player) {
        GuardState state = players.state(player);
        if ((!state.invited || state.fired) && state.rank < Rank.STAGIAR.level()) {
            player.tell("Manualul este pentru recruții invitați și străjeri. Vorbește cu Comisaru'.");
            return;
        }
        if (state.suspended || state.fired || state.resigned) {
            player.tell("Manualul de instruire nu este disponibil în starea ta curentă.");
            return;
        }
        if (hasManual(player)) {
            player.tell("Ai deja Manualul de instruire în inventar.");
            return;
        }
        var spec = ItemSpec.of(ctx.policies().trainingManualItem, 1);
        if (!player.giveVerified(spec)) {
            player.tell("Inventarul este plin; Manualul nu a putut fi predat.");
            return;
        }
        audit.record("training_manual", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS", "manual_issued");
        player.tell("Ai primit Manualul de instruire. Deschide-l pentru regulament.");
    }

    // ------------------------------------------------------------ admin rank ops

    public void promote(PlayerGateway actor, PlayerGateway target) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Promovările sunt decizia finală a Comisarului.");
            return;
        }
        GuardState state = players.state(target);
        if (state.rank < Rank.STAGIAR.level() || state.rank >= Rank.INSPECTOR.level()
                || state.suspended || state.resigned || state.fired || state.resignationPending) {
            actor.tell("Ținta nu poate fi promovată prin această comandă.");
            return;
        }
        int nextRank = state.rank + 1;
        int required = ctx.policies().promotionServiceBlocks.getOrDefault(nextRank, 0);
        if (state.serviceBlocks < required) {
            actor.tell("Mai sunt necesare " + (required - state.serviceBlocks) + " blocuri de serviciu.");
            return;
        }
        state.rank = nextRank;
        players.save(target.uuid(), state);
        grantRankUp(target, state, nextRank);
        audit.record("promote", actor.name(), actor.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS", "rank=" + nextRank);
        target.tell("Promovare: " + ctx.policies().rankName(nextRank) + ".");
        actor.tell(target.name() + " a fost promovat la " + ctx.policies().rankName(nextRank) + ".");
    }

    public void demote(PlayerGateway actor, PlayerGateway target) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Retrogradările sunt decizia Comisarului.");
            return;
        }
        GuardState state = players.state(target);
        if (state.rank <= Rank.CIVIL.level()) {
            actor.tell("Ținta este deja Civil.");
            return;
        }
        int nextRank = state.rank - 1;
        if (nextRank < Rank.STAGIAR.level() && state.duty) {
            DutyEngine.endDuty(state, "demoted_to_civil", now(), salaryFor(target, state), ctx.policies());
            restoreDutyFaction(target, state);
        }
        state.rank = nextRank;
        // kitClaimedRank is never lowered: gear already granted stays owned and
        // a later re-promotion cannot re-claim the same rank kit.
        int docked = Math.min(Math.max(0, ctx.policies().demotionServiceBlockCost), (int) state.serviceBlocks);
        if (docked > 0) {
            state.serviceBlocks -= docked;
            target.tell("S-au retras " + docked + " puncte de serviciu în urma retrogradării.");
        }
        players.save(target.uuid(), state);
        if (nextRank < Rank.STAGIAR.level()) fireStatusChange(target, "demotat la Civil");
        audit.record("demote", actor.name(), actor.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS",
                "rank_changed,serviceBlocksDocked=" + docked);
        target.tell("Retrogradare: " + ctx.policies().rankName(state.rank) + ".");
        actor.tell(target.name() + " a fost retrogradat.");
    }

    public void suspend(PlayerGateway actor, PlayerGateway target) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Suspendările sunt decizia Comisarului.");
            return;
        }
        GuardState state = players.state(target);
        state.suspended = true;
        state.resignationPending = false;
        state.resignationDeadlineAt = null;
        state.resignationRank = null;
        if (state.duty) {
            DutyEngine.endDuty(state, "suspended", now(), salaryFor(target, state), ctx.policies());
            restoreDutyFaction(target, state);
        }
        long docked = Math.min(Math.max(0, ctx.policies().suspensionRequisitionCost), state.requisitionPoints);
        if (docked > 0) {
            state.requisitionPoints -= docked;
            target.tell("S-au retras " + docked + " puncte de rechiziție în urma suspendării.");
        }
        players.save(target.uuid(), state);
        fireStatusChange(target, "suspendat din Strajă");
        audit.record("suspend", actor.name(), actor.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS",
                "suspended,requisitionDocked=" + docked);
        target.tell("Ai fost suspendat din Strajă.");
        actor.tell(target.name() + " a fost suspendat.");
    }

    /**
     * §14: the Comisar authorizes an experienced person directly at a rank,
     * skipping application/exam — the personnel equivalent of a field
     * commission. Only civilians qualify; existing or former members go
     * through promote/reinstate instead.
     */
    public boolean authorizeAt(PlayerGateway actor, PlayerGateway target, int rank) {
        if (!players.isCommissioner(actor) && !actor.isOp()) {
            actor.tell("Autorizarea directă este decizia Comisarului.");
            return false;
        }
        if (target == null) {
            actor.tell("Jucătorul nu este online sau nu există.");
            return false;
        }
        if (rank < Rank.STAGIAR.level() || rank > Rank.INSPECTOR.level()) {
            actor.tell("Rang invalid. Folosește un rang între Stagiar și Inspector.");
            return false;
        }
        GuardState state = players.state(target);
        if (state.rank > 0 || state.fired || state.resigned || state.suspended) {
            actor.tell(target.name() + " are deja o fișă Straja — folosește promovare/reintegrare.");
            return false;
        }
        state.rank = rank;
        state.invited = true;
        state.applicationState = "AUTHORIZED";
        state.applicationRecordedBy = actor.name();
        players.save(target.uuid(), state);
        roomAutoAssign.onPromotedToGuard(target);
        grantRankUp(target, state, rank);
        audit.record("personnel_authorize", actor.name(), actor.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS", "rank=" + rank);
        target.tell("Comisarul te-a autorizat direct în Strajă ca "
                + ctx.policies().rankName(rank) + ".");
        actor.tell(target.name() + " autorizat direct ca " + ctx.policies().rankName(rank) + ".");
        return true;
    }

    public boolean reinstate(PlayerGateway actor, PlayerGateway target) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Reintegrarea este decizia Comisarului.");
            return false;
        }
        GuardState state = players.state(target);
        if (!state.suspended || state.fired || state.resigned || state.rank < Rank.STAGIAR.level()) {
            actor.tell("Ținta nu este într-o stare care poate fi reintegrată.");
            audit.record("reinstate", actor.name(), actor.uuid().toString(),
                    target.name(), target.uuid().toString(), "REFUSED", "target_not_suspended");
            return false;
        }
        state.suspended = false;
        players.save(target.uuid(), state);
        roomAutoAssign.onPromotedToGuard(target);
        audit.record("reinstate", actor.name(), actor.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS", "reinstated");
        target.tell("Ai fost reintegrat în Strajă ca " + ctx.policies().rankName(state.rank) + ".");
        actor.tell(target.name() + " a fost reintegrat.");
        return true;
    }

    public void fire(PlayerGateway actor, PlayerGateway target) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Îndepărtarea din Strajă este decizia Comisarului.");
            return;
        }
        GuardState state = players.state(target);
        if (state.duty) {
            DutyEngine.endDuty(state, "fired", now(), salaryFor(target, state), ctx.policies());
            restoreDutyFaction(target, state);
        }
        state.fired = true;
        state.invited = false;
        state.rank = Rank.CIVIL.level();
        state.requisitionPoints = 0;
        state.suspended = false;
        state.resigned = false;
        state.resignedAt = null;
        state.rejoinAvailableAt = null;
        state.formerRank = null;
        state.resignationPending = false;
        state.resignationDeadlineAt = null;
        state.resignationRank = null;
        players.save(target.uuid(), state);
        fireStatusChange(target, "îndepărtat din Strajă");
        audit.record("fire", actor.name(), actor.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS", "fired");
        target.tell("Ai fost îndepărtat din Strajă.");
        actor.tell(target.name() + " a fost îndepărtat din Strajă.");
    }

    // ------------------------------------------------------------ duty

    /** Read-only affordances for native surfaces; mutators revalidate anyway. */
    @Override
    public DutyView dutyView(PlayerGateway player) {
        GuardState state = players.state(player);
        boolean activeGuard = state.rank >= Rank.STAGIAR.level()
                && !state.suspended && !state.fired && !state.resigned;
        String checkpointId = "";
        if (state.duty && "NORMAL".equals(state.mode) && "ACTIVE".equals(state.patrolState)
                && state.patrolIndex >= 0 && state.patrolIndex < state.route.size()) {
            checkpointId = state.route.get(state.patrolIndex);
        }
        boolean canClaimSalary = activeGuard && (state.unpaidSalary > 0
                || "PENDING".equals(state.salaryPaymentStatus)
                || "IN_PROGRESS".equals(state.salaryPaymentStatus)
                || "REVIEW".equals(state.salaryPaymentStatus));
        return new DutyView(
                activeGuard,
                activeGuard && !state.duty && !state.resignationPending,
                checkpointId,
                activeGuard && state.duty,
                canClaimSalary,
                activeGuard,
                activeGuard && state.duty
                        && (state.foodReadyAt == null || state.foodReadyAt <= now()),
                activeGuard && state.kitClaimedRank < state.rank,
                activeGuard && !state.resignationPending,
                state.resignationPending && state.resignationDeadlineAt != null
                        && state.resignationDeadlineAt <= now(),
                state.resignationPending,
                state.resigned && (state.rejoinAvailableAt == null
                        || state.rejoinAvailableAt <= now()));
    }

    public void startDuty(PlayerGateway player) {
        GuardState state = players.state(player);
        if (state.rank >= Rank.STAGIAR.level()) {
            if (state.resignationPending) { player.tell(coreError("resignation_pending")); return; }
            if (state.resigned) { player.tell(coreError("resigned")); return; }
            if (state.fired) { player.tell(coreError("fired")); return; }
        }
        if (!players.permissionLevel(player, state).atLeast(PermissionLevel.GUARD)) {
            player.tell("Doar un străjer activ poate începe serviciul.");
            return;
        }
        // §11: an overdue activity report can block a new shift (opt-in).
        if (ctx.policies().reportBlockDutyWhenOverdue && !players.isCommissioner(player)) {
            var reports = ctx.reports().read();
            String uuid = player.uuid().toString();
            if (com.dwurdy.straja.domain.model.ActivityReports.ensureAnchor(reports, uuid, now())) {
                ctx.reports().write(reports);
            }
            if (com.dwurdy.straja.domain.model.ActivityReports.isOverdue(
                    reports, uuid, now(), ctx.policies().reportIntervalDays * 86_400_000L)) {
                player.tell("Raportul tău de activitate este restant. "
                        + "Depune-l la Secretariat înainte de a începe serviciul.");
                return;
            }
        }
        if (freeDutyEligible(player, state)) {
            Result free = DutyEngine.startFreeDuty(state, now(), ctx.policies(),
                    players.isCommissioner(player));
            if (applyCoreResult(player, state, free)) {
                captureDutyFaction(player, state);
                players.save(player.uuid(), state);
                audit.record("duty_start", player.name(), player.uuid().toString(),
                        player.name(), player.uuid().toString(), "SUCCESS", "free_shift");
                player.tell("Tură liberă începută. Ești plătit pe oră; "
                        + "termini tura când dorești de la secretară sau cu /straja stop.");
            }
            return;
        }
        if (!nearSecretary(player)) {
            player.tell("Tura de patrulare începe la secretară. Mergi la Secretara Străjii pentru a intra în serviciu.");
            return;
        }
        SetupData setup = ctx.setup().read();
        var route = setup.checkpoints.stream().filter(SetupData.Checkpoint::isPlaced).map(c -> c.id).toList();
        int minCheckpoints = Math.max(1, ctx.policies().patrolMinCheckpoints);
        if (route.size() < minCheckpoints) {
            player.tell("Checkpoint-urile nu sunt configurate. Comisaru' trebuie să marcheze cel puțin "
                    + minCheckpoints + " puncte de patrulare.");
            return;
        }
        Result result = DutyEngine.startDuty(state, route, now(), setup.missionMinutes, ctx.policies());
        if (applyCoreResult(player, state, result)) {
            captureDutyFaction(player, state);
            markDutyActivity(player, state, now());
            // §25: snapshot required rounds at shift start so ending the
            // emergency mid-shift does not strand an active patrol.
            var emergency = ctx.emergency().read();
            if (emergency.active) {
                state.requiredRounds = emergency.requiredRounds;
                player.tell("Stare de urgență activă: această tură cere "
                        + state.requiredRounds + " runde complete de patrulare.");
            }
            players.save(player.uuid(), state);
            audit.record("duty_start", player.name(), player.uuid().toString(),
                    player.name(), player.uuid().toString(), "SUCCESS", "started");
            player.tell("Serviciu început. Primul checkpoint: " + state.route.get(0) + ". Ai "
                    + state.missionMinutes.get(state.route.get(0)) + " minute.");
        }
    }

    public void checkpoint(PlayerGateway player, String id) {
        GuardState state = players.state(player);
        if (!players.permissionLevel(player, state).atLeast(PermissionLevel.GUARD)) {
            player.tell("Doar un străjer activ poate activa checkpoint-uri.");
            return;
        }
        SetupData setup = ctx.setup().read();
        var point = setup.checkpoints.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
        if (point == null || !atCheckpoint(player, point)) {
            player.tell("Nu ești la checkpoint-ul " + id + ".");
            return;
        }
        markDutyActivity(player, state, now());
        Result result = DutyEngine.activateCheckpoint(state, id, now(), salaryFor(player, state), setup.missionMinutes, ctx.policies());
        if (applyCoreResult(player, state, result)) {
            audit.record("duty_checkpoint", player.name(), player.uuid().toString(),
                    player.name(), player.uuid().toString(), "SUCCESS", id);
        }
    }

    private boolean atCheckpoint(PlayerGateway player, SetupData.Checkpoint point) {
        if (!point.isPlaced() || !player.dimension().equals(point.dimension)) return false;
        double dx = player.x() - point.x, dy = player.y() - point.y, dz = player.z() - point.z;
        return dx * dx + dy * dy + dz * dz <= 9; // 3-block radius, same as reference
    }

    public void stopDuty(PlayerGateway player) {
        GuardState state = players.state(player);
        if (!players.permissionLevel(player, state).atLeast(PermissionLevel.GUARD)) {
            player.tell("Doar un străjer activ poate încheia serviciul.");
            return;
        }
        if (!state.duty) {
            player.tell("Nu ești în serviciu.");
            return;
        }
        if (!"FREE".equals(state.mode) && !nearSecretary(player)) {
            player.tell("Tura normală se încheie la secretară. Mergi la Secretara Străjii pentru a ieși din serviciu.");
            return;
        }
        markDutyActivity(player, state, now());
        Result result = DutyEngine.endDuty(state, "voluntary_stop", now(), salaryFor(player, state), ctx.policies());
        if (applyCoreResult(player, state, result)) {
            audit.record("duty_stop", player.name(), player.uuid().toString(),
                    player.name(), player.uuid().toString(), "SUCCESS", "voluntary_stop");
        }
    }

    /**
     * Login recovery: closes a duty left active by a previous runtime boot,
     * then stamps the current boot id so a relogin inside the same runtime
     * keeps an active duty.
     */
    @Override
    public void recoverOnLogin(PlayerGateway player) {
        if (player == null || player.uuid() == null) return;
        GuardState state = players.state(player.uuid());
        closeDutyAfterRestart(player, state, bootId.get());
        state.runtimeBootId = bootId.get();
        players.save(player.uuid(), state);
    }

    /** Ends a stale active duty left over from before a server restart. */
    public boolean closeDutyAfterRestart(PlayerGateway player, GuardState state, String bootId) {
        if (!state.duty || bootId.equals(state.runtimeBootId)) return false;
        state.duty = false;
        state.mode = "OFF_DUTY";
        state.patrolState = "OFF";
        state.waitingUntil = null;
        state.deadlineAt = null;
        state.lastAccrualAt = null;
        state.lastDutyActivityAt = null;
        state.lastDutyActivityX = null;
        state.lastDutyActivityY = null;
        state.lastDutyActivityZ = null;
        state.salaryActivityPaused = false;
        state.lastEndReason = "server_restart";
        restoreDutyFaction(player, state);
        players.save(player.uuid(), state);
        player.tell("Serviciul activ a fost închis la restart; timpul offline nu se plătește.");
        return true;
    }

    // ------------------------------------------------------------ special duty

    public void specialStart(PlayerGateway actor, PlayerGateway target) {
        GuardState targetState = players.state(target);
        if (!canAuthorize(actor, "special_duty", target.name(), targetState.rank)) {
            actor.tell("Special Duty poate fi autorizat de Comisaru' sau de un Inspector pentru alt străjer.");
            return;
        }
        if (!operational(targetState)) {
            actor.tell("Ținta nu este un străjer operațional.");
            return;
        }
        Result result = DutyEngine.startSpecial(targetState, actor.name(), now(), salaryFor(target, targetState), ctx.policies());
        if (applyCoreResult(target, targetState, result)) {
            actor.tell("Special Duty pornit pentru " + target.name() + ".");
        }
    }

    public void specialResume(PlayerGateway actor, PlayerGateway target) {
        GuardState targetState = players.state(target);
        if (!canAuthorize(actor, "resume_special", target.name(), targetState.rank)) {
            actor.tell("Nu ai autoritate pentru reluarea Special Duty.");
            return;
        }
        if (!operational(targetState)) {
            actor.tell("Ținta nu este un străjer operațional.");
            return;
        }
        Result result = DutyEngine.resumeSpecial(targetState, now(), salaryFor(target, targetState), ctx.policies());
        if (applyCoreResult(target, targetState, result)) {
            actor.tell("Patrula lui " + target.name() + " a fost reluată.");
        }
    }

    public void specialComplete(PlayerGateway actor, PlayerGateway target) {
        GuardState targetState = players.state(target);
        if (!canAuthorize(actor, "complete_special", target.name(), targetState.rank)) {
            actor.tell("Nu ai autoritate pentru închiderea Special Duty.");
            return;
        }
        if (!operational(targetState)) {
            actor.tell("Ținta nu este un străjer operațional.");
            return;
        }
        Result result = DutyEngine.completeSpecial(targetState, now(), salaryFor(target, targetState), ctx.policies());
        if (applyCoreResult(target, targetState, result)) {
            actor.tell("Special Duty al lui " + target.name() + " a fost închis.");
        }
    }

    // ------------------------------------------------------------ resignation

    public void resign(PlayerGateway player, String confirmation) {
        GuardState state = players.state(player);
        String normalized = confirmation == null ? "" : confirmation.trim().toLowerCase();
        if (state.resigned) {
            player.tell("Ai deja demisia semnată.");
            return;
        }
        if (normalized.equals("anuleaza") || normalized.equals("cancel")) {
            Result cancelled = DutyEngine.cancelResignation(state);
            if (!cancelled.ok()) player.tell("Nu există o demisie în așteptare.");
            else {
                players.save(player.uuid(), state);
                audit.record("resignation_cancel", player.name(), player.uuid().toString(),
                        player.name(), player.uuid().toString(), "SUCCESS", "cancelled");
                player.tell("Cererea de demisie a fost anulată.");
            }
            return;
        }
        if (!state.resignationPending) {
            if (state.duty) {
                DutyEngine.endDuty(state, "resignation_notice", now(), salaryFor(player, state), ctx.policies());
                restoreDutyFaction(player, state);
            }
            Result started = DutyEngine.beginResignation(state, now(), ctx.policies().resignationNoticeMinutes);
            if (!started.ok()) {
                player.tell(coreError(started.code()));
                return;
            }
            players.save(player.uuid(), state);
            fireStatusChange(player, "demisie în preaviz");
            audit.record("resignation_start", player.name(), player.uuid().toString(),
                    player.name(), player.uuid().toString(), "SUCCESS", "notice_started");
            player.tell("Demisia intră în preaviz pentru " + ctx.policies().resignationNoticeMinutes
                    + " minute. Camera rămâne a ta în acest interval ca să-ți strângi lucrurile. După expirare semnează la Comisaru' sau cere anularea.");
            return;
        }
        if (!normalized.equals("confirm")) {
            player.tell("Demisia este în așteptare. Mai sunt " + prettyTime(state.resignationDeadlineAt)
                    + ". Confirmă la Comisaru' sau cere anularea.");
            return;
        }
        long cooldownMs = ctx.policies().resignationCooldownDays * 24L * 60 * 60 * 1000;
        Result completed = DutyEngine.completeResignation(state, now(), cooldownMs);
        if (!completed.ok()) {
            player.tell("resignation_wait".equals(completed.code())
                    ? "Nu poți semna încă. Mai sunt " + prettyTime(state.resignationDeadlineAt) + "."
                    : coreError(completed.code()));
            return;
        }
        players.save(player.uuid(), state);
        fireStatusChange(player, "demisie semnată");
        audit.record("resignation_complete", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS", "completed");
        player.tell("Demisie semnată. Camera a fost eliberată, iar cooldown-ul este de "
                + ctx.policies().resignationCooldownDays + " zile. Soldul acumulat rămâne disponibil.");
    }

    @Override
    public void beginResignation(PlayerGateway player) {
        resign(player, null);
    }

    @Override
    public void confirmResignation(PlayerGateway player) {
        resign(player, "confirm");
    }

    @Override
    public void cancelResignation(PlayerGateway player) {
        resign(player, "cancel");
    }

    public void rejoin(PlayerGateway player) {
        GuardState state = players.state(player);
        if (!state.resigned) {
            player.tell("Nu ai o demisie activă.");
            return;
        }
        if (state.rejoinAvailableAt != null && state.rejoinAvailableAt > now()) {
            player.tell("Revenirea este disponibilă peste " + prettyTime(state.rejoinAvailableAt) + ".");
            return;
        }
        int candidate = state.formerRank != null && state.formerRank >= Rank.STAGIAR.level()
                ? state.formerRank : Rank.STAGIAR.level();
        int restored = Math.min(candidate, Rank.SERGENT.level());
        state.rank = restored;
        state.resigned = false;
        state.resignedAt = null;
        state.rejoinAvailableAt = null;
        state.formerRank = null;
        state.resignationPending = false;
        state.resignationDeadlineAt = null;
        state.resignationRank = null;
        state.invited = true;
        state.fired = false;
        state.suspended = false;
        state.quizPassed = true;
        state.applicationState = "AUTHORIZED";
        state.quizIndex = ctx.policies().quiz.size();
        players.save(player.uuid(), state);
        audit.record("rejoin", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS", "rejoined");
        roomAutoAssign.onPromotedToGuard(player);
        player.tell("Reîncadrare aprobată automat: " + ctx.policies().rankName(restored)
                + ". Inspectorul revine cel mult ca Sergent.");
    }

    // ------------------------------------------------------------ economy/equipment

    public void salary(PlayerGateway player) {
        GuardState state = players.state(player);
        int amount = Math.max(0, state.unpaidSalary);
        if (amount <= 0) {
            player.tell("Nu ai salariu disponibil.");
            return;
        }
        if (!ctx.currency().available()) {
            player.tell("Moneda externă nu este configurată. Comisaru' trebuie să confirme ID-urile înainte de plata salariilor. Soldul a fost păstrat.");
            return;
        }
        String payoutId = state.salaryPayoutId != null ? state.salaryPayoutId
                : "salary:" + player.uuid() + ":" + amount;
        if ("REVIEW".equals(state.salaryPaymentStatus)) {
            player.tell("Plata salariului este blocată pentru verificarea Comisarului.");
            return;
        }
        if ("IN_PROGRESS".equals(state.salaryPaymentStatus)) {
            // A previous attempt never reached a persisted outcome (crash between
            // the deposit and the state save). A surviving receipt proves the coins
            // were delivered; without one the attempt stays locked rather than
            // risking a second payout.
            if (ctx.currency().hasReceipt(player, payoutId)) {
                state.unpaidSalary = 0;
                state.salaryPaymentStatus = "PAID";
                state.salaryPaymentError = null;
                state.salaryPayoutId = null;
                players.save(player.uuid(), state);
                audit.record("salary_payout", player.name(), player.uuid().toString(),
                        player.name(), player.uuid().toString(), "SUCCESS", "recovered_receipt");
                player.tell("Salariu plătit în monede fizice.");
            } else {
                state.salaryPaymentStatus = "REVIEW";
                state.salaryPaymentError = "unconfirmed_previous_attempt";
                players.save(player.uuid(), state);
                audit.record("salary_payout", player.name(), player.uuid().toString(),
                        player.name(), player.uuid().toString(), "FAILED", "unconfirmed_previous_attempt");
                player.tell("Plata anterioară nu a putut fi confirmată; blocată pentru verificarea Comisarului.");
            }
            return;
        }
        state.salaryPayoutId = payoutId;
        state.salaryPaymentStatus = "IN_PROGRESS";
        players.save(player.uuid(), state);

        CurrencyProvider.Deposit payout = ctx.currency().deposit(player, amount, payoutId);
        if (!payout.ok()) {
            state.salaryPaymentStatus = payout.delivered() > 0 ? "REVIEW" : "PENDING";
            state.salaryPaymentError = payout.error() == null ? "delivery_failed" : payout.error();
            state.salaryDeliveredDenominations = payout.delivered();
            players.save(player.uuid(), state);
            player.tell(payout.delivered() > 0
                    ? "Salariul a fost livrat parțial; plata este blocată pentru verificare."
                    : "Plata nu a putut fi pregătită. Soldul a fost păstrat.");
            return;
        }
        boolean recovered = ctx.currency().hasReceipt(player, payoutId);
        state.unpaidSalary = 0;
        state.salaryPaymentStatus = "PAID";
        state.salaryPaymentError = null;
        state.salaryPayoutId = null;
        state.salaryDeliveredDenominations = payout.delivered();
        players.save(player.uuid(), state);
        audit.record("salary_payout", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS", recovered ? "recovered_receipt" : "paid");
        player.tell("Salariu plătit în monede fizice.");
    }

    public void coins(PlayerGateway player) {
        int balance = ctx.currency().available() ? ctx.currency().balanceOf(player) : 0;
        player.tell("Monede în inventar: " + balance + " (sold Straja: " + players.state(player).unpaidSalary + ").");
    }

    public void food(PlayerGateway player) {
        GuardState state = players.state(player);
        if (!players.permissionLevel(player, state).atLeast(PermissionLevel.GUARD)) {
            player.tell("Hrana de serviciu este disponibilă doar pentru un străjer activ.");
            return;
        }
        if (state.rank < Rank.STAGIAR.level()) {
            player.tell("Hrana de serviciu este disponibilă de la Stagiar.");
            return;
        }
        if (!state.duty) {
            player.tell("Hrana de serviciu se ridică numai în timpul unei ture active.");
            return;
        }
        if (state.foodReadyAt != null && state.foodReadyAt > now()) {
            player.tell("Hrana poate fi ridicată peste " + prettyTime(state.foodReadyAt) + ".");
            return;
        }
        if (!player.giveVerified(ItemSpec.of(ctx.policies().foodItem, ctx.policies().foodAmount))) {
            player.tell("Hrana nu încape în inventar. Eliberează un slot și încearcă din nou.");
            return;
        }
        state.foodReadyAt = now() + ctx.policies().foodCooldownMinutes * DutyEngine.MINUTE_MS;
        players.save(player.uuid(), state);
        audit.record("food_claim", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS", "claimed");
        player.tell("Ai primit " + ctx.policies().foodAmount + " pâini.");
    }

    public void kit(PlayerGateway player) {
        GuardState state = players.state(player);
        if (state.rank < Rank.STAGIAR.level()) {
            player.tell("Kitul este disponibil de la Stagiar.");
            return;
        }
        if (state.kitClaimedRank >= state.rank) {
            player.tell("Kitul pentru rangul curent a fost deja ridicat.");
            return;
        }
        if (equipment.giveKit(player, state)) {
            audit.record("kit_claim", player.name(), player.uuid().toString(),
                    player.name(), player.uuid().toString(), "SUCCESS",
                    Rank.of(state.rank).name());
        }
    }

    /**
     * EQ-006: manual merit docking. The Comisar docks requisition points
     * (spendable merit); promotion service blocks stay separate and are only
     * docked by demotion. Values clamp at zero and are audited.
     */
    public boolean meritDock(PlayerGateway actor, PlayerGateway target, int points) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Doar Comisaru' poate modifica soldul de rechiziție.");
            return false;
        }
        if (points <= 0) {
            actor.tell("Cantitatea trebuie să fie un număr pozitiv.");
            return false;
        }
        GuardState state = players.state(target);
        long docked = Math.min(points, state.requisitionPoints);
        state.requisitionPoints -= docked;
        players.save(target.uuid(), state);
        audit.record("merit_dock", actor.name(), actor.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS",
                "requisition-" + docked + " (rămas " + state.requisitionPoints + ")");
        target.tell("Comisaru' ți-a retras " + docked + " puncte de rechiziție. Sold: "
                + state.requisitionPoints + ".");
        actor.tell(target.name() + ": -" + docked + " puncte de rechiziție (sold "
                + state.requisitionPoints + ").");
        return true;
    }

    /** Read-only merit balance for {@code /straja merit} and status surfaces. */
    public void showMerit(PlayerGateway player) {
        GuardState state = players.state(player);
        player.tell("Puncte de serviciu (promovare): " + state.serviceBlocks
                + " | puncte de rechiziție (cheltuibile la Armurier): " + state.requisitionPoints + ".");
    }

    // ------------------------------------------------------------ setup

    public void setCheckpoint(PlayerGateway player, String id) {
        setCheckpointAt(player, id, player.dimension(), player.x(), player.y(), player.z());
    }

    /** Same checkpoint write as {@link #setCheckpoint}, at an explicit position (admin tools). */
    public void setCheckpointAt(PlayerGateway player, String id, String dimension,
                                double x, double y, double z) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' poate configura checkpoint-urile.");
            return;
        }
        SetupData setup = ctx.setup().read();
        var point = setup.checkpoints.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
        if (point == null) {
            player.tell("Checkpoint necunoscut. Creează slotul cu /straja checkpoint add.");
            return;
        }
        point.dimension = dimension;
        point.x = Math.floor(x);
        point.y = Math.floor(y);
        point.z = Math.floor(z);
        ctx.setup().write(setup);
        player.tell(id + " salvat la " + point.x.intValue() + ", " + point.y.intValue() + ", "
                + point.z.intValue() + " (" + point.dimension + ").");
    }

    /**
     * PAT-001: canonical add for patrol route slots. Returns the new
     * checkpoint id on success, {@code null} on refusal (limit reached or no
     * authority). The patrol wand converges on this path.
     */
    public String addCheckpoint(PlayerGateway player) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' poate configura checkpoint-urile.");
            return null;
        }
        SetupData setup = ctx.setup().read();
        int max = Math.max(1, ctx.policies().patrolMaxCheckpoints);
        if (setup.checkpoints.size() >= max) {
            player.tell("Limita de " + max + " checkpoint-uri a fost atinsă.");
            return null;
        }
        String id = setup.nextCheckpointId();
        setup.checkpoints.add(SetupData.newCheckpoint(id));
        ctx.setup().write(setup);
        player.tell("Checkpoint nou: " + id + " (total " + setup.checkpoints.size()
                + "). Marchează poziția cu /straja set-checkpoint " + id + ".");
        return id;
    }

    /**
     * PAT-001: canonical remove for patrol route slots. Removing a slot also
     * drops its mission-time override; remaining slots keep their order.
     */
    public boolean removeCheckpoint(PlayerGateway player, String id) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' poate configura checkpoint-urile.");
            return false;
        }
        SetupData setup = ctx.setup().read();
        boolean removed = setup.checkpoints.removeIf(c -> c.id.equals(id));
        if (!removed) {
            player.tell("Checkpoint necunoscut: " + id + ".");
            return false;
        }
        setup.missionMinutes.remove(id);
        ctx.setup().write(setup);
        player.tell(id + " eliminat. Ruta are acum " + setup.checkpoints.size() + " checkpoint-uri.");
        return true;
    }

    /**
     * PAT-001: canonical whole-route replace — the patrol wand and any future
     * surface converge here. Rebuilds the checkpoint list as
     * {@code checkpoint_1..N} at the given positions and drops mission-time
     * overrides for removed slots.
     */
    public boolean definePatrolRoute(PlayerGateway player, String dimension, List<double[]> points) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' poate configura checkpoint-urile.");
            return false;
        }
        int min = Math.max(1, ctx.policies().patrolMinCheckpoints);
        int max = Math.max(1, ctx.policies().patrolMaxCheckpoints);
        int n = points == null ? 0 : points.size();
        if (n < min || n > max) {
            player.tell("Traseul cere între " + min + " și " + max
                    + " puncte distincte — " + n + " înregistrate.");
            return false;
        }
        SetupData setup = ctx.setup().read();
        setup.checkpoints.clear();
        for (int i = 0; i < n; i++) {
            var point = SetupData.newCheckpoint("checkpoint_" + (i + 1));
            point.dimension = dimension;
            point.x = Math.floor(points.get(i)[0]);
            point.y = Math.floor(points.get(i)[1]);
            point.z = Math.floor(points.get(i)[2]);
            setup.checkpoints.add(point);
        }
        setup.missionMinutes.keySet().retainAll(
                setup.checkpoints.stream().map(c -> c.id).collect(java.util.stream.Collectors.toSet()));
        ctx.setup().write(setup);
        player.tell("Traseul de patrulare a fost salvat (" + n + " checkpoint-uri).");
        return true;
    }

    public void setMissionTime(PlayerGateway player, String id, int minutes) {
        GuardState state = players.state(player);
        if (!players.permissionLevel(player, state).atLeast(PermissionLevel.LIEUTENANT)) {
            player.tell("Doar Inspectorul sau Comisaru' pot stabili timpul misiunii.");
            return;
        }
        SetupData setup = ctx.setup().read();
        var point = setup.checkpoints.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
        if (point == null || minutes < ctx.policies().missionMinMinutes || minutes > ctx.policies().missionMaxMinutes) {
            player.tell("Folosește un checkpoint valid și un timp întreg între "
                    + ctx.policies().missionMinMinutes + " și " + ctx.policies().missionMaxMinutes + " minute.");
            return;
        }
        setup.missionMinutes.put(id, minutes);
        ctx.setup().write(setup);
        player.tell(id + " are acum timp de misiune: " + minutes + " minute.");
    }

    public void setLocation(PlayerGateway player, String name) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' poate configura locațiile administrative.");
            return;
        }
        Map<String, String> aliases = new java.util.LinkedHashMap<>();
        aliases.put("reports", "reportsLectern");
        aliases.put("raport", "reportsLectern");
        aliases.put("mailbox", "commissionerMailbox");
        aliases.put("office", "commissionerOffice");
        aliases.put("receptionist", "receptionist");
        aliases.put("reception", "receptionist");
        aliases.put("secretary", "secretary");
        aliases.put("secretara", "secretary");
        aliases.put("recruiter", "recruiter");
        aliases.put("recrutor", "recruiter");
        aliases.put("prison-release", "prisonRelease");
        aliases.put("release", "prisonRelease");
        aliases.put("infirmary", "infirmary");
        aliases.put("infirmerie", "infirmary");
        aliases.put("trainer", "trainer");
        aliases.put("instructor", "trainer");
        aliases.put("hq", "hq");
        aliases.put("sediu", "hq");
        String key = aliases.get(name == null ? "" : name.trim().toLowerCase());
        if (key == null) {
            player.tell("Locație necunoscută: reports, mailbox, office, receptionist, secretary, prison-release, infirmary, trainer sau hq.");
            return;
        }
        stampLocation(player, key, player.dimension(), player.x(), player.y(), player.z());
    }

    /**
     * Same location write as {@link #setLocation}, at an explicit position and
     * with a canonical {@link SetupData#LOCATION_KEYS} key (admin tools).
     */
    public void stampLocation(PlayerGateway player, String key, String dimension,
                              double x, double y, double z) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' poate configura locațiile administrative.");
            return;
        }
        if (java.util.Arrays.asList(SetupData.LOCATION_KEYS).indexOf(key) < 0) {
            player.tell("Locație necunoscută: reports, mailbox, office, receptionist, secretary, prison-release, infirmary, trainer sau hq.");
            return;
        }
        SetupData setup = ctx.setup().read();
        var location = new SetupData.Location();
        location.dimension = dimension;
        location.x = Math.floor(x);
        location.y = Math.floor(y);
        location.z = Math.floor(z);
        setup.locations.put(key, location);
        ctx.setup().write(setup);
        player.tell(key + " salvat la " + (int) location.x + ", " + (int) location.y + ", " + (int) location.z + ".");
    }

    public void showSetup(PlayerGateway player) {
        SetupData setup = ctx.setup().read();
        var registry = ctx.npcs().read();
        var missingLocations = SetupChecklist.missingLocations(setup);
        var missingPoints = SetupChecklist.unplacedCheckpoints(setup);
        var missingNpcs = SetupChecklist.missingNpcRoles(registry, NpcAdminService.ROLE_ORDER);
        player.tell("[Straja] Checklist de configurare:");
        player.tell(check(players.isCommissioner(player))
                + " Comisar: " + (players.isCommissioner(player)
                        ? "setat (tu)"
                        : "nesetat — definește-l în [identity] din straja-server.toml"));
        player.tell(check(missingLocations.isEmpty())
                + " Locații administrative: " + (SetupData.LOCATION_KEYS.length - missingLocations.size())
                + "/" + SetupData.LOCATION_KEYS.length
                + (missingLocations.isEmpty() ? "" : " — lipsesc: " + String.join(", ", missingLocations)));
        player.tell(check(missingPoints.isEmpty())
                + " Checkpoint-uri patrulare: " + (setup.checkpoints.size() - missingPoints.size())
                + "/" + setup.checkpoints.size()
                + (missingPoints.isEmpty() ? "" : " — lipsesc: " + String.join(", ", missingPoints)));
        player.tell(check(missingNpcs.isEmpty())
                + " NPC-uri: " + (NpcAdminService.ROLE_ORDER.size() - missingNpcs.size())
                + "/" + NpcAdminService.ROLE_ORDER.size()
                + (missingNpcs.isEmpty() ? "" : " — lipsesc: " + String.join(", ", missingNpcs)));
        String next = SetupChecklist.nextStep(setup, registry, NpcAdminService.ROLE_ORDER);
        player.tell(next == null
                ? "Configurare completă. Rafinează punctele cu /straja set-location / set-checkpoint."
                : "Următorul pas: " + next);
    }

    private static String check(boolean done) {
        return done ? "✓" : "✗";
    }

    /** One-shot: stamps every administrative location at the player's position. */
    public void setupLocationsHere(PlayerGateway player) {
        setupLocationsAt(player, player.dimension(), player.x(), player.y(), player.z());
    }

    /** Same batch write as {@link #setupLocationsHere}, at an explicit position (admin tools). */
    public void setupLocationsAt(PlayerGateway player, String dimension, double x, double y, double z) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' poate configura locațiile administrative.");
            return;
        }
        SetupData setup = ctx.setup().read();
        for (String key : SetupData.LOCATION_KEYS) {
            var location = new SetupData.Location();
            location.dimension = dimension;
            location.x = Math.floor(x);
            location.y = Math.floor(y);
            location.z = Math.floor(z);
            setup.locations.put(key, location);
        }
        ctx.setup().write(setup);
        player.tell("Toate cele " + SetupData.LOCATION_KEYS.length
                + " locații administrative au fost salvate aici. Mută-le individual cu /straja set-location <nume>.");
    }

    /**
     * One-shot: lays the configured patrol route as a ring around the player
     * and seeds a default mission time for each leg. Works for any number of
     * checkpoint slots; re-running re-centers the ring.
     */
    public void setupPatrol(PlayerGateway player) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' poate configura checkpoint-urile.");
            return;
        }
        SetupData setup = ctx.setup().read();
        int n = setup.checkpoints.size();
        if (n == 0) {
            player.tell("Nu există sloturi de checkpoint. Adaugă-le cu /straja checkpoint add.");
            return;
        }
        int radius = 8;
        double baseX = Math.floor(player.x());
        double baseY = Math.floor(player.y());
        double baseZ = Math.floor(player.z());
        for (int i = 0; i < n; i++) {
            var point = setup.checkpoints.get(i);
            double angle = 2 * Math.PI * i / n;
            point.dimension = player.dimension();
            point.x = baseX + Math.round(radius * Math.cos(angle));
            point.y = baseY;
            point.z = baseZ + Math.round(radius * Math.sin(angle));
            setup.missionMinutes.putIfAbsent(point.id, defaultMissionMinutes());
        }
        ctx.setup().write(setup);
        player.tell("Traseu circular cu raza de " + radius + " blocuri creat în jurul tău ("
                + n + " checkpoint-uri). Mută-le cu /straja set-checkpoint <id>.");
    }

    private int defaultMissionMinutes() {
        return Math.min(ctx.policies().missionMaxMinutes, Math.max(30, ctx.policies().missionMinMinutes));
    }

    // ------------------------------------------------------------ status / rules / inbox

    @Override
    public void showStatus(PlayerGateway player) {
        GuardState state = players.state(player);
        player.tell(ctx.policies().rankName(state.rank) + " | lifecycle: " + state.lifecycle
                + " | perm: " + players.permissionLevel(player, state)
                + " | serviciu: " + (state.duty ? "DA (" + state.mode + ")" : "NU")
                + " | blocuri: " + state.serviceBlocks + " | sold: " + state.unpaidSalary
                + " | rechiziție: " + state.requisitionPoints);
        if (state.nativeFaction != null && !state.nativeFaction.isBlank()) {
            player.tell("Facțiune nativă: " + state.nativeFaction
                    + (state.duty ? " — în timpul turei ești Străjer al Castelului." : "."));
        }
        if (state.specializations != null && !state.specializations.isEmpty()) {
            player.tell("Funcții: " + String.join(", ", state.specializations) + ".");
        }
        if (state.resignationPending) {
            player.tell("Demisie în așteptare. Semnarea este disponibilă peste " + prettyTime(state.resignationDeadlineAt) + ".");
        }
        if (state.resigned) {
            player.tell("Demisie semnată. Reîncadrarea este disponibilă " + prettyTime(state.rejoinAvailableAt)
                    + ". Rang la revenire: maximum Sergent.");
            return;
        }
        if (state.duty) {
            player.tell("Checkpoint activ: " + (state.patrolIndex < state.route.size() ? state.route.get(state.patrolIndex) : "—")
                    + " | stare: " + state.patrolState
                    + " | timp: " + prettyTime(state.deadlineAt != null ? state.deadlineAt : state.waitingUntil));
        }
    }

    @Override
    public void showRules(PlayerGateway player) {
        GuardState state = players.state(player);
        String title = players.isCommissioner(player) ? ctx.policies().commissionerTitle : ctx.policies().rankName(state.rank);
        player.tell("Ranguri: " + ctx.policies().rankNames.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(java.util.Map.Entry::getValue)
                .collect(java.util.stream.Collectors.joining(" → "))
                + "; tu ești " + title + ".");
        player.tell("Stagiar: amenzi standard, patrulă și rapoarte; fără baston, cătușe, arest sau ordine.");
        player.tell("Străjer: poate folosi bastonul/cătușele și executa arestări după misiune sau mandat.");
        player.tell("Sergent: preia plângeri, investighează și mobilizează Stagiari/Străjeri; nu emite misiuni plătite.");
        player.tell("Inspectorul și Comisaru' declară misiuni plătite la secretară și aprobă recompensele.");
        player.tell("Patrulă: " + ctx.setup().read().checkpoints.size() + " checkpoint-uri; "
                + ctx.policies().checkpointUnlockMinutes + " minute pauză între puncte; tura durează maximum "
                + ctx.policies().patrolMaxMinutes + " minute reale.");
        player.tell("Salariu: plată orară, acumulată continuu. Monede: 1 / 64 / 4096 / 262144 Bronz.");
        player.tell("Echipamentul de rang este al tău definitiv; avansarea aduce kit nou + bonus de "
                + ctx.policies().promotionBonusHours + "h salariu. Rezervele se iau de la Armurier cu puncte de rechiziție.");
        player.tell("Special Duty suspendă temporar checkpoint-urile și cere autorizare. Rapoartele merg la Comisaru'.");
        player.tell("Semnarea demisiei se face la Comisaru'. Revenirea este posibilă după cooldown și este limitată la Sergent.");
        var training = pendingModules(state);
        player.tell(!training.isEmpty()
                ? "Instruire disponibilă: " + training.size() + " module. Vorbește cu Instructorul pentru următoarea întrebare."
                : "Instruirea disponibilă pentru rangul tău este finalizată.");
    }

    public void addInbox(String type, String sender, String text) {
        var messages = ctx.inbox().read();
        InboxMessage message = new InboxMessage();
        message.type = type;
        message.sender = sender;
        message.text = text;
        message.at = now();
        messages.add(message);
        ctx.inbox().write(messages);
    }

    public void report(PlayerGateway player, String text) {
        if (text == null || text.isBlank()) {
            player.tell("Prezintă raportul complet direct Comisaru'ului.");
            return;
        }
        addInbox("REPORT", player.name(), text.trim());
        player.tell("Raportul a fost trimis la Comisaru'.");
    }

    public void message(PlayerGateway player, String text) {
        if (text == null || text.isBlank()) {
            player.tell("Vorbește cu Comisaru' și transmite-i mesajul complet.");
            return;
        }
        addInbox("MESSAGE", player.name(), text.trim());
        player.tell("Mesajul a fost trimis la Comisaru'.");
    }

    public void request(PlayerGateway player, String text) {
        if (text == null || text.isBlank()) {
            player.tell("Depune cererea direct la Comisaru', cu toate detaliile necesare.");
            return;
        }
        addInbox("REQUEST", player.name(), text.trim());
        player.tell("Cererea a fost trimisă la Comisaru'.");
    }

    // ------------------------------------------------------------ activity tracking

    /** Anti-AFK: called once per server tick for each online player. */
    @Override
    public DutyEngine.TickResult tickPlayerDuty(PlayerGateway player) {
        GuardState state = players.state(player);
        if (!state.duty) return new DutyEngine.TickResult(List.of());
        long current = now();
        // Free-duty ranks are trusted: salary accrues without the movement gate.
        var activity = "FREE".equals(state.mode)
                ? new ActivityResult(current, false, false, false)
                : prepareSalaryActivity(player, state, current);
        var result = DutyEngine.tickDuty(state, current, salaryFor(player, state),
                ctx.setup().read().missionMinutes, ctx.policies(), activity.accrualNow);
        if (!state.duty) {
            restoreDutyFaction(player, state);
        }
        if (activity.pausing) {
            state.salaryActivityPaused = true;
            player.tell("Acumularea salariului s-a oprit: nicio mișcare de "
                    + ctx.policies().salaryActivityGraceSeconds + "s. Mișcă-te pentru a relua.");
        }
        players.save(player.uuid(), state);
        notifyEvents(player, result.events());
        return result;
    }

    /** Movement-based salary activity gate; returns the accrual timestamp. */
    public ActivityResult prepareSalaryActivity(PlayerGateway player, GuardState state, long current) {
        var p = ctx.policies();
        long graceMs = Math.max(0, p.salaryActivityGraceSeconds) * 1000L;
        double threshold = Math.max(0, p.salaryActivityMoveThreshold);
        if (!state.duty) return new ActivityResult(current, false, false, false);

        Long lastAt = state.lastDutyActivityAt;
        boolean missingMarker = lastAt == null || state.lastDutyActivityX == null
                || state.lastDutyActivityY == null || state.lastDutyActivityZ == null;
        if (missingMarker) {
            state.lastAccrualAt = current;
            state.dutyRemainderMs = 0;
            state.lastDutyActivityAt = current;
            state.lastDutyActivityX = player.x();
            state.lastDutyActivityY = player.y();
            state.lastDutyActivityZ = player.z();
            state.salaryActivityPaused = false;
            return new ActivityResult(current, false, false, false);
        }

        double dx = player.x() - state.lastDutyActivityX;
        double dy = player.y() - state.lastDutyActivityY;
        double dz = player.z() - state.lastDutyActivityZ;
        boolean moved = Math.sqrt(dx * dx + dy * dy + dz * dz) >= threshold;

        if (moved) {
            boolean wasPaused = state.salaryActivityPaused;
            boolean crossedGrace = !wasPaused && current - lastAt >= graceMs;
            state.lastDutyActivityAt = current;
            state.lastDutyActivityX = player.x();
            state.lastDutyActivityY = player.y();
            state.lastDutyActivityZ = player.z();
            state.salaryActivityPaused = false;
            if (wasPaused || crossedGrace) {
                state.lastAccrualAt = current;
                state.dutyRemainderMs = 0;
            }
            return new ActivityResult(current, true, false, wasPaused);
        }

        if (state.salaryActivityPaused) {
            state.lastAccrualAt = current;
            return new ActivityResult(current, false, false, false);
        }
        if (current - lastAt >= graceMs) {
            return new ActivityResult(lastAt + graceMs, false, true, false);
        }
        return new ActivityResult(current, false, false, false);
    }

    /** Marks an authorized command as real activity (closes the AFK gap). */
    public void markDutyActivity(PlayerGateway player, GuardState state, long current) {
        if (!state.duty) return;
        boolean wasPaused = state.salaryActivityPaused;
        Long lastAt = state.lastDutyActivityAt;
        long graceMs = Math.max(0, ctx.policies().salaryActivityGraceSeconds) * 1000L;
        boolean crossedGrace = !wasPaused && lastAt != null && current - lastAt >= graceMs;
        state.lastDutyActivityAt = current;
        state.lastDutyActivityX = player.x();
        state.lastDutyActivityY = player.y();
        state.lastDutyActivityZ = player.z();
        state.salaryActivityPaused = false;
        if (wasPaused || crossedGrace) {
            state.lastAccrualAt = current;
            state.dutyRemainderMs = 0;
        }
    }

    public record ActivityResult(long accrualNow, boolean moved, boolean pausing, boolean resumed) {}
}
