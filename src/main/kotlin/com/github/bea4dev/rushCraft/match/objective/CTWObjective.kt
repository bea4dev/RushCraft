package com.github.bea4dev.rushCraft.match.objective

import com.github.bea4dev.rushCraft.match.Match
import com.github.bea4dev.rushCraft.match.MatchObjective
import com.github.bea4dev.rushCraft.match.MatchTeam
import com.github.bea4dev.rushCraft.match.mode.CTW
import com.github.bea4dev.rushCraft.match.mode.WoolState

class CTWObjective : MatchObjective<CTW> {
    override fun getWinners(match: Match<CTW>): List<MatchTeam> {
        return match.teams.filter { team ->
            val wools = match.mode.woolsOf(team)
            wools.isNotEmpty() && wools.all { it.state == WoolState.PLACED }
        }
    }
}
