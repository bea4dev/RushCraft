package com.github.bea4dev.rushCraft.manager

import com.github.bea4dev.rushCraft.match.Match
import com.github.bea4dev.rushCraft.match.mode.CTW
import com.github.bea4dev.rushCraft.scheduler.Tickable

object MatchManager: Tickable {
    private var state: MatchState = MatchState.Waiting

    fun tickSeconds() {
        when (state) {
            is MatchState.Waiting -> {
                this.state = MatchState.InMatch(Match(CTW(), listOf()))
            }
            is MatchState.InMatch -> {
                val match = (state as MatchState.InMatch).match
            }
        }
    }

    private var t = 0

    override fun tick() {
        if (t % 20 == 0) {
            tickSeconds()
        }
        t++
    }

    override fun isAlive(): Boolean {
        return true
    }

    sealed interface MatchState {
        object Waiting : MatchState
        class InMatch(val match: Match<*>) : MatchState
    }
}