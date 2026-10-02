package com.dwurdy.straja.adapter.out.npc.customnpcs;

import com.dwurdy.straja.application.port.out.NpcGuardGateway;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/**
 * CustomNPCs implementation of {@link NpcGuardGateway}. Reflection-only: the
 * mod must keep loading when CustomNPCs is absent, so every cross-boundary
 * type is resolved lazily and failures degrade to "unavailable".
 *
 * <p>Trust boundary: all mutations run through server-side console commands
 * ({@code noppes faction}, {@code noppes quest}) or direct entity fields.
 * Nothing NPC-scripted is trusted as authority.</p>
 */
public final class CustomNpcsGuardGateway implements NpcGuardGateway {
    private final MinecraftServer server;
    private final Class<?> npcClass;       // noppes.npcs.entity.EntityCustomNpc, may be null
    private final Class<?> playerWrapper;  // noppes.npcs.api.wrapper.PlayerWrapper, may be null

    private CustomNpcsGuardGateway(MinecraftServer server, Class<?> npcClass, Class<?> playerWrapper) {
        this.server = server;
        this.npcClass = npcClass;
        this.playerWrapper = playerWrapper;
    }

    /** Builds the gateway when CustomNPCs is on the classpath; otherwise a disabled port. */
    public static NpcGuardGateway create(MinecraftServer server) {
        try {
            Class<?> npc = Class.forName("noppes.npcs.entity.EntityCustomNpc");
            Class<?> wrapper;
            try {
                wrapper = Class.forName("noppes.npcs.api.wrapper.PlayerWrapper");
            } catch (ClassNotFoundException e) {
                wrapper = null;
            }
            return new CustomNpcsGuardGateway(server, npc, wrapper);
        } catch (Throwable t) {
            return NpcGuardGateway.disabled();
        }
    }

    @Override public boolean available() {
        return npcClass != null;
    }

    private ServerPlayer player(UUID id) {
        return id == null ? null : server.getPlayerList().getPlayer(id);
    }

    private Entity entity(UUID id) {
        if (id == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    private void run(String command) {
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    private static String quoted(String name) {
        return '"' + name.replace("\"", "") + '"';
    }

    @Override public Integer factionPoints(UUID playerId, int factionId) {
        ServerPlayer sp = player(playerId);
        if (sp == null || playerWrapper == null) return null;
        try {
            Object wrapper = playerWrapper.getConstructor(ServerPlayer.class).newInstance(sp);
            Object points = playerWrapper.getMethod("getFactionPoints", int.class).invoke(wrapper, factionId);
            return points instanceof Number n ? n.intValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public void setFactionPoints(UUID playerId, int factionId, int points) {
        ServerPlayer sp = player(playerId);
        if (sp == null) return;
        run("noppes faction " + quoted(sp.getGameProfile().getName()) + " " + factionId + " set " + points);
    }

    @Override public void startQuestForTeam(String teamName, int questId) {
        run("noppes quest start @a[team=" + teamName + "] " + questId);
    }

    @Override public void startQuestForPlayer(UUID playerId, int questId) {
        ServerPlayer sp = player(playerId);
        if (sp == null) return;
        run("noppes quest start " + quoted(sp.getGameProfile().getName()) + " " + questId);
    }

    @Override public void finishQuestForTeam(String teamName, int questId) {
        run("noppes quest finish @a[team=" + teamName + "] " + questId);
    }

    @Override public List<GuardRef> guardsNear(String dimension, double x, double y, double z,
            double radius, int factionId) {
        List<GuardRef> found = new ArrayList<>();
        if (npcClass == null) return found;
        ServerLevel level = null;
        for (ServerLevel l : server.getAllLevels()) {
            if (l.dimension().location().toString().equals(dimension)) level = l;
        }
        if (level == null) return found;
        var box = new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        for (Entity e : level.getEntitiesOfClass(Entity.class, box, npcClass::isInstance)) {
            if (e instanceof LivingEntity living && living.isDeadOrDying()) continue;
            if (factionOf(e) != factionId) continue;
            found.add(new GuardRef(e.getUUID(), e.getX(), e.getY(), e.getZ()));
        }
        return found;
    }

    private static int factionOf(Entity npc) {
        try {
            Field f = npc.getClass().getField("faction");
            Object faction = f.get(npc);
            if (faction == null) return -1;
            Object id = faction.getClass().getField("id").get(faction);
            return id instanceof Number n ? n.intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private Entity npc(UUID id) {
        Entity e = entity(id);
        return e != null && npcClass != null && npcClass.isInstance(e) ? e : null;
    }

    @Override public boolean hasLineOfSight(UUID guardId, UUID playerId) {
        Entity npc = npc(guardId);
        ServerPlayer sp = player(playerId);
        if (!(npc instanceof LivingEntity living) || sp == null) return false;
        return living.hasLineOfSight(sp);
    }

    @Override public int aggroRange(UUID guardId, int fallback) {
        Entity npc = npc(guardId);
        if (npc == null) return fallback;
        try {
            Object stats = npc.getClass().getField("stats").get(npc);
            if (stats == null) return fallback;
            Object range = stats.getClass().getField("aggroRange").get(stats);
            return range instanceof Number n && n.intValue() > 0 ? n.intValue() : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    @Override public void setTarget(UUID guardId, UUID playerId) {
        Entity npc = npc(guardId);
        ServerPlayer sp = player(playerId);
        if (!(npc instanceof Mob mob) || sp == null) return;
        if (mob.getTarget() != sp) mob.setTarget(sp);
    }

    @Override public void clearTargetIfTargeting(UUID guardId, UUID playerId) {
        Entity npc = npc(guardId);
        ServerPlayer sp = player(playerId);
        if (!(npc instanceof Mob mob) || sp == null) return;
        if (mob.getTarget() == sp) mob.setTarget(null);
    }
}
