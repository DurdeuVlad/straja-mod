package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Durable registry of all issued identity cards. */
public class IdentityCardStore {
    public int nextCardNumber = 1;
    public Map<String, IdentityCard> cards = new LinkedHashMap<>();
    /** Lazily re-creates {@code cards} after a corrupt or partial read. */
    public Map<String, IdentityCard> ensureCards() {
        if (cards == null) cards = new LinkedHashMap<>();
        return cards;
    }

}
