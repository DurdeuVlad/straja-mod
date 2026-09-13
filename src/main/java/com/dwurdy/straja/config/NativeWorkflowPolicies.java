package com.dwurdy.straja.config;

import com.dwurdy.straja.domain.model.StrajaPolicies;

/**
 * Transitional native-workflow invariants layered over the legacy config
 * schema. This keeps old config files loadable while the gameplay contract
 * moves to 20-minute paid days and 64-bronze silver coins.
 */
public final class NativeWorkflowPolicies {
    private NativeWorkflowPolicies() {}

    public static void apply(StrajaPolicies policies) {
        // One paid Minecraft duty day = 20 minutes.
        policies.salaryBlockMinutes = 20;

        // The legacy config schema stores item ids but hard-coded silver at
        // value 100. Preserve the configured silver item id while changing its
        // native denomination to the agreed 64 bronze.
        String silver = policies.coinItemIds.remove(100);
        if (silver != null && !silver.isBlank()) {
            policies.coinItemIds.put(64, silver);
        }
    }
}
