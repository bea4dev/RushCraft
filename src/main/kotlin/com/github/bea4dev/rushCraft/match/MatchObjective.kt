package com.github.bea4dev.rushCraft.match

interface MatchObjective<G : MatchGameMode<G>> {
    /** 勝利条件を満たしたチーム。空なら試合続行 */
    fun getWinners(match: Match<G>): List<MatchTeam>
}
