package com.dwurdy.straja.domain.model;

/** Outcome of a pure, idempotent custody transition attempt. */
public record CustodyTransitionResult(boolean ok, boolean idempotent, String code) {
    public static CustodyTransitionResult applied() {
        return new CustodyTransitionResult(true, false, "APPLIED");
    }

    public static CustodyTransitionResult replay() {
        return new CustodyTransitionResult(true, true, "IDEMPOTENT_REPLAY");
    }

    public static CustodyTransitionResult rejected(String code) {
        return new CustodyTransitionResult(false, false, code);
    }
}
