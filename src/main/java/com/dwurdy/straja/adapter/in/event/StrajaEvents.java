package com.dwurdy.straja.adapter.in.event;

import com.dwurdy.straja.adapter.in.npc.NpcRoles;
import com.dwurdy.straja.adapter.in.npc.StrajaNpcEntity;
import com.dwurdy.straja.adapter.in.item.AdminToolSurface;
import com.dwurdy.straja.adapter.in.item.PhysicalItemSurface;
import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.dwurdy.straja.domain.model.Capability;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;


/**
 * NeoForge event adapter: server lifecycle, per-tick duty timers, and
 * login-time restart recovery. No business rules live here — everything is
 * delegated to application services.
 */
public final class StrajaEvents {
    /**
     * gameTime of the last admin-tool click handled per player. A non-consuming
     * block or entity interact makes the client send an item-use packet right
     * after — that follow-up must not fire the air gesture (patrol finish,
     * template clear) on top of the action that was just handled.
     */
    private final java.util.Map<java.util.UUID, Long> toolClickHandledAt = new java.util.HashMap<>();

    public StrajaEvents() {}

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) return;
        runtime.serverGateway().tick();
        runtime.missionRoleplay().tick();
        runtime.custodyRoleplay().tick();
        runtime.prisonRoleplay().tick();
        runtime.fineRoleplay().tick();
        if (runtime.serverGateway().tickCount() % 20 != 0) return;
        runtime.formSessions().purgeExpired();
        com.dwurdy.straja.adapter.in.npc.NpcInteractionService.purgeExpiredTokens();
        for (var player : runtime.serverGateway().onlinePlayers()) {
            runtime.guardDuty().tickPlayerDuty(player);
        }
        for (var sp : event.getServer().getPlayerList().getPlayers()) {
            refreshRankNameplate(runtime, sp);
        }
        if (runtime.policies().testCommandsEnabled && runtime.policies().isLocalEnvironment()) {
            for (var virtual : runtime.testPlayers().all()) {
                runtime.guardDuty().tickPlayerDuty(virtual);
            }
        }
    }

    /** §4: bracketed rank prefix on chat sender names. */
    @SubscribeEvent
    public void onNameFormat(PlayerEvent.NameFormat event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || !runtime.policies().rankPrefixChat) return;
        var gateway = new MinecraftPlayerGateway(event.getEntity().getServer(), event.getEntity().getUUID());
        String prefix = runtime.playerQueries().rankPrefixFor(gateway);
        if (prefix != null) {
            event.setDisplayname(Component.literal(prefix + " ").append(event.getDisplayname()));
        }
    }

    /** §4: bracketed rank prefix in the TAB player list. */
    @SubscribeEvent
    public void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || !runtime.policies().rankPrefixTab) return;
        var gateway = new MinecraftPlayerGateway(event.getEntity().getServer(), event.getEntity().getUUID());
        String prefix = runtime.playerQueries().rankPrefixFor(gateway);
        if (prefix != null) {
            event.setDisplayName(Component.literal(prefix + " ").append(event.getDisplayName()));
        }
    }

    /**
     * §4 nameplate surface (opt-in): applies the "[Rank] Name" custom name and
     * clears it only while it is ours — a foreign custom name is left alone.
     * Runs once a second per online player.
     */
    private void refreshRankNameplate(StrajaRuntime runtime, net.minecraft.server.level.ServerPlayer sp) {
        String profile = sp.getGameProfile().getName();
        var current = sp.getCustomName();
        String owned = current != null ? current.getString() : null;
        boolean ours = owned != null && owned.startsWith("[") && owned.endsWith(" " + profile);
        if (!runtime.policies().rankPrefixNameplate) {
            if (ours) { sp.setCustomName(null); sp.setCustomNameVisible(false); }
            return;
        }
        var gateway = new MinecraftPlayerGateway(sp.getServer(), sp.getUUID());
        String prefix = runtime.playerQueries().rankPrefixFor(gateway);
        String expected = prefix != null ? prefix + " " + profile : null;
        if (expected == null) {
            if (ours) { sp.setCustomName(null); sp.setCustomNameVisible(false); }
            return;
        }
        if (!expected.equals(owned)) sp.setCustomName(Component.literal(expected));
        if (!sp.isCustomNameVisible()) sp.setCustomNameVisible(true);
    }

    /** Re-applies registry name/skin when a persisted NPC entity loads. */
    @SubscribeEvent
    public void onNpcJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !(event.getEntity() instanceof StrajaNpcEntity npc)) return;
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) return;
        var registration = runtime.npcRegistry().registration(npc.getStringUUID());
        if (registration == null) {
            // Re-adopt persisted entities that outlived the registry; the port
            // rejects unknown roles rather than creating junk records.
            runtime.npcRegistry().adopt(npc.getStringUUID(), npc.getRoleId());
            return;
        }
        if (registration.role() != null && !registration.role().isEmpty()) {
            npc.setRoleId(registration.role());
        }
        if (registration.skin() != null && !registration.skin().isEmpty()) {
            npc.setSkin(registration.skin());
        }
        if (registration.displayName() != null && !registration.displayName().isEmpty()) {
            npc.setCustomName(Component.literal(registration.displayName()));
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var gateway = new MinecraftPlayerGateway(event.getEntity().getServer(), player.getUUID());
        runtime.guardDuty().recoverOnLogin(gateway);
        runtime.custodyRoleplay().recoverOnLogin(gateway);
        runtime.prisonRoleplay().recoverOnLogin(gateway);
        runtime.complaintRoleplay().claimPendingRewards(gateway);
        runtime.missionRoleplay().deliverPendingRewards(gateway);
        runtime.fineRoleplay().recoverOnLogin(gateway);
        runtime.archiveRoleplay().deliverPending(gateway);
        runtime.emergencyRoleplay().deliverUrgency(gateway);
        runtime.roomRoleplay().assignAutomatically(gateway);
        runtime.roomRoleplay().processWaitlist();
        runtime.audienceRoleplay().deliverOutcome(gateway);
        String setupHint = runtime.playerQueries().setupHintFor(gateway);
        if (setupHint != null) {
            gateway.tell("[Straja] Configurarea este incompletă. " + setupHint
                    + " Rulează /straja setup pentru checklist-ul complet.");
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var gateway = new MinecraftPlayerGateway(event.getEntity().getServer(), player.getUUID());
        runtime.custodyRoleplay().recoverOnLogout(gateway);
        // Pending admin-tool state (routes, corners, templates) never survives logout.
        runtime.adminTools().clearState(gateway);
        toolClickHandledAt.remove(player.getUUID());
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
        if (runtime.custodyRoleplay().isDowned(targetGateway)) {
            event.setCanceled(true);
            return;
        }
        var attackerEntity = event.getSource().getEntity();
        if (!(attackerEntity instanceof net.minecraft.server.level.ServerPlayer attacker)) return;
        var attackerGateway = new MinecraftPlayerGateway(attacker.getServer(), attacker.getUUID());
        // A restrained attacker cannot deal damage.
        if (runtime.custodyRoleplay().actionBlocked(attackerGateway, "combat")) {
            event.setCanceled(true);
            return;
        }
        var outcome = runtime.custodyRoleplay().batonStrike(attackerGateway, targetGateway,
                target.getHealth(), target.getAbsorptionAmount(), event.getAmount());
        switch (outcome.action()) {
            case CANCEL -> event.setCanceled(true);
            case ALLOW_NONLETHAL -> event.setAmount((float) runtime.custodyRoleplay()
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
        if (runtime.custodyRoleplay().actionBlocked(gateway, "interact")) {
            event.setCanceled(true);
            return;
        }
        // Admin tools claim the entity click before any roleplay routing: the
        // wand opens the NPC menu, the cloner captures the template. A
        // non-Straja target (or wrong holder) is denied inside the service.
        // The interact packet fires once per hand — claim the offhand
        // follow-up too so a second menu or the roleplay surface cannot leak.
        var heldTool = AdminToolSurface.tool(gateway.mainHand());
        if (heldTool == AdminToolSurface.Tool.NPC_WAND
                || heldTool == AdminToolSurface.Tool.NPC_CLONER) {
            event.setCanceled(true);
            if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
                // The item-use packet trailing this interact must not also
                // fire the air gesture (patrol finish / template clear).
                toolClickHandledAt.put(player.getUUID(), player.level().getGameTime());
                String targetUuid = event.getTarget().getStringUUID();
                if (heldTool == AdminToolSurface.Tool.NPC_WAND) {
                    NpcRoles.sendToolMenu(player,
                            runtime.adminTools().npcWandMenu(gateway, targetUuid));
                } else {
                    runtime.adminTools().cloneCapture(gateway, targetUuid);
                }
            }
            return;
        }
        if (!(event.getTarget() instanceof net.minecraft.server.level.ServerPlayer target)) return;
        var targetGateway = new MinecraftPlayerGateway(target.getServer(), target.getUUID());
        String held = gateway.mainHand().id();
        if ("straja:order_book".equals(held) || "straja:mission_carnet".equals(held)) {
            if (runtime.missionRoleplay().issueDraft(gateway, targetGateway)) event.setCanceled(true);
            return;
        }
        if ("straja:fine_book".equals(held)) {
            if (runtime.fineRoleplay().issueFromDraft(gateway, targetGateway)) event.setCanceled(true);
            return;
        }
        boolean handled = switch (held) {
            case "straja:cuffs" -> runtime.custodyRoleplay().requestCuffs(gateway, targetGateway);
            case "straja:rope" -> runtime.custodyRoleplay().applyRope(gateway, targetGateway);
            case "straja:head_sack" -> runtime.custodyRoleplay().applyHeadSack(gateway, targetGateway);
            case "straja:cuff_key", "straja:crowbar", "straja:bolt_cutters", "straja:keychain" ->
                    runtime.custodyRoleplay().release(gateway, targetGateway);
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
        if (runtime.custodyRoleplay().actionBlocked(gateway, "item_use")) event.setCanceled(true);
        else if (useAdminTool(runtime, player, gateway, event.getHand())) event.setCanceled(true);
        else if (usePhysicalItem(runtime, gateway)) event.setCanceled(true);
    }

    /**
     * Air clicks with admin tools: sneak + click finishes the patrol route or
     * clears the cloner template; a plain patrol-wand click reports progress.
     * Main hand only — the offhand follow-up packet must not fire it twice.
     */
    private boolean useAdminTool(StrajaRuntime runtime,
                                 net.minecraft.server.level.ServerPlayer player,
                                 PlayerGateway gateway,
                                 net.minecraft.world.InteractionHand hand) {
        if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) return false;
        // The item-use packet also trails every non-consuming block/entity
        // click with the tool — that follow-up is not an air click.
        Long handledAt = toolClickHandledAt.get(player.getUUID());
        if (handledAt != null && player.level().getGameTime() - handledAt <= 1) return false;
        return switch (AdminToolSurface.tool(gateway.mainHand())) {
            case PATROL_WAND -> {
                if (player.isShiftKeyDown()) runtime.adminTools().patrolFinish(gateway);
                else runtime.adminTools().patrolStatus(gateway);
                yield true;
            }
            case NPC_CLONER -> {
                if (!player.isShiftKeyDown()) yield false;
                runtime.adminTools().cloneClear(gateway);
                yield true;
            }
            default -> false;
        };
    }

    @SubscribeEvent
    public void onLeftClickBlock(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getLevel().isClientSide()
                || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var gateway = new MinecraftPlayerGateway(player.getServer(), player.getUUID());
        if (runtime.custodyRoleplay().actionBlocked(gateway, "interact")) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onRightClickBlock(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getLevel().isClientSide()
                || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var gateway = new MinecraftPlayerGateway(player.getServer(), player.getUUID());
        if (runtime.custodyRoleplay().actionBlocked(gateway, "interact")) {
            event.setCanceled(true);
            return;
        }
        var pos = event.getPos();
        String dimension = player.level().dimension().location().toString();
        if (runtime.roomRoleplay().protectBlock(gateway, dimension, pos.getX(), pos.getY(), pos.getZ())) {
            event.setCanceled(true);
            return;
        }
        // The block-interact packet fires once per hand; routing the main-hand
        // packet only keeps a single click from toggling a waypoint on and
        // straight back off, while an item in the offhand still works.
        if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
            var tool = AdminToolSurface.tool(gateway.mainHand());
            switch (tool) {
            case PRISON_MARKER -> {
                // Two-click corner selection; once both corners exist the
                // confirm token registers the cell through the normal path.
                if (runtime.adminTools().cellClick(gateway, dimension,
                        pos.getX(), pos.getY(), pos.getZ())) {
                    NpcRoles.sendToolPrompt(player,
                            "[Straja] Celula este delimitată.",
                            "Confirmă înregistrarea celulei", "tool-cell-confirm");
                }
            }
            case PATROL_WAND -> runtime.adminTools().patrolClick(gateway, dimension,
                    pos.getX(), pos.getY(), pos.getZ());
            case SURVEY_ROD -> NpcRoles.sendToolMenu(player, runtime.adminTools().surveyMenu(
                    gateway, dimension, pos.getX(), pos.getY(), pos.getZ()));
            case NPC_CLONER -> {
                // Click a block face: spawn a registered copy of the captured
                // template one block off the face, like /straja npc spawn.
                var spawnPos = event.getFace() == null ? pos.above() : pos.relative(event.getFace());
                var template = runtime.adminTools().cloneSpawnAt(gateway, dimension,
                        spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
                if (template != null) {
                    var entity = StrajaNpcEntity.spawn(player.serverLevel(),
                            spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                            template.role(), template.skin(), template.displayName());
                    runtime.adminTools().registerClone(gateway, entity.getStringUUID());
                }
            }
            case ROOM_MARKER ->
                    runtime.roomRoleplay().markerSelect(gateway, dimension,
                            pos.getX(), pos.getY(), pos.getZ());
            default -> {}
            }
            if (tool != AdminToolSurface.Tool.NONE) {
                // The client sends an item-use packet after any non-consuming
                // block result — the air gesture must not double-fire here.
                toolClickHandledAt.put(player.getUUID(), player.level().getGameTime());
                event.setCanceled(true);
                return;
            }
        }
        if (usePhysicalItem(runtime, gateway)) event.setCanceled(true);
    }

    /**
     * Routes only the already-registered paper items to safe player-facing
     * service reads.  Item custom data is untrusted input: record IDs are
     * bounded before they reach a service, which then performs the persisted
     * existence and access checks.
     */
    private boolean usePhysicalItem(StrajaRuntime runtime, PlayerGateway player) {
        ItemView item = player.mainHand();
        switch (PhysicalItemSurface.action(item)) {
            case MISSION_CARNET -> {
                // A delivered order is visible through the normal mission
                // projection; an issuer's reusable carnet shows its own draft.
                if (PhysicalItemSurface.validRecordId(item.data("StrajaMissionId")) != null) {
                    runtime.missionRoleplay().list(player);
                } else if (runtime.playerQueries().isCommissioner(player)
                        || runtime.playerQueries().hasCapability(player, Capability.CREATE_MISSIONS)) {
                    runtime.missionRoleplay().draftStatus(player);
                } else {
                    runtime.missionRoleplay().list(player);
                }
                return true;
            }
            case ARCHIVE_FOLDER -> {
                String folderId = PhysicalItemSurface.validRecordId(item.data("ArchiveFolderId"));
                if (folderId == null) runtime.archiveRoleplay().listFolders(player);
                else runtime.archiveRoleplay().readFolder(player, folderId);
                return true;
            }
            case ARCHIVE_DOCUMENT -> {
                String documentId = PhysicalItemSurface.firstValidRecordId(
                        item.data("ArchiveDocumentId"), item.data("ArchiveCopyOf"));
                if (documentId == null) {
                    player.tell("Documentul arhivei nu are o referință validă.");
                } else {
                    runtime.archiveRoleplay().readSheet(player, documentId);
                }
                return true;
            }
            case ARCHIVE_TOOL -> {
                // Carbon paper, the archive stamp and official envelopes are
                // consumables/tools: they never act on item metadata. The
                // archivist's projected actions appear while carrying them.
                player.tell("Folosește acest obiect la Arhivistă — acțiunile apar în funcție de ce porți la tine.");
                return true;
            }
            case FINE_BOOK -> {
                if (runtime.playerQueries().hasCapability(player, Capability.ISSUE_FINES)) {
                    player.tell(runtime.fineRoleplay().draftText(player));
                } else {
                    player.tell("Registrul de Amenzi este rezervat Străjii active.");
                }
                return true;
            }
            case FINE_NOTICE -> {
                // listFines filters to the holder's own fines or commissioner
                // visibility; the notice ID is intentionally not trusted as a
                // payment authorization.
                runtime.fineRoleplay().listFines(player);
                return true;
            }
            case TRAINING_MANUAL -> {
                runtime.guardDuty().showRules(player);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Occupied room blocks cannot be modified by placed blocks. */
    @SubscribeEvent
    public void onBlockPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getLevel().isClientSide()
                || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var gateway = new MinecraftPlayerGateway(player.getServer(), player.getUUID());
        var pos = event.getPos();
        String dimension = player.level().dimension().location().toString();
        if (!runtime.playerQueries().isCommissioner(gateway)
                && runtime.roomRoleplay().protectBlock(gateway, dimension, pos.getX(), pos.getY(), pos.getZ())) {
            event.setCanceled(true);
        }
    }

    /** Explosions cannot destroy occupied room blocks or their managed signs. */
    @SubscribeEvent
    public void onExplosion(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getLevel().isClientSide()) return;
        String dimension = event.getLevel().dimension().location().toString();
        event.getAffectedBlocks().removeIf(pos -> runtime.roomRoleplay().isProtectedBlock(
                dimension, pos.getX(), pos.getY(), pos.getZ()));
    }

    /** Blocks inside a configured cell are protected from non-admin players. */
    @SubscribeEvent
    public void onBlockBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getLevel().isClientSide()
                || !(event.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var gateway = new MinecraftPlayerGateway(player.getServer(), player.getUUID());
        if (runtime.playerQueries().isCommissioner(gateway)) return;
        var pos = event.getPos();
        String dimension = player.level().dimension().location().toString();
        if (runtime.prisonRoleplay().insideCell(dimension, pos.getX(), pos.getY(), pos.getZ())) {
            event.setCanceled(true);
            gateway.tell("Celula este protejată. Doar adminii o pot modifica sau deschide.");
            return;
        }
        if (runtime.roomRoleplay().protectBlock(gateway, dimension, pos.getX(), pos.getY(), pos.getZ())) {
            event.setCanceled(true);
            return;
        }
        if (runtime.roomRoleplay().roomAtSign(dimension, pos.getX(), pos.getY(), pos.getZ()) != null) {
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
        runtime.fineRoleplay().createJailerAssaultMission(gateway, npc.getStringUUID(), outcome);
    }

    /**
     * Jailer death escalates the assault mission; a wanted suspect's death at a
     * guard's hand closes the arrest task with the reduced death bounty.
     */
    @SubscribeEvent
    public void onEntityDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getEntity().level().isClientSide()) return;
        // Custody always recovers a dying player's restraint state first.
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer victim) {
            var victimGateway = new MinecraftPlayerGateway(victim.getServer(), victim.getUUID());
            runtime.custodyRoleplay().recoverAfterDeath(victimGateway);
        }
        if (!(event.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer attacker)) return;
        var gateway = new MinecraftPlayerGateway(attacker.getServer(), attacker.getUUID());
        if (event.getEntity() instanceof StrajaNpcEntity npc && "jailer".equals(npc.getRoleId())) {
            runtime.fineRoleplay().createJailerAssaultMission(gateway, npc.getStringUUID(), "KILLED");
            return;
        }
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer victim) {
            var victimGateway = new MinecraftPlayerGateway(victim.getServer(), victim.getUUID());
            runtime.fineRoleplay().suspectKilled(gateway, victimGateway);
        }
    }

    @SubscribeEvent
    public void onUseItem(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Start event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || event.getEntity().level().isClientSide()
                || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var gateway = new MinecraftPlayerGateway(player.getServer(), player.getUUID());
        if (runtime.custodyRoleplay().actionBlocked(gateway, "item_use")) {
            event.setCanceled(true);
        }
    }
}
