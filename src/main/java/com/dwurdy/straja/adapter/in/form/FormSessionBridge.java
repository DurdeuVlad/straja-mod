package com.dwurdy.straja.adapter.in.form;

import com.dwurdy.straja.application.port.in.FormSessionUseCase;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

/**
 * Adapter bridge between the NeoForge menu/payload transport and the inbound
 * form-session port. Configured once at bootstrap; depends only on the port.
 */
public final class FormSessionBridge {
    private static volatile FormSessionUseCase sessions;

    private FormSessionBridge() {}

    public static void install(FormSessionUseCase useCase) {
        sessions = useCase;
    }

    public static void clear() {
        sessions = null;
    }

    public static void open(ServerPlayer player, FormSessionUseCase.Request request) {
        FormSessionUseCase port = sessions;
        if (player == null || port == null) return;
        Optional<FormSessionUseCase.View> view = port.open(player.getUUID(), request);
        if (view.isEmpty()) return;
        OptionalInt opened = player.openMenu(new SimpleMenuProvider(
                        (id, inv, p) -> new StrajaFormMenu(id, inv, view.get()),
                        Component.literal(view.get().title())),
                buf -> StrajaFormMenu.writeView(buf, view.get()));
        if (opened.isEmpty()) {
            port.cancel(player.getUUID(), view.get().sessionId());
        }
    }

    public static Optional<FormSessionUseCase.Submission> consume(
            UUID owner, String sessionId, Map<String, String> values) {
        FormSessionUseCase port = sessions;
        if (port == null) return Optional.empty();
        return port.submit(owner, sessionId, values);
    }

    public static boolean cancelSession(UUID owner, String sessionId) {
        FormSessionUseCase port = sessions;
        return port != null && port.cancel(owner, sessionId);
    }
}
