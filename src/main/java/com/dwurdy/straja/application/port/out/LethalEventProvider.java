package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.LethalEventResolver;
import java.util.ServiceConfigurationError;

/**
 * Optional terminal-state provider boundary. Implementations may inspect
 * their own attachments/state, but the Straja resolver only receives a
 * bounded eligibility claim. The default implementation is safe when the
 * optional mod is absent.
 */
public interface LethalEventProvider {
    LethalEventResolver.ProviderSelection offer(PlayerGateway target, String sourceId);

    /**
     * Gives the selected provider the same event identity so it can apply its
     * own DBNO/terminal state without Straja touching provider internals.
     */
    /**
     * Applies the provider-owned terminal state. Returning false (or throwing)
     * rejects ownership so the caller can let vanilla death proceed safely.
     */
    default boolean claim(PlayerGateway target, String sourceId, String eventId) {
        return false;
    }

    static LethalEventProvider none() {
        return (target, sourceId) -> LethalEventResolver.ProviderSelection.none();
    }

    /** Discovers an optional provider without making it a required dependency. */
    static LethalEventProvider discover() {
        try {
            return java.util.ServiceLoader.load(LethalEventProvider.class)
                    .findFirst().orElseGet(LethalEventProvider::none);
        } catch (ServiceConfigurationError ignored) {
            return none();
        }
    }
}
