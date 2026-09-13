package com.dwurdy.straja;

import com.dwurdy.straja.adapter.in.command.StrajaCommands;
import com.dwurdy.straja.adapter.in.event.StrajaEvents;
import com.dwurdy.straja.adapter.in.form.FormPayloads;
import com.dwurdy.straja.adapter.in.npc.StrajaNpcEntity;
import com.dwurdy.straja.bootstrap.StrajaItems;
import com.dwurdy.straja.bootstrap.StrajaMenus;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.dwurdy.straja.config.StrajaServerConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root: wires NeoForge adapters to the domain/application ports.
 * All business rules live in application services; this class only registers.
 */
@Mod(StrajaMod.MOD_ID)
public class StrajaMod {
    public static final String MOD_ID = "straja";
    public static final Logger LOGGER = LoggerFactory.getLogger("Straja");

    public StrajaMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, StrajaServerConfig.SPEC);
        StrajaItems.register(modBus);
        StrajaMenus.register(modBus);
        StrajaNpcEntity.register(modBus);
        modBus.addListener(RegisterPayloadHandlersEvent.class, FormPayloads::register);
        NeoForge.EVENT_BUS.addListener(StrajaCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.register(new StrajaEvents());
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,
                event -> StrajaRuntime.start(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class,
                event -> StrajaRuntime.stop());
        LOGGER.info("Straja mod initialized");
    }
}
