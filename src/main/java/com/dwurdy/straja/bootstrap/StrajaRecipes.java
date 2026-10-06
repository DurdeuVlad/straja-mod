package com.dwurdy.straja.bootstrap;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.adapter.in.crafting.SmithingForgeRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

/** Recipe types/serializers for #247 physical forging. */
public final class StrajaRecipes {
    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, StrajaMod.MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, StrajaMod.MOD_ID);

    public static final Supplier<RecipeType<SmithingForgeRecipe>> SMITHING_FORGE_TYPE =
            RECIPE_TYPES.register("smithing_forge",
                    () -> RecipeType.simple(ResourceLocation.fromNamespaceAndPath(
                            StrajaMod.MOD_ID, "smithing_forge")));
    public static final Supplier<RecipeSerializer<SmithingForgeRecipe>> SMITHING_FORGE_SERIALIZER =
            RECIPE_SERIALIZERS.register("smithing_forge", SmithingForgeRecipe.Serializer::new);

    private StrajaRecipes() {}

    public static void register(IEventBus bus) {
        RECIPE_TYPES.register(bus);
        RECIPE_SERIALIZERS.register(bus);
    }
}
