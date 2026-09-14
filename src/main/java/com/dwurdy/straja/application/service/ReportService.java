package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.ReportUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.ActivityReport;
import com.dwurdy.straja.domain.model.ActivityReportStore;
import com.dwurdy.straja.domain.model.ActivityReports;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.StrajaPolicies;

import java.util.ArrayList;
import java.util.List;

/**
 * §11 weekly activity reports: members file through the Secretary; the
 * Comisar reviews (accept / return-with-note / call-in). The reporting
 * interval self-anchors on first contact; overdue reports optionally gate
 * duty start (reports.blockDutyWhenOverdue).
 */
public class ReportService implements ReportUseCase {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int FIELD_LIMIT = 2000;

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;

    public ReportService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    private StrajaPolicies p() {
        return ctx.policies();
    }

    private long now() {
        return ctx.clock().nowMillis();
    }

    private long intervalMillis() {
        return Math.max(1, p().reportIntervalDays) * DAY_MS;
    }

    private boolean isMember(PlayerGateway player) {
        var state = players.state(player);
        return players.isCommissioner(player)
                || (state.rank >= Rank.STAGIAR.level() && !state.fired && !state.resigned);
    }

    /** True when the member's report is past due (anchor self-stamps on first contact). */
    public boolean overdueFor(PlayerGateway player) {
        String uuid = player.uuid().toString();
        ActivityReportStore store = ctx.reports().read();
        boolean stamped = ActivityReports.ensureAnchor(store, uuid, now());
        if (stamped) ctx.reports().write(store);
        return ActivityReports.isOverdue(store, uuid, now(), intervalMillis());
    }

    @Override
    public List<AvailableAction> availableActions(PlayerGateway player) {
        if (!isMember(player)) return List.of();
        var actions = new ArrayList<AvailableAction>();
        actions.add(new AvailableAction(Action.SUBMIT, ""));
        actions.add(new AvailableAction(Action.STATUS, ""));
        if (players.isCommissioner(player)) {
            actions.add(new AvailableAction(Action.REVIEW_LIST, ""));
            for (ActivityReport r : ctx.reports().read().pendingReview()) {
                actions.add(new AvailableAction(Action.REVIEW, r.id));
            }
        }
        return List.copyOf(actions);
    }

    @Override
    public boolean submit(PlayerGateway player, String activity, String missions,
                          String incidents, String notes) {
        if (!isMember(player)) {
            player.tell("Doar membrii Străjii depun rapoarte de activitate.");
            return false;
        }
        if (activity == null || activity.isBlank()) {
            player.tell("Raportul trebuie să descrie activitatea din perioada raportată.");
            return false;
        }
        long now = now();
        String uuid = player.uuid().toString();
        ActivityReportStore store = ctx.reports().read();
        ActivityReports.ensureAnchor(store, uuid, now);
        ActivityReport report = store.openFor(uuid);
        boolean resubmission;
        if (report != null && ActivityReport.COMISAR_REVIEW.equals(report.status)) {
            player.tell("Raportul " + report.id + " te așteaptă la Comisar; nu poți depune altul acum.");
            return false;
        }
        if (report == null) {
            report = new ActivityReport();
            report.id = "R" + store.nextId++;
            report.authorUuid = uuid;
            report.authorName = player.name();
            report.revision = 1;
            store.reports.put(report.id, report);
            resubmission = false;
        } else {
            report.revision += 1;
            resubmission = ActivityReport.RETURNED.equals(report.status);
        }
        report.periodStart = store.anchors.get(uuid);
        report.periodEnd = now;
        report.activity = trim(activity);
        report.missions = trim(missions);
        report.incidents = trim(incidents);
        report.notes = trim(notes);
        report.status = ActivityReport.SUBMITTED;
        report.submittedAt = now;
        report.reviewedBy = "";
        report.reviewNote = "";
        report.reviewedAt = null;
        // A fresh interval starts at filing.
        store.anchors.put(uuid, now);
        ctx.reports().write(store);
        audit.record("report_submit", player.name(), uuid, report.id, uuid, "SUCCESS",
                resubmission ? "revision_" + report.revision : "filed");
        player.tell("Raportul " + report.id + " a fost depus la Secretariat" +
                (resubmission ? " (revizia " + report.revision + ")" : "")
                + ". Următoarea raportare: peste " + p().reportIntervalDays + " zile.");
        return true;
    }

