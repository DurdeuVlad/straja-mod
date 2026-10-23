package com.dwurdy.straja.adapter.in.menu;

import com.dwurdy.straja.StrajaMod;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Desk GUI wire payloads. {@code desk_page} (server→client) replaces the
 * rendered page — the desk's {@code broadcastChanges} equivalent, since a
 * slotless menu has nothing to sync. {@code desk_click} (client→server)
 * carries only the session id, the page sequence the click saw, and the
 * widget slot — never an action id, label, or authorization data, so a forged
 * packet can only ever trigger a button the server itself rendered.
 */
public final class DeskPayloads {
    private static volatile Consumer<Page> clientPageConsumer = page -> {};

    private DeskPayloads() {}

    /** StrajaClient installs the screen-side consumer at client bootstrap. */
    public static void setClientPageConsumer(Consumer<Page> consumer) {
        clientPageConsumer = consumer == null ? page -> {} : consumer;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(Page.TYPE, Page.CODEC, DeskPayloads::handlePage);
        registrar.playToServer(Click.TYPE, Click.CODEC, DeskPayloads::handleClick);
    }

    /** A freshly rendered page pushed to the open screen. */
    public record Page(DeskWire.View view) implements CustomPacketPayload {
        public static final Type<Page> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(StrajaMod.MOD_ID, "desk_page"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Page> CODEC =
                StreamCodec.of(
                        (buf, payload) -> DeskWire.write(
                                DeskGuiMenu.adaptFor(buf), payload.view()),
                        buf -> new Page(DeskWire.read(DeskGuiMenu.adaptFor(buf))));

        @Override
        public Type<Page> type() {
            return TYPE;
        }
    }

    /** One widget activation: the slot inside the page the client rendered. */
    public record Click(String sessionId, int seq, int slot)
            implements CustomPacketPayload {
        public static final Type<Click> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(StrajaMod.MOD_ID, "desk_click"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Click> CODEC =
                StreamCodec.of(
                        (buf, payload) -> {
                            buf.writeUtf(payload.sessionId(), DeskWire.MAX_SESSION_ID);
                            buf.writeVarInt(payload.seq());
                            buf.writeVarInt(payload.slot());
                        },
                        buf -> {
                            String sessionId = buf.readUtf(DeskWire.MAX_SESSION_ID);
                            int seq = buf.readVarInt();
                            int slot = buf.readVarInt();
                            DeskWire.checkClickBounds(seq, slot);
                            return new Click(sessionId, seq, slot);
                        });

        @Override
        public Type<Click> type() {
            return TYPE;
        }
    }

    private static void handlePage(Page payload, IPayloadContext context) {
        context.enqueueWork(() -> clientPageConsumer.accept(payload));
    }

    private static void handleClick(Click payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (player.containerMenu instanceof DeskGuiMenu menu) {
            menu.acceptClick(payload.sessionId(), payload.seq(), payload.slot());
        }
    }
}
