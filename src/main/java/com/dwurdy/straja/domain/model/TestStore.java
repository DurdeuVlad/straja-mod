package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Debug/test-only state: commissioner override + guided UAT flags. */
public class TestStore {
    /** Debug-commissioner override (local-only, mirrors straja_debug_commissioner_uuid). */
    public String debugCommissionerUuid = "";
    /** Guided UAT progress keyed by player UUID. */
    public Map<String, Integer> guidedSteps = new LinkedHashMap<>();
}
