package com.dwurdy.straja.adapter.in.event;

import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.dwurdy.straja.domain.model.Rank;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Player-facing rank labels sourced from authoritative Straja state, not teams. */
public final class StrajaNameEvents {
    @SubscribeEvent
    public void onNameFormat(PlayerEvent.NameFormat event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) return;
        var state = runtime.players().state(event.getEntity().getUUID());
        if (!state.authorized()) return;
        event.setDisplayname(Component.literal("[" + Rank.of(state.rank).displayName() + "] ")
                .append(event.getUsername().copy()));
    }

    @SubscribeEvent
    public void onTabName(PlayerEvent.TabListNameFormat event) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null || !(event.getEntity() instanceof ServerPlayer player)) return;
        var state = runtime.players().state(player.getUUID());
        if (!state.authorized()) return;
        event.setDisplayName(Component.literal("[" + Rank.of(state.rank).displayName() + "] ")
                .append(Component.literal(player.getGameProfile().getName())));
    }
}
