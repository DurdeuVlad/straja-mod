package com.dwurdy.straja.domain.model;

/** How a law checkpoint treats a rejected crossing. */
public enum CheckpointMode {
    /** Repel to the pushback point and close linked doors; never arrests. */
    DENY,
    /** Violations go straight into custody. */
    ARREST
}
