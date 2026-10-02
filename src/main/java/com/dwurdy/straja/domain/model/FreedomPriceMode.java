package com.dwurdy.straja.domain.model;

/** How a labor camp computes the buyout price of a prisoner's freedom. */
public enum FreedomPriceMode {
    /** A flat base-currency price configured per camp or in TOML. */
    FLAT,
    /** Outstanding fines times a configured multiplier. */
    FINES_MULTIPLIER
}