    @Override
    public void status(PlayerGateway player) {
        if (!isMember(player)) {
            player.tell("Doar membrii Străjii au rapoarte de activitate.");
            return;
        }
        String uuid = player.uuid().toString();
        ActivityReportStore store = ctx.reports().read();
        ActivityReports.ensureAnchor(store, uuid, now());
        ctx.reports().write(store);
        ActivityReport latest = store.latestFor(uuid);
        if (latest == null) {
            player.tell("Nu ai depus încă niciun raport de activitate.");
        } else {
            player.tell("Ultimul raport: " + latest.id + " (revizia " + latest.revision
                    + ") — " + describeStatus(latest) + ".");
            if (latest.reviewNote != null && !latest.reviewNote.isBlank()) {
                player.tell("Nota Comisarului: " + latest.reviewNote);
            }
        }
        long due = ActivityReports.dueAt(store, uuid, now(), intervalMillis());
        long daysLeft = (due - now()) / DAY_MS;
        if (ActivityReports.isOverdue(store, uuid, now(), intervalMillis())) {
            player.tell("Raportul de activitate este RESTANT — depune-l la Secretariat.");
        } else {
            player.tell("Următorul raport e așteptat în " + Math.max(0, daysLeft) + " zile.");
        }
    }

    @Override
    public void listForReview(PlayerGateway player) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' verifică rapoartele de activitate.");
            return;
        }
        var pending = ctx.reports().read().pendingReview();
        if (pending.isEmpty()) {
            player.tell("Nu există rapoarte în așteptare.");
            return;
        }
        player.tell("Rapoarte în așteptare: " + pending.size());
        for (ActivityReport r : pending) {
            player.tell("  " + r.id + " — " + r.authorName + " (revizia " + r.revision + ")");
        }
    }

    @Override
    public boolean review(PlayerGateway player, String id, String decision, String note) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' verifică rapoartele de activitate.");
            return false;
        }
        ActivityReportStore store = ctx.reports().read();
        ActivityReport report = id == null ? null : store.reports.get(id.trim());
        if (report == null || !ActivityReport.SUBMITTED.equals(report.status)) {
            player.tell("Raportul " + id + " nu așteaptă o decizie.");
            return false;
        }
        String normalized = decision == null ? "" : decision.trim().toLowerCase();
        String status = switch (normalized) {
            case "accept", "accepta", "acceptă" -> ActivityReport.ACCEPTED;
            case "return", "returneaza", "returnează" -> ActivityReport.RETURNED;
            case "call", "cheama", "cheamă" -> ActivityReport.COMISAR_REVIEW;
            default -> null;
        };
        if (status == null) {
            player.tell("Decizie necunoscută — folosește accept, return sau call.");
            return false;
        }
        if (ActivityReport.RETURNED.equals(status) && (note == null || note.isBlank())) {
            player.tell("Un raport returnat are nevoie de o notă pentru autor.");
            return false;
        }
        report.status = status;
        report.reviewedBy = player.name();
        report.reviewNote = note == null ? "" : note.trim();
        report.reviewedAt = now();
        ctx.reports().write(store);
        audit.record("report_review", player.name(), player.uuid().toString(),
                report.id, report.authorUuid, "SUCCESS", status);
        player.tell(switch (status) {
            case ActivityReport.ACCEPTED -> "Raportul " + report.id + " a fost acceptat.";
            case ActivityReport.RETURNED -> "Raportul " + report.id + " a fost returnat cu notă.";
            default -> report.authorName + " este chemat la audiență pentru raportul " + report.id + ".";
        });
        return true;
    }

    private static String describeStatus(ActivityReport r) {
        return switch (r.status) {
            case ActivityReport.ACCEPTED -> "ACCEPTAT";
            case ActivityReport.RETURNED -> "RETURNAT — corectează și retrimite";
            case ActivityReport.COMISAR_REVIEW -> "ești așteptat la Comisar";
            default -> "DEPUS, în așteptarea verificării";
        };
    }

    private static String trim(String value) {
        if (value == null) return "";
        String t = value.trim();
        return t.length() > FIELD_LIMIT ? t.substring(0, FIELD_LIMIT) : t;
    }
}
