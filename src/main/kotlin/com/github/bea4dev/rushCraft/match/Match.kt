package com.github.bea4dev.rushCraft.match

import com.github.bea4dev.rushCraft.scheduler.Tickable
import org.bukkit.entity.Player
import java.util.stream.Collectors

class Match<out G: MatchGameMode>(private val mode: G, val teams: List<MatchTeam>): Tickable {
    private val objective = mode.createObjective()

    fun isEnd(): Boolean {
        return !this.objective.getWinners().isEmpty()
    }

    fun getMembers(): List<Player> {
        return teams.stream()
            .flatMap { team -> team.getMembers().stream() }
            .collect(Collectors.toList())
    }

    override fun tick() {

    }

    override fun isAlive(): Boolean {
        return true
    }
}
