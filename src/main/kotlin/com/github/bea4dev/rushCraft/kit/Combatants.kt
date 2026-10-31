package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.manager.MatchManager
import org.bukkit.GameMode
import org.bukkit.entity.Player

/**
 * 特殊アイテムを使える・当たる対象かの判定。
 */
object Combatants {
    /** 試合中の参加者で、特殊アイテムを使える状態か */
    fun canUse(player: Player): Boolean {
        return isTarget(player) && !ShieldManager.isStunned(player)
    }

    /** 試合中の参加者で、攻撃が当たる状態か */
    fun isTarget(player: Player): Boolean {
        return player.isOnline &&
            !player.isDead &&
            (player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE) &&
            MatchManager.isRunning(player.world) &&
            MatchManager.isParticipant(player)
    }

    /** 別々のチームに参加しているか */
    fun isEnemy(a: Player, b: Player): Boolean {
        val match = MatchManager.match ?: return false
        val teamA = match.getTeam(a) ?: return false
        val teamB = match.getTeam(b) ?: return false
        return teamA != teamB
    }
}
