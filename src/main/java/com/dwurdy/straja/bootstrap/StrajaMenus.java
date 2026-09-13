package com.dwurdy.straja.bootstrap;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.adapter.in.form.StrajaFormMenu;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Native Straja menu types. */
public final class StrajaMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, StrajaMod.MOD_ID);

    public static final Supplier<MenuType<StrajaFormMenu>> FORM =
            MENUS.register("form", () -> IMenuTypeExtension.create(StrajaFormMenu::client));

    private StrajaMenus() {}

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }
}
