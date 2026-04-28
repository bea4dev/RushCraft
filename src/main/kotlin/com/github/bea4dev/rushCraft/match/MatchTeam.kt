package com.github.bea4dev.rushCraft.match

import org.bukkit.entity.Player

class MatchTeam {
    private val members = mutableListOf<Player>()

    fun join(player: Player) {
        members += player
    }

    fun getMembers(): List<Player> {
        return members
    }
}
