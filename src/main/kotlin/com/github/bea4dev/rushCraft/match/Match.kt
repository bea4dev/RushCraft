package com.github.bea4dev.rushCraft.match

import com.github.bea4dev.rushCraft.scheduler.Tickable

class Match<out G: MatchGameMode>(private val mode: G, val teams: List<MatchTeam>): Tickable {
    private val objective = mode.createObjective()

    fun isEnd(): Boolean {
        return !this.objective.getWinners().isEmpty()
    }

    override fun tick() {

    }

    override fun isAlive(): Boolean {
        return true
    }
}
