package com.github.bea4dev.rushCraft.tutorial

import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent
import com.github.bea4dev.rushCraft.kit.KitActionEvent
import com.github.bea4dev.rushCraft.kit.TrapManager
import com.github.bea4dev.rushCraft.match.CombatTracker
import org.bukkit.damage.DamageType
import org.bukkit.entity.Firework
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * チュートリアルのワールドの保護と、進行の判定に使うイベント。
 *
 * - ブロックは各チュートリアルが許した場所にしか置けず、許したものしか壊せない
 * - プレイヤーは死なず、倒される・奈落に落ちると段階の最初からやり直す
 * - 満腹度は減らず、アイテムは捨てられない
 */
class TutorialListener : Listener {
    private fun isTutorialWorld(entity: org.bukkit.entity.Entity): Boolean = TutorialWorld.world == entity.world

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        TutorialManager.leave(event.player, restore = false)
    }

    /** 他の方法でワールドから出された場合はチュートリアルを終える */
    @EventHandler
    fun onChangeWorld(event: PlayerChangedWorldEvent) {
        if (TutorialWorld.world != event.from) return
        TutorialManager.leave(event.player, restore = false)
    }

    // ------------------------------------------------------------------
    // ブロック
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!isTutorialWorld(event.player)) return
        val session = TutorialManager.session(event.player)
        if (session == null || !session.arena.contains(event.block.location) || !session.onBlockBreak(event)) {
            event.isCancelled = true
        }
    }

    // 即席壁の設置判定 (擬似的な BlockPlaceEvent) もここを通る
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!isTutorialWorld(event.player)) return
        val session = TutorialManager.session(event.player)
        if (session == null || !session.arena.contains(event.block.location) || !session.onBlockPlace(event)) {
            event.isCancelled = true
        }
    }

    // ------------------------------------------------------------------
    // ダメージ
    // ------------------------------------------------------------------

    /** プレイヤーは死なずにやり直す */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val session = TutorialManager.session(player) ?: return
        if (session.isDown || session.isCompleted) {
            event.isCancelled = true
            return
        }
        if (event.finalDamage < player.health) return
        event.isCancelled = true
        session.onDown(downReason(player, event))
    }

    private fun downReason(player: Player, event: EntityDamageEvent): String {
        CombatTracker.peekSpecialDeath(player)?.let { death ->
            return when (death.messageKey) {
                "rushcraft.match.death.knife" -> "rushcraft.tutorial.down.knife"
                "rushcraft.match.death.tnt" -> "rushcraft.tutorial.down.tnt"
                "rushcraft.match.death.tnt-self" -> "rushcraft.tutorial.down.tnt-self"
                "rushcraft.match.death.trap" -> "rushcraft.tutorial.down.trap"
                else -> "rushcraft.tutorial.down.killed"
            }
        }
        return when (event.cause) {
            EntityDamageEvent.DamageCause.VOID -> "rushcraft.tutorial.down.void"
            EntityDamageEvent.DamageCause.FALL -> "rushcraft.tutorial.down.fall"
            else -> "rushcraft.tutorial.down.killed"
        }
    }

    /** 演出の花火ではダメージを受けない */
    @EventHandler(ignoreCancelled = true)
    fun onFireworkDamage(event: EntityDamageByEntityEvent) {
        if (event.damager is Firework && isTutorialWorld(event.entity)) event.isCancelled = true
    }

    /** プレイヤーが敵のダミーに当てた攻撃を数える */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHitDummy(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? LivingEntity ?: return
        val session = TutorialManager.sessionOf(victim) ?: return
        if (session.dummyOf(victim)?.enemy != true) return
        val attacker = when (val damager = event.damager) {
            is Projectile -> damager.shooter
            else -> damager
        }
        if (attacker != session.player) return
        session.add("hit")
        session.add("hit.${event.damageSource.damageType.key.key}")
        session.dummyOf(victim)?.lastHit = causeOf(event.damageSource.damageType)
    }

    @EventHandler
    fun onDummyDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val session = TutorialManager.sessionOf(entity) ?: return
        if (entity is Player) return
        event.drops.clear()
        event.droppedExp = 0
        val dummy = session.dummyOf(entity) ?: return

        // 誰が何で倒したか
        val special = CombatTracker.takeSpecialDeath(entity)
        val source = event.damageSource
        val killer = special?.killer ?: source.causingEntity?.uniqueId
        val cause = when {
            // 投げ返した TNT で着火した本人が爆死した (自爆なので誰のキルにもならない)
            special?.messageKey == "rushcraft.match.death.tnt-self" -> "tnt-self"
            killer != session.player.uniqueId -> "other"
            special != null -> special.messageKey.substringAfterLast('.')
            else -> causeOf(source.damageType)
        }
        session.onDummyDeath(dummy, cause)
    }

    /** 倒し方の名前 (kill.<名前> で数える) */
    private fun causeOf(type: DamageType): String = when (type) {
        DamageType.MACE_SMASH -> "mace"
        DamageType.ARROW -> "arrow"
        DamageType.SPEAR -> "spear"
        else -> "melee"
    }

    // ------------------------------------------------------------------
    // その他の保護
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    fun onFoodLevelChange(event: FoodLevelChangeEvent) {
        if (isTutorialWorld(event.entity) && event.foodLevel < event.entity.foodLevel) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (TutorialManager.isInTutorial(event.player)) event.isCancelled = true
    }

    /** 会場を見て回る演出はスニークで飛ばせる */
    @EventHandler
    fun onStopSpectating(event: PlayerStopSpectatingEntityEvent) {
        val tour = TutorialManager.session(event.player)?.tour ?: return
        event.isCancelled = true
        tour.skip()
    }

    // ------------------------------------------------------------------
    // 進行の判定
    // ------------------------------------------------------------------

    @EventHandler
    fun onKitAction(event: KitActionEvent) {
        val player = event.actor as? Player ?: return
        TutorialManager.session(player)?.onKitAction(event)
    }

    @EventHandler
    fun onProjectileHit(event: ProjectileHitEvent) {
        val session = TutorialManager.sessionAt(event.entity.location) ?: return
        session.onProjectileHit(event)
    }

    /** トラップの感圧板を踏んだ (トラップならキャンセルされている) */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onStep(event: PlayerInteractEvent) {
        if (event.action != Action.PHYSICAL) return
        val block = event.clickedBlock ?: return
        val session = TutorialManager.session(event.player) ?: return
        if (TrapManager.isTrap(block)) session.add("trap.stepped")
    }
}
