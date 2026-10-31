package com.github.bea4dev.rushCraft.match.mode

import com.github.bea4dev.rushCraft.match.Match
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.block.Block
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.FireworkExplodeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.inventory.ItemStack

class CTWListener(private val match: Match<CTW>) : Listener {
    private val ctw: CTW
        get() = match.mode

    private fun isMatchWorld(block: Block): Boolean = block.world == match.world

    /**
     * モニュメントへの設置はここで最終判断する。
     * モニュメントが建築禁止エリア内にあっても設置できるよう、優先度を HIGHEST にして
     * 他のリスナーによるキャンセルを上書きする。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        if (!isMatchWorld(block)) return
        val wool = ctw.woolAt(block) ?: return
        if (!match.isAlive()) return

        val player = event.player
        val team = match.getTeam(player)
        if (wool.state == WoolState.PLACED || team != wool.team || block.type != wool.material) {
            event.isCancelled = true
            player.sendActionBar(
                Component.translatable(
                    "rushcraft.match.ctw.monument.wrong",
                    NamedTextColor.RED,
                    wool.team.displayName(),
                    wool.displayName(),
                )
            )
            return
        }

        event.isCancelled = false
        ctw.place(wool, player)
    }

    /**
     * 無限湧きの羊毛。ブロックは壊さずに羊毛を 1 つ配る。
     * 建築禁止エリア内に置かれていても動くよう、他の判定より先 (LOW) に処理する。
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onSpawnerBreak(event: BlockBreakEvent) {
        val block = event.block
        if (!isMatchWorld(block) || !match.isAlive()) return
        val wool = ctw.spawnerAt(block) ?: return
        val player = event.player
        event.isCancelled = true

        val team = match.getTeam(player) ?: return
        if (ctw.isProtectedFrom(wool.material, team)) {
            player.sendActionBar(Component.translatable("rushcraft.match.ctw.wool.protected", NamedTextColor.RED))
            return
        }
        player.inventory.addItem(ItemStack(wool.material)).values.forEach {
            player.world.dropItemNaturally(player.location, it)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        if (!isMatchWorld(block)) return
        val player = event.player

        if (ctw.woolAt(block) != null) {
            event.isCancelled = true
            player.sendActionBar(Component.translatable("rushcraft.match.ctw.monument.protected", NamedTextColor.RED))
            return
        }

        // 守る側が自陣の羊毛を壊して奪取できなくするのを防ぐ
        if (ctw.isProtectedFrom(block.type, match.getTeam(player))) {
            event.isCancelled = true
            player.sendActionBar(Component.translatable("rushcraft.match.ctw.wool.protected", NamedTextColor.RED))
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (player.world != match.world) return
        if (ctw.isProtectedFrom(event.item.itemStack.type, match.getTeam(player))) {
            event.isCancelled = true
        }
    }

    /**
     * チェストなどから他チームの目標の羊毛を取り出せないようにする。
     */
    @EventHandler(ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (player.world != match.world) return
        val team = match.getTeam(player)

        val clickedTop = event.clickedInventory != null && event.clickedInventory == event.view.topInventory
        val current = event.currentItem
        if (clickedTop && current != null && ctw.isProtectedFrom(current.type, team)) {
            event.isCancelled = true
            player.sendActionBar(Component.translatable("rushcraft.match.ctw.wool.protected", NamedTextColor.RED))
            return
        }
        if (event.action == InventoryAction.COLLECT_TO_CURSOR && ctw.isProtectedFrom(event.cursor.type, team)) {
            event.isCancelled = true
        }
    }

    /** 染料での羊毛のクラフトによる抜け道を塞ぐ */
    @EventHandler
    fun onPrepareCraft(event: PrepareItemCraftEvent) {
        val result = event.inventory.result ?: return
        if (event.view.player.world != match.world) return
        if (ctw.objectivesOf(result.type).isNotEmpty()) {
            event.inventory.result = null
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (event.entity.world != match.world) return
        event.blockList().removeIf { ctw.woolAt(it) != null }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (!isMatchWorld(event.block)) return
        event.blockList().removeIf { ctw.woolAt(it) != null }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (!isMatchWorld(event.block)) return
        if (event.blocks.any { ctw.woolAt(it) != null || ctw.woolAt(it.getRelative(event.direction)) != null }) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (!isMatchWorld(event.block)) return
        if (event.blocks.any { ctw.woolAt(it) != null || ctw.woolAt(it.getRelative(event.direction)) != null }) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onFireworkExplode(event: FireworkExplodeEvent) {
        if (event.entity.world != match.world) return
        ctw.onFireworkExplode(event.entity)
    }

    /** 設置演出の花火ではダメージを受けない */
    @EventHandler(ignoreCancelled = true)
    fun onFireworkDamage(event: EntityDamageByEntityEvent) {
        val firework = event.damager as? Firework ?: return
        if (ctw.isCelebrationFirework(firework)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        if (isMatchWorld(event.block) && ctw.woolAt(event.block) != null) {
            event.isCancelled = true
        }
    }
}
