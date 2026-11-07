package com.github.bea4dev.rushCraft.kit

import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * 特殊アイテムで何かが起きたことを知らせるイベント (チュートリアルの進行判定に使う)。
 *
 * @param actor 行動した人 (パリィした人・入れ替えた人など)
 * @param target 相手 (いれば)
 */
class KitActionEvent(val actor: LivingEntity, val action: Action, val target: LivingEntity? = null) : Event() {
    enum class Action {
        /** 近接攻撃をパリィした */
        PARRY_MELEE,

        /** 飛んできたもの (矢・投げナイフ・TNT・スワッパー) をパリィした */
        PARRY_PROJECTILE,

        /** 盾でガードして攻撃を防いだ */
        GUARD,

        /** スワッパーで位置を入れ替えた */
        SWAP,

        /** 投げた TNT を相手に持たせた */
        TNT_STUCK,

        /** 持たされた TNT を投げた (投げ返した) */
        TNT_THROWN_BACK,

        /** 即席壁を作った */
        WALL_BUILT,

        /** 即席壁で相手を打ち上げた */
        WALL_LAUNCH,

        /** メイスで飛び上がった */
        MACE_LEAP,

        /** 槍で突進した */
        SPEAR_LUNGE,

        /** トラップを置いた */
        TRAP_PLACED,

        /** 透明スパイで透明になった */
        SPY_CLOAK,

        /** 透明スパイが敵に見つかった (target は見つけた敵) */
        SPY_REVEALED,
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS

        fun call(actor: LivingEntity, action: Action, target: LivingEntity? = null) {
            Bukkit.getPluginManager().callEvent(KitActionEvent(actor, action, target))
        }
    }
}
