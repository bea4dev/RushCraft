package com.github.bea4dev.rushCraft.manager

import com.github.bea4dev.rushCraft.match.Match
import com.github.bea4dev.rushCraft.match.mode.CTW
import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit

const val MATCH_WAITING_COUNT = 10

object MatchManager : Tickable {
    var state: MatchState = MatchState.Waiting(MATCH_WAITING_COUNT, 0)
        private set
    var match = this.rotation()
        private set

    fun tickSeconds() {
        when (state) {
            is MatchState.Waiting -> {
                val playerCount = match.getMembers().size
                val waiting = state as MatchState.Waiting
                val previousPlayerCount = waiting.previousPlayerCount

                if (previousPlayerCount != playerCount && playerCount >= match.teams.size) {
                    Bukkit.getOnlinePlayers().forEach {
                        it.sendMessage(
                            Component.translatable(
                                "rushcraft.match.waiting.need",
                                Component.text(match.teams.size - playerCount).color(NamedTextColor.RED)
                            ).color(NamedTextColor.GREEN)
                        )
                    }
                }

                waiting.previousPlayerCount = playerCount

                if (playerCount < match.teams.size) {
                    waiting.seconds = MATCH_WAITING_COUNT
                    return
                }

                val (showSeconds, playSound) = when (waiting.seconds) {
                    10 -> Pair(true, null)
                    5 -> Pair(true, null)
                    4 -> Pair(true, null)
                    3 -> Pair(true, 1.0)
                    2 -> Pair(true, 1.0)
                    1 -> Pair(true, 1.0)
                    0 -> Pair(false, 2.0)
                    else -> Pair(false, null)
                }

                if (showSeconds) {
                    Bukkit.getOnlinePlayers().forEach {
                        it.sendMessage(
                            Component.translatable(
                                "rushcraft.match.waiting.countdown",
                                Component.text(waiting.seconds).color(NamedTextColor.RED)
                            ).color(NamedTextColor.GREEN)
                        )
                    }
                }

                if (playSound != null) {
                    Bukkit.getOnlinePlayers().forEach {
                        it.playSound(
                            Sound.sound(
                                org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING,
                                Sound.Source.MASTER,
                                1.0F,
                                playSound.toFloat()
                            )
                        )
                    }
                }

                if (waiting.seconds == 0) {
                    Bukkit.getOnlinePlayers().forEach {
                        it.sendMessage(Component.text("######################").color(NamedTextColor.GREEN))
                        it.sendMessage(Component.translatable("match.start").color(NamedTextColor.GOLD))
                        it.sendMessage(Component.text("######################").color(NamedTextColor.GREEN))
                    }
                    state = MatchState.Running
                    return
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
        class Waiting(var seconds: Int, var previousPlayerCount: Int) : MatchState
        object Running : MatchState
        class Finished(var seconds: Int) : MatchState
    }
}