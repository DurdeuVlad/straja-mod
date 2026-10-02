package com.dwurdy.straja.adapter.in;

import net.minecraft.network.chat.Component;

/** Shared player-facing text components for inbound adapters. */
public final class StrajaText {
    private StrajaText() {
    }

    /** Refusal component matching {@code PlayerGateway.refuse}: reason + concrete remedy. */
    public static Component refusal(String reasonKey, String remedyKey, Object... reasonArgs) {
        return Component.translatable("straja.refusal.format",
                Component.translatable(reasonKey, reasonArgs),
                Component.translatable(remedyKey));
    }
}
