package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.AuditEntry;

/** Bounded append-only audit trail. */
public class AuditService {
    private final StrajaContext ctx;

    public AuditService(StrajaContext ctx) {
        this.ctx = ctx;
    }

    public void record(String action, String actor, String actorUuid,
                       String target, String targetUuid, String result, String details) {
        if (!ctx.policies().auditEnabled) return;
        AuditEntry entry = new AuditEntry();
        entry.at = ctx.clock().nowMillis();
        entry.action = action;
        entry.actor = actor == null ? "" : actor;
        entry.actorUuid = actorUuid == null ? "" : actorUuid;
        entry.target = target == null ? "" : target;
        entry.targetUuid = targetUuid == null ? "" : targetUuid;
        entry.result = result == null ? "" : result;
        entry.details = details == null ? "" : details;
        ctx.audit().append(entry);
    }

    public java.util.List<AuditEntry> tail(int count) {
        return ctx.audit().tail(count);
    }
}
