package com.github.bea4dev.rushCraft.match

import org.bukkit.Bukkit
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 最後に攻撃してきた敵を覚えておき、奈落に突き落とした判定に使う。
 * バニラのキル判定 (最後の被弾から 5 秒) では落下中に時間切れになることがあり、パリィのふっとばしも対象外なため。
 *
 * 特殊アイテムによる即死 (投げナイフ・TNT・トラップ) の死亡メッセージとキルの記録もここで扱う。
 */
object CombatTracker {
    /** 攻撃されてからこの tick 数以内に奈落で死んだら、攻撃者のキルにする */
    private const val VOID_KILL_TICKS = 200

    private class Hit(val attacker: UUID, val tick: Int)

    /** 特殊アイテムで倒されたときの死亡メッセージ */
    class SpecialDeath(val killer: UUID?, val messageKey: String, val tick: Int)

    private val lastHits = HashMap<UUID, Hit>()
    private val specialDeaths = HashMap<UUID, SpecialDeath>()

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

    /**
     * 防具や耐性を無視して即死させる。killer がいればその人のキルになる。
     * messageKey の死亡メッセージには {0} に倒された人、{1} に倒した人が入る。
     * (チュートリアルのダミーも同じように倒せる)
     */
    fun kill(victim: LivingEntity, killer: LivingEntity?, messageKey: String) {
        if (victim.isDead) return
        specialDeaths[victim.uniqueId] = SpecialDeath(killer?.uniqueId, messageKey, Bukkit.getCurrentTick())
        val source = if (killer != null) {
            if (victim is Player && killer is Player) record(victim, killer)
            DamageSource.builder(DamageType.GENERIC_KILL).withCausingEntity(killer).withDirectEntity(killer).build()
        } else {
            DamageSource.builder(DamageType.GENERIC_KILL).build()
        }
        victim.damage(Float.MAX_VALUE.toDouble(), source)
        // 死ななかった場合 (イベントがキャンセルされたなど) に残らないようにする
        if (!victim.isDead) specialDeaths.remove(victim.uniqueId)
    }

    /** kill() でダメージを与えている最中なら、その情報 (取り出さない) */
    fun peekSpecialDeath(victim: Entity): SpecialDeath? {
        return specialDeaths[victim.uniqueId]?.takeIf { Bukkit.getCurrentTick() - it.tick <= 1 }
    }

    /** kill() による死亡なら、その情報を取り出す */
    fun takeSpecialDeath(victim: Entity): SpecialDeath? {
        val death = specialDeaths.remove(victim.uniqueId) ?: return null
        return death.takeIf { Bukkit.getCurrentTick() - it.tick <= 1 }
    }

    fun clear(player: Player) {
        lastHits.remove(player.uniqueId)
        specialDeaths.remove(player.uniqueId)
    }

    fun reset() {
        lastHits.clear()
        specialDeaths.clear()
    }
}
