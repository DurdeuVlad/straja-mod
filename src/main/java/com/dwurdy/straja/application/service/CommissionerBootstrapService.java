package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.function.BiFunction;

/**
 * Commissioner identity bootstrap: outside a local environment there is no
 * commissioner until an operator/console pins one. The selection writes the
 * canonical UUID into the authoritative server config (via the injected pin
 * writer — production binds {@code StrajaServerConfig.pinCommissioner}),
 * applies it to the live policies immediately, and audits the change. Until a
 * pin exists every commissioner-gated action already fails closed.
 */
public class CommissionerBootstrapService {
    private final StrajaContext ctx;
    private final AuditService audit;
    private final BiFunction<String, String, Boolean> pinWriter;

    public CommissionerBootstrapService(StrajaContext ctx, AuditService audit,
                                        BiFunction<String, String, Boolean> pinWriter) {
        this.ctx = ctx;
        this.audit = audit;
        this.pinWriter = pinWriter;
    }

    /** Readback: environment, pin state, and who currently holds the identity. */
    public void status(PlayerGateway actor) {
        if (!actor.isOp()) {
            actor.refuse("straja.commissioner.op_only", "straja.remedy.ask_admin");
            return;
        }
        StrajaPolicies p = ctx.policies();
        boolean pinned = p.commissionerUuid != null && !p.commissionerUuid.isBlank();
        actor.tell("Comisar: " + (pinned
                ? p.commissionerName + " — uuid " + p.commissionerUuid
                : "nesetat (rol nealocat)"));
        actor.tell("Mediu: " + p.environment + " | pin de uuid cerut: " + pinRequired(p)
                + " | fallback pe nume: " + p.allowNameFallback);
    }

    /** Pin the online target player as commissioner; persists into the server config. */
    public void select(PlayerGateway actor, PlayerGateway target) {
        if (!actor.isOp()) {
            actor.refuse("straja.commissioner.op_only", "straja.remedy.ask_admin");
            return;
        }
        if (target == null) {
            actor.refuse("straja.common.target_offline", "straja.remedy.fix_retry");
            return;
        }
        String uuid = target.uuid().toString();
        String name = target.name();
        boolean persisted;
        try {
            persisted = Boolean.TRUE.equals(pinWriter.apply(uuid, name));
        } catch (RuntimeException e) {
            // Config not loaded/writable — treat as a persistence failure, not a crash.
            persisted = false;
        }
        StrajaPolicies p = ctx.policies();
        p.commissionerUuid = uuid;
        p.commissionerName = name;
        audit.record(AuditActions.COMMISSIONER_PIN, actor.name(), actor.uuid().toString(),
                name, uuid, persisted ? AuditResults.SUCCESS : AuditResults.PERSIST_FAILED,
                "commissionerUuid");
        actor.tell(persisted
                ? "Comisar setat: " + name + " — uuid salvat în config (persistent)."
                : "Comisar setat: " + name + " — ATENȚIE: salvarea în config a eșuat; "
                        + "selecția se pierde la restart.");
    }

    private boolean pinRequired(StrajaPolicies p) {
        return !p.isLocalEnvironment()
                && (p.requireCommissionerUuidOutsideLocal || p.requireUuid);
    }
}
