package com.dwurdy.straja.adapter.in.crafting;

import com.dwurdy.straja.bootstrap.StrajaIngredients;
import com.dwurdy.straja.bootstrap.StrajaItems;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;

/**
 * Smithing-template ingredient for #247 document forging: matches only a
 * stack that carries genuine registry/issuance data — a real exemplar the
 * forger has held. A blank {@code identity_card} item never matches: the
 * physical enforcement of "you can't fake a document class you've never
 * held". The {@code kind} field pins the document class per recipe.
 */
public record ExemplarIngredient(String kind) implements ICustomIngredient {
    /** Data key each document class carries when genuinely issued/registered. */
    private static final Map<String, String> KIND_KEYS = Map.of(
            "card", "IdentityCardId",
            "document", "DocumentId",
            "instrument", "InstrumentId");

    public static final MapCodec<ExemplarIngredient> CODEC = RecordCodecBuilder.mapCodec(
            group -> group.group(
                    com.mojang.serialization.Codec.STRING
                            .optionalFieldOf("kind", "any")
                            .forGetter(ExemplarIngredient::kind))
                    .apply(group, ExemplarIngredient::new));

    public static boolean hasExemplarData(ItemStack stack, String key) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        String value = data.copyTag().getString(key);
        return value != null && !value.isBlank();
    }

    @Override
    public boolean test(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        String key = switch (kind) {
            case "card" -> "straja:identity_card".equals(id) ? KIND_KEYS.get("card") : null;
            case "document" -> "straja:official_document".equals(id) ? KIND_KEYS.get("document") : null;
            case "instrument" -> "straja:official_instrument".equals(id) ? KIND_KEYS.get("instrument") : null;
            default -> switch (id) {
                case "straja:identity_card" -> KIND_KEYS.get("card");
                case "straja:official_document" -> KIND_KEYS.get("document");
                case "straja:official_instrument" -> KIND_KEYS.get("instrument");
                default -> null;
            };
        };
        // An artifact-serial-stamped item of the right class also counts:
        // a registered exemplar is the gold standard a forger studies.
        if (key == null) return false;
        var runtime = com.dwurdy.straja.bootstrap.StrajaRuntime.get();
        boolean required = runtime == null
                || runtime.policies() == null
                || runtime.policies().forgeryExemplarRequired;
        if (!required) return true; // template accepts the doc class outright
        return hasExemplarData(stack, key) || hasExemplarData(stack, "ArtifactSerial");
    }

    @Override
    public Stream<ItemStack> getItems() {
        // Recipe-book display hints only — actual matching is data-dependent.
        return switch (kind) {
            case "card" -> Stream.of(new ItemStack(StrajaItems.IDENTITY_CARD.get()));
            case "document" -> Stream.of(new ItemStack(StrajaItems.OFFICIAL_DOCUMENT.get()));
            case "instrument" -> Stream.of(new ItemStack(StrajaItems.OFFICIAL_INSTRUMENT.get()));
            default -> Stream.of(
                    new ItemStack(StrajaItems.IDENTITY_CARD.get()),
                    new ItemStack(StrajaItems.OFFICIAL_DOCUMENT.get()),
                    new ItemStack(StrajaItems.OFFICIAL_INSTRUMENT.get()));
        };
    }

    @Override
    public boolean isSimple() {
        // Data-dependent: must always be stack-tested.
        return false;
    }

    @Override
    public IngredientType<?> getType() {
        return StrajaIngredients.EXEMPLAR.get();
    }
}
