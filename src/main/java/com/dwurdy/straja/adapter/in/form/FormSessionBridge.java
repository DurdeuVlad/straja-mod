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
    private static final String TRANSLATION_KEY_PREFIX = "\u001f";
    private static volatile FormSessionUseCase sessions;

    private FormSessionBridge() {}

    public static void install(FormSessionUseCase useCase) {
        sessions = useCase;
    }

    public static void clear() {
        sessions = null;
    }

    /** Encodes a client-resolved translation key in the existing string form protocol. */
    public static String translationKey(String key) {
        if (key == null || key.isBlank() || key.contains(TRANSLATION_KEY_PREFIX)) {
            throw new IllegalArgumentException("invalid form translation key");
        }
        return TRANSLATION_KEY_PREFIX + key;
    }

    /** Resolves encoded form text while retaining literal text for existing forms. */
    public static Component component(String text) {
        if (text != null && text.startsWith(TRANSLATION_KEY_PREFIX)) {
            return Component.translatable(text.substring(TRANSLATION_KEY_PREFIX.length()));
        }
        return Component.literal(text == null ? "" : text);
    }

    public static Optional<FormSessionUseCase.View> open(
            ServerPlayer player, FormSessionUseCase.Request request) {
        FormSessionUseCase port = sessions;
        if (player == null || port == null) return Optional.empty();
        Optional<FormSessionUseCase.View> view = port.open(player.getUUID(), request);
        if (view.isEmpty()) return Optional.empty();
        OptionalInt opened = player.openMenu(new SimpleMenuProvider(
                        (id, inv, p) -> new StrajaFormMenu(id, inv, view.get()),
                        component(view.get().title())),
                buf -> StrajaFormMenu.writeView(buf, view.get()));
        if (opened.isEmpty()) {
            port.cancel(player.getUUID(), view.get().sessionId());
            return Optional.empty();
        }
        return view;
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
