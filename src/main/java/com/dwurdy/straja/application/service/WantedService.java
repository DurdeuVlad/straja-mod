package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.PrisonerStatus;

import java.util.UUID;

/**
 * LAW-007: the authoritative wanted/fugitive query surface. One service owns
 * the answer to "is this player hunted" so guards, checkpoints, and storage
 * watch all read the same truth instead of re-deriving it from stores.
 *
 * <p>A player is wanted while any of these hold:
 * <ul>
 *   <li>an ACTIVE BOLO record names them (admin marks, escape BOLOs);</li>
 *   <li>their prisoner register entry is FUGITIVE — the custody truth, so a
 *       cancelled BOLO cannot quietly unpursue a runaway;</li>
 *   <li>a legacy KubeJS wanted mark is still unexpired.</li>
 * </ul>
 *
 * <p>Escort suspension composes on top: a wanted player who is cuffed and
 * tethered to a reachable officer is "under escort" — already caught — and
 * guards must not engage. {@link #dropAggroAround} enforces that immediately
 * on cuff rather than waiting for the next periodic scan.
 */
public final class WantedService
        implements com.dwurdy.straja.application.port.in.WantedRoleplayUseCase {
    private final StrajaContext ctx;
    private final AuditService audit;
    private final BoloService bolos;
    private final CustodyRoleplayUseCase custody;

    public WantedService(StrajaContext ctx, AuditService audit,
                         BoloService bolos, CustodyRoleplayUseCase custody) {
        this.ctx = ctx;
        this.audit = audit;
        this.bolos = bolos;
        this.custody = custody;
    }

    /** Wanted-on-sight: live BOLO, register FUGITIVE, or unexpired legacy mark. */
    @Override
    public boolean isWanted(UUID uuid) {
        if (uuid == null) return false;
        String key = uuid.toString();
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(key);
        if (rec != null && rec.status == PrisonerStatus.FUGITIVE) return true;
        Long until = reg.legacyWantedUntil().get(key);
        if (until != null && until > ctx.clock().nowMillis()) return true;
        for (var bolo : bolos.active()) {
            if (bolo != null && key.equals(bolo.subjectUuid)) return true;
        }
        return false;
    }

    /**
     * Snapshot of every wanted uuid for a scan pass — reads the register and
     * the BOLO store once instead of per-player JSON parses inside a loop.
     */
    public java.util.Set<String> wantedUuids() {
        var out = new java.util.HashSet<String>();
        var reg = ctx.prisonerRegister().read();
        reg.prisoners().forEach((key, rec) -> {
            if (rec != null && rec.status == PrisonerStatus.FUGITIVE) out.add(key);
        });
        long now = ctx.clock().nowMillis();
        reg.legacyWantedUntil().forEach((uuid, until) -> {
            if (until != null && until > now) out.add(uuid);
        });
        for (var bolo : bolos.active()) {
            if (bolo != null && bolo.subjectUuid != null) out.add(bolo.subjectUuid);
        }
        return out;
    }

    /**
     * Cuffed and the cuffing officer is within the tether radius — the
     * suspect is already caught, so the hunt holds fire. The tether radius
     * (not the tighter gate bypass) is the escort definition.
     */
    @Override
    public boolean isUnderEscort(PlayerGateway target) {
        if (target == null || custody == null) return false;
        return custody.escortOfficerWithin(target, ctx.policies().escortTetherRadius) != null;
    }

    @Override
    public boolean guardDamageBlocked(UUID attackerEntityId, String attackerRole,
                                      PlayerGateway target) {
        if (!isUnderEscort(target)) return false;
        if ("guard".equals(attackerRole) || "jailer".equals(attackerRole)) return true;
        return attackerEntityId != null && ctx.npcGuards().isGuardOf(
                attackerEntityId, ctx.policies().storageFactionId);
    }

    /**
     * Clears any guard currently targeting the player — invoked the moment a
     * restraint lands so escort suspension does not wait for the periodic
     * aggro scan.
     */
    public void dropAggroAround(PlayerGateway target) {
        if (target == null || target.uuid() == null) return;
        var guards = ctx.npcGuards();
        if (!guards.available()) return;
        // CustomNPCs aggroRange can exceed the scan radius — sweep twice as
        // far so a guard that locked on from a distance is still cleared.
        double radius = Math.max(ctx.policies().storageAggroRange * 2.0, 32);
        for (var guard : guards.guardsNear(target.dimension(), target.x(), target.y(),
                target.z(), radius, ctx.policies().storageFactionId)) {
            guards.clearTargetIfTargeting(guard.id(), target.uuid());
        }
    }

    /** Restraint-applied hook: the hunt is called off the instant cuffs land. */
    public void onCuffed(PlayerGateway officer, PlayerGateway target) {
        if (target == null) return;
        dropAggroAround(target); // thief-flagged too — escort suspends all aggro
        if (!isWanted(target.uuid())) return;
        audit.record("wanted_escort", officer == null ? "" : officer.name(),
                officer == null ? "" : String.valueOf(officer.uuid()),
                target.name(), String.valueOf(target.uuid()),
                "SUCCESS", "aggro_suspended");
    }
}
