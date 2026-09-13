package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.PolicyConfigUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.application.port.out.PolicyOverrideStore;
import com.dwurdy.straja.domain.model.PolicyRegistry;
import com.dwurdy.straja.domain.model.Result;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runtime policy overrides: the Comisar (or an op/console) edits curated keys
 * in-game; changes apply to the live {@link StrajaPolicies} immediately and
 * persist through the {@link PolicyOverrideStore}. The {@code baseline}
 * snapshot holds the TOML-resolved defaults so {@code reset} can restore them.
 * Authority is revalidated here — adapters only route.
 */
public class PolicyService implements PolicyConfigUseCase {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private final StrajaPolicies baseline;
    private final PolicyOverrideStore store;

    public PolicyService(StrajaContext ctx, PlayerService players, AuditService audit,
                         StrajaPolicies baseline, PolicyOverrideStore store) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
        this.baseline = baseline;
        this.store = store;
    }

    private boolean allowed(PlayerGateway actor) {
        return players.isCommissioner(actor) || actor.isOp();
    }

    /**
     * Bootstrap hook: applies every persisted override to the live policies.
     * Returns the keys that failed to apply (malformed persisted values are
     * skipped so last-good state is preserved).
     */
    public List<String> applyPersistedOverrides() {
        Map<String, String> overrides = store.read();
        List<String> failed = new ArrayList<>();
        for (var entry : overrides.entrySet()) {
            Result result = PolicyRegistry.apply(ctx.policies(), entry.getKey(), entry.getValue());
            if (!result.ok()) failed.add(entry.getKey());
        }
        return failed;
    }

    @Override
    public void list(PlayerGateway actor) {
        if (!allowed(actor)) { actor.tell("Doar Comisaru' sau un operator poate vedea configurația."); return; }
        Map<String, List<String>> bySection = new TreeMap<>();
        for (String path : PolicyRegistry.keys().keySet()) {
            bySection.computeIfAbsent(path.substring(0, path.indexOf('.')), s -> new ArrayList<>())
                    .add(path);
        }
        actor.tell("Chei configurabile (" + PolicyRegistry.keys().size() + "):");
        bySection.forEach((section, keys) ->
                actor.tell("[" + section + "] " + String.join(", ", keys)));
    }

    @Override
    public void get(PlayerGateway actor, String key) {
        if (!allowed(actor)) { actor.tell("Doar Comisaru' sau un operator poate vedea configurația."); return; }
        if (!PolicyRegistry.keys().containsKey(key)) {
            actor.tell("Cheie necunoscută: " + key + ". Vezi /straja policy list.");
            return;
        }
        boolean overridden = store.read().containsKey(key);
        actor.tell(key + " = " + PolicyRegistry.read(ctx.policies(), key)
                + (overridden ? " (suprascris în " + store.describe() + ")" : " (implicit)"));
    }

    @Override
    public void set(PlayerGateway actor, String key, String rawValue) {
        if (!allowed(actor)) { actor.tell("Doar Comisaru' sau un operator poate modifica configurația."); return; }
        Result applied = PolicyRegistry.apply(ctx.policies(), key, rawValue);
        if (!applied.ok()) {
            actor.tell(switch (applied.code()) {
                case "unknown_key" -> "Cheie necunoscută: " + key + ". Vezi /straja policy list.";
                case "empty_value" -> "Valoare lipsă pentru " + key + ".";
                default -> "Valoare invalidă pentru " + key + " (tip: "
                        + PolicyRegistry.keys().get(key).kind() + ").";
            });
            return;
        }
        Map<String, String> overrides = new LinkedHashMap<>(store.read());
        overrides.put(key, rawValue.trim());
        store.write(overrides);
        audit.record("policy_set", actor.name(), actor.uuid().toString(),
                actor.name(), actor.uuid().toString(), "SUCCESS", key);
        actor.tell(key + " = " + PolicyRegistry.read(ctx.policies(), key)
                + " — aplicat imediat și salvat în " + store.describe() + ".");
    }

    @Override
    public void reset(PlayerGateway actor, String key) {
        if (!allowed(actor)) { actor.tell("Doar Comisaru' sau un operator poate modifica configurația."); return; }
        Map<String, String> overrides = new LinkedHashMap<>(store.read());
        if (!PolicyRegistry.keys().containsKey(key)) {
            actor.tell("Cheie necunoscută: " + key + ". Vezi /straja policy list.");
            return;
        }
        overrides.remove(key);
        store.write(overrides);
        // Restore the configured (TOML) default through the same strict path.
        String baselineValue = PolicyRegistry.read(baseline, key);
        PolicyRegistry.apply(ctx.policies(), key, baselineValue);
        audit.record("policy_reset", actor.name(), actor.uuid().toString(),
                actor.name(), actor.uuid().toString(), "SUCCESS", key);
        actor.tell(key + " resetat la valoarea configurată: " + baselineValue + ".");
    }
}
