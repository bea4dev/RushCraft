package com.github.bea4dev.rushCraft.match

import com.github.bea4dev.rushCraft.map.TeamDefinition
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

class MatchTeam(val definition: TeamDefinition) {
    // ログアウトしたプレイヤーの参照を持ち続けないよう UUID で管理する
    private val members = linkedSetOf<UUID>()

    val id: String
        get() = definition.id

    val color: TeamColor
        get() = definition.color

    val size: Int
        get() = members.size

    val isFull: Boolean
        get() = definition.maxPlayers > 0 && members.size >= definition.maxPlayers

    fun join(player: Player) {
        members += player.uniqueId
    }

    fun leave(player: Player) {
        members -= player.uniqueId
    }

    operator fun contains(player: Player): Boolean = player.uniqueId in members

    fun getMembers(): List<Player> {
        return members.mapNotNull(Bukkit::getPlayer)
    }

    fun displayName(): Component = definition.displayName()
}
