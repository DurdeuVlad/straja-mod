package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.ComplaintRepository;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.domain.model.Complaint;
import com.dwurdy.straja.domain.model.ComplaintStore;
import java.util.function.Predicate;

/** Time-based complaint escalation independent of loaded NPCs or player login. */
public final class ComplaintEscalationService {
    private final ComplaintRepository repository;
    private final Clock clock;
    private final IdGenerator ids;
    private final AuthorizationService authorization;
    private final Predicate<String> inspectorAvailable;
    private final java.util.function.Function<String, String> inspectorResolver;
    private final long defaultSlaMillis;
    private AuditService audit;

    public ComplaintEscalationService(ComplaintRepository repository, Clock clock, IdGenerator ids,
                                      Predicate<String> inspectorAvailable) {
        this(repository, clock, ids, null, inspectorAvailable, 7L * 24 * 60 * 60 * 1000);
    }

    public ComplaintEscalationService(ComplaintRepository repository, Clock clock, IdGenerator ids,
                                      AuthorizationService authorization, Predicate<String> inspectorAvailable) {
        this(repository, clock, ids, authorization, inspectorAvailable, 7L * 24 * 60 * 60 * 1000);
    }

    public ComplaintEscalationService(ComplaintRepository repository, Clock clock, IdGenerator ids,
                                      AuthorizationService authorization,
                                      java.util.function.Function<String, String> inspectorResolver,
                                      long defaultSlaMillis) {
        this.repository = repository; this.clock = clock; this.ids = ids; this.authorization = authorization;
        this.inspectorResolver = inspectorResolver == null ? ignored -> null : inspectorResolver;
        this.inspectorAvailable = ignored -> this.inspectorResolver.apply(ignored) != null;
        this.defaultSlaMillis = defaultSlaMillis <= 0 ? 7L * 24 * 60 * 60 * 1000 : defaultSlaMillis;
    }

    private ComplaintEscalationService(ComplaintRepository repository, Clock clock, IdGenerator ids,
                                       AuthorizationService authorization, Predicate<String> inspectorAvailable,
                                       long defaultSlaMillis) {
        this.repository = repository; this.clock = clock; this.ids = ids; this.authorization = authorization;
        this.inspectorAvailable = inspectorAvailable == null ? ignored -> false : inspectorAvailable;
        this.inspectorResolver = ignored -> this.inspectorAvailable.test(ignored) ? "INSPECTOR_SCOPE" : null;
        this.defaultSlaMillis = defaultSlaMillis;
    }

    public void useAuditService(AuditService audit) {
        this.audit = audit;
    }

    public synchronized Complaint assignDeadline(String complaintId, long slaMillis) {
        if (slaMillis <= 0) throw new IllegalArgumentException("SLA must be positive");
        ComplaintStore store = repository.read(); Complaint complaint = require(store, complaintId);
        if (complaint.originalDeadline == null) complaint.originalDeadline = clock.nowMillis() + slaMillis;
        storeWrite(store); return complaint;
    }

    public synchronized Complaint linkInvestigationMission(String complaintId) {
        ComplaintStore store = repository.read(); Complaint complaint = require(store, complaintId);
        if (complaint.investigationMissionId == null || complaint.investigationMissionId.isBlank()) {
            complaint.investigationMissionId = "INVESTIGATION:" + complaint.id;
            complaint.version++;
            storeWrite(store);
        }
        return complaint;
    }

    public synchronized int escalateDue(String commissionerUuid, String jurisdiction) {
        ComplaintStore store = repository.read(); int changed = 0; long now = clock.nowMillis();
        for (Complaint complaint : store.complaints) {
            if (complaint == null || !complaint.isOpen()) continue;
            if (complaint.originalDeadline == null) complaint.originalDeadline = complaint.createdAt + defaultSlaMillis;
            long deadline = complaint.deadlineExtensions == null || complaint.deadlineExtensions.isEmpty()
                    ? complaint.originalDeadline : complaint.deadlineExtensions.get(complaint.deadlineExtensions.size() - 1);
            if (deadline > now) continue;
            if ("OVERDUE".equals(complaint.status) || "ESCALATED".equals(complaint.status)) continue;
            complaint.overdueAt = now;
            complaint.status = "OVERDUE";
            complaint.investigationMissionId = complaint.investigationMissionId == null
                    || complaint.investigationMissionId.isBlank()
                    ? "INVESTIGATION:" + complaint.id : complaint.investigationMissionId;
            String inspector = inspectorResolver.apply(jurisdiction);
            complaint.escalationTargetUuid = inspector == null || inspector.isBlank() ? commissionerUuid : inspector;
            complaint.status = "ESCALATED";
            complaint.version++; changed++;
            if (audit != null) {
                audit.record("complaint_escalation", "SYSTEM", "SYSTEM", complaint.id,
                        complaint.accusedUuid, "SUCCESS",
                        "target=" + complaint.escalationTargetUuid + ", mission=" + complaint.investigationMissionId);
            }
        }
        if (changed > 0) storeWrite(store); return changed;
    }

    public synchronized Complaint extend(String actorUuid, String complaintId, long extensionMillis) {
        if (extensionMillis <= 0) throw new IllegalArgumentException("extension must be positive");
        ComplaintStore store = repository.read(); Complaint complaint = require(store, complaintId);
        if (authorization != null) {
            var context = com.dwurdy.straja.domain.model.AuthorizationContext.of(actorUuid, "REVIEW_APPEALS");
            context.subjectUuid = complaint.accusedUuid;
            if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_AUTHORIZATION");
        }
        if (complaint.deadlineExtensions == null) complaint.deadlineExtensions = new java.util.ArrayList<>();
        long base = complaint.deadlineExtensions.isEmpty()
                ? (complaint.originalDeadline == null ? clock.nowMillis() : complaint.originalDeadline)
                : complaint.deadlineExtensions.get(complaint.deadlineExtensions.size() - 1);
        complaint.deadlineExtensions.add(base + extensionMillis);
        complaint.version++; storeWrite(store); return complaint;
    }

    private void storeWrite(ComplaintStore store) { repository.write(store); }
    private static Complaint require(ComplaintStore store, String id) {
        Complaint complaint = store.find(id); if (complaint == null) throw new IllegalArgumentException("unknown complaint"); return complaint;
    }
}
