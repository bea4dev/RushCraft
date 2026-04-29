package com.github.bea4dev.rushCraft.match

interface MatchGameMode<G : MatchGameMode<G>> {
    fun createObjective(): MatchObjective<G>
}
