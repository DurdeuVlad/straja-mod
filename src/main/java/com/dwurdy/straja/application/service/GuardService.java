package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.CurrencyProvider;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.DomainEvent;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.InboxMessage;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.PermissionLevel;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.Result;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.List;
import java.util.Map;

/**
 * Player-facing use cases: recruitment, quiz, duty lifecycle, salary, food,
 * kit/regear, resignation/rejoin, inbox and admin rank operations.
 * Ports of the reference flows; all authority checks stay server-side.
 */
public class GuardService {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private final EquipmentService equipment;

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
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
        this.equipment = equipment;
    }

    public void onPromotedToGuard(RankChangeHook hook) {
        if (hook != null) this.roomAutoAssign = hook;
    }

    // ------------------------------------------------------------ helpers

    private long now() { return ctx.clock().nowMillis(); }

    private int salaryFor(GuardState state) { return ctx.policies().salaryPerBlock(state.rank); }

    private boolean canAuthorize(PlayerGateway actor, String action, String targetName, int targetRank) {
        GuardState actorState = players.state(actor);
        return DutyEngine.canAuthorize(players.isCommissioner(actor), actor.name(), actorState.rank,
                targetName, targetRank, action);
    }

    private static boolean operational(GuardState state) {
        return state.rank >= Rank.JUNIOR.level() && !state.suspended && !state.fired
                && !state.resigned && !state.resignationPending;
    }

    private void notifyEvents(PlayerGateway player, List<DomainEvent> events) {
        for (DomainEvent event : events) {
            switch (event.type()) {
                case "salary_block" -> player.tell("Ai acumulat +" + event.data().get("amount")
                        + " monede în sold (" + event.data().get("blocks") + " blocuri de 10 minute).");
                case "checkpoint_activated" -> player.tell("Checkpoint atins. Următorul devine disponibil peste 10 minute.");
                case "checkpoint_available" -> player.tell("Checkpoint disponibil: " + event.data().get("checkpoint")
                        + ". Ai " + event.data().get("missionMinutes") + " minute.");
                case "patrol_complete" -> player.tell("Patrulă completă. Salariul rămâne în sold; folosește /straja salary.");
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
        equipment.settleEquipmentDebt(state);
        if (!state.duty && state.serviceEquipment != null) equipment.reclaimServiceEquipment(player, state);
        players.save(player.uuid(), state);
        notifyEvents(player, result.events());
        return true;
    }

    public static String coreError(String code) {
        return switch (code == null ? "" : code) {
            case "rank_required" -> "Ai nevoie de rangul Străjer Junior.";
            case "already_on_duty" -> "Ești deja în serviciu.";
            case "suspended" -> "Ești suspendat și nu poți începe serviciul.";
            case "resignation_pending" -> "Demisia este deja în așteptare; nu poți începe un serviciu nou.";
            case "resigned" -> "Ai demisionat și ești în cooldown pentru revenire.";
            case "fired" -> "Ai fost îndepărtat din Strajă și nu poți începe serviciul.";
            case "route_invalid" -> "Traseul trebuie să conțină exact patru checkpoint-uri distincte.";
            case "not_normal_duty" -> "Nu ești într-o patrulă normală activă.";
            case "checkpoint_not_active" -> "Checkpoint-ul nu este disponibil încă. Respectă pauza de 10 minute.";
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
        if (state.rank >= Rank.JUNIOR.level() || state.resigned || state.fired || state.suspended
                || state.duty || state.resignationPending) {
            actor.tell("Invitația se poate acorda doar unui Civil eligibil, fără statut activ sau suspendat.");
            audit.record("invite", actor.name(), actor.uuid().toString(),
                    target.name(), target.uuid().toString(), "REFUSED", "target_not_eligible");
            return false;
        }
        state.invited = true;
        state.fired = false;
        state.suspended = false;
        if (state.rank < Rank.JUNIOR.level()) {
            state.quizIndex = 0;
            state.quizPassed = false;
            state.quizCooldownAt = null;
            state.quizOrder = new java.util.ArrayList<>();
        }
        players.save(target.uuid(), state);
        audit.record("invite", actor.name(), actor.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS", "invited");
        target.tell("Ai fost invitat în Straja Castelului. Rulează /straja recrute pentru regulament și quiz.");
        actor.tell("Invitația a fost trimisă lui " + target.name() + ".");
        return true;
    }

    public void recruit(PlayerGateway player) {
        GuardState state = players.state(player);
        if (!state.invited || state.fired) {
            player.tell("Nu ai invitație activă. Vorbește cu recepționera sau cu Comisaru'.");
            return;
        }
        if (state.rank >= Rank.JUNIOR.level()) {
            player.tell("Ești deja " + Rank.of(state.rank).displayName() + ".");
            return;
        }
        var question = ensureQuizOrder(state);
        players.save(player.uuid(), state);
        player.tell("Recrutarea folosește progressive disclosure. "
                + (question != null ? question.question() : "Rulează /straja quiz <răspuns>."));
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

    /** /straja quiz <answer>: initial quiz below JUNIOR, training modules after. */
    public void quiz(PlayerGateway player, String answer) {
        GuardState state = players.state(player);
        if (!state.invited || state.fired) {
            player.tell("Ai nevoie de o invitație de la Comisaru'.");
            return;
        }
        if (state.rank >= Rank.JUNIOR.level() || state.quizPassed) {
            trainingQuiz(player, answer);
            return;
        }
        if (state.quizCooldownAt != null && state.quizCooldownAt > now()) {
            player.tell("Mai încearcă peste " + prettyTime(state.quizCooldownAt) + ".");
            return;
        }
        var item = ensureQuizOrder(state);
        if (item == null) {
            state.quizPassed = true;
            state.rank = Rank.JUNIOR.level();
            players.save(player.uuid(), state);
            roomAutoAssign.onPromotedToGuard(player);
            player.tell("Quiz promovat. Ai devenit Străjer Junior.");
            return;
        }
        if (item.accepts(answer)) {
            state.quizIndex += 1;
            state.quizCooldownAt = null;
            if (state.quizIndex >= ctx.policies().quiz.size()) {
                state.quizPassed = true;
                state.rank = Rank.JUNIOR.level();
                state.kitClaimedRank = 0;
                players.save(player.uuid(), state);
                roomAutoAssign.onPromotedToGuard(player);
                player.tell("Quiz promovat. Ai devenit Străjer Junior. Poți începe serviciul și ridica echipamentul.");
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

    private void trainingQuiz(PlayerGateway player, String answer) {
        GuardState state = players.state(player);
        if (state.rank < Rank.JUNIOR.level()) {
            player.tell("Quiz-ul de rang se deschide după recrutarea ca Străjer Junior.");
            return;
        }
        if (state.trainingQuizCooldownAt != null && state.trainingQuizCooldownAt > now()) {
            player.tell("Mai încearcă peste " + prettyTime(state.trainingQuizCooldownAt) + ".");
            return;
        }
        var item = currentTrainingItem(state);
        if (item == null) {
            players.save(player.uuid(), state);
            player.tell("Nu ai încă module de instruire disponibile pentru rangul tău. Verifică /straja regulament.");
            return;
        }
        if (answer == null || answer.isBlank()) {
            players.save(player.uuid(), state);
            player.tell("Instruire " + item.id() + ": " + item.question());
            return;
        }
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
        var available = ctx.policies().trainingQuiz.stream()
                .filter(q -> q.minRank() <= state.rank && !Boolean.TRUE.equals(state.trainingPassed.get(q.id())))
                .toList();
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

    // ------------------------------------------------------------ admin rank ops

    public void promote(PlayerGateway actor, PlayerGateway target) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Promovările sunt decizia finală a Comisarului.");
            return;
        }
        GuardState state = players.state(target);
        if (state.rank < Rank.JUNIOR.level() || state.rank >= Rank.LIEUTENANT.level()
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
        state.kitClaimedRank = 0;
        players.save(target.uuid(), state);
        target.tell("Promovare: " + Rank.of(nextRank).displayName() + ".");
        actor.tell(target.name() + " a fost promovat la " + Rank.of(nextRank).displayName() + ".");
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
        if (nextRank < Rank.JUNIOR.level() && (state.duty || state.serviceEquipment != null)) {
            Result ended = DutyEngine.endDuty(state, "demoted_to_civil", now(), salaryFor(state), ctx.policies());
            equipment.settleEquipmentDebt(state);
            equipment.reclaimServiceEquipment(target, state);
        }
        state.rank = nextRank;
        state.kitClaimedRank = 0;
        players.save(target.uuid(), state);
        if (nextRank < Rank.JUNIOR.level()) fireStatusChange(target, "demotat la Civil");
        audit.record("demote", actor.name(), actor.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS", "rank_changed");
        target.tell("Retrogradare: " + Rank.of(state.rank).displayName() + ".");
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
            DutyEngine.endDuty(state, "suspended", now(), salaryFor(state), ctx.policies());
            equipment.settleEquipmentDebt(state);
            equipment.reclaimServiceEquipment(target, state);
        }
        players.save(target.uuid(), state);
        fireStatusChange(target, "suspendat din Strajă");
        audit.record("suspend", actor.name(), actor.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS", "suspended");
        target.tell("Ai fost suspendat din Strajă.");
        actor.tell(target.name() + " a fost suspendat.");
    }

    public boolean reinstate(PlayerGateway actor, PlayerGateway target) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Reintegrarea este decizia Comisarului.");
            return false;
        }
        GuardState state = players.state(target);
        if (!state.suspended || state.fired || state.resigned || state.rank < Rank.JUNIOR.level()) {
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
        target.tell("Ai fost reintegrat în Strajă ca " + Rank.of(state.rank).displayName() + ".");
        actor.tell(target.name() + " a fost reintegrat.");
        return true;
    }

    public void fire(PlayerGateway actor, PlayerGateway target) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Îndepărtarea din Strajă este decizia Comisarului.");
            return;
        }
        GuardState state = players.state(target);
        if (state.duty || state.serviceEquipment != null) {
            DutyEngine.endDuty(state, "fired", now(), salaryFor(state), ctx.policies());
            equipment.settleEquipmentDebt(state);
            equipment.reclaimServiceEquipment(target, state);
        }
        state.fired = true;
        state.invited = false;
        state.rank = Rank.CIVIL.level();
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

    public void startDuty(PlayerGateway player) {
        GuardState state = players.state(player);
        if (state.rank >= Rank.JUNIOR.level()) {
            if (state.resignationPending) { player.tell(coreError("resignation_pending")); return; }
            if (state.resigned) { player.tell(coreError("resigned")); return; }
            if (state.fired) { player.tell(coreError("fired")); return; }
        }
        if (!players.permissionLevel(player, state).atLeast(PermissionLevel.GUARD)) {
            player.tell("Doar un străjer activ poate începe serviciul.");
            return;
        }
        SetupData setup = ctx.setup().read();
        var route = setup.checkpoints.stream().filter(SetupData.Checkpoint::isPlaced).map(c -> c.id).toList();
        if (setup.checkpoints.size() < 4 || route.size() < 4) {
            player.tell("Checkpoint-urile nu sunt configurate. Comisaru' trebuie să stea în fiecare punct și să ruleze /straja set-checkpoint checkpoint_1 ... checkpoint_4.");
            return;
        }
        Result result = DutyEngine.startDuty(state, route, now(), setup.missionMinutes, ctx.policies());
        if (applyCoreResult(player, state, result)) {
            if (!equipment.issueServiceEquipment(player, state)) {
                Result failed = DutyEngine.endDuty(state, "service_equipment_issue_failed", now(), salaryFor(state), ctx.policies());
                applyCoreResult(player, state, failed);
                player.tell("Serviciul nu a început: echipamentul de serviciu nu a putut fi predat complet.");
                return;
            }
            markDutyActivity(player, state, now());
            players.save(player.uuid(), state);
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
        Result result = DutyEngine.activateCheckpoint(state, id, now(), salaryFor(state), setup.missionMinutes, ctx.policies());
        applyCoreResult(player, state, result);
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
        markDutyActivity(player, state, now());
        Result result = DutyEngine.endDuty(state, "voluntary_stop", now(), salaryFor(state), ctx.policies());
        if (applyCoreResult(player, state, result)) {
            audit.record("duty_stop", player.name(), player.uuid().toString(),
                    player.name(), player.uuid().toString(), "SUCCESS", "voluntary_stop");
        }
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
        equipment.reclaimServiceEquipment(player, state);
        players.save(player.uuid(), state);
        player.tell("Serviciul activ a fost închis la restart; timpul offline nu se plătește.");
        return true;
    }

    // ------------------------------------------------------------ special duty

    public void specialStart(PlayerGateway actor, PlayerGateway target) {
        GuardState targetState = players.state(target);
        if (!canAuthorize(actor, "special_duty", target.name(), targetState.rank)) {
            actor.tell("Special Duty poate fi autorizat de Comisaru' sau de un Locotenent pentru alt străjer.");
            return;
        }
        if (!operational(targetState)) {
            actor.tell("Ținta nu este un străjer operațional.");
            return;
        }
        Result result = DutyEngine.startSpecial(targetState, actor.name(), now(), salaryFor(targetState), ctx.policies());
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
        Result result = DutyEngine.resumeSpecial(targetState, now(), salaryFor(targetState), ctx.policies());
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
        Result result = DutyEngine.completeSpecial(targetState, now(), salaryFor(targetState), ctx.policies());
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
                DutyEngine.endDuty(state, "resignation_notice", now(), salaryFor(state), ctx.policies());
                equipment.settleEquipmentDebt(state);
                equipment.reclaimServiceEquipment(player, state);
            }
            Result started = DutyEngine.beginResignation(state, now(), ctx.policies().resignationNoticeMinutes);
            if (!started.ok()) {
                player.tell(coreError(started.code()));
                return;
            }
            players.save(player.uuid(), state);
            audit.record("resignation_start", player.name(), player.uuid().toString(),
                    player.name(), player.uuid().toString(), "SUCCESS", "notice_started");
            player.tell("Demisia intră în preaviz pentru " + ctx.policies().resignationNoticeMinutes
                    + " minute. Camera rămâne a ta în acest interval ca să-ți strângi lucrurile. După expirare rulează /straja demisie confirm. Poți anula cu /straja demisie anuleaza.");
            return;
        }
        if (!normalized.equals("confirm")) {
            player.tell("Demisia este în așteptare. Mai sunt " + prettyTime(state.resignationDeadlineAt)
                    + ". Confirmă cu /straja demisie confirm sau anulează cu /straja demisie anuleaza.");
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
        int candidate = state.formerRank != null && state.formerRank >= Rank.JUNIOR.level()
                ? state.formerRank : Rank.JUNIOR.level();
        int restored = Math.min(candidate, Rank.SENIOR.level());
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
        state.quizIndex = ctx.policies().quiz.size();
        state.kitClaimedRank = 0;
        state.regearPending = false;
        players.save(player.uuid(), state);
        audit.record("rejoin", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS", "rejoined");
        roomAutoAssign.onPromotedToGuard(player);
        player.tell("Reîncadrare aprobată automat: " + Rank.of(restored).displayName()
                + ". Locotenentul revine cel mult ca Străjer Senior.");
    }

    // ------------------------------------------------------------ economy/equipment

    public void salary(PlayerGateway player) {
        GuardState state = players.state(player);
        int debtPaid = equipment.settleEquipmentDebt(state);
        if (debtPaid > 0) players.save(player.uuid(), state);
        int amount = Math.max(0, state.unpaidSalary);
        if (amount <= 0) {
            player.tell(state.equipmentDebt > 0
                    ? "Nu ai salariu disponibil. Datoria pentru echipament este " + state.equipmentDebt + " monede."
                    : "Nu ai salariu disponibil.");
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
        player.tell("Salariu plătit în monede fizice."
                + (debtPaid > 0 ? " Datorie echipament achitată: " + debtPaid + "." : ""));
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
        if (state.rank < Rank.JUNIOR.level()) {
            player.tell("Hrana de serviciu este disponibilă de la Străjer Junior.");
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
        player.tell("Ai primit " + ctx.policies().foodAmount + " pâini.");
    }

    public void kit(PlayerGateway player) {
        GuardState state = players.state(player);
        if (state.rank < Rank.JUNIOR.level()) {
            player.tell("Kitul este disponibil de la Străjer Junior.");
            return;
        }
        if (state.kitClaimedRank >= state.rank) {
            player.tell("Kitul pentru rangul curent a fost deja ridicat.");
            return;
        }
        equipment.giveKit(player, state);
    }

    public void requestRegear(PlayerGateway player) {
        GuardState state = players.state(player);
        if (!players.permissionLevel(player, state).atLeast(PermissionLevel.GUARD)) {
            player.tell("Doar un străjer activ poate cere regear.");
            return;
        }
        if (state.rank < Rank.JUNIOR.level()) {
            player.tell("Regear-ul este disponibil de la Străjer Junior.");
            return;
        }
        if (state.duty) {
            player.tell("Încheie serviciul înainte de regear.");
            return;
        }
        if (state.regearPending) {
            player.tell("Cererea de regear este deja în așteptare.");
            return;
        }
        state.regearPending = true;
        players.save(player.uuid(), state);
        addInbox("REGEAR", player.name(), "Solicitare regear pentru "
                + Rank.of(state.rank).displayName() + ". Cost: "
                + ctx.policies().regearCost.getOrDefault(state.rank, 0) + ".");
        player.tell("Cererea a fost trimisă. Costul este "
                + ctx.policies().regearCost.getOrDefault(state.rank, 0) + " monede.");
    }

    public void approveRegear(PlayerGateway actor, PlayerGateway target) {
        GuardState targetState = players.state(target);
        if (!canAuthorize(actor, "regear", target.name(), targetState.rank)) {
            actor.tell("Doar Comisaru' sau un Locotenent poate aproba regear-ul altui străjer.");
            return;
        }
        if (!targetState.regearPending) {
            actor.tell("Ținta nu are o cerere de regear.");
            return;
        }
        if (!operational(targetState) || targetState.duty) {
            actor.tell("Ținta nu mai este eligibilă pentru regear: trebuie să fie activă și în afara serviciului.");
            return;
        }
        int cost = ctx.policies().regearCost.getOrDefault(targetState.rank, 0);
        if (targetState.unpaidSalary < cost) {
            actor.tell("Ținta nu are sold suficient. Sold: " + targetState.unpaidSalary + ", cost: " + cost + ".");
            return;
        }
        targetState.unpaidSalary -= cost;
        targetState.kitClaimedRank = 0;
        players.save(target.uuid(), targetState);
        equipment.giveKit(target, targetState);
        actor.tell("Regear aprobat pentru " + target.name() + ".");
        target.tell("Regear aprobat. Cost: " + cost + " monede.");
    }

    // ------------------------------------------------------------ setup

    public void setCheckpoint(PlayerGateway player, String id) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' poate configura checkpoint-urile.");
            return;
        }
        SetupData setup = ctx.setup().read();
        var point = setup.checkpoints.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
        if (point == null) {
            player.tell("Checkpoint necunoscut. Folosește checkpoint_1 până checkpoint_4.");
            return;
        }
        point.dimension = player.dimension();
        point.x = Math.floor(player.x());
        point.y = Math.floor(player.y());
        point.z = Math.floor(player.z());
        ctx.setup().write(setup);
        player.tell(id + " salvat la " + point.x.intValue() + ", " + point.y.intValue() + ", "
                + point.z.intValue() + " (" + point.dimension + ").");
    }

    public void setMissionTime(PlayerGateway player, String id, int minutes) {
        GuardState state = players.state(player);
        if (!players.permissionLevel(player, state).atLeast(PermissionLevel.LIEUTENANT)) {
            player.tell("Doar Locotenentul sau Comisaru' pot stabili timpul misiunii.");
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
        aliases.put("prison-release", "prisonRelease");
        aliases.put("release", "prisonRelease");
        aliases.put("infirmary", "infirmary");
        aliases.put("infirmerie", "infirmary");
        String key = aliases.get(name == null ? "" : name.trim().toLowerCase());
        if (key == null) {
            player.tell("Locație necunoscută: reports, mailbox, office, receptionist, secretary, prison-release sau infirmary.");
            return;
        }
        SetupData setup = ctx.setup().read();
        var location = new SetupData.Location();
        location.dimension = player.dimension();
        location.x = Math.floor(player.x());
        location.y = Math.floor(player.y());
        location.z = Math.floor(player.z());
        setup.locations.put(key, location);
        ctx.setup().write(setup);
        player.tell(key + " salvat la " + (int) location.x + ", " + (int) location.y + ", " + (int) location.z + ".");
    }

    public void showSetup(PlayerGateway player) {
        SetupData setup = ctx.setup().read();
        for (var point : setup.checkpoints) {
            player.tell(point.id + ": " + (point.isPlaced()
                    ? point.x.intValue() + ", " + point.y.intValue() + ", " + point.z.intValue() : "neconfigurat"));
        }
        setup.locations.forEach((key, loc) ->
                player.tell(key + ": " + loc.x + ", " + loc.y + ", " + loc.z));
    }

    // ------------------------------------------------------------ status / rules / inbox

    public void showStatus(PlayerGateway player) {
        GuardState state = players.state(player);
        player.tell(Rank.of(state.rank).displayName() + " | lifecycle: " + state.lifecycle
                + " | perm: " + players.permissionLevel(player, state)
                + " | serviciu: " + (state.duty ? "DA (" + state.mode + ")" : "NU")
                + " | blocuri: " + state.serviceBlocks + " | sold: " + state.unpaidSalary
                + (state.equipmentDebt > 0 ? " | datorie echipament: " + state.equipmentDebt : ""));
        if (state.resignationPending) {
            player.tell("Demisie în așteptare. Semnarea este disponibilă peste " + prettyTime(state.resignationDeadlineAt) + ".");
        }
        if (state.resigned) {
            player.tell("Demisie semnată. Reîncadrarea este disponibilă " + prettyTime(state.rejoinAvailableAt)
                    + ". Rang la revenire: maximum Străjer Senior.");
            return;
        }
        if (state.duty) {
            player.tell("Checkpoint activ: " + (state.patrolIndex < state.route.size() ? state.route.get(state.patrolIndex) : "—")
                    + " | stare: " + state.patrolState
                    + " | timp: " + prettyTime(state.deadlineAt != null ? state.deadlineAt : state.waitingUntil));
        }
        if (state.regearPending) player.tell("Regear: cerere în așteptare.");
    }

    public void showRules(PlayerGateway player) {
        GuardState state = players.state(player);
        String title = players.isCommissioner(player) ? ctx.policies().commissionerTitle : Rank.of(state.rank).displayName();
        player.tell("Ranguri: Străjer Junior → Străjer → Străjer Senior → Locotenent; tu ești " + title + ".");
        player.tell("Junior: amenzi standard, patrulă și rapoarte; fără baston, cătușe, arest sau ordine.");
        player.tell("Străjer: poate folosi bastonul/cătușele și executa arestări după misiune sau mandat.");
        player.tell("Senior: preia plângeri, investighează și mobilizează Juniori/Străjeri; nu emite misiuni plătite.");
        player.tell("Locotenentul și Comisaru' declară misiuni plătite la secretară și aprobă recompensele.");
        player.tell("Patrulă: 4 checkpoint-uri; 10 minute pauză între puncte; timpul fiecărei misiuni este stabilit de Locotenent sau Comisaru'.");
        player.tell("Salariu: un bloc complet la fiecare 10 minute. Monede: 1 / 10 / 100 / 1000.");
        player.tell("Special Duty suspendă temporar checkpoint-urile și cere autorizare. Rapoartele merg la Comisaru'.");
        player.tell("Poți semna /straja demisie confirm. Revenirea este posibilă după cooldown și este limitată la Străjer Senior.");
        var training = ctx.policies().trainingQuiz.stream()
                .filter(q -> q.minRank() <= state.rank && !Boolean.TRUE.equals(state.trainingPassed.get(q.id())))
                .toList();
        player.tell(!training.isEmpty()
                ? "Instruire disponibilă: " + training.size() + " module. Rulează /straja quiz pentru următoarea întrebare."
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
            player.tell("Folosește /straja report <text>.");
            return;
        }
        addInbox("REPORT", player.name(), text.trim());
        player.tell("Raportul a fost trimis la Comisaru'.");
    }

    public void message(PlayerGateway player, String text) {
        if (text == null || text.isBlank()) {
            player.tell("Folosește /straja message <text>.");
            return;
        }
        addInbox("MESSAGE", player.name(), text.trim());
        player.tell("Mesajul a fost trimis la Comisaru'.");
    }

    public void request(PlayerGateway player, String text) {
        if (text == null || text.isBlank()) {
            player.tell("Folosește /straja request <text>.");
            return;
        }
        addInbox("REQUEST", player.name(), text.trim());
        player.tell("Cererea a fost trimisă la Comisaru'.");
    }

    // ------------------------------------------------------------ activity tracking

    /** Anti-AFK: called once per server tick for each online player. */
    public DutyEngine.TickResult tickPlayerDuty(PlayerGateway player) {
        GuardState state = players.state(player);
        if (!state.duty) return new DutyEngine.TickResult(List.of());
        long current = now();
        var activity = prepareSalaryActivity(player, state, current);
        var p = ctx.policies();
        // Service equipment lease: expiry ends the duty and the gear is
        // reclaimed below like any other duty termination.
        var lease = state.serviceEquipment;
        if (lease != null && p.serviceLeaseMinutes > 0
                && current - lease.issuedAt >= p.serviceLeaseMinutes * DutyEngine.MINUTE_MS) {
            var ended = DutyEngine.endDuty(state, "service_lease_expired", current,
                    salaryFor(state), p);
            player.tell("Contractul de echipament a expirat (" + p.serviceLeaseMinutes
                    + " min). Tura s-a încheiat și echipamentul a fost rechemat.");
            equipment.reclaimServiceEquipment(player, state);
            players.save(player.uuid(), state);
            notifyEvents(player, ended.events());
            return new DutyEngine.TickResult(ended.events());
        }
        var result = DutyEngine.tickDuty(state, current, salaryFor(state),
                ctx.setup().read().missionMinutes, ctx.policies(), activity.accrualNow);
        if (!state.duty && state.serviceEquipment != null) {
            equipment.reclaimServiceEquipment(player, state);
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
