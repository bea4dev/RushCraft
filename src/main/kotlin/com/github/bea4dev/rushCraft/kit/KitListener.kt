package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.RushCraft
import com.github.bea4dev.rushCraft.menu.KitMenu
import com.github.bea4dev.rushCraft.menu.TeamMenu
import com.github.bea4dev.rushCraft.tutorial.TutorialManager
import com.github.bea4dev.rushCraft.tutorial.TutorialMenu
import org.bukkit.Bukkit
import org.bukkit.damage.DamageType
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * キット選択などの GUI アイテム、死亡時のドロップ、盾のパリィとスタン。
 */
class KitListener : Listener {
    // ------------------------------------------------------------------
    // GUI アイテム
    // ------------------------------------------------------------------

    private fun open(player: Player, type: KitManager.UiItem) {
        when (type) {
            KitManager.UiItem.TEAM_SELECTOR -> TeamMenu.open(player)
            KitManager.UiItem.KIT_SELECTOR -> KitMenu.open(player)
            KitManager.UiItem.TUTORIAL -> TutorialMenu.open(player)
            KitManager.UiItem.LEAVE_TUTORIAL -> TutorialManager.leave(player)
        }
    }

    // 待機中はワールド保護で操作がキャンセルされるので、キャンセル済みでも受け取る
    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val type = KitManager.uiItemOf(event.item) ?: return
        // TNT を持たされている間のクリックは投げる操作 (SpecialItemListener)
        if (TntManager.isHolding(event.player)) return
        event.isCancelled = true
        open(event.player, type)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val hotbar = if (event.click == ClickType.NUMBER_KEY) player.inventory.getItem(event.hotbarButton) else null
        val type = KitManager.uiItemOf(event.currentItem)
            ?: KitManager.uiItemOf(event.cursor)
            ?: KitManager.uiItemOf(hotbar)
            ?: return
        event.isCancelled = true
        // インベントリ画面を閉じてから開く
        Bukkit.getScheduler().runTask(RushCraft.plugin, Runnable {
            player.closeInventory()
            open(player, type)
        })
    }

    @EventHandler(ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) {
        if (KitManager.uiItemOf(event.oldCursor) != null) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (KitManager.uiItemOf(event.itemDrop.itemStack) != null) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        if (KitManager.uiItemOf(event.mainHandItem) != null || KitManager.uiItemOf(event.offHandItem) != null) {
            event.isCancelled = true
        }
    }

    // ------------------------------------------------------------------
    // 死亡時のドロップ
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    fun onDeathStunned(event: PlayerDeathEvent) {
        // スタン中に没収していたアイテムもドロップの対象にする
        event.drops += ShieldManager.takeStunnedItems(event.player)
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        event.drops.removeIf(KitManager::isNoDrop)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        ShieldManager.release(event.player)
    }

    // ------------------------------------------------------------------
    // パリィ
    // ------------------------------------------------------------------

    /** 近接攻撃 (メイスの叩きつけ・槍を含む) か */
    private fun isMelee(event: EntityDamageByEntityEvent): Boolean {
        if (event.cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK ||
            event.cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
        ) return true
        val type = event.damageSource.damageType
        return type == DamageType.MACE_SMASH || type == DamageType.SPEAR
    }

    // 攻撃してくるのはプレイヤーとは限らない (チュートリアルのダミー)
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onMeleeParry(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = event.damager as? LivingEntity ?: return
        if (!isMelee(event)) return
        if (!ShieldManager.isParrying(victim)) return
        // 味方からの攻撃は対象外
        if (!Combatants.isTarget(victim) || !Combatants.isEnemy(victim, attacker)) return

        event.isCancelled = true
        ShieldManager.parryMelee(victim, attacker)
    }

    /** ガード中に受けた近接攻撃を覚えておく (直後にガードを解除するとパリィになる) */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGuardedMelee(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = event.damager as? LivingEntity ?: return
        if (!isMelee(event)) return
        if (!Combatants.isTarget(victim) || !Combatants.isEnemy(victim, attacker)) return
        ShieldManager.onGuardedMelee(victim, attacker)
    }

    /** ガードしたまま攻撃を受けると盾の耐久が大きく減る */
    @Suppress("DEPRECATION")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockedHit(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        if (!Combatants.isTarget(victim)) return
        if (!event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) return
        if (event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) >= 0.0) return
        ShieldManager.onBlockedHit(victim)
    }

    @EventHandler(ignoreCancelled = true)
    fun onArrowParry(event: ProjectileHitEvent) {
        val victim = event.hitEntity as? Player ?: return
        val arrow = event.entity as? AbstractArrow ?: return
        if (arrow.shooter == victim || !ShieldManager.isParrying(victim)) return
        event.isCancelled = true
        ShieldManager.parryArrow(victim, arrow)
    }

    // ------------------------------------------------------------------
    // スタン中の制限
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    fun onDismount(event: EntityDismountEvent) {
        val entity = event.entity as? LivingEntity ?: return
        if (ShieldManager.isStunned(entity)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onStunnedAttack(event: EntityDamageByEntityEvent) {
        // 特殊アイテムによる即死 (爆発までにスタンしていても TNT は有効)
        if (event.cause == EntityDamageEvent.DamageCause.KILL) return
        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return
        if (ShieldManager.isStunned(attacker)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onStunnedBreak(event: BlockBreakEvent) {
        if (ShieldManager.isStunned(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onStunnedPlace(event: BlockPlaceEvent) {
        if (ShieldManager.isStunned(event.player)) event.isCancelled = true
    }
}
