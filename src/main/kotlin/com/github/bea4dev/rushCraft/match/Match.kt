package com.github.bea4dev.rushCraft.match

import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.entity.Player
import java.util.stream.Collectors

class Match<G : MatchGameMode<G>>(private val mode: G, val teams: List<MatchTeam>) : Tickable {
    private val objective: MatchObjective<G> = mode.createObjective()
    private var isRunning = true

    fun getMembers(): List<Player> {
        return teams.stream()
            .flatMap { team -> team.getMembers().stream() }
            .collect(Collectors.toList())
    }

    private fun win(winners: List<MatchTeam>) {
        val isDraw = winners.size == teams.size
        val winnerMembers = teams.stream()
            .flatMap { team -> team.getMembers().stream() }
            .collect(Collectors.toList())

        when (isDraw) {
            true -> {
                winnerMembers.forEach {
                    it.sendMessage(Component.translatable("rushcraft.match.finished.draw").color(NamedTextColor.GREEN))
                    it.showTitle(
                        Title.title(
                            Component.translatable("rushcraft.match.finished.draw").color(NamedTextColor.GREEN),
                            Component.empty()
                        )
                    )
                }
            }

            false -> {

            }
        }

        winnerMembers.forEach {
            it.playSound(
                Sound.sound(org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, Sound.Source.MASTER, 1f, 1f),
                Sound.Emitter.self()
            )
        }
    }

    override fun tick() {
        val winners = objective.getWinners(this)

        if (winners.isNotEmpty()) {
            this.win(winners)
            this.isRunning = false
            return
        }
    }

    override fun isAlive(): Boolean {
        return this.isRunning
    }
}
