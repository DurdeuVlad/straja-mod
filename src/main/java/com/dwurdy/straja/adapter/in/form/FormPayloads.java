package com.dwurdy.straja.adapter.in.form;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.application.port.in.FormSessionUseCase.Submission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Serverbound form payloads: the client sends only the opaque session id plus
 * bounded field values — never identity, authorization, or record state.
 * Field text is never logged.
 */
public final class FormPayloads {
    private static final int MAX_SESSION_ID = 128;
    private static final int MAX_FIELDS = 4;
    private static final int MAX_FIELD_ID = 32;
    private static final int MAX_VALUE = 2_000;

    private static volatile BiConsumer<ServerPlayer, Submission> submissionConsumer =
            (player, submission) -> {};

    private FormPayloads() {}

    public static void setSubmissionConsumer(BiConsumer<ServerPlayer, Submission> consumer) {
        submissionConsumer = consumer == null ? (player, submission) -> {} : consumer;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(Submit.TYPE, Submit.CODEC, FormPayloads::handleSubmit);
        registrar.playToServer(Cancel.TYPE, Cancel.CODEC, FormPayloads::handleCancel);
    }

    public record Submit(String sessionId, Map<String, String> values)
            implements CustomPacketPayload {
        public static final Type<Submit> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(StrajaMod.MOD_ID, "form_submit"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Submit> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeUtf(payload.sessionId(), MAX_SESSION_ID);
                    buf.writeVarInt(payload.values().size());
                    for (Map.Entry<String, String> entry : payload.values().entrySet()) {
                        buf.writeUtf(entry.getKey(), MAX_FIELD_ID);
                        buf.writeUtf(entry.getValue(), MAX_VALUE);
                    }
                },
                buf -> {
                    String sessionId = buf.readUtf(MAX_SESSION_ID);
                    int count = buf.readVarInt();
                    if (count < 0 || count > MAX_FIELDS) {
                        throw new IllegalArgumentException(
                                "form field count out of bounds: " + count);
                    }
                    Map<String, String> values = new LinkedHashMap<>();
                    for (int i = 0; i < count; i++) {
                        values.put(buf.readUtf(MAX_FIELD_ID), buf.readUtf(MAX_VALUE));
                    }
                    return new Submit(sessionId, values);
                });

        @Override
        public Type<Submit> type() {
            return TYPE;
        }
    }

    public record Cancel(String sessionId) implements CustomPacketPayload {
        public static final Type<Cancel> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(StrajaMod.MOD_ID, "form_cancel"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Cancel> CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeUtf(payload.sessionId(), MAX_SESSION_ID),
                buf -> new Cancel(buf.readUtf(MAX_SESSION_ID)));

        @Override
        public Type<Cancel> type() {
            return TYPE;
        }
    }

    private static void handleSubmit(Submit payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var submission = FormSessionBridge.consume(
                player.getUUID(), payload.sessionId(), payload.values());
        submission.ifPresent(s -> {
            submissionConsumer.accept(player, s);
            player.closeContainer();
        });
    }

    private static void handleCancel(Cancel payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        FormSessionBridge.cancelSession(player.getUUID(), payload.sessionId());
    }
}
