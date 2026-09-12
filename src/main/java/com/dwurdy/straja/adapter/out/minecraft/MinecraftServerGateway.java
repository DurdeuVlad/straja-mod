package com.dwurdy.straja.adapter.out.minecraft;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.application.port.out.ServerGateway;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** MinecraftServer → ServerGateway. */
public class MinecraftServerGateway implements ServerGateway {
    private final MinecraftServer server;
    private long tickCount;

    public MinecraftServerGateway(MinecraftServer server) {
        this.server = server;
    }

    @Override public List<PlayerGateway> onlinePlayers() {
        List<PlayerGateway> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            players.add(new MinecraftPlayerGateway(server, player.getUUID()));
        }
        return players;
    }

    @Override public PlayerGateway findPlayer(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) return null;
        String wanted = nameOrUuid.trim();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(wanted)
                    || player.getUUID().toString().equalsIgnoreCase(wanted)) {
                return new MinecraftPlayerGateway(server, player.getUUID());
            }
        }
        return null;
    }

    @Override public long tickCount() { return tickCount; }

    /** Called once per server tick by the event adapter. */
    public void tick() {
        tickCount++;
    }
}
