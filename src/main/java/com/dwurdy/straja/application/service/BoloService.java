package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.BoloAuthority;
import com.dwurdy.straja.domain.model.BoloRecord;
import com.dwurdy.straja.domain.model.BoloStatus;
import com.dwurdy.straja.domain.model.BoloStore;
import com.dwurdy.straja.domain.model.FineTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;

/** Informational watch notices; an authoritative warrant remains separate. */
public final class BoloService {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private DispatchService dispatch; // FUN-6: BOLOs and fugitive marks raise calls

    public BoloService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    /** FUN-6 wiring — set post-construction by the runtime. */
    public void useDispatch(DispatchService dispatch) { this.dispatch = dispatch; }

    /** Subject's live position when online; blank coords otherwise. */
    private double[] subjectPosition(String subjectUuid) {
        for (var p : ctx.server().onlinePlayers()) {
            if (p != null && p.uuid() != null && p.uuid().toString().equals(subjectUuid)) {
                return new double[]{p.x(), p.y(), p.z()};
            }
        }
        return null;
    }

    private String subjectDimension(String subjectUuid) {
        for (var p : ctx.server().onlinePlayers()) {
            if (p != null && p.uuid() != null && p.uuid().toString().equals(subjectUuid)) {
                return p.dimension();
            }
        }
        return "";
    }

    private void raiseBoloCall(BoloRecord record, String severity) {
        if (dispatch == null || record == null) return;
        double[] pos = subjectPosition(record.subjectUuid);
        dispatch.raise(DispatchService.SOURCE_BOLO, record.id,
                "BOLO: " + record.subjectName, record.reason,
                subjectDimension(record.subjectUuid),
                pos != null ? pos[0] : 0, pos != null ? pos[1] : 0, pos != null ? pos[2] : 0,
                pos != null ? "ultima poziție cunoscută" : "",
                record.subjectUuid, record.subjectName,
                record.issuerUuid, severity, "ENLISTED");
    }

    private long now() { return ctx.clock().nowMillis(); }

    private BoloStore store() {
        BoloStore store = ctx.bolos().read();
        store.ensureRecords();
        for (BoloRecord record : store.records) {
            if (record == null) continue;
            if (record.status == null) record.status = BoloStatus.ACTIVE;
            if (record.authority == null) record.authority = BoloAuthority.INFORMATION_ONLY;
        }
        return store;
    }

    public synchronized BoloRecord create(PlayerGateway issuer, PlayerGateway subject,
                                           String reason, String notes,
                                           BoloAuthority authority, String incidentId) {
        if (issuer == null || subject == null || !ctx.policies().bolosEnabled) {
            if (issuer != null) issuer.refuse("straja.bolo.disabled", "straja.remedy.ask_comisar");
            return null;
        }
        var issuerState = players.state(issuer);
        if (!players.isOnDutyGuard(issuer) || issuerState == null
                || issuerState.rank < ctx.policies().boloMinimumIssuerRank) {
            issuer.refuse("straja.bolo.issue_rank", "straja.remedy.duty");
            audit.record(AuditActions.BOLO_CREATE, issuer.name(), uuid(issuer), subject.name(),
                    uuid(subject), AuditResults.REFUSED, "issuer_not_eligible");
            return null;
        }
        if (reason == null || reason.isBlank()
                || reason.trim().length() > ctx.policies().boloMaxReasonLength) {
            issuer.refuse("straja.bolo.reason_required", "straja.remedy.fix_retry");
            return null;
        }
        BoloAuthority requested = authority == null ? BoloAuthority.INFORMATION_ONLY : authority;
        if (requested == BoloAuthority.ARREST_AUTHORIZED
                && !hasAuthoritativeTask(subject, null)) {
            issuer.tell("Un BOLO nu poate crea singur autoritate de arest.");
            audit.record(AuditActions.BOLO_CREATE, issuer.name(), uuid(issuer), subject.name(),
                    uuid(subject), AuditResults.REFUSED, "missing_authoritative_task");
            return null;
        }
        BoloStore data = store();
        long activeForSubject = data.records.stream()
                .filter(r -> r != null && r.status == BoloStatus.ACTIVE
                        && uuid(subject).equals(r.subjectUuid))
                .count();
        if (activeForSubject >= ctx.policies().boloMaxActivePerSubject) {
            issuer.refuse("straja.bolo.too_many", "straja.remedy.wait");
            return null;
        }
        BoloRecord record = new BoloRecord();
        record.id = data.nextBoloId();
        record.subjectUuid = uuid(subject);
        record.subjectName = subject.name();
        record.reason = clean(reason, ctx.policies().boloMaxReasonLength);
        record.notes = clean(notes, ctx.policies().incidentMaxDescriptionLength);
        record.issuerUuid = uuid(issuer);
        record.issuerName = issuer.name();
        record.issuerRank = issuerState.rank;
        record.createdAt = now();
        record.expiresAt = now() + ctx.policies().boloDefaultExpirationSeconds * 1_000L;
        record.authority = requested;
        record.linkedIncidentId = cleanId(incidentId);
        if (requested == BoloAuthority.ARREST_AUTHORIZED) {
            record.linkedArrestTaskId = findAuthoritativeTaskId(subject, null);
        }
        data.records.add(record);
        ctx.bolos().write(data);
        issuer.tell("BOLO " + record.id + " a fost emis pentru " + subject.name()
                + ". INFORMARE — NU ESTE MANDAT DE AREST.");
        audit.record(AuditActions.BOLO_CREATE, issuer.name(), uuid(issuer), record.id,
                record.subjectUuid, AuditResults.SUCCESS, record.authority.name());
        raiseBoloCall(record, "ROUTINE");
        return record;
    }

