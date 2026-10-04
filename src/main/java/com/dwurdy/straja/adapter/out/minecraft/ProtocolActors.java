package com.dwurdy.straja.adapter.out.minecraft;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.application.service.ProtocolService;
import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * #242 protocol suspect — a Carpet-style joined fake player. A plain
 * {@code FakePlayer} is never in the player list, so {@code isOnline()} and
 * every custody path that resolves through it would treat the suspect as
 * offline; joining it via {@code placeNewPlayer} makes it a real,
 * cuffable, arrestable, escortable player from the domain's point of view.
 *
 * <p>The uuid is deterministic per tester ({@code nameUUIDFromBytes} of the
 * tester uuid) so a second spawn re-uses the same suspect identity instead
 * of stacking fakes.
 */
public final class ProtocolActors implements ProtocolService.ActorSpawner {
    private final MinecraftServer server;

    public ProtocolActors(MinecraftServer server) { this.server = server; }

    @Override
    public UUID spawn(PlayerGateway anchor, String name) {
        UUID uuid = UUID.nameUUIDFromBytes(
                ("straja:protocol:" + anchor.uuid()).getBytes(StandardCharsets.UTF_8));
        ResourceLocation dim = ResourceLocation.tryParse(anchor.dimension());
        ServerLevel level = dim == null ? server.overworld()
                : server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
        if (level == null) level = server.overworld();

        ServerPlayer fake = server.getPlayerList().getPlayer(uuid);
        if (fake == null) {
            GameProfile profile = new GameProfile(uuid, name);
            fake = FakePlayerFactory.get(level, profile);
            // placeNewPlayer installs a real ServerGamePacketListenerImpl whose
            // keepalive would kick a packetless fake within ~30s — and the
            // FakePlayerNetHandler the constructor set is bound to NeoForge's
            // channel-less FakeConnection, so per-tick broadcasts that read
            // connection.channel() would NPE on it. Join on a SilentConnection
            // (in-memory EmbeddedChannel — channel() valid, writes absorbed),
            // then swap in a SilentNetHandler bound to it: sends no-op and
            // tick is suppressed so no keepalive ever runs.
            SilentConnection connection = new SilentConnection();
            server.getPlayerList().placeNewPlayer(connection, fake,
                    CommonListenerCookie.createInitial(profile, false));
            fake.connection = new SilentNetHandler(server, connection, fake);
        }
        fake.teleportTo(level, anchor.x() + 1.5, anchor.y(), anchor.z() + 1.5,
                java.util.Set.of(), 0.0f, 0.0f);
        return uuid;
    }

    @Override
    public void dismiss(UUID actorUuid) {
        ServerPlayer fake = server.getPlayerList().getPlayer(actorUuid);
        if (fake == null) return;
        server.getPlayerList().remove(fake);
        fake.discard();
    }

    /** Carpet-style connection: a real Netty {@code EmbeddedChannel} is
     *  attached so {@code channel()} is non-null — vanilla's join path and
     *  NeoForge's datapack sync touch channel attributes directly — while
     *  sends and protocol setup stay no-ops (writes die in the channel's
     *  in-memory buffer). {@code channelActive} sets the private field
     *  itself when the handler registers; the fallback covers it if not. */
    private static final class SilentConnection extends net.minecraft.network.Connection {
        SilentConnection() {
            super(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            io.netty.channel.embedded.EmbeddedChannel channel =
                    new io.netty.channel.embedded.EmbeddedChannel(this);
            if (channel() == null) channel.pipeline().fireChannelActive();
        }
        @Override public <T extends net.minecraft.network.PacketListener> void
                setupInboundProtocol(net.minecraft.network.ProtocolInfo<T> info, T listener) {}
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet,
                                 net.minecraft.network.PacketSendListener listener) {}
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet,
                                 net.minecraft.network.PacketSendListener listener,
                                 boolean flush) {}
    }

    /** A real {@code ServerGamePacketListenerImpl} bound to the
     *  {@code SilentConnection} — so anything reading
     *  {@code player.connection.getConnection().channel()} (NeoForge
     *  {@code hasChannel} checks, custody sync broadcasts) sees a valid
     *  channel — but {@code tick} is suppressed (no keepalive timeout) and
     *  sends stay swallowed. {@code onDisconnect} is inherited, so kicking
     *  or dismissing the suspect still runs real leave bookkeeping. */
    private static final class SilentNetHandler
            extends net.minecraft.server.network.ServerGamePacketListenerImpl {
        SilentNetHandler(MinecraftServer server,
                         net.minecraft.network.Connection connection,
                         ServerPlayer player) {
            super(server, connection, player,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }
        @Override public void tick() {}
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet,
                                 net.minecraft.network.PacketSendListener listener) {}
    }
}
