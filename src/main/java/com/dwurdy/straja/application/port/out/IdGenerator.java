package com.dwurdy.straja.application.port.out;

/** Deterministic-friendly ID source. Implementations must never repeat an ID. */
public interface IdGenerator {
    /** e.g. newId("F") -> "F-7". */
    String newId(String prefix);

    /** Raw unique token for receipts/serials. */
    String token();
}