    public synchronized boolean cancel(PlayerGateway actor, String boloId) {
        BoloStore data = store();
        BoloRecord record = data.find(boloId);
        if (record == null || record.status != BoloStatus.ACTIVE) {
            if (actor != null) actor.refuse("straja.bolo.inactive", "straja.remedy.retry");
            return false;
        }
        boolean issuer = actor != null && uuid(actor).equals(record.issuerUuid);
        var actorState = actor == null ? null : players.state(actor);
        boolean superior = actor != null && (players.isCommissioner(actor)
                || (actorState != null
                    && actorState.rank >= ctx.policies().boloCancellationMinimumRank));
        if (!issuer && !superior) {
            if (actor != null) actor.refuse("straja.bolo.cancel_auth", "straja.remedy.ask_comisar");
            return false;
        }
        record.status = BoloStatus.CANCELLED;
        ctx.bolos().write(data);
        audit.record(AuditActions.BOLO_CANCEL, actor.name(), uuid(actor), record.id,
                record.subjectUuid, AuditResults.SUCCESS, "");
        if (dispatch != null)
            dispatch.closeForSource(DispatchService.SOURCE_BOLO, record.id, "cancelled");
        actor.tell("BOLO " + record.id + " a fost anulat.");
        return true;
    }

    /**
     * System-side clear on arrest — custody consumes the hunt, bypassing the
     * issuer/superior gate on {@link #cancel} (checkpoints have no issuer).
     * Returns the number of active BOLOs cancelled.
     */
    public synchronized int clearFor(UUID subjectUuid) {
        return resolveFor(subjectUuid, BoloStatus.CANCELLED, "arrest_clear");
    }

    /**
     * LAW-007: a lawful resolution marks active marks RESOLVED rather than
     * CANCELLED — arrest and formal release both end the hunt on the books.
     */
    public synchronized int resolveFor(UUID subjectUuid, BoloStatus status, String note) {
        String key = subjectUuid == null ? "" : subjectUuid.toString();
        if (key.isEmpty()) return 0;
        BoloStore data = store();
        int cleared = 0;
        var action = status == BoloStatus.RESOLVED ? AuditActions.BOLO_RESOLVE : AuditActions.BOLO_CANCEL;
        for (BoloRecord record : data.records) {
            if (record != null && record.status == BoloStatus.ACTIVE
                    && key.equals(record.subjectUuid)) {
                record.status = status;
                cleared++;
                audit.record(action, "system", "", record.id,
                        record.subjectUuid, AuditResults.SUCCESS, note);
                if (dispatch != null)
                    dispatch.closeForSource(DispatchService.SOURCE_BOLO, record.id, note);
            }
        }
        if (cleared > 0) ctx.bolos().write(data);
        return cleared;
    }

    /**
     * Same resolution keyed by uuid OR subject name — the fallback for
     * corrupt or legacy sentence records whose uuid will not parse.
     */
    public synchronized int resolveForSubject(String uuidOrName, BoloStatus status,
                                              String note) {
        if (uuidOrName == null || uuidOrName.isBlank()) return 0;
        BoloStore data = store();
        int cleared = 0;
        var action = status == BoloStatus.RESOLVED ? AuditActions.BOLO_RESOLVE : AuditActions.BOLO_CANCEL;
        for (BoloRecord record : data.records) {
            if (record != null && record.status == BoloStatus.ACTIVE
                    && (uuidOrName.equals(record.subjectUuid)
                            || uuidOrName.equalsIgnoreCase(record.subjectName))) {
                record.status = status;
                cleared++;
                audit.record(action, "system", "", record.id,
                        record.subjectUuid, AuditResults.SUCCESS, note);
                if (dispatch != null)
                    dispatch.closeForSource(DispatchService.SOURCE_BOLO, record.id, note);
            }
        }
        if (cleared > 0) ctx.bolos().write(data);
        return cleared;
    }

