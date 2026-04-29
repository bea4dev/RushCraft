package com.github.bea4dev.rushCraft.match.mode

import com.github.bea4dev.rushCraft.match.MatchGameMode
import com.github.bea4dev.rushCraft.match.MatchObjective
import com.github.bea4dev.rushCraft.match.objective.CTWObjective

class CTW : MatchGameMode<CTW> {
    override fun createObjective(): MatchObjective<CTW> {
        return CTWObjective()
    }
}
