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

/** Informational watch notices; an authoritative warrant remains separate. */
public final class BoloService {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;

    public BoloService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    private long now() { return ctx.clock().nowMillis(); }

    private BoloStore store() {
        BoloStore store = ctx.bolos().read();
        if (store.records == null) store.records = new ArrayList<>();
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
            if (issuer != null) issuer.tell("Sistemul BOLO este dezactivat.");
            return null;
        }
        if (!players.isOnDutyGuard(issuer)
                || players.state(issuer).rank < ctx.policies().boloMinimumIssuerRank) {
            issuer.tell("Nu ai rangul sau serviciul necesar pentru a emite un BOLO.");
            audit.record("bolo_create", issuer.name(), uuid(issuer), subject.name(),
                    uuid(subject), "REFUSED", "issuer_not_eligible");
            return null;
        }
        if (reason == null || reason.isBlank()
                || reason.trim().length() > ctx.policies().boloMaxReasonLength) {
            issuer.tell("Motivul BOLO este obligatoriu și are o lungime limitată.");
            return null;
        }
        BoloAuthority requested = authority == null ? BoloAuthority.INFORMATION_ONLY : authority;
        if (requested == BoloAuthority.ARREST_AUTHORIZED
                && !hasAuthoritativeTask(subject, null)) {
            issuer.tell("Un BOLO nu poate crea singur autoritate de arest.");
            audit.record("bolo_create", issuer.name(), uuid(issuer), subject.name(),
                    uuid(subject), "REFUSED", "missing_authoritative_task");
            return null;
        }
        BoloStore data = store();
        long activeForSubject = data.records.stream()
                .filter(r -> r != null && r.status == BoloStatus.ACTIVE
                        && uuid(subject).equals(r.subjectUuid))
                .count();
        if (activeForSubject >= ctx.policies().boloMaxActivePerSubject) {
            issuer.tell("Subiectul are deja prea multe BOLO-uri active.");
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
        record.issuerRank = players.state(issuer).rank;
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
        audit.record("bolo_create", issuer.name(), uuid(issuer), record.id,
                record.subjectUuid, "SUCCESS", record.authority.name());
        return record;
    }

    public synchronized boolean cancel(PlayerGateway actor, String boloId) {
        BoloStore data = store();
        BoloRecord record = data.find(boloId);
        if (record == null || record.status != BoloStatus.ACTIVE) {
            if (actor != null) actor.tell("BOLO-ul nu mai este activ.");
            return false;
        }
        boolean issuer = actor != null && uuid(actor).equals(record.issuerUuid);
        boolean superior = actor != null && (players.isCommissioner(actor)
                || players.state(actor).rank >= ctx.policies().boloCancellationMinimumRank);
        if (!issuer && !superior) {
            if (actor != null) actor.tell("Nu ai autoritatea de a anula acest BOLO.");
            return false;
        }
        record.status = BoloStatus.CANCELLED;
        ctx.bolos().write(data);
        audit.record("bolo_cancel", actor.name(), uuid(actor), record.id,
                record.subjectUuid, "SUCCESS", "");
        actor.tell("BOLO " + record.id + " a fost anulat.");
        return true;
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
                audit.record("bolo_expire", "system", "", record.id,
                        record.subjectUuid, "SUCCESS", "");
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
