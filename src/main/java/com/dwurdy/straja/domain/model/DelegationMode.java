package com.dwurdy.straja.domain.model;

/**
 * How a delegatable request queue gets decided. {@code MANUAL} is the
 * pre-delegation behavior; {@code STANDING_ORDER} resolves requests matching a
 * published order; {@code TIMEOUT} presumes the queue's configured outcome
 * after the window; {@code DIGEST} keeps human decisions but batches them.
 */
public enum DelegationMode {
    MANUAL,
    STANDING_ORDER,
    TIMEOUT,
    DIGEST
}
