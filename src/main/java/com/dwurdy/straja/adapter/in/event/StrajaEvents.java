package com.dwurdy.straja.adapter.in.event;

import com.dwurdy.straja.adapter.in.npc.NpcRoles;
import com.dwurdy.straja.adapter.in.npc.StrajaNpcEntity;
import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * NeoForge event adapter: server lifecycle, per-tick duty timers, and
 * login-time restart recovery. No business rules live here — everything is
 * delegated to application services.
 */
public final class StrajaEvents {
    public StrajaEvents() {}

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) return;
        runtime.serverGateway().tick();
        runtime.missions().tick();
        runtime.custody().tick();
        runtime.prison().tick();
        runtime.fines().tick();
        if (runtime.serverGateway().tickCount() % 20 != 0) return;
        for (var player : runtime.serverGateway().onlinePlayers()) {
            runtime.guards().tickPlayerDuty(player);
        }
        if (runtime.policies().testCommandsEnabled && runtime.policies().isLocalEnvironment()) {
            for (var virtual : runtime.testPlayers().all()) {
                runtime.guards().tickPlayerDuty(virtual);
            }
        }
    }

    /** Re-applies registry name/skin when a persisted NPC entity loads. */
    @SubscribeEvent
    public void onNpcJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !(event.getEntity() instanceof StrajaNpcEntity npc)) return;
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) return;
        var record = runtime.context().npcs().read().npcs.get(npc.getStringUUID());
        if (record == null) {
            // Re-adopt persisted entities that outlived the registry — but only
            // when the entity still carries a valid role; otherwise leave it
            // unregistered rather than creating junk records.
            if (NpcRoles.isKnown(npc.getRoleId())) {
                runtime.npcs().register(npc.getStringUUID(), npc.getRoleId());
            }
            return;
        }
        if (record.role != null && !record.role.isEmpty()) npc.setRoleId(record.role);
        if (record.skin != null && !record.skin.isEmpty()) npc.setSkin(record.skin);
        if (record.displayName != null && !record.displayName.isEmpty()) {
            npc.setCustomName(Component.literal(record.displayName));
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var gateway = new MinecraftPlayerGateway(event.getEntity().getServer(), player.getUUID());
        var state = runtime.players().state(player.getUUID());
        runtime.guards().closeDutyAfterRestart(gateway, state, runtime.bootId());
        state.runtimeBootId = runtime.bootId();
        runtime.players().save(player.getUUID(), state);
        runtime.custody().deliverPendingKeys(gateway);
        runtime.custody().deliverPendingItems(gateway);
        runtime.complaints().claimPendingRewards(gateway);
        runtime.archive().deliverPending(gateway);
        runtime.rooms().assignAutomatically(gateway);
        runtime.rooms().processWaitlist();
    }

    /**
     * Baton strikes, cuffed-attacker combat lock and downed-target immunity.
     * Non-lethal baton hits are capped so the mechanic can never kill.
     */
    @SubscribeEvent
    public void onIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer target)) return;
        var targetGateway = new MinecraftPlayerGateway(target.getServer(), target.getUUID());
        // Downed targets cannot be harmed further.
        if (runtime.custody().isDowned(targetGateway)) {
            event.setCanceled(true);
            return;
        }
        var attackerEntity = event.getSource().getEntity();
        if (!(attackerEntity instanceof net.minecraft.server.level.ServerPlayer attacker)) return;
        var attackerGateway = new MinecraftPlayerGateway(attacker.getServer(), attacker.getUUID());
        // A restrained attacker cannot deal damage.
        if (runtime.custody().actionBlocked(attackerGateway, "combat")) {
            event.setCanceled(true);
            return;
        }
        var outcome = runtime.custody().batonStrike(attackerGateway, targetGateway,
                target.getHealth(), target.getAbsorptionAmount(), event.getAmount());
        switch (outcome.action()) {
            case CANCEL -> event.setCanceled(true);
            case ALLOW_NONLETHAL -> event.setAmount((float) runtime.custody()
                    .capBatonDamage(target.getHealth(), target.getAbsorptionAmount()));
            default -> {}
        }
    }

    /**
     * Right-clicking a player with a restraint tool routes to the custody
     * service: cuffs request, rope bind, head sack, key/cutters release.
     */
    @SubscribeEvent
    public void onEntityInteract(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getLevel().isClientSide()
                || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var gateway = new MinecraftPlayerGateway(player.getServer(), player.getUUID());
        if (runtime.custody().actionBlocked(gateway, "interact")) {
            event.setCanceled(true);
            return;
        }
        if (!(event.getTarget() instanceof net.minecraft.server.level.ServerPlayer target)) return;
        var targetGateway = new MinecraftPlayerGateway(target.getServer(), target.getUUID());
        String held = gateway.mainHand().id();
        if ("straja:fine_book".equals(held)) {
            if (runtime.fines().issueFromDraft(gateway, targetGateway)) event.setCanceled(true);
            return;
        }
        boolean handled = switch (held) {
            case com.dwurdy.straja.application.service.CustodyService.CUFFS ->
                    runtime.custody().requestCuffs(gateway, targetGateway);
            case com.dwurdy.straja.application.service.CustodyService.ROPE ->
                    runtime.custody().applyRope(gateway, targetGateway);
            case com.dwurdy.straja.application.service.CustodyService.HEAD_SACK ->
                    runtime.custody().applyHeadSack(gateway, targetGateway);
            case com.dwurdy.straja.application.service.CustodyService.CUFF_KEY,
                 com.dwurdy.straja.application.service.CustodyService.CROWBAR,
                 com.dwurdy.straja.application.service.CustodyService.BOLT_CUTTERS,
                 com.dwurdy.straja.application.service.CustodyService.KEYCHAIN ->
                    runtime.custody().release(gateway, targetGateway);
            default -> false;
        };
        if (handled) event.setCanceled(true);
    }

    /** Restrained players cannot use items, blocks or entities. */
    @SubscribeEvent
    public void onRightClickItem(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getLevel().isClientSide()
                || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var gateway = new MinecraftPlayerGateway(player.getServer(), player.getUUID());
        if (runtime.custody().actionBlocked(gateway, "item_use")) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onLeftClickBlock(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getLevel().isClientSide()
                || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var gateway = new MinecraftPlayerGateway(player.getServer(), player.getUUID());
        if (runtime.custody().actionBlocked(gateway, "interact")) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onRightClickBlock(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getLevel().isClientSide()
                || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var gateway = new MinecraftPlayerGateway(player.getServer(), player.getUUID());
        if (runtime.custody().actionBlocked(gateway, "interact")) {
            event.setCanceled(true);
            return;
        }
        if ("straja:room_marker".equals(gateway.mainHand().id())) {
            var pos = event.getPos();
            String dimension = player.level().dimension().location().toString();
            runtime.rooms().markerSelect(gateway, dimension, pos.getX(), pos.getY(), pos.getZ());
            event.setCanceled(true);
        }
    }

    /** Blocks inside a configured cell are protected from non-admin players. */
    @SubscribeEvent
    public void onBlockBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getLevel().isClientSide()
                || !(event.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var gateway = new MinecraftPlayerGateway(player.getServer(), player.getUUID());
        if (runtime.players().isCommissioner(gateway)) return;
        var pos = event.getPos();
        String dimension = player.level().dimension().location().toString();
        if (runtime.prison().insideCell(dimension, pos.getX(), pos.getY(), pos.getZ())) {
            event.setCanceled(true);
            gateway.tell("Celula este protejată. Doar adminii o pot modifica sau deschide.");
            return;
        }
        if (runtime.rooms().protectBlock(gateway, dimension, pos.getX(), pos.getY(), pos.getZ())) {
            event.setCanceled(true);
            return;
        }
        if (runtime.rooms().roomAtSign(dimension, pos.getX(), pos.getY(), pos.getZ()) != null) {
            event.setCanceled(true);
            gateway.tell("Semnul camerei este gestionat automat de Straja.");
        }
    }

    /** Hurting or killing the jailer NPC spawns a JAILER_ASSAULT arrest mission. */
    @SubscribeEvent
    public void onNpcDamage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getEntity().level().isClientSide()
                || !(event.getEntity() instanceof StrajaNpcEntity npc)
                || !"jailer".equals(npc.getRoleId())) return;
        if (!(event.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer attacker)) return;
        var gateway = new MinecraftPlayerGateway(attacker.getServer(), attacker.getUUID());
        String outcome = npc.getHealth() <= 0.5f ? "KILLED" : "WOUNDED";
        runtime.fines().createJailerAssaultMission(gateway, npc.getStringUUID(), outcome);
    }

    /**
     * Jailer death escalates the assault mission; a wanted suspect's death at a
     * guard's hand closes the arrest task with the reduced death bounty.
     */
    @SubscribeEvent
    public void onEntityDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getEntity().level().isClientSide()) return;
        if (!(event.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer attacker)) return;
        var gateway = new MinecraftPlayerGateway(attacker.getServer(), attacker.getUUID());
        if (event.getEntity() instanceof StrajaNpcEntity npc && "jailer".equals(npc.getRoleId())) {
            runtime.fines().createJailerAssaultMission(gateway, npc.getStringUUID(), "KILLED");
            return;
        }
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer victim) {
            var victimGateway = new MinecraftPlayerGateway(victim.getServer(), victim.getUUID());
            runtime.fines().suspectKilled(gateway, victimGateway);
        }
    }

    @SubscribeEvent
    public void onUseItem(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Start event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getEntity().level().isClientSide()
                || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var gateway = new MinecraftPlayerGateway(player.getServer(), player.getUUID());
        if (runtime.custody().actionBlocked(gateway, "item_use")) {
            event.setCanceled(true);
        }
    }
}
