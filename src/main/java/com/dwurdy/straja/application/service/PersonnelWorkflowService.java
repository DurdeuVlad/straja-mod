package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.InboxMessage;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.Rank;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Native personnel workflow layered on the existing GuardService.
 *
 * <p>This service owns recruitment applications, persistent personnel identity,
 * weekly activity reports and audience requests. Scoreboard team membership is
 * deliberately absent from authorization decisions.</p>
 */
public final class PersonnelWorkflowService {
    private static final long REAL_DAY_MS = 24L * 60 * 60 * 1000;

    private final StrajaContext ctx;
    private final PlayerService players;
    private final GuardService guards;
    private final AuditService audit;

    public PersonnelWorkflowService(StrajaContext ctx, PlayerService players,
                                    GuardService guards, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.guards = guards;
        this.audit = audit;
    }

    private long now() {
        return ctx.clock().nowMillis();
    }

    private long reportIntervalMs() {
        return Math.max(1, ctx.policies().activityReportIntervalRealDays) * REAL_DAY_MS;
    }

    /**
     * Lazily normalizes legacy/native personnel records. Rank/state is the
     * authority source; a scoreboard team can never create authorization.
     */
    public GuardState ensurePersonnelRecord(PlayerGateway player) {
        GuardState state = players.state(player);
        if (!state.authorized()) return state;

        boolean changed = false;
        if (state.serviceNumber == null || state.serviceNumber.isBlank()) {
            state.serviceNumber = serviceNumberFor(player.uuid());
            changed = true;
        }
        if (state.authorizedAt == null) {
            state.authorizedAt = now();
            changed = true;
        }
        if (state.authorizedBy == null || state.authorizedBy.isBlank()) {
            state.authorizedBy = "legacy/native-migration";
            changed = true;
        }
        if (state.nextActivityReportDueAt == null) {
            state.nextActivityReportDueAt = state.authorizedAt + reportIntervalMs();
            changed = true;
        }
        if (changed) players.save(player.uuid(), state);
        return state;
    }

    private static String serviceNumberFor(UUID uuid) {
        String compact = uuid.toString().replace("-", "").toUpperCase(Locale.ROOT);
        return "SJ-" + compact.substring(0, Math.min(12, compact.length()));
    }

    /** Receptionist intake: records the application and supplies RP paperwork. */
    public boolean submitApplication(PlayerGateway player) {
        GuardState state = players.state(player);
        if (state.authorized()) {
            ensurePersonnelRecord(player);
            player.tell("Ești deja membru autorizat al Străjii.");
            return false;
        }
        if (state.fired) {
            player.tell("Dosarul tău este marcat ca îndepărtat din Strajă. Doar Comisaru' poate schimba această situație.");
            return false;
        }
        if (state.resigned) {
            player.tell("Ai o demisie înregistrată. Folosește procedura de reîncadrare, nu o cerere nouă.");
            return false;
        }

        boolean first = state.applicationSubmittedAt == null;
        if (first) {
            state.applicationSubmittedAt = now();
            state.invited = true;
            state.quizIndex = 0;
            state.quizPassed = false;
            state.quizCooldownAt = null;
            state.quizOrder = new ArrayList<>();
            players.save(player.uuid(), state);
            audit.record("recruitment_application", player.name(), player.uuid().toString(),
                    player.name(), player.uuid().toString(), "SUCCESS", "submitted_at_reception");
        }

        // Paperwork is intentionally non-authoritative: losing/renaming these
        // items never changes the stored application state.
        player.give(ItemSpec.of("minecraft:paper", 1).named("Cerere de recrutare - Straja"));
        player.give(ItemSpec.of("minecraft:book", 1).named("Regulamentul Străjii"));
        player.tell(first
                ? "Cererea a fost înregistrată. Ai primit documentele; mergi acum la Recrutor pentru test."
                : "Cererea ta este deja înregistrată. Mergi la Recrutor pentru test.");
        return true;
    }

    /** Recruiter intake: only valid applicants are allowed into the quiz flow. */
    public void recruiterIntake(PlayerGateway player) {
        GuardState state = players.state(player);
        if (state.authorized()) {
            state = ensurePersonnelRecord(player);
            player.tell("Dosar activ: " + state.serviceNumber + " | " + Rank.of(state.rank).displayName() + ".");
            return;
        }
        if (state.applicationSubmittedAt == null || !state.invited) {
            player.tell("Nu ai o cerere de recrutare activă. Vorbește mai întâi cu Recepționistul.");
            return;
        }
        player.tell("Recrutor: testul de admitere este obligatoriu și complet. Răspunde cu /straja quiz <răspuns>.");
        guards.recruit(player);
    }

