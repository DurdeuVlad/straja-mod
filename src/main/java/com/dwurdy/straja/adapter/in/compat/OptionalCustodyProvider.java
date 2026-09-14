package com.dwurdy.straja.adapter.in.compat;

import net.minecraft.server.level.ServerPlayer;

/**
 * Optional provider boundary for lethal player damage.
 *
 * <p>The interface contains no optional-mod types. Implementations are loaded
 * only after their loader ID has been detected, so the absent-mod build never
 * links an optional provider class.</p>
 */
public interface OptionalCustodyProvider {
    ProviderState inspect(ServerPlayer target);

    record ProviderState(boolean eligibleForDbno, boolean dbnoActive) {
        public static final ProviderState NONE = new ProviderState(false, false);
    }
}