    /**
     * System-side fugitive marker — a prisoner breached custody without an
     * official release, so the wanted registry must track them even though
     * no player issued the BOLO. Idempotent per subject while one is active;
     * the open sentence (recorded in {@code linkedArrestTaskId}) is the
     * arrest authority, so the record stays INFORMATION_ONLY like
     * {@link #clearFor} bypasses issuer gates in reverse.
     */
    public synchronized BoloRecord markFugitive(String subjectUuid, String subjectName,
                                                String reason, String sentenceId) {
        if (subjectUuid == null || subjectUuid.isBlank()
                || !ctx.policies().bolosEnabled) return null;
        expire();
        BoloStore data = store();
        for (BoloRecord record : data.records) {
            if (record != null && record.status == BoloStatus.ACTIVE
                    && subjectUuid.equals(record.subjectUuid)) {
                raiseBoloCall(record, "URGENT"); // re-alert if the call expired
                return record; // already hunted — do not stack markers
            }
        }
        BoloRecord record = new BoloRecord();
        record.id = data.nextBoloId();
        record.subjectUuid = subjectUuid;
        record.subjectName = subjectName == null ? subjectUuid : subjectName;
        record.reason = clean(reason, ctx.policies().boloMaxReasonLength);
        record.issuerName = "system";
        record.issuerUuid = "";
        record.issuerRank = 0;
        record.createdAt = now();
        record.expiresAt = 0; // custody breach only clears on recapture/release
        record.authority = BoloAuthority.INFORMATION_ONLY;
        record.linkedArrestTaskId = sentenceId == null ? "" : sentenceId;
        data.records.add(record);
        ctx.bolos().write(data);
        audit.record(AuditActions.BOLO_CREATE, "system", "", record.id,
                record.subjectUuid, AuditResults.SUCCESS, "fugitive_escape sentenceId=" + sentenceId);
        // FUN-6: a custody breach is a pursuit call — pushed urgently.
        raiseBoloCall(record, "URGENT");
        return record;
    }

    /**
     * #248 — system-side forgery marker. A gate scanner or inspector just
     * caught the subject carrying a forged/unregistered artifact: the marker
     * is an arrest warrant ({@link BoloAuthority#ARREST_AUTHORIZED}) with the
     * standard BOLO expiration. Idempotent per subject while one is active —
     * a second flag refreshes nothing, which keeps repeated gate hits from
     * stacking records.
     */
    public synchronized BoloRecord flagForgery(String subjectUuid, String subjectName,
                                               String reason) {
        if (subjectUuid == null || subjectUuid.isBlank()
                || !ctx.policies().bolosEnabled) return null;
        expire();
        BoloStore data = store();
        for (BoloRecord record : data.records) {
            if (record != null && record.status == BoloStatus.ACTIVE
                    && subjectUuid.equals(record.subjectUuid)) {
                // Already hunted — the marker stays, but the forgery still
                // lands on the warrant so the record reflects why (#248).
                if (record.reason == null || !record.reason.contains("fals")) {
                    record.reason = clean(
                            (record.reason == null ? "" : record.reason)
                                    + "; falsificare depistată",
                            ctx.policies().boloMaxReasonLength);
                    ctx.bolos().write(data);
                }
                raiseBoloCall(record, "URGENT"); // a live warrant keeps its call fresh
                return record;
            }
        }
        BoloRecord record = new BoloRecord();
        record.id = data.nextBoloId();
        record.subjectUuid = subjectUuid;
        record.subjectName = subjectName == null ? subjectUuid : subjectName;
        record.reason = clean(reason, ctx.policies().boloMaxReasonLength);
        record.issuerName = "system";
        record.issuerUuid = "";
        record.issuerRank = 0;
        record.createdAt = now();
        record.expiresAt = now()
                + Math.max(60L, ctx.policies().boloDefaultExpirationSeconds) * 1_000L;
        record.authority = BoloAuthority.ARREST_AUTHORIZED;
        data.records.add(record);
        ctx.bolos().write(data);
        audit.record(AuditActions.BOLO_CREATE, "system", "", record.id,
                record.subjectUuid, AuditResults.SUCCESS, "forgery_detected");
        raiseBoloCall(record, "URGENT");
        return record;
    }

