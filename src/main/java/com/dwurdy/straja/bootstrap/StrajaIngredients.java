package com.dwurdy.straja.bootstrap;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.adapter.in.crafting.ExemplarIngredient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.crafting.IngredientType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import java.util.function.Supplier;

/** Custom ingredient types for #247 physical forging recipes. */
public final class StrajaIngredients {
    public static final DeferredRegister<IngredientType<?>> INGREDIENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.INGREDIENT_TYPES, StrajaMod.MOD_ID);

    /** Smithing template slot: only a genuine data-carrying document matches. */
    public static final Supplier<IngredientType<ExemplarIngredient>> EXEMPLAR =
            INGREDIENT_TYPES.register("exemplar",
                    () -> new IngredientType<>(ExemplarIngredient.CODEC));

    private StrajaIngredients() {}

    public static void register(IEventBus bus) {
        INGREDIENT_TYPES.register(bus);
    }
}
