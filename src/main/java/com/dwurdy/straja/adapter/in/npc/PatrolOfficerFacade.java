package com.dwurdy.straja.adapter.in.npc;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.application.service.PatrolService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Adapts the live {@link StrajaNpcEntity} into the {@link PatrolService.Officer}
 * port — navigation, melee and position reads only; every decision stays in
 * the service so it remains headless-testable. The patrol post is the
 * entity's persisted restriction point (set at spawn), falling back to its
 * current position for officers spawned without one.
 */
public final class PatrolOfficerFacade implements PatrolService.Officer {
    /** Chase speed multiplier — wanted suspects cannot outrun the law on
     *  foot inside its own jurisdiction. */
    private static final double ENGAGE_SPEED = 1.15;
    private static final double RETURN_SPEED = 1.0;

    private final StrajaNpcEntity npc;
    private final ServerLevel level;

    public PatrolOfficerFacade(StrajaNpcEntity npc, ServerLevel level) {
        this.npc = npc;
        this.level = level;
    }

    private ServerPlayer resolve(PlayerGateway target) {
        if (target == null || target.uuid() == null) return null;
        return level.getEntity(target.uuid()) instanceof ServerPlayer player
                ? player : null;
    }



    @Override
    public String entityUuid() {
        return npc.getStringUUID();
    }

    @Override
    public String dimension() {
        return level.dimension().location().toString();
    }

    @Override
    public double x() { return npc.getX(); }

    @Override
    public double y() { return npc.getY(); }

    @Override
    public double z() { return npc.getZ(); }

    @Override
    public double postX() { return npc.postX(); }

    @Override
    public double postY() { return npc.postY(); }

    @Override
    public double postZ() { return npc.postZ(); }

    @Override
    public double distanceTo(PlayerGateway target) {
        var player = resolve(target);
        return player == null ? Double.MAX_VALUE : npc.distanceTo(player);
    }

    @Override
    public void moveTo(PlayerGateway target) {
        var player = resolve(target);
        if (player != null) npc.getNavigation().moveTo(player, ENGAGE_SPEED);
    }

    @Override
    public void moveToPost() {
        npc.getNavigation().moveTo(npc.postX(), npc.postY(), npc.postZ(),
                RETURN_SPEED);
    }

    @Override
    public void melee(PlayerGateway target) {
        var player = resolve(target);
        if (player != null) npc.doHurtTarget(player);
    }

    @Override
    public void stopMoving() {
        npc.getNavigation().stop();
    }
}
