package com.dwurdy.straja.bootstrap;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.adapter.out.npc.content.NpcContentProfileJsonLoader;
import com.dwurdy.straja.adapter.out.npc.customnpcs.CustomNpcsNpcSurfaceProvider;
import com.dwurdy.straja.application.port.in.NpcSurfaceActionTokenIssuer;
import com.dwurdy.straja.application.service.NpcContentCatalog;
import com.dwurdy.straja.application.service.NpcSurfaceActionService;
import com.dwurdy.straja.application.service.NpcSurfaceProviderRegistry;
import com.dwurdy.straja.domain.model.NpcActionRequest;
import com.dwurdy.straja.domain.model.NpcActionResult;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Server lifecycle composition root for provider-neutral NPC presentation.
 * Binding/content APIs are intentionally small so admin tooling can be added
 * without exposing provider objects to the application layer.
 */
public final class NpcPresentationRuntime {
    private static final AtomicReference<RuntimeState> STATE = new AtomicReference<>();

    private NpcPresentationRuntime() {}

    public static synchronized void start(MinecraftServer server) {
        if (STATE.get() != null) return;
        NpcSurfaceProviderRegistry providers = new NpcSurfaceProviderRegistry();
        NpcSurfaceActionService actions = new NpcSurfaceActionService(
                providers,
                (playerId, binding) -> playerAtBinding(server, playerId, binding),
                request -> dispatch(server, request));
        CustomNpcsNpcSurfaceProvider customNpcs = new CustomNpcsNpcSurfaceProvider(
                actions,
                actions,
                message -> StrajaMod.LOGGER.info("{}", message));
        providers.register(customNpcs);
        STATE.set(new RuntimeState(
                server,
                providers,
                actions,
                customNpcs,
                loadCatalog()));
        StrajaMod.LOGGER.info("NPC presentation runtime started; CustomNPCs available={}",
                customNpcs.available());
    }

    public static synchronized void stop() {
        STATE.set(null);
    }

    public static RuntimeState require() {
        RuntimeState state = STATE.get();
        if (state == null) throw new IllegalStateException("NPC presentation runtime is not started");
        return state;
    }

    /** Binds and publishes a canonical profile through the active provider. */
    public static NpcProviderResult bindAndPublish(NpcBinding binding) {
        RuntimeState state = require();
        NpcProviderResult bound = state.providers().bind(binding);
        if (bound.status() != NpcProviderResult.Status.ACCEPTED) return bound;
        NpcSurfaceSnapshot surface = state.catalog().require(binding.surfaceProfileId()).bind(binding);
        return state.providers().publish(surface);
    }

    private static NpcContentCatalog loadCatalog() {
        try (var stream = NpcPresentationRuntime.class.getClassLoader().getResourceAsStream(
                "data/straja/npc/straja.reception.admission.json")) {
            if (stream == null) {
                throw new IllegalStateException("default NPC profile resource is missing");
            }
            var loader = new NpcContentProfileJsonLoader();
            return new NpcContentCatalog(List.of(loader.load(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("NPC content catalog failed to load", exception);
        }
    }

    private static boolean playerAtBinding(MinecraftServer server, UUID playerId, NpcBinding binding) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return false;
        Entity host = findEntity(server, binding.hostEntityUuid());
        return host != null && host.level() == player.level()
                && host.distanceToSqr(player) <= 64.0D;
    }

    private static Entity findEntity(MinecraftServer server, String entityUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(entityUuid);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) return entity;
        }
        return null;
    }

    private static NpcActionResult dispatch(MinecraftServer server, NpcActionRequest request) {
        ServerPlayer player = server.getPlayerList().getPlayer(request.playerId());
        if (player == null) {
            return new NpcActionResult(
                    NpcActionResult.Status.UNAUTHORIZED, "player-offline", "player is not online");
        }
        boolean handled = com.dwurdy.straja.adapter.in.npc.NpcRoles.performAction(
                request.actionId().value(), player, player.serverLevel());
        return handled
                ? new NpcActionResult(NpcActionResult.Status.ACCEPTED, "dispatched", "action dispatched")
                : new NpcActionResult(
                        NpcActionResult.Status.REJECTED,
                        "action-rejected",
                        "the action is no longer available");
    }

    public record RuntimeState(
            MinecraftServer server,
            NpcSurfaceProviderRegistry providers,
            NpcSurfaceActionTokenIssuer actions,
            CustomNpcsNpcSurfaceProvider customNpcs,
            NpcContentCatalog catalog) {}
}