    /** Trainer only exposes post-recruitment modules; it never grants membership. */
    public void trainerIntake(PlayerGateway player) {
        GuardState state = ensurePersonnelRecord(player);
        if (!state.authorized()) {
            player.tell("Instructorul lucrează doar cu membri autorizați ai Străjii.");
            return;
        }
        player.tell("Instructor: următorul modul disponibil se răspunde cu /straja quiz <răspuns>.");
        guards.quiz(player, null);
    }

    /** Commissioner override for experienced personnel (e.g. former captain). */
    public boolean authorizeExperienced(PlayerGateway actor, PlayerGateway target, int rank) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Doar Comisaru' poate autoriza direct personal cu experiență.");
            return false;
        }
        if (rank < Rank.JUNIOR.level() || rank > Rank.LIEUTENANT.level()) {
            actor.tell("Rang invalid. Folosește nivelul 1-4.");
            return false;
        }

        GuardState state = players.state(target);
        if (state.duty) {
            actor.tell("Încheie tura țintei înainte de schimbarea directă a statutului.");
            return false;
        }
        state.rank = rank;
        state.invited = true;
        state.quizPassed = true;
        state.quizIndex = ctx.policies().quiz.size();
        state.quizCooldownAt = null;
        state.fired = false;
        state.suspended = false;
        state.resigned = false;
        state.resignationPending = false;
        state.authorizedAt = now();
        state.authorizedBy = actor.uuid().toString();
        state.serviceNumber = serviceNumberFor(target.uuid());
        if (state.nextActivityReportDueAt == null) {
            state.nextActivityReportDueAt = state.authorizedAt + reportIntervalMs();
        }
        players.save(target.uuid(), state);
        audit.record("personnel_authorize", actor.name(), actor.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS", "rank=" + rank);
        target.tell("Ai fost autorizat oficial ca " + Rank.of(rank).displayName()
                + ". Număr de serviciu: " + state.serviceNumber + ".");
        actor.tell(target.name() + " a fost autorizat ca " + Rank.of(rank).displayName() + ".");
        return true;
    }

    public boolean revokeAuthorization(PlayerGateway actor, PlayerGateway target) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Doar Comisaru' poate revoca autorizația.");
            return false;
        }
        guards.fire(actor, target);
        return !players.state(target).authorized();
    }

    public boolean activityReportDue(PlayerGateway player) {
        GuardState state = ensurePersonnelRecord(player);
        if (!state.authorized()) return false;
        if ("RETURNED".equals(state.activityReportStatus)
                || "CALLED_IN".equals(state.activityReportStatus)
                || "DUE".equals(state.activityReportStatus)) return true;
        return state.nextActivityReportDueAt != null && now() >= state.nextActivityReportDueAt;
    }

    public boolean canStartDuty(PlayerGateway player) {
        GuardState state = ensurePersonnelRecord(player);
        if (!state.authorized()) {
            player.tell("Nu ai o autorizație Straja validă.");
            return false;
        }
        if (ctx.policies().activityReportBlocksDutyWhenDue && activityReportDue(player)) {
            state.activityReportStatus = "DUE";
            players.save(player.uuid(), state);
            player.tell("Raportul săptămânal de activitate este restant. Depune-l la Secretară înainte de o tură nouă.");
            return false;
        }
        return true;
    }

    public boolean submitActivityReport(PlayerGateway player, String text) {
        GuardState state = ensurePersonnelRecord(player);
        if (!state.authorized()) {
            player.tell("Doar personalul autorizat poate depune raport de activitate.");
            return false;
        }
        if (text == null || text.isBlank()) {
            player.tell("Raportul nu poate fi gol.");
            return false;
        }

        InboxMessage message = new InboxMessage();
        message.id = ctx.ids().newId("activity-report");
        message.type = "ACTIVITY_REPORT";
        message.sender = player.name();
        message.senderUuid = player.uuid().toString();
        message.text = text.trim();
        message.at = now();
        message.status = "OPEN";
        var inbox = ctx.inbox().read();
        inbox.add(message);
        ctx.inbox().write(inbox);

        state.lastActivityReportAt = message.at;
        state.nextActivityReportDueAt = message.at + reportIntervalMs();
        state.activityReportStatus = "SUBMITTED";
        state.activityReportReviewMessage = null;
        players.save(player.uuid(), state);
        audit.record("activity_report_submit", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS", message.id);
        player.tell("Raportul " + message.id + " a fost depus la Secretară pentru verificarea Comisarului.");
        return true;
    }

    public boolean requestAudience(PlayerGateway player, String reason) {
        GuardState state = ensurePersonnelRecord(player);
        if (!state.authorized()) {
            player.tell("Cererea de audiență prin Secretariat este disponibilă personalului Străjii.");
            return false;
        }
        if ("REQUESTED".equals(state.audienceStatus)) {
            player.tell("Ai deja o cerere de audiență în așteptare.");
            return false;
        }

        InboxMessage message = new InboxMessage();
        message.id = ctx.ids().newId("audience");
        message.type = "AUDIENCE";
        message.sender = player.name();
        message.senderUuid = player.uuid().toString();
        message.text = reason == null || reason.isBlank() ? "Solicitare de audiență" : reason.trim();
        message.at = now();
        message.status = "OPEN";
        var inbox = ctx.inbox().read();
        inbox.add(message);
        ctx.inbox().write(inbox);

        state.audienceRequestedAt = message.at;
        state.audienceStatus = "REQUESTED";
        players.save(player.uuid(), state);
        audit.record("audience_request", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS", message.id);
        player.tell("Secretariatul a înregistrat cererea de audiență " + message.id + ".");
        return true;
    }

    public List<InboxMessage> pendingPersonnelInbox(PlayerGateway actor) {
        if (!players.isCommissioner(actor)) return List.of();
        return ctx.inbox().read().stream()
                .filter(m -> ("ACTIVITY_REPORT".equals(m.type) || "AUDIENCE".equals(m.type))
                        && (m.status == null || m.status.isBlank() || "OPEN".equals(m.status)))
                .toList();
    }

    public boolean reviewActivityReport(PlayerGateway actor, String reportId,
                                        String decision, String reviewText) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Doar Comisaru' poate verifica rapoartele de activitate.");
            return false;
        }
        String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACCEPTED", "RETURNED", "CALLED_IN").contains(normalized)) {
            actor.tell("Decizie invalidă: ACCEPTED, RETURNED sau CALLED_IN.");
            return false;
        }

        var inbox = ctx.inbox().read();
        InboxMessage found = inbox.stream()
                .filter(m -> "ACTIVITY_REPORT".equals(m.type) && reportId.equals(m.id))
                .findFirst().orElse(null);
        if (found == null) {
            actor.tell("Raport necunoscut: " + reportId);
            return false;
        }
        found.status = normalized;
        found.read = true;
        found.reviewText = reviewText == null ? "" : reviewText.trim();
        ctx.inbox().write(inbox);

        updateReportOwner(found, normalized, found.reviewText);
        audit.record("activity_report_review", actor.name(), actor.uuid().toString(),
                found.sender, found.senderUuid, "SUCCESS", reportId + ":" + normalized);
        actor.tell("Raport " + reportId + " -> " + normalized + ".");
        return true;
    }

    private void updateReportOwner(InboxMessage report, String decision, String note) {
        if (report.senderUuid == null || report.senderUuid.isBlank()) return;
        try {
            UUID uuid = UUID.fromString(report.senderUuid);
            GuardState state = players.state(uuid);
            state.activityReportStatus = decision;
            state.activityReportReviewMessage = note == null || note.isBlank() ? null : note;
            if ("RETURNED".equals(decision) || "CALLED_IN".equals(decision)) {
                state.nextActivityReportDueAt = now();
            }
            players.save(uuid, state);

            PlayerGateway online = ctx.server().findPlayer(report.senderUuid);
            if (online != null) {
                if ("ACCEPTED".equals(decision)) {
                    online.tell("Raportul tău de activitate a fost acceptat de Comisar.");
                } else if ("RETURNED".equals(decision)) {
                    online.tell("Raportul de activitate a fost returnat pentru completări. " + safeNote(note));
                } else {
                    online.tell("Comisarul te cheamă la birou în legătură cu raportul de activitate. " + safeNote(note));
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Legacy/name-only inbox entries are intentionally not rebound by name.
        }
    }

    public boolean resolveAudience(PlayerGateway actor, String requestId) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Doar Comisaru' poate rezolva cereri de audiență.");
            return false;
        }
        var inbox = ctx.inbox().read();
        InboxMessage found = inbox.stream()
                .filter(m -> "AUDIENCE".equals(m.type) && requestId.equals(m.id))
                .findFirst().orElse(null);
        if (found == null) {
            actor.tell("Cerere necunoscută: " + requestId);
            return false;
        }
        found.status = "RESOLVED";
        found.read = true;
        ctx.inbox().write(inbox);
        if (found.senderUuid != null && !found.senderUuid.isBlank()) {
            try {
                UUID uuid = UUID.fromString(found.senderUuid);
                GuardState state = players.state(uuid);
                state.audienceStatus = "RESOLVED";
                players.save(uuid, state);
                PlayerGateway online = ctx.server().findPlayer(found.senderUuid);
                if (online != null) online.tell("Cererea ta de audiență a fost soluționată de Comisar.");
            } catch (IllegalArgumentException ignored) {}
        }
        audit.record("audience_resolve", actor.name(), actor.uuid().toString(),
                found.sender, found.senderUuid, "SUCCESS", requestId);
        actor.tell("Cererea " + requestId + " a fost marcată rezolvată.");
        return true;
    }

    private static String safeNote(String note) {
        return note == null || note.isBlank() ? "" : "Observație: " + note;
    }
}
