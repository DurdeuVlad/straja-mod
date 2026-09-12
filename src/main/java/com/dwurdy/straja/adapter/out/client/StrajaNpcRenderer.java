package com.dwurdy.straja.adapter.out.client;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.adapter.in.npc.StrajaNpcEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Humanoid NPC renderer. The skin field is resolved to
 * {@code straja:textures/entity/npc/<skin>.png}; a namespaced value containing
 * ':' is treated as a full texture path so resource packs can supply skins.
 * Client-only: never referenced from common code.
 */
public class StrajaNpcRenderer extends HumanoidMobRenderer<StrajaNpcEntity, PlayerModel<StrajaNpcEntity>> {
    private static final ResourceLocation FALLBACK =
            ResourceLocation.fromNamespaceAndPath(StrajaMod.MOD_ID, "textures/entity/npc/default.png");

    public StrajaNpcRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(StrajaNpcEntity entity) {
        String skin = entity.getSkin();
        if (skin == null || skin.isBlank() || "default".equals(skin)) return FALLBACK;
        if (skin.contains(":")) {
            ResourceLocation custom = ResourceLocation.tryParse(skin);
            return custom != null ? custom : FALLBACK;
        }
        return ResourceLocation.fromNamespaceAndPath(
                StrajaMod.MOD_ID, "textures/entity/npc/" + skin + ".png");
    }
}
