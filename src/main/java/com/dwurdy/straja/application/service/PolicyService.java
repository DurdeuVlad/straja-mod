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
        if (!allowed(actor)) { actor.refuse("straja.policy.view_comisar", "straja.remedy.ask_comisar"); return; }
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
        if (!allowed(actor)) { actor.refuse("straja.policy.view_comisar", "straja.remedy.ask_comisar"); return; }
        if (!PolicyRegistry.keys().containsKey(key)) {
            actor.refuse("straja.policy.key_unknown", "straja.remedy.fix_retry", key);
            return;
        }
        boolean overridden = store.read().containsKey(key);
        actor.tell(key + " = " + PolicyRegistry.read(ctx.policies(), key)
                + (overridden ? " (suprascris în " + store.describe() + ")" : " (implicit)"));
    }

    @Override
    public void set(PlayerGateway actor, String key, String rawValue) {
        if (!allowed(actor)) { actor.refuse("straja.policy.modify_comisar", "straja.remedy.ask_comisar"); return; }
        Result applied = PolicyRegistry.apply(ctx.policies(), key, rawValue);
        if (!applied.ok()) {
            switch (applied.code()) {
                case "unknown_key" ->
                        actor.refuse("straja.policy.key_unknown",
                                "straja.remedy.fix_retry", key);
                case "empty_value" ->
                        actor.refuse("straja.policy.empty_value",
                                "straja.remedy.fix_retry", key);
                default ->
                        actor.refuse("straja.policy.bad_value",
                                "straja.remedy.fix_retry", key,
                                PolicyRegistry.keys().get(key).kind());
            }
            return;
        }
        Map<String, String> overrides = new LinkedHashMap<>(store.read());
        overrides.put(key, rawValue.trim());
        boolean persisted = store.write(overrides);
        audit.record("policy_set", actor.name(), actor.uuid().toString(),
                actor.name(), actor.uuid().toString(),
                persisted ? "SUCCESS" : "PERSIST_FAILED", key);
        actor.tell(key + " = " + PolicyRegistry.read(ctx.policies(), key)
                + (persisted
                        ? " — aplicat imediat și salvat în " + store.describe() + "."
                        : " — aplicat imediat, dar NU s-a putut salva în " + store.describe()
                                + " (se pierde la restart)."));
    }

    @Override
    public void reset(PlayerGateway actor, String key) {
        if (!allowed(actor)) { actor.refuse("straja.policy.modify_comisar", "straja.remedy.ask_comisar"); return; }
        Map<String, String> overrides = new LinkedHashMap<>(store.read());
        if (!PolicyRegistry.keys().containsKey(key)) {
            actor.refuse("straja.policy.key_unknown", "straja.remedy.fix_retry", key);
            return;
        }
        overrides.remove(key);
        boolean persisted = store.write(overrides);
        // Restore the configured (TOML) default through the same strict path.
        String baselineValue = PolicyRegistry.read(baseline, key);
        PolicyRegistry.apply(ctx.policies(), key, baselineValue);
        audit.record("policy_reset", actor.name(), actor.uuid().toString(),
                actor.name(), actor.uuid().toString(),
                persisted ? "SUCCESS" : "PERSIST_FAILED", key);
        actor.tell(key + " resetat la valoarea configurată: " + baselineValue
                + (persisted ? "." : " (dar fișierul nu s-a putut actualiza)."));
    }
}
