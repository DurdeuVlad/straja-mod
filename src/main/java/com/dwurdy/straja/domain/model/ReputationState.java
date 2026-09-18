package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class ReputationState {
    public String playerUuid = "";
    public String playerName = "";
    public int score;
    public String band = "NEUTRAL";
    public long updatedAt;
    /** Positive prison-task rehabilitation earned per authoritative sentence. */
    public Map<String, Integer> prisonTaskRehabilitation = new LinkedHashMap<>();
}
