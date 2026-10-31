package com.github.bea4dev.rushCraft.setup

import com.github.bea4dev.rushCraft.map.BlockPos
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

class SetupListener : Listener {
    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val tool = SetupManager.toolOf(event.item) ?: return
        event.isCancelled = true

        val session = SetupManager.session(player)
        if (session == null) {
            player.sendMessage(Component.translatable("rushcraft.setup.not-editing", NamedTextColor.RED))
            return
        }

        when (tool) {
            SetupManager.Tool.MENU -> SetupMenu.openMain(player, session)
            SetupManager.Tool.WAND -> {
                val block = event.clickedBlock ?: return
                when (event.action) {
                    Action.LEFT_CLICK_BLOCK -> SetupManager.setPosition(player, true, BlockPos.of(block))
                    Action.RIGHT_CLICK_BLOCK -> SetupManager.setPosition(player, false, BlockPos.of(block))
                    else -> {}
                }
            }
        }
    }

    // クリエイティブでは左クリックで即座にブロックが壊れるため、ツールでの破壊を防ぐ
    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (SetupManager.toolOf(event.player.inventory.itemInMainHand) != null) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (SetupManager.toolOf(event.itemInHand) != null) {
            event.isCancelled = true
        }
    }

    // ブロックの変更も未保存の変更として扱う
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreakMonitor(event: BlockBreakEvent) {
        SetupManager.session(event.player)?.dirty = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlaceMonitor(event: BlockPlaceEvent) {
        SetupManager.session(event.player)?.dirty = true
    }
}
