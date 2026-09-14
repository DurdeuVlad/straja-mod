package com.dwurdy.straja.adapter.in.compat.vampirism;

import com.dwurdy.straja.adapter.in.compat.OptionalCustodyProvider;
import com.dwurdy.straja.adapter.in.compat.VampirismOwnershipPolicy;
import de.teamlapen.vampirism.api.VampirismAPI;
import net.minecraft.server.level.ServerPlayer;

/**
 * Isolated adapter for Vampirism 1.21-1.10.13's public API classifier.
 *
 * <p>Vampirism remains the owner of DBNO creation, resurrection, and stake
 * finishing. This adapter only observes ownership state for Straja's
 * precedence decision.</p>
 */
public final class VampirismProvider implements OptionalCustodyProvider {
    @Override
    public ProviderState inspect(ServerPlayer target) {
        if (target == null) return ProviderState.NONE;
        try {
            return VampirismAPI.getVampirePlayer(target)
                    .map(vampire -> VampirismOwnershipPolicy.classify(
                            true, vampire.getLevel(), vampire.isDBNO()))
                    .orElse(ProviderState.NONE);
        } catch (RuntimeException | LinkageError ignored) {
            // A mismatched or incompletely initialized optional mod must not
            // take down Straja; fail closed to vanilla/provider handling.
            return ProviderState.NONE;
        }
    }
}
