package com.dwurdy.straja.adapter.in.npc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * The patrulier's duty beat: delegates every decision to the headless
 * {@link com.dwurdy.straja.application.service.PatrolService} through the
 * {@link PatrolOfficerFacade} port. Self-gates on the role so desk NPCs
 * never run it; the service throttles its own cadence.
 */
final class PatrolDutyGoal extends Goal {
    private final StrajaNpcEntity npc;
    private int cooldown;

    PatrolDutyGoal(StrajaNpcEntity npc) {
        this.npc = npc;
    }

    @Override
    public boolean canUse() {
        return NpcRoles.PATRULIER.equals(npc.getRoleId());
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (--cooldown > 0) return;
        cooldown = 5; // ~4 decision beats per second
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        if (runtime == null || !(npc.level() instanceof ServerLevel level)) return;
        runtime.patrols().tick(new PatrolOfficerFacade(npc, level));
    }
}
