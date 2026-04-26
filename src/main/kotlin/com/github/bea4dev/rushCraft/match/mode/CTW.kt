package com.github.bea4dev.rushCraft.match.mode

import com.github.bea4dev.rushCraft.match.MatchGameMode
import com.github.bea4dev.rushCraft.match.MatchObjective
import com.github.bea4dev.rushCraft.match.objective.CTWObjective

class CTW: MatchGameMode {
    override fun createObjective(): MatchObjective {
        return CTWObjective()
    }
}