package com.github.bea4dev.rushCraft.match

interface MatchObjective<G : MatchGameMode<G>> {
    fun getWinners(match: Match<G>): List<MatchTeam>
}