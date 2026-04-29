package com.github.bea4dev.rushCraft.match.objective

import com.github.bea4dev.rushCraft.match.Match
import com.github.bea4dev.rushCraft.match.MatchObjective
import com.github.bea4dev.rushCraft.match.MatchTeam
import com.github.bea4dev.rushCraft.match.mode.CTW

class CTWObjective : MatchObjective<CTW> {
    override fun getWinners(match: Match<CTW>): List<MatchTeam> {
        return emptyList()
    }
}
