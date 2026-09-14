package com.dwurdy.straja.adapter.in.network;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase.VisualState;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-to-client custody visual state. It is intentionally a tiny render
 * projection: the client cannot use it to authorize movement, damage,
 * release, or custody actions.
 */
public record CustodyVisualPayload(UUID playerId, byte mode, byte restraint, boolean blindfolded)
        implements CustomPacketPayload {
    public static final byte NORMAL = 0;
    public static final byte FAINT = 1;
    public static final byte CARRIED = 2;
    public static final byte RESTRAINED = 3;
    public static final byte NO_RESTRAINT = 0;
    public static final byte ROPE = 1;
    public static final byte CUFFS = 2;

    public static final Type<CustodyVisualPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StrajaMod.MOD_ID, "custody_visual"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CustodyVisualPayload> CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeUUID(payload.playerId());
                        buf.writeByte(payload.mode());
                        buf.writeByte(payload.restraint());
                        buf.writeBoolean(payload.blindfolded());
                    },
                    buf -> new CustodyVisualPayload(
                            buf.readUUID(), buf.readByte(), buf.readByte(), buf.readBoolean()));

    private static volatile Consumer<CustodyVisualPayload> clientConsumer = payload -> {};

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, CODEC, CustodyVisualPayload::handle);
    }

    public static void setClientConsumer(Consumer<CustodyVisualPayload> consumer) {
        clientConsumer = consumer == null ? payload -> {} : consumer;
    }

    public static CustodyVisualPayload from(VisualState state) {
        byte mode = switch (state.mode()) {
            case NORMAL -> NORMAL;
            case FAINT -> FAINT;
            case CARRIED -> CARRIED;
            case RESTRAINED -> RESTRAINED;
        };
        byte restraint = switch (state.restraint()) {
            case NONE -> NO_RESTRAINT;
            case ROPE -> ROPE;
            case CUFFS -> CUFFS;
        };
        return new CustodyVisualPayload(state.playerId(), mode, restraint, state.blindfolded());
    }

    @Override
    public Type<CustodyVisualPayload> type() {
        return TYPE;
    }

    private static void handle(CustodyVisualPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> clientConsumer.accept(payload));
    }
}
