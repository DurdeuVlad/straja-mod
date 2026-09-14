package com.dwurdy.straja.application.port.out;

/**
 * Scoreboard-team/faction membership (§7): the Straja captures a guard's
 * native team at duty start, moves them to the operational Straja team, and
 * restores the captured one at shift end.
 */
public interface FactionGateway {

    /** Name of the team the player currently belongs to, or null. */
    String teamOf(PlayerGateway player);

    /** Creates the team when missing and joins the player to it. */
    void joinTeam(PlayerGateway player, String teamName);

    /** Removes the player from whatever team they are on. */
    void leaveTeam(PlayerGateway player);

    /** Whether a team with this name currently exists. */
    boolean teamExists(String teamName);
}
