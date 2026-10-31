package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 使い切った・壊れた特殊アイテムを時間で復活させる (盾・投げナイフ・TNT)。
 * 死んだ場合はリスポーンで配り直されるので取り消す。
 */
object ItemRecharge : Tickable {
    private class Pending(val special: SpecialItem, val at: Int)

    private val pending = HashMap<UUID, MutableList<Pending>>()

    fun schedule(player: Player, special: SpecialItem, ticks: Int) {
        val list = pending.getOrPut(player.uniqueId, ::mutableListOf)
        list.removeIf { it.special == special }
        list += Pending(special, Bukkit.getCurrentTick() + ticks)
    }

    override fun tick() {
        val now = Bukkit.getCurrentTick()
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val (uuid, list) = iterator.next()
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !Combatants.isTarget(player)) {
                iterator.remove()
                continue
            }
            // スタン中はアイテムを没収しているので、解除されてから渡す
            if (ShieldManager.isStunned(player)) continue
            list.removeIf { entry ->
                if (now < entry.at) return@removeIf false
                give(player, entry.special)
                true
            }
            if (list.isEmpty()) iterator.remove()
        }
    }

    private fun give(player: Player, special: SpecialItem) {
        val inventory = player.inventory
        if (inventory.contents.any { KitManager.specialOf(it) == special }) return
        if (special == SpecialItem.TNT && TntManager.isActive(player)) return
        val color = MatchManager.match?.getTeam(player)?.color ?: return
        for ((slot, stack) in KitManager.createSpecialItems(special, color)) {
            KitManager.give(player, slot, stack)
        }
        player.playSound(Sound.sound(org.bukkit.Sound.ITEM_ARMOR_EQUIP_IRON, Sound.Source.PLAYER, 1f, 1.2f), Sound.Emitter.self())
        player.sendActionBar(
            Component.translatable("rushcraft.kit.recharged", NamedTextColor.GREEN, Component.translatable(special.nameKey))
        )
    }

    fun clear(player: Player) {
        pending.remove(player.uniqueId)
    }

    fun reset() {
        pending.clear()
    }

    override fun isAlive(): Boolean = true
}
