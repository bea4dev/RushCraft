package com.github.bea4dev.rushCraft.kit

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import com.github.bea4dev.rushCraft.RushCraft
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Trident
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemDamageEvent
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
        if (event.action == Action.PHYSICAL) return
        val rightClick = event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK
        when (KitManager.specialOf(event.item)) {
            SpecialItem.THROWING_KNIFE -> {
                if (!rightClick) return
                event.isCancelled = true
                if (!Combatants.canUse(player)) return
                // 投げたら復活するまで使えない
                ItemRecharge.useUp(player, SpecialItem.THROWING_KNIFE)
                ThrowingKnife.throwKnife(player)
            }

            SpecialItem.INSTANT_WALL -> {
                // 普通のブロックとしては置かせない
                event.isCancelled = true
                val clicked = event.clickedBlock
                if (event.action != Action.RIGHT_CLICK_BLOCK || clicked == null || !Combatants.canUse(player)) return
                if (!InstantWall.build(player, clicked.getRelative(event.blockFace))) return
                ItemRecharge.useUp(player, SpecialItem.INSTANT_WALL)
                ItemRecharge.schedule(player, SpecialItem.INSTANT_WALL, InstantWall.RECHARGE_TICKS)
            }

            SpecialItem.TNT -> {
                // 左右どちらのクリックでも着火する。ブロックとして置かせない
                event.isCancelled = true
                if (!Combatants.canUse(player)) return
                TntManager.ignite(player)
            }

            else -> {}
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlaceSpecial(event: BlockPlaceEvent) {
        val special = KitManager.specialOf(event.itemInHand)
        if (special == SpecialItem.TNT || special == SpecialItem.INSTANT_WALL) event.isCancelled = true
    }

    // ------------------------------------------------------------------
    // トラップ
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlaceTrap(event: BlockPlaceEvent) {
        if (KitManager.specialOf(event.itemInHand) != SpecialItem.TRAP) return
        TrapManager.place(event.player, event.blockPlaced)
    }

    /** トラップの感圧板は誰が踏んでも反応させず、敵が踏んだときだけ爆発させる */
    @EventHandler(priority = EventPriority.LOW)
    fun onStepTrap(event: PlayerInteractEvent) {
        if (event.action != Action.PHYSICAL) return
        val block = event.clickedBlock ?: return
        if (TrapManager.onStep(event.player, block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onEntityStepTrap(event: EntityInteractEvent) {
        if (TrapManager.isTrap(event.block)) event.isCancelled = true
    }

    /** 壊されたトラップは何もドロップせずに解除する */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreakTrap(event: BlockBreakEvent) {
        if (!TrapManager.isTrap(event.block)) return
        event.isDropItems = false
        TrapManager.remove(event.block)
    }

    /** 下のブロックが壊れたなどで外れたとき */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDestroyTrap(event: BlockDestroyEvent) {
        if (!TrapManager.isTrap(event.block)) return
        event.setWillDrop(false)
        TrapManager.remove(event.block)
    }

    // ------------------------------------------------------------------
    // メイス・槍
    // ------------------------------------------------------------------

    /** メイスの叩きつけはダメージを上げる (盾などの処理より先に) */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onSmash(event: EntityDamageByEntityEvent) {
        if (MaceManager.isSmash(event)) MaceManager.boostSmash(event)
    }

    /** メイスの叩きつけが当たったら、周りの敵も巻き込む */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSmashed(event: EntityDamageByEntityEvent) {
        if (!MaceManager.isSmash(event)) return
        val victim = event.entity as? LivingEntity ?: return
        MaceManager.splashSmash(event.damager as LivingEntity, victim, event.damage)
    }

    /** ダメージを受けたらメイスのチャージを解除する */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamaged(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        MaceManager.onDamaged(player)
    }

    /**
     * 槍の耐久はバニラでは減らさず、使った回数として数える。
     * 盾の耐久は ShieldManager が内部で持つので、アイテムの耐久は減らさない。
     */
    @EventHandler(ignoreCancelled = true)
    fun onItemDamage(event: PlayerItemDamageEvent) {
        when (KitManager.specialOf(event.item)) {
            SpecialItem.SPEAR -> {
                event.isCancelled = true
                if (Combatants.canUse(event.player)) SpearManager.onUse(event.player)
            }

            SpecialItem.SHIELD -> event.isCancelled = true
            else -> {}
        }
    }

    // ------------------------------------------------------------------
    // スワッパー
    // ------------------------------------------------------------------

    // パリィ (KitListener) で弾かれた場合はキャンセルされている
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSwapperHit(event: ProjectileHitEvent) {
        val trident = event.entity as? Trident ?: return
        if (!Swapper.isSwapper(trident)) return
        // チュートリアルではダミーにも当たる
        val thrower = trident.shooter as? LivingEntity ?: return
        val target = event.hitEntity as? LivingEntity ?: return
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

    /** TNT を持っている間・TNT アイテムでの攻撃は、攻撃せずに投げる・着火する */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onAttackWithTnt(event: EntityDamageByEntityEvent) {
        val attacker = event.damager as? Player ?: return
        if (TntManager.isHolding(attacker)) {
            event.isCancelled = true
            TntManager.throwHeld(attacker)
        } else if (KitManager.specialOf(attacker.inventory.itemInMainHand) == SpecialItem.TNT) {
            // TNT アイテムで殴ったときも着火する
            event.isCancelled = true
            if (Combatants.canUse(attacker)) TntManager.ignite(attacker)
        }
    }

    /** 補充待ちのアイテムは捨てられない */
    @EventHandler(ignoreCancelled = true)
    fun onDropPlaceholder(event: PlayerDropItemEvent) {
        if (ItemRecharge.isPlaceholder(event.itemDrop.itemStack)) event.isCancelled = true
    }

    // ------------------------------------------------------------------
    // 後片付け
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        TntManager.onLeave(event.player)
        Swapper.removeThrown(event.player)
        MaceManager.clear(event.player)
        SpearManager.clear(event.player)
        // リスポーンで配り直される
        ItemRecharge.clear(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        TntManager.onLeave(event.player)
        Swapper.removeThrown(event.player)
        MaceManager.clear(event.player)
        SpearManager.clear(event.player)
        ItemRecharge.clear(event.player)
    }
}