    /**
     * KJS-1 — generic system wanted marker for the legacy command bridges
     * (prototype {@code strajaWantedUntil} / the {@code aggressive} verb). An
     * arrest-authority warrant with the standard BOLO expiration, idempotent
     * per subject while one is active — no player issuer exists behind a
     * command block or NPC script.
     */
    public synchronized BoloRecord flagSystem(String subjectUuid, String subjectName,
                                              String reason) {
        if (subjectUuid == null || subjectUuid.isBlank()
                || !ctx.policies().bolosEnabled) return null;
        expire();
        BoloStore data = store();
        for (BoloRecord record : data.records) {
            if (record != null && record.status == BoloStatus.ACTIVE
                    && subjectUuid.equals(record.subjectUuid)) {
                raiseBoloCall(record, "URGENT"); // re-alert if the call expired
                return record; // already hunted — do not stack markers
            }
        }
        BoloRecord record = new BoloRecord();
        record.id = data.nextBoloId();
        record.subjectUuid = subjectUuid;
        record.subjectName = subjectName == null ? subjectUuid : subjectName;
        record.reason = clean(reason, ctx.policies().boloMaxReasonLength);
        record.issuerName = "system";
        record.issuerUuid = "";
        record.issuerRank = 0;
        record.createdAt = now();
        record.expiresAt = now()
                + Math.max(60L, ctx.policies().boloDefaultExpirationSeconds) * 1_000L;
        record.authority = BoloAuthority.ARREST_AUTHORIZED;
        data.records.add(record);
        ctx.bolos().write(data);
        audit.record(AuditActions.BOLO_CREATE, "system", "", record.id,
                record.subjectUuid, AuditResults.SUCCESS, "legacy_bridge");
        raiseBoloCall(record, "URGENT");
        return record;
    }

    public synchronized List<BoloRecord> active() {
        expire();
        return store().records.stream()
                .filter(record -> record != null && record.status == BoloStatus.ACTIVE)
                .toList();
    }

    public synchronized void expire() {
        BoloStore data = store();
        boolean changed = false;
        for (BoloRecord record : data.records) {
            if (record != null && record.status == BoloStatus.ACTIVE
                    && record.expiresAt > 0 && record.expiresAt <= now()) {
                record.status = BoloStatus.EXPIRED;
                changed = true;
                audit.record(AuditActions.BOLO_EXPIRE, "system", "", record.id,
                        record.subjectUuid, AuditResults.SUCCESS, "");
                if (dispatch != null)
                    dispatch.closeForSource(DispatchService.SOURCE_BOLO, record.id, "expired");
            }
        }
        if (changed) ctx.bolos().write(data);
    }

    /** BOLOs never authorize arrest; only the linked task can do so. */
    public boolean hasArrestAuthority(BoloRecord record) {
        if (record == null || record.status != BoloStatus.ACTIVE
                || record.authority != BoloAuthority.ARREST_AUTHORIZED) return false;
        return hasAuthoritativeTaskById(record.linkedArrestTaskId, record.subjectUuid);
    }

    public boolean hasAuthoritativeTask(PlayerGateway subject) {
        return hasAuthoritativeTask(subject, null);
    }

    private boolean hasAuthoritativeTask(PlayerGateway subject, String taskId) {
        return findAuthoritativeTaskId(subject, taskId) != null;
    }

    private String findAuthoritativeTaskId(PlayerGateway subject, String taskId) {
        String targetUuid = subject == null ? "" : uuid(subject);
        var taskStore = ctx.fines().read();
        if (taskStore.tasks == null) return null;
        for (FineTask task : taskStore.tasks) {
            if (task == null || !isAuthoritative(task)) continue;
            if (taskId != null && !taskId.isBlank() && !taskId.equals(task.id)) continue;
            if (!targetUuid.isBlank() && targetUuid.equals(task.targetUuid)) return task.id;
        }
        return null;
    }

    private boolean hasAuthoritativeTaskById(String taskId, String subjectUuid) {
        if (taskId == null || taskId.isBlank()) return false;
        var taskStore = ctx.fines().read();
        if (taskStore.tasks == null) return false;
        for (FineTask task : taskStore.tasks) {
            if (task != null && taskId.equals(task.id) && isAuthoritative(task)
                    && (subjectUuid == null || subjectUuid.isBlank()
                        || subjectUuid.equals(task.targetUuid))) return true;
        }
        return false;
    }

    private static boolean isAuthoritative(FineTask task) {
        return task.kind != null
                && ("FINE_RECOVERY".equals(task.kind)
                    || "HEARING_WARRANT".equals(task.kind)
                    || "JAILER_ASSAULT".equals(task.kind))
                && task.status != null
                && !List.of("CANCELLED", "COMPLETED", "SUSPECT_KILLED").contains(task.status);
    }

    private static String uuid(PlayerGateway player) {
        return player == null || player.uuid() == null ? "" : player.uuid().toString();
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("\\p{Cntrl}", " ");
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private static String cleanId(String value) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.matches("[A-Za-z0-9_-]{1,80}") ? clean : "";
    }
}
