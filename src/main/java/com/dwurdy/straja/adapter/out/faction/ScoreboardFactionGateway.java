package com.dwurdy.straja.adapter.out.faction;

import com.dwurdy.straja.application.port.out.FactionGateway;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/** Vanilla scoreboard teams as the faction vehicle for §7 capture/restore. */
public class ScoreboardFactionGateway implements FactionGateway {
    private final MinecraftServer server;

    public ScoreboardFactionGateway(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String teamOf(PlayerGateway player) {
        PlayerTeam team = scoreboard().getPlayersTeam(player.name());
        return team != null ? team.getName() : null;
    }

    @Override
    public void joinTeam(PlayerGateway player, String teamName) {
        Scoreboard board = scoreboard();
        PlayerTeam team = board.getPlayerTeam(teamName);
        if (team == null) {
            team = board.addPlayerTeam(teamName);
        }
        board.addPlayerToTeam(player.name(), team);
    }

    @Override
    public void leaveTeam(PlayerGateway player) {
        Scoreboard board = scoreboard();
        PlayerTeam team = board.getPlayersTeam(player.name());
        if (team != null) {
            board.removePlayerFromTeam(player.name(), team);
        }
    }

    @Override
    public boolean teamExists(String teamName) {
        return teamName != null && scoreboard().getPlayerTeam(teamName) != null;
    }

    private Scoreboard scoreboard() {
        return server.getScoreboard();
    }
}
