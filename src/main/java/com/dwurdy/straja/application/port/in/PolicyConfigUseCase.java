package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;

/**
 * In-game runtime policy editing (Comisar / ops): get, set and reset
 * overridable policy keys. Values apply to the live services immediately and
 * persist in the YAML override store across restarts.
 */
public interface PolicyConfigUseCase {

    /** Lists all overridable keys grouped by section. */
    void list(PlayerGateway actor);

    /** Shows the effective value and whether it comes from an override. */
    void get(PlayerGateway actor, String key);

    /** Validates and applies an override, then persists it. */
    void set(PlayerGateway actor, String key, String rawValue);

    /** Removes the override and restores the configured (TOML) default. */
    void reset(PlayerGateway actor, String key);
}
