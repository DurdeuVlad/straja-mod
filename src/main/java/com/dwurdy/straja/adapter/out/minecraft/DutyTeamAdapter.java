package com.dwurdy.straja.adapter.out.minecraft;

import com.dwurdy.straja.application.service.PlayerService;
import com.dwurdy.straja.domain.model.GuardState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Platform adapter for the temporary on-duty scoreboard faction.
 *
 * <p>Authorization never comes from the scoreboard. This adapter only makes
 * the visible faction follow duty state and restores the player's previous
 * team afterwards.</p>
 */
public final class DutyTeamAdapter {
    public static final String DUTY_TEAM = "Straja";

    private final MinecraftServer server;

    public DutyTeamAdapter(MinecraftServer server) {
        this.server = server;
    }

    /** Synchronizes one online player's scoreboard team with persisted duty state. */
    public void sync(ServerPlayer player, GuardState state, PlayerService players) {
        Scoreboard scoreboard = server.getScoreboard();
        String holder = player.getScoreboardName();
        boolean changed = false;

        if (state.duty) {
            if (!state.dutyHomeTeamCaptured) {
                PlayerTeam current = scoreboard.getPlayersTeam(holder);
                // If a legacy active duty is already in Straja, the original
                // team is unknowable; restore to no team rather than guessing.
                state.dutyHomeTeam = current != null && !DUTY_TEAM.equals(current.getName())
                        ? current.getName() : null;
                state.dutyHomeTeamCaptured = true;
                changed = true;
            }

            PlayerTeam straja = scoreboard.getPlayerTeam(DUTY_TEAM);
            if (straja == null) straja = scoreboard.addPlayerTeam(DUTY_TEAM);
            PlayerTeam current = scoreboard.getPlayersTeam(holder);
            if (current != straja) {
                scoreboard.removePlayerFromTeam(holder);
                scoreboard.addPlayerToTeam(holder, straja);
            }
        } else if (state.dutyHomeTeamCaptured) {
            scoreboard.removePlayerFromTeam(holder);
            String previous = state.dutyHomeTeam;
            if (previous != null && !previous.isBlank()) {
                PlayerTeam home = scoreboard.getPlayerTeam(previous);
                if (home != null) {
                    scoreboard.addPlayerToTeam(holder, home);
                } else {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "[Straja] Facțiunea anterioară '" + previous
                                    + "' nu mai există; ai rămas fără facțiune."));
                }
            }
            state.dutyHomeTeamCaptured = false;
            state.dutyHomeTeam = null;
            changed = true;
        }

        if (changed) players.save(player.getUUID(), state);
    }
}
