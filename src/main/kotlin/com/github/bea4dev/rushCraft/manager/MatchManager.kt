package com.github.bea4dev.rushCraft.manager

import com.github.bea4dev.rushCraft.match.Match
import com.github.bea4dev.rushCraft.match.mode.CTW
import com.github.bea4dev.rushCraft.scheduler.Tickable

object MatchManager: Tickable {
    var state: MatchState = MatchState.Waiting(10)
        private set
    var match = this.rotation()
        private set

    fun tickSeconds() {
        when (state) {
            is MatchState.Waiting -> {
                val waiting = state as MatchState.Waiting
                val showSeconds = when (waiting.seconds) {
                    10 -> true
                    5 -> true
                    4 -> true
                    3 -> true
                    2 -> true
                    1 -> true
                    else -> false
                }

                waiting.seconds--
            }
            is MatchState.Running -> {

            }
            is MatchState.Finished -> {

            }
        }
    }

    private fun rotation(): Match<*> {
        return Match(CTW(), listOf())
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
        class Waiting(var seconds: Int) : MatchState
        object Running : MatchState
        class Finished(var seconds: Int) : MatchState
    }
}