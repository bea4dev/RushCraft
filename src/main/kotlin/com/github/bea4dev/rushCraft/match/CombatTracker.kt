package com.github.bea4dev.rushCraft.match

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 最後に攻撃してきた敵を覚えておき、奈落に突き落とした判定に使う。
 * バニラのキル判定 (最後の被弾から 5 秒) では落下中に時間切れになることがあり、パリィのふっとばしも対象外なため。
 */
object CombatTracker {
    /** 攻撃されてからこの tick 数以内に奈落で死んだら、攻撃者のキルにする */
    private const val VOID_KILL_TICKS = 200

    private class Hit(val attacker: UUID, val tick: Int)

    private val lastHits = HashMap<UUID, Hit>()

    fun record(victim: Player, attacker: Player) {
        if (victim == attacker) return
        lastHits[victim.uniqueId] = Hit(attacker.uniqueId, Bukkit.getCurrentTick())
    }

    /** 直近に攻撃してきたオンラインのプレイヤー */
    fun lastAttacker(victim: Player): Player? {
        val hit = lastHits[victim.uniqueId] ?: return null
        if (Bukkit.getCurrentTick() - hit.tick > VOID_KILL_TICKS) return null
        return Bukkit.getPlayer(hit.attacker)
    }

    fun clear(player: Player) {
        lastHits.remove(player.uniqueId)
    }

    fun reset() {
        lastHits.clear()
    }
}
