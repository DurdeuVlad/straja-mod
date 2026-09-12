package com.dwurdy.straja.adapter.out.minecraft;

import com.dwurdy.straja.application.port.out.WorldGateway;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.network.chat.Component;

/** Minecraft world → WorldGateway (bounded block reads + sign placement). */
public class MinecraftWorldGateway implements WorldGateway {
    private final MinecraftServer server;

    public MinecraftWorldGateway(MinecraftServer server) {
        this.server = server;
    }

    private ServerLevel level(String dimension) {
        if (dimension == null || dimension.isBlank()) return server.overworld();
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        if (id == null) return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    @Override public BlockInfo blockAt(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return null;
        var pos = new BlockPos(x, y, z);
        var state = level.getBlockState(pos);
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        boolean air = state.isAir();
        boolean door = state.getBlock() instanceof DoorBlock;
        boolean solid = state.isSolidRender(level, pos);
        return new BlockInfo(id, air, door, solid);
    }

    @Override public boolean setRoomSign(String dimension, int x, int y, int z, String facing,
                                       String line1, String line2, String line3) {
        ServerLevel level = level(dimension);
        if (level == null) return false;
        try {
            BlockPos pos = new BlockPos(x, y, z);
            var state = Blocks.OAK_WALL_SIGN.defaultBlockState();
            DirectionProperty prop = BlockStateProperties.HORIZONTAL_FACING;
            var direction = switch (facing == null ? "north" : facing.toLowerCase()) {
                case "east" -> net.minecraft.core.Direction.EAST;
                case "south" -> net.minecraft.core.Direction.SOUTH;
                case "west" -> net.minecraft.core.Direction.WEST;
                default -> net.minecraft.core.Direction.NORTH;
            };
            state = state.setValue(prop, direction);
            level.setBlock(pos, state, 3);
            var entity = level.getBlockEntity(pos);
            if (entity instanceof SignBlockEntity sign) {
                SignText text = new SignText()
                        .setMessage(0, Component.literal(line1))
                        .setMessage(1, Component.literal(line2))
                        .setMessage(2, Component.literal(line3));
                sign.setText(text, true);
            }
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }
}
