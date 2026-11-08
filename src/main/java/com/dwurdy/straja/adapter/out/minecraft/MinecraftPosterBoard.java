package com.dwurdy.straja.adapter.out.minecraft;

import com.dwurdy.straja.application.port.out.PosterBoardGateway;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * POSTER-1: the physical poster surface — an ItemDisplay wearing the
 * wanted head and a TextDisplay captioning name + reward, both center
 * billboards so the poster reads from any angle. 1.21.1 display entities
 * are NBT-driven (setters private), so each render clears the pair and
 * respawns it from a crafted tag — safe because renders only fire on face
 * changes while the spot is unobserved. Entities carry a {@code strajaPoster}
 * persistent-data tag so restarts/chunk reloads are adopted, never duped.
 */
public final class MinecraftPosterBoard implements PosterBoardGateway {

    private static final String TAG = "strajaPoster";
    private static final double CLEAR_RADIUS = 2.5;
    private static final double TEXT_OFFSET = 0.62;

    private final MinecraftServer server;

    public MinecraftPosterBoard(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void renderFace(String spotId, String dimension, double x, double y,
                           double z, String targetUuid, String targetName, int reward) {
        var level = level(dimension);
        if (level == null) {
            com.mojang.logging.LogUtils.getLogger().error(
                    "[Straja] poster {} skipped — no level for dimension '{}'",
                    spotId, dimension);
            return;
        }
        clear(level, spotId, x, y, z);
        spawn(level, headTag(level, targetUuid, targetName), x, y, z, spotId);
        spawn(level, textTag(level, Component.literal("§c§lCĂUTAT\n§f" + targetName
                + "\n§eRecompensă: " + reward + " B")),
                x, y - TEXT_OFFSET, z, spotId);
    }

    @Override
    public void renderIdle(String spotId, String dimension,
                           double x, double y, double z) {
        var level = level(dimension);
        if (level == null) return;
        clear(level, spotId, x, y, z);
        spawn(level, headTag(level, null, null), x, y, z, spotId);
        spawn(level, textTag(level, Component.literal("§7Nimic de căutat")),
                x, y - TEXT_OFFSET, z, spotId);
    }

    @Override
    public void remove(String spotId, String dimension, double x, double y, double z) {
        var level = level(dimension);
        if (level == null) return;
        clear(level, spotId, x, y, z);
    }

    @Override
    public int watchersNear(String dimension, double x, double y,
                            double z, double radius) {
        var level = level(dimension);
        if (level == null) return 0;
        return level.getEntitiesOfClass(ServerPlayer.class,
                AABB.ofSize(new Vec3(x, y, z), radius * 2, radius * 2, radius * 2),
                Player::isAlive).size();
    }

    private ServerLevel level(String dimension) {
        if (dimension == null || dimension.isBlank()) return server.overworld();
        for (var level : server.getAllLevels()) {
            if (dimension.equals(level.dimension().location().toString())) return level;
        }
        return null;
    }

    private void clear(ServerLevel level, String spotId, double x, double y, double z) {
        level.getEntitiesOfClass(Display.class,
                AABB.ofSize(new Vec3(x, y, z),
                        CLEAR_RADIUS * 2, CLEAR_RADIUS * 2, CLEAR_RADIUS * 2),
                e -> spotId.equals(e.getPersistentData().getString(TAG)))
                .forEach(Display::discard);
    }

    /** Spawn a display entity from its NBT — the vanilla summon path. */
    private void spawn(ServerLevel level, CompoundTag tag,
                       double x, double y, double z, String spotId) {
        try {
            var type = EntityType.byString(tag.getString("id"))
                    .map(t -> t.create(level)).orElse(null);
            if (type == null) {
                com.mojang.logging.LogUtils.getLogger().error(
                        "[Straja] poster spawn failed: unknown display type {}",
                        tag.getString("id"));
                return;
            }
            type.load(tag);
            type.moveTo(x, y, z, type.getYRot(), type.getXRot());
            type.getPersistentData().putString(TAG, spotId);
            if (!level.addFreshEntity(type)) {
                com.mojang.logging.LogUtils.getLogger().error(
                        "[Straja] poster spawn rejected by level at {},{},{}",
                        x, y, z);
            }
        } catch (RuntimeException | Error ex) {
            com.mojang.logging.LogUtils.getLogger().error(
                    "[Straja] poster spawn failed at {},{},{}", x, y, z, ex);
        }
    }

    private static CompoundTag baseTag(String entityId) {
        var tag = new CompoundTag();
        tag.putString("id", entityId);
        tag.putString(Display.TAG_BILLBOARD, "center");
        return tag;
    }

    /**
     * The wanted head — the profile cache carries skins for anyone who
     * logged in this session; an unresolvable profile still posts the
     * name + reward, the face just stays anonymous.
     */
    private static CompoundTag headTag(ServerLevel level,
                                       String uuidStr, String name) {
        var stack = new ItemStack(Items.PLAYER_HEAD);
        var profile = profileFor(level, uuidStr, name);
        profile.ifPresent(p -> stack.set(DataComponents.PROFILE, new ResolvableProfile(p)));
        var tag = baseTag("minecraft:item_display");
        tag.put("item", stack.save(level.registryAccess()));
        return tag;
    }

    /**
     * The wanted face — an online player resolves live off the player list;
     * a logged-out target falls back to the profile cache, then the name.
     * An unresolvable profile still posts name + reward, face anonymous.
     */
    private static Optional<GameProfile> profileFor(ServerLevel level,
                                                    String uuidStr, String name) {
        if (uuidStr != null && !uuidStr.isBlank()) {
            try {
                var uuid = UUID.fromString(uuidStr);
                var online = level.getServer().getPlayerList().getPlayer(uuid);
                if (online != null) return Optional.of(online.getGameProfile());
                var cache = level.getServer().getProfileCache();
                if (cache != null) {
                    var byUuid = cache.get(uuid);
                    if (byUuid.isPresent()) return byUuid;
                }
            } catch (IllegalArgumentException ignored) {}
        }
        var cache = level.getServer().getProfileCache();
        if (cache != null && name != null && !name.isBlank()) return cache.get(name);
        return Optional.empty();
    }

    private static CompoundTag textTag(ServerLevel level, Component text) {
        var tag = baseTag("minecraft:text_display");
        tag.putString(Display.TextDisplay.TAG_TEXT,
                Component.Serializer.toJson(text, level.registryAccess()));
        // The loader decodes "alignment" unconditionally — an absent key
        // logs a "Display entityNot a string" codec error on every spawn.
        tag.putString("alignment", "center");
        return tag;
    }
}
