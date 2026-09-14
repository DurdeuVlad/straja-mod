package com.dwurdy.straja.domain.model;

/**
 * §13 mission template: fast issuing through editable presets. Templates own
 * ranks/hours/risk/participants — never fixed cash values; the recommended
 * reward re-derives from the current wage table at issue time.
 */
public class MissionTemplate {
    public String id = "";               // T<n>
    public String name = "";
    public int minRank = 1;
    /** Estimated work hours; fractional values allowed. */
    public double estimatedHours = 1.0;
    /** Hazard multiplier on the hourly wage. */
    public double risk = 1.0;
    /** Maximum participants whose work is paid from the budget. */
    public int maxPaidParticipants = 1;
    /** Deadline for the issued mission, in minutes. */
    public int deadlineMinutes = 60;
    public String objective = "";
    /** When true the issued mission substitutes patrol duty for assignees. */
    public boolean supersedesPatrol;
    public boolean enabled = true;
    public long createdAt;
    public long updatedAt;
}
