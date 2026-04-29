package com.github.bea4dev.rushCraft.match

import com.github.bea4dev.rushCraft.scheduler.Tickable
import org.bukkit.entity.Player
import java.util.stream.Collectors

class Match<G : MatchGameMode<G>>(private val mode: G, val teams: List<MatchTeam>) : Tickable {
    private val objective: MatchObjective<G> = mode.createObjective()

    fun getMembers(): List<Player> {
        return teams.stream()
            .flatMap { team -> team.getMembers().stream() }
            .collect(Collectors.toList())
    }

    override fun tick() {

    }

    override fun isAlive(): Boolean {
        return this.objective.getWinners(this).isEmpty()
    }
}
