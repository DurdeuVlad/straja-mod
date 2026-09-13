package com.dwurdy.straja.adapter.out.client;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.adapter.in.npc.StrajaNpcEntity;
import com.dwurdy.straja.bootstrap.StrajaMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only registrations: NPC renderer, screens, and the config editor GUI. */
@Mod(value = StrajaMod.MOD_ID, dist = Dist.CLIENT)
public class StrajaClient {
    public StrajaClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class,
                event -> event.registerEntityRenderer(
                        StrajaNpcEntity.NPC.get(), StrajaNpcRenderer::new));
        modBus.addListener(RegisterMenuScreensEvent.class,
                event -> event.register(StrajaMenus.FORM.get(), StrajaFormScreen::new));
        // Exposes straja-server.toml through the mod list "Config" button.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
