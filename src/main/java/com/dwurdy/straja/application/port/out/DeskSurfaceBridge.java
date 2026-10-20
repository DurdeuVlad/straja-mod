package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.NpcActionResult;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.UUID;

/**
 * The seam a desk session uses to render and dispatch a provider-bound NPC
 * surface — implemented by the {@code desk-menu} provider under
 * {@code adapter.out.npc.chestmenu} so the session never references the
 * provider implementation. {@link #resolve} returns the player-projected
 * published snapshot (null once the surface is retracted); {@link #submit}
 * mints a fresh provider action token and submits it through
 * {@code NpcSurfaceActionService} — the same five-minute-capable contract
 * the CustomNPCs GuiCustom path uses, never the 15-second chat tokens.
 */
public interface DeskSurfaceBridge {
    NpcSurfaceSnapshot resolve(UUID playerId, NpcBinding binding);

    NpcActionResult submit(UUID playerId, NpcBinding binding, String actionId);
}
