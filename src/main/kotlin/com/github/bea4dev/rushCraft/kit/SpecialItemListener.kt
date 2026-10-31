package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.RushCraft
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.entity.Trident
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerPickupArrowEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * スワッパー・投げナイフ・TNT の操作。
 */
class SpecialItemListener : Listener {
    // 空中への右クリックは最初からキャンセル扱いで届くので、キャンセル済みでも受け取る
    @EventHandler(priority = EventPriority.HIGH)
    fun onUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        // TNT を持っている間は、何を持っていてもクリックで投げる
        if (TntManager.isHolding(player)) {
            if (event.action == Action.PHYSICAL) return
            event.isCancelled = true
            TntManager.throwHeld(player)
            return
        }
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        when (KitManager.specialOf(event.item)) {
            SpecialItem.THROWING_KNIFE -> {
                event.isCancelled = true
                if (!Combatants.canUse(player)) return
                // 投げたら復活するまで使えない
                player.inventory.setItemInMainHand(null)
                ThrowingKnife.throwKnife(player)
            }

            SpecialItem.TNT -> {
                // ブロックとして置かせない
                event.isCancelled = true
                if (!Combatants.canUse(player)) return
                TntManager.ignite(player)
            }

            else -> {}
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlaceTnt(event: BlockPlaceEvent) {
        if (KitManager.specialOf(event.itemInHand) == SpecialItem.TNT) event.isCancelled = true
    }

    // ------------------------------------------------------------------
    // スワッパー
    // ------------------------------------------------------------------

    // パリィ (KitListener) で弾かれた場合はキャンセルされている
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSwapperHit(event: ProjectileHitEvent) {
        val trident = event.entity as? Trident ?: return
        if (!Swapper.isSwapper(trident)) return
        val thrower = trident.shooter as? Player ?: return
        val target = event.hitEntity as? Player ?: return
        if (target == thrower) return
        // ダメージを与えてから入れ替える
        Bukkit.getScheduler().runTask(RushCraft.plugin, Runnable { Swapper.swap(thrower, target) })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onThrowSwapper(event: ProjectileLaunchEvent) {
        val trident = event.entity as? Trident ?: return
        if (Swapper.isSwapper(trident)) Swapper.track(trident)
    }

    /** 他の人が投げたスワッパーは拾えない */
    @EventHandler(ignoreCancelled = true)
    fun onPickupSwapper(event: PlayerPickupArrowEvent) {
        val trident = event.arrow as? Trident ?: return
        if (Swapper.isSwapper(trident) && trident.ownerUniqueId != event.player.uniqueId) event.isCancelled = true
    }

    // ------------------------------------------------------------------
    // TNT を持っている間はクリック (左右どちらでも) で投げる
    // ------------------------------------------------------------------

    @EventHandler
    fun onSwing(event: PlayerAnimationEvent) {
        if (event.animationType != PlayerAnimationType.ARM_SWING) return
        if (TntManager.isHolding(event.player)) TntManager.throwHeld(event.player)
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onInteractEntityWithTnt(event: PlayerInteractEntityEvent) {
        if (!TntManager.isHolding(event.player)) return
        event.isCancelled = true
        TntManager.throwHeld(event.player)
    }

    /** TNT を持っている間の攻撃は、攻撃せずに投げる */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onAttackWithTnt(event: EntityDamageByEntityEvent) {
        val attacker = event.damager as? Player ?: return
        if (!TntManager.isHolding(attacker)) return
        event.isCancelled = true
        TntManager.throwHeld(attacker)
    }

    // ------------------------------------------------------------------
    // 後片付け
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        TntManager.onLeave(event.player)
        Swapper.removeThrown(event.player)
        // リスポーンで配り直される
        ItemRecharge.clear(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        TntManager.onLeave(event.player)
        Swapper.removeThrown(event.player)
        ItemRecharge.clear(event.player)
    }
}
