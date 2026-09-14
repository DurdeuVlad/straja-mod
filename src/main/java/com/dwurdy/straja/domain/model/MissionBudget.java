package com.dwurdy.straja.domain.model;

/**
 * §13 budget formula: per-participant reward = hourlyWage(minRank)
 * × estimatedHours × riskMultiplier, stored in Bronze-equivalents and
 * converted to physical coins only at payout.
 */
public final class MissionBudget {
    private MissionBudget() {}

    public static int perParticipant(int hourlyWageBronze, double estimatedHours, double risk) {
        if (hourlyWageBronze <= 0 || estimatedHours <= 0 || risk <= 0) return 0;
        return (int) Math.ceil(hourlyWageBronze * estimatedHours * risk);
    }

    public static int teamBudget(int perParticipant, int maxPaidParticipants) {
        return perParticipant * Math.max(0, maxPaidParticipants);
    }
}
