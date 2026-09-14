package com.dwurdy.straja.adapter.out.client;

import com.dwurdy.straja.adapter.in.network.CustodyVisualPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * Client-only visual projection for custody state.
 *
 * <p>The server sends the mode and the client only renders it. This class
 * deliberately contains no interaction, movement, damage, or release logic;
 * those remain server-authoritative.</p>
 */
public final class StrajaClientVisuals {
    private final Map<UUID, CustodyVisualPayload> states = new ConcurrentHashMap<>();
    private final Map<Integer, CustodyVisualPayload> transformedPlayers = new ConcurrentHashMap<>();

    public void accept(CustodyVisualPayload payload) {
        if (payload == null || payload.playerId() == null) return;
        if (payload.mode() == CustodyVisualPayload.NORMAL
                && payload.restraint() == CustodyVisualPayload.NO_RESTRAINT
                && !payload.blindfolded()) {
            states.remove(payload.playerId());
        } else {
            states.put(payload.playerId(), payload);
        }
    }

    @SubscribeEvent
    public void onPlayerPre(RenderPlayerEvent.Pre event) {
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) return;
        CustodyVisualPayload state = states.get(player.getUUID());
        if (state == null) return;

        if (state.mode() == CustodyVisualPayload.FAINT
                || state.mode() == CustodyVisualPayload.CARRIED) {
            PoseStack pose = event.getPoseStack();
            pose.pushPose();
            if (state.mode() == CustodyVisualPayload.FAINT) {
                rotateAroundBody(pose, 90.0F, 0.35D);
            } else {
                // The passenger relationship supplies the carry position; this
                // small tilt keeps the carried body visibly distinct without
                // allowing the faint pose to win over the carry projection.
                rotateAroundBody(pose, 12.0F, 0.8D);
                pose.scale(0.95F, 0.95F, 0.95F);
            }
            transformedPlayers.put(player.getId(), state);
        }
    }

    @SubscribeEvent
    public void onPlayerPost(RenderPlayerEvent.Post event) {
        CustodyVisualPayload state = states.get(event.getEntity().getUUID());
        if (state != null && state.restraint() != CustodyVisualPayload.NO_RESTRAINT) {
            renderRestraint(event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(), state);
        }
        if (transformedPlayers.remove(event.getEntity().getId()) != null) {
            event.getPoseStack().popPose();
        }
    }

    @SubscribeEvent
    public void onGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        CustodyVisualPayload state = states.get(minecraft.player.getUUID());
        if (state == null || !state.blindfolded()) return;

        GuiGraphics graphics = event.getGuiGraphics();
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0xE8000000);
    }

    private static void rotateAroundBody(PoseStack pose, float degrees, double pivotY) {
        pose.translate(0.0D, pivotY, 0.0D);
        pose.mulPose(Axis.ZP.rotationDegrees(degrees));
        pose.translate(0.0D, -pivotY, 0.0D);
    }

    private static void renderRestraint(
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            CustodyVisualPayload state) {
        VertexConsumer line = buffers.getBuffer(RenderType.lines());
        PoseStack.Pose last = pose.last();
        int red = state.restraint() == CustodyVisualPayload.ROPE ? 125 : 220;
        int green = state.restraint() == CustodyVisualPayload.ROPE ? 75 : 180;
        int blue = state.restraint() == CustodyVisualPayload.ROPE ? 35 : 45;

        // Two bands at wrist height communicate the restraint from either
        // camera angle while remaining independent of model/skin geometry.
        line(line, last, -0.34F, 1.12F, -0.16F, 0.34F, 1.12F, -0.16F,
                red, green, blue, 255, packedLight);
        line(line, last, -0.34F, 1.02F, 0.12F, 0.34F, 1.02F, 0.12F,
                red, green, blue, 255, packedLight);
    }

    private static void line(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            int red,
            int green,
            int blue,
            int alpha,
            int packedLight) {
        consumer.addVertex(pose, x1, y1, z1)
                .setColor(red, green, blue, alpha)
                .setLight(packedLight)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
        consumer.addVertex(pose, x2, y2, z2)
                .setColor(red, green, blue, alpha)
                .setLight(packedLight)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }
}
