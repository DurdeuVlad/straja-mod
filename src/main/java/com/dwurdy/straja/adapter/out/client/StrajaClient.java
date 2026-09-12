package com.dwurdy.straja.adapter.out.client;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.adapter.in.npc.StrajaNpcEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client-only registrations: NPC renderer and screens. */
@Mod(value = StrajaMod.MOD_ID, dist = Dist.CLIENT)
public class StrajaClient {
    public StrajaClient(IEventBus modBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class,
                event -> event.registerEntityRenderer(
                        StrajaNpcEntity.NPC.get(), StrajaNpcRenderer::new));
    }
}
