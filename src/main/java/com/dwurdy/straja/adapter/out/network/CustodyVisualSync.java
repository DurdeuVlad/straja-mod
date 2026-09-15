package com.dwurdy.straja.adapter.out.network;

import com.dwurdy.straja.adapter.in.network.CustodyVisualPayload;
import com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase.VisualState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Sends only changed visual projections and clears departed player state. */
public final class CustodyVisualSync {
    private static final Map<UUID, CustodyVisualPayload> lastBroadcast = new HashMap<>();

    private CustodyVisualSync() {}

    public static synchronized void reset() {
        lastBroadcast.clear();
    }

    public static synchronized void sync(List<VisualState> states) {
        var current = new HashMap<UUID, CustodyVisualPayload>();
        for (var state : states) {
            if (state == null || state.playerId() == null || state.mode() == null) continue;
            var payload = CustodyVisualPayload.from(state);
            current.put(state.playerId(), payload);
            if (!payload.equals(lastBroadcast.get(state.playerId()))) {
                broadcast(payload);
            }
        }
        for (UUID departed : lastBroadcast.keySet()) {
            if (!current.containsKey(departed)) {
                broadcast(new CustodyVisualPayload(
                        departed,
                        CustodyVisualPayload.NORMAL,
                        CustodyVisualPayload.NO_RESTRAINT,
                        false));
            }
        }
        lastBroadcast.clear();
        lastBroadcast.putAll(current);
    }

    public static void syncToPlayer(ServerPlayer player, List<VisualState> states) {
        if (player == null) return;
        if (!NetworkRegistry.hasChannel(player.connection, CustodyVisualPayload.TYPE.id())) return;
        for (var state : states) {
            if (state != null && state.playerId() != null && state.mode() != null) {
                PacketDistributor.sendToPlayer(player, CustodyVisualPayload.from(state));
            }
        }
    }

    /**
     * Broadcasts only to connections that negotiated the payload. Players
     * without a modded channel (vanilla clients, headless/mock players) get
     * skipped — sending to them throws rather than degrading gracefully.
     */
    private static void broadcast(CustodyVisualPayload payload) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (NetworkRegistry.hasChannel(player.connection, payload.type().id())) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }
}
