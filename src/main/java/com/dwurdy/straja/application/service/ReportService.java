package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.ReportUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.ActivityReport;
import com.dwurdy.straja.domain.model.ActivityReportStore;
import com.dwurdy.straja.domain.model.ActivityReports;
import com.dwurdy.straja.domain.model.DecisionWords;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.StrajaPolicies;

import java.util.ArrayList;
import java.util.List;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;

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

    /**
     * Caps drafted text to a byte-safe length — the form wire path bounds
     * field values by UTF-8 BYTES, so diacritics count double. Truncation
     * keeps an honest "…și încă N" tail when lines are dropped.
     */
    private static String capBytes(String text, int maxBytes) {
        if (text == null) return "";
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        String suffix = " […]";
        int budget = maxBytes - suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int end = Math.min(text.length(), budget);
        while (end > 0 && text.substring(0, end)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length > budget) {
            end--;
        }
        return text.substring(0, end) + suffix;
    }

    /** True when the member's report is past due (anchor self-stamps on first contact). */
    public boolean overdueFor(PlayerGateway player) {
        String uuid = player.uuid().toString();
        ActivityReportStore store = ctx.reports().read();
        boolean stamped = ActivityReports.ensureAnchor(store, uuid, now());
        stamped |= ActivityReports.ensureBlockSnapshot(store, uuid,
                players.state(player).serviceBlocks);
        if (stamped) ctx.reports().write(store);
        return ActivityReports.isOverdue(store, uuid, now(), intervalMillis());
    }

    /**
     * FUN-4: assembles the report's content from the recorded ledgers —
     * service-block delta over the window, completed missions, incidents
     * led, arrests made, and fines issued. The draft invents nothing: an
     * empty window produces an honest "no recorded activity" line.
     */
    @Override
    public ReportDraft draft(PlayerGateway player) {
        if (!isMember(player)) {
            player.refuse("straja.report.members_only", "straja.remedy.reception");
            return null;
        }
        String uuid = player.uuid().toString();
        var state = players.state(player);
        ActivityReportStore store = ctx.reports().read();
        boolean stamped = ActivityReports.ensureAnchor(store, uuid, now());
        stamped |= ActivityReports.ensureBlockSnapshot(store, uuid, state.serviceBlocks);
        if (stamped) ctx.reports().write(store);
        return assembleDraft(store, uuid, state, now(),
                ctx.missions().read().missions, ctx.incidents().read().incidents,
                ctx.arrestRecords().read().records, ctx.fines().read().fines);
    }

    /** Pure assembly over pre-read ledgers — shared by draft() and the sweep. */
    private ReportDraft assembleDraft(ActivityReportStore store, String uuid,
                                      com.dwurdy.straja.domain.model.GuardState state, long now,
                                      List<com.dwurdy.straja.domain.model.Mission> missionList,
                                      List<com.dwurdy.straja.domain.model.Incident> incidentList,
                                      List<com.dwurdy.straja.domain.model.ArrestRecord> arrestList,
                                      List<com.dwurdy.straja.domain.model.Fine> fineList) {
        long windowStart = store.anchors.getOrDefault(uuid, now);
        long blocksDelta = ActivityReports.blocksInWindow(store, uuid, state.serviceBlocks);

        var missionLines = new ArrayList<String>();
        for (var m : missionList) {
            if (m == null || m.completedAt == null) continue;
            if (m.completedAt < windowStart || m.completedAt > now) continue;
            boolean mine = m.assignees != null && m.assignees.stream()
                    .anyMatch(a -> a != null && uuid.equals(a.uuid));
            if (mine) missionLines.add(m.id + " — " + m.objective);
        }

        var incidentLines = new ArrayList<String>();
        for (var i : incidentList) {
            if (i == null) continue;
            if (!(uuid.equals(i.leadGuardUuid) || (i.supportingGuardUuids != null
                    && i.supportingGuardUuids.contains(uuid)))) continue;
            if (i.createdAt < windowStart || i.createdAt > now) continue;
            incidentLines.add(i.id + (i.resolvedAt != null ? " (rezolvat)" : " (în desfășurare)"));
        }

        int arrests = 0;
        for (var r : arrestList) {
            if (r != null && uuid.equals(r.arrestingGuardUuid)
                    && r.startedAt >= windowStart && r.startedAt <= now) arrests++;
        }
        int fines = 0;
        for (var f : fineList) {
            if (f != null && uuid.equals(f.issuerUuid)
                    && f.issuedAt >= windowStart && f.issuedAt <= now) fines++;
        }

        String activity = "Puncte de serviciu în perioadă: +" + blocksDelta
                + " (total " + state.serviceBlocks + "). "
                + "Arestări efectuate: " + arrests + ". Amenzi emise: " + fines + ".";
        String missions = missionLines.isEmpty()
                ? "Nicio misiune finalizată în perioadă."
                : "Misiuni finalizate (" + missionLines.size() + "): "
                        + String.join("; ", missionLines) + ".";
        String incidents = incidentLines.isEmpty()
                ? "Niciun incident condus în perioadă."
                : "Incidente (" + incidentLines.size() + "): "
                        + String.join("; ", incidentLines) + ".";
        if (blocksDelta == 0 && missionLines.isEmpty() && incidentLines.isEmpty()
                && arrests == 0 && fines == 0) {
            activity = "Nu există activitate înregistrată în perioada raportată.";
        }
        return new ReportDraft(capBytes(activity, FIELD_LIMIT),
                capBytes(missions, FIELD_LIMIT), capBytes(incidents, FIELD_LIMIT),
                windowStart, now);
    }

    /**
     * FUN-4: files an AUTO_FILED draft for every member whose interval
     * deadline passed. Runs headless from the server tick; skips members
     * with a report already in the pipeline and members who left the corps.
     * Returns the number filed.
     */
    public synchronized int autoFileSweep() {
        if (!p().reportsAutoFileOverdue) return 0;
        ActivityReportStore store = ctx.reports().read();
        long now = now();
        // Ledger reads are full-store parses — hoist above the member loop.
        var missions = ctx.missions().read().missions;
        var incidents = ctx.incidents().read().incidents;
        var arrests = ctx.arrestRecords().read().records;
        var fines = ctx.fines().read().fines;
        boolean changed = false;
        int filed = 0;
        for (String uuid : new ArrayList<>(store.anchors.keySet())) {
            try {
                if (!ActivityReports.isOverdue(store, uuid, now, intervalMillis())) continue;
                if (store.openFor(uuid) != null) continue;
                java.util.UUID uid;
                try {
                    uid = java.util.UUID.fromString(uuid);
                } catch (IllegalArgumentException bad) {
                    continue;
                }
                var state = players.state(uid);
                if (state.rank < Rank.STAGIAR.level() || state.fired || state.resigned) continue;
                // Seed a missing snapshot so the first auto-filed window
                // reports an honest delta instead of lifetime totals.
                changed |= ActivityReports.ensureBlockSnapshot(store, uuid, state.serviceBlocks);
                ReportDraft draft = assembleDraft(store, uuid, state, now,
                        missions, incidents, arrests, fines);
                ActivityReport report = new ActivityReport();
                report.id = "R" + store.nextId++;
                report.authorUuid = uuid;
                report.authorName = state.lastKnownName == null || state.lastKnownName.isBlank()
                        ? uuid : state.lastKnownName;
                report.revision = 1;
                report.periodStart = draft.windowStart();
                report.periodEnd = now;
                report.activity = draft.activity();
                report.missions = draft.missions();
                report.incidents = draft.incidents();
                report.notes = "";
                report.status = ActivityReport.SUBMITTED;
                report.source = ActivityReport.AUTO_FILED;
                report.submittedAt = now;
                store.reports.put(report.id, report);
                store.anchors.put(uuid, now);
                store.blockSnapshots.put(uuid, state.serviceBlocks);
                filed++;
                changed = true;
                audit.record(AuditActions.REPORT_SUBMIT, "Straja", "", report.id, uuid,
                        AuditResults.SUCCESS, "auto_filed");
            } catch (RuntimeException error) {
                // One member's failure must not wedge the sweep — or the
                // server tick that hosts it.
                com.dwurdy.straja.StrajaMod.LOGGER.warn(
                        "report auto-file failed for {}: {}", uuid, error.toString());
            }
        }
        if (changed) ctx.reports().write(store);
        return filed;
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
            player.refuse("straja.report.members_only", "straja.remedy.reception");
            return false;
        }
        if (activity == null || activity.isBlank()) {
            player.refuse("straja.report.content_required", "straja.remedy.fix_retry");
            return false;
        }
        long now = now();
        String uuid = player.uuid().toString();
        ActivityReportStore store = ctx.reports().read();
        ActivityReports.ensureAnchor(store, uuid, now);
        ActivityReports.ensureBlockSnapshot(store, uuid, players.state(player).serviceBlocks);
        ActivityReport report = store.openFor(uuid);
        boolean resubmission;
        if (report != null && ActivityReport.COMISAR_REVIEW.equals(report.status)) {
            player.refuse("straja.report.pending", "straja.remedy.ask_comisar", report.id);
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
        report.source = ActivityReport.SIGNED;
        report.submittedAt = now;
        report.reviewedBy = "";
        report.reviewNote = "";
        report.reviewedAt = null;
        // A fresh interval starts at filing.
        store.anchors.put(uuid, now);
        store.blockSnapshots.put(uuid, players.state(player).serviceBlocks);
        ctx.reports().write(store);
        audit.record(AuditActions.REPORT_SUBMIT, player.name(), uuid, report.id, uuid, AuditResults.SUCCESS,
                resubmission ? "revision_" + report.revision : "filed");
        player.tell("Raportul " + report.id + " a fost depus la Secretariat" +
                (resubmission ? " (revizia " + report.revision + ")" : "")
                + ". Următoarea raportare: peste " + p().reportIntervalDays + " zile.");
        return true;
    }

    @Override
    public void status(PlayerGateway player) {
        if (!isMember(player)) {
            player.refuse("straja.report.list_members", "straja.remedy.reception");
            return;
        }
        String uuid = player.uuid().toString();
        ActivityReportStore store = ctx.reports().read();
        ActivityReports.ensureAnchor(store, uuid, now());
        ActivityReports.ensureBlockSnapshot(store, uuid, players.state(player).serviceBlocks);
        ctx.reports().write(store);
        ActivityReport latest = store.latestFor(uuid);
        if (latest == null) {
            player.refuse("straja.report.none_mine", "straja.remedy.secretary");
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
            player.refuse("straja.report.verify_comisar", "straja.remedy.ask_comisar");
            return;
        }
        var pending = ctx.reports().read().pendingReview();
        if (pending.isEmpty()) {
            player.refuse("straja.report.none_pending", "straja.remedy.wait");
            return;
        }
        player.tell("Rapoarte în așteptare: " + pending.size());
        for (ActivityReport r : pending) {
            String marker = ActivityReport.AUTO_FILED.equals(r.source) ? " [AUTO]" : "";
            player.tell("  " + r.id + " — " + r.authorName + " (revizia " + r.revision + ")" + marker);
        }
    }

    @Override
    public boolean review(PlayerGateway player, String id, String decision, String note) {
        if (!players.isCommissioner(player)) {
            player.refuse("straja.report.verify_comisar", "straja.remedy.ask_comisar");
            return false;
        }
        ActivityReportStore store = ctx.reports().read();
        ActivityReport report = id == null ? null : store.reports.get(id.trim());
        if (report == null || !ActivityReport.SUBMITTED.equals(report.status)) {
            player.refuse("straja.report.not_pending", "straja.remedy.retry", id);
            return false;
        }
        String status = DecisionWords.is(decision, DecisionWords.REPORT_ACCEPT)
                ? ActivityReport.ACCEPTED
                : DecisionWords.is(decision, DecisionWords.REPORT_RETURN)
                        ? ActivityReport.RETURNED
                        : DecisionWords.is(decision, DecisionWords.REPORT_SUMMON)
                                ? ActivityReport.COMISAR_REVIEW
                                : null;
        if (status == null) {
            player.refuse("straja.report.bad_decision", "straja.remedy.fix_retry");
            return false;
        }
        if (ActivityReport.RETURNED.equals(status) && (note == null || note.isBlank())) {
            player.refuse("straja.report.return_note", "straja.remedy.fix_retry");
            return false;
        }
        report.status = status;
        report.reviewedBy = player.name();
        report.reviewNote = note == null ? "" : note.trim();
        report.reviewedAt = now();
        ctx.reports().write(store);
        audit.record(AuditActions.REPORT_REVIEW, player.name(), player.uuid().toString(),
                report.id, report.authorUuid, AuditResults.SUCCESS, status);
        player.tell(switch (status) {
            case ActivityReport.ACCEPTED -> "Raportul " + report.id + " a fost acceptat.";
            case ActivityReport.RETURNED -> "Raportul " + report.id + " a fost returnat cu notă.";
            default -> report.authorName + " este chemat la audiență pentru raportul " + report.id + ".";
        });
        return true;
    }

    /**
     * Delegated review: the delegation engine accepts a pending report under
     * the recorded authority — no player session required, and the authority
     * must still hold commissioner status at review time. Delegation only
     * accepts; returns and summons stay human.
     */
    public synchronized boolean reviewDelegated(String actorUuid, String actorName, String id) {
        if (!players.isCommissioner(actorName, actorUuid == null ? null : uuid(actorUuid))) {
            return false;
        }
        ActivityReportStore store = ctx.reports().read();
        ActivityReport report = id == null ? null : store.reports.get(id.trim());
        if (report == null || !ActivityReport.SUBMITTED.equals(report.status)) {
            return false;
        }
        report.status = ActivityReport.ACCEPTED;
        report.reviewedBy = actorName;
        report.reviewNote = "";
        report.reviewedAt = now();
        ctx.reports().write(store);
        audit.record(AuditActions.REPORT_REVIEW, actorName, actorUuid,
                report.id, report.authorUuid, AuditResults.SUCCESS, ActivityReport.ACCEPTED);
        return true;
    }

    private static java.util.UUID uuid(String raw) {
        try {
            return java.util.UUID.fromString(raw);
        } catch (RuntimeException error) {
            return null;
        }
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
