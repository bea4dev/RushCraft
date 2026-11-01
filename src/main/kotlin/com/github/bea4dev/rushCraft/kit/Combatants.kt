package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.match.MatchTeam
import com.github.bea4dev.rushCraft.match.TeamColor
import com.github.bea4dev.rushCraft.tutorial.TutorialManager
import com.github.bea4dev.rushCraft.tutorial.TutorialSide
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

/**
 * 特殊アイテムを使える・当たる対象かの判定。
 *
 * 試合では参加者 (チームで敵味方を分ける)、チュートリアルではそのプレイヤーとダミー
 * (セッションごとの陣営で敵味方を分ける) が対象になる。
 */
object Combatants {
    /** 特殊アイテムを使える状態か */
    fun canUse(player: Player): Boolean {
        return isTarget(player) && !ShieldManager.isStunned(player)
    }

    /** 攻撃が当たる状態か */
    fun isTarget(entity: LivingEntity): Boolean {
        if (!entity.isValid || entity.isDead) return false
        if (entity is Player) {
            if (!entity.isOnline) return false
            if (entity.gameMode != GameMode.SURVIVAL && entity.gameMode != GameMode.ADVENTURE) return false
        }
        TutorialManager.sessionOf(entity)?.let { return it.isCombatant(entity) }
        return entity is Player && MatchManager.isRunning(entity.world) && MatchManager.isParticipant(entity)
    }

    /** 敵味方の区別に使う陣営 (試合ではチーム、チュートリアルでは TutorialSide) */
    fun sideOf(entity: LivingEntity): Any? {
        TutorialManager.sessionOf(entity)?.let { return it.sideOf(entity) }
        return (entity as? Player)?.let { MatchManager.match?.getTeam(it) }
    }

    /** 別々の陣営か (チュートリアルは同じセッションの中だけで敵味方を分ける) */
    fun isEnemy(a: LivingEntity, b: LivingEntity): Boolean {
        val sideA = sideOf(a) ?: return false
        val sideB = sideOf(b) ?: return false
        if (sideA is TutorialSide || sideB is TutorialSide) {
            return sideA is TutorialSide && sideB is TutorialSide && sideA.session == sideB.session && sideA.enemy != sideB.enemy
        }
        return sideA != sideB
    }

    /** 陣営の色 (TNT の線や即席壁の色) */
    fun colorOf(entity: LivingEntity): TeamColor? {
        return when (val side = sideOf(entity)) {
            is MatchTeam -> side.color
            is TutorialSide -> side.color
            else -> null
        }
    }

    /** 特殊アイテムの効果が続く場所か (試合中のワールドか、進行中のチュートリアルの範囲) */
    fun isArena(location: Location): Boolean {
        return MatchManager.isRunning(location.world) || TutorialManager.isActiveArea(location)
    }
}
