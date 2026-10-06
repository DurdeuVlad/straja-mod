package com.dwurdy.straja.adapter.in.crafting;

import com.dwurdy.straja.bootstrap.StrajaRecipes;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;

/**
 * #247 — forge recipe type. Behaves like {@code smithing_transform} but
 * stamps the produced stack with a {@code StrajaForgePending} marker plus a
 * serialized snapshot of the exemplar (template slot). The roll itself is
 * deferred to the take — the marker is what lets the take-hook tell "this
 * stack is a fresh forge result" apart from any other copy of the item,
 * and it survives every delivery path (normal take, shift-click, hopper).
 * The exemplar snapshot is refunded when the pending marker resolves, so a
 * genuine reference inspires unlimited copies on every take path.
 */
public class SmithingForgeRecipe extends SmithingTransformRecipe {
    public static final String PENDING_KEY = "StrajaForgePending";
    public static final String EXEMPLAR_KEY = "StrajaForgeExemplar";

    /** Own field copies — the parent's are package-private. */
    private final Ingredient templateIngredient;
    private final Ingredient baseIngredient;
    private final Ingredient additionIngredient;
    private final ItemStack resultStack;

    public SmithingForgeRecipe(Ingredient template, Ingredient base,
                               Ingredient addition, ItemStack result) {
        super(template, base, addition, result);
        this.templateIngredient = template;
        this.baseIngredient = base;
        this.additionIngredient = addition;
        this.resultStack = result;
    }

    @Override
    public ItemStack assemble(SmithingRecipeInput input, HolderLookup.Provider registries) {
        ItemStack out = super.assemble(input, registries);
        ItemStack exemplar = input.template();
        CustomData.update(DataComponents.CUSTOM_DATA, out, tag -> {
            tag.putString(PENDING_KEY, "1");
            if (!exemplar.isEmpty()) {
                net.minecraft.nbt.Tag saved = exemplar.save(registries, new CompoundTag());
                tag.put(EXEMPLAR_KEY, saved);
            }
        });
        return out;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return StrajaRecipes.SMITHING_FORGE_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return StrajaRecipes.SMITHING_FORGE_TYPE.get();
    }

    public static class Serializer implements RecipeSerializer<SmithingForgeRecipe> {
        private static final MapCodec<SmithingForgeRecipe> CODEC = RecordCodecBuilder.mapCodec(
                group -> group.group(
                        Ingredient.CODEC.fieldOf("template").forGetter(r -> r.templateIngredient),
                        Ingredient.CODEC.fieldOf("base").forGetter(r -> r.baseIngredient),
                        Ingredient.CODEC.fieldOf("addition").forGetter(r -> r.additionIngredient),
                        ItemStack.STRICT_CODEC.fieldOf("result").forGetter(r -> r.resultStack))
                        .apply(group, SmithingForgeRecipe::new));
        private static final StreamCodec<RegistryFriendlyByteBuf, SmithingForgeRecipe> STREAM_CODEC =
                StreamCodec.of(Serializer::toNetwork, Serializer::fromNetwork);

        @Override public MapCodec<SmithingForgeRecipe> codec() { return CODEC; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, SmithingForgeRecipe> streamCodec() {
            return STREAM_CODEC;
        }

        private static SmithingForgeRecipe fromNetwork(RegistryFriendlyByteBuf buffer) {
            Ingredient template = Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);
            Ingredient base = Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);
            Ingredient addition = Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);
            ItemStack result = ItemStack.STREAM_CODEC.decode(buffer);
            return new SmithingForgeRecipe(template, base, addition, result);
        }

        private static void toNetwork(RegistryFriendlyByteBuf buffer, SmithingForgeRecipe recipe) {
            Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.templateIngredient);
            Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.baseIngredient);
            Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.additionIngredient);
            ItemStack.STREAM_CODEC.encode(buffer, recipe.resultStack);
        }
    }
}
