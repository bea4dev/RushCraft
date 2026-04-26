package com.github.bea4dev.rushCraft.match.objective

import com.github.bea4dev.rushCraft.match.MatchObjective
import com.github.bea4dev.rushCraft.match.MatchTeam

class CTWObjective: MatchObjective {
    override fun getWinners(): List<MatchTeam> {
        return emptyList()
    }
}