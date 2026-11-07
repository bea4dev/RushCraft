package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.scheduler.Tickable
import com.github.bea4dev.rushCraft.tutorial.TutorialManager
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * 使い切った・壊れた特殊アイテムを時間で復活させる (盾・投げナイフ・TNT・即席壁・槍)。
 * 死んだ場合はリスポーンで配り直されるので取り消す。
 * チュートリアル中は何度も試せるよう、すぐに復活させる。
 *
 * 盾以外は、使い切ったスロットに復活するまで「補充待ち」のアイテムを置いておく
 * (空いたスロットに拾ったブロックなどが入り、復活したアイテムが別の場所に入ってしまわないように)。
 * 補充待ちのアイテムには復活までの残り時間をクールダウンの表示で出す。
 */
object ItemRecharge : Tickable {
    private val PLACEHOLDER_KEY = NamespacedKey("rushcraft", "recharging")

    private class Pending(val special: SpecialItem, val at: Int)

    private val pending = HashMap<UUID, MutableList<Pending>>()

    fun schedule(player: Player, special: SpecialItem, ticks: Int) {
        val list = pending.getOrPut(player.uniqueId, ::mutableListOf)
        list.removeIf { it.special == special }
        val delay = TutorialManager.rechargeTicks(player, ticks)
        list += Pending(special, Bukkit.getCurrentTick() + delay)
        player.setCooldown(cooldownGroup(special), delay)
    }

    /**
     * 復活の予定を入れる前に、補充待ちのアイテムにクールダウンの表示だけを出す
     * (透明スパイのように、効果が終わってから復活の時間を数え始めるもの)。
     */
    fun showCooldown(player: Player, special: SpecialItem, ticks: Int) {
        player.setCooldown(cooldownGroup(special), ticks)
    }

    /**
     * 手に持っている特殊アイテムを 1 つ使った。無くなったら、復活するまで補充待ちのアイテムを置いておく。
     */
    fun useUp(player: Player, special: SpecialItem) {
        val inventory = player.inventory
        val stack = inventory.itemInMainHand
        if (stack.amount > 1) {
            stack.amount--
            inventory.setItemInMainHand(stack)
            return
        }
        inventory.setItemInMainHand(placeholder(special))
    }

    /** 補充待ちのアイテム (使えない・捨てられない) */
    private fun placeholder(special: SpecialItem): ItemStack {
        val stack = ItemStack(Material.FIREWORK_STAR)
        stack.editMeta { meta ->
            meta.itemName(
                Component.translatable("rushcraft.kit.recharging", NamedTextColor.GRAY, Component.translatable(special.nameKey))
                    .decoration(TextDecoration.ITALIC, false)
            )
            // 種類ごとに別々のクールダウン表示にする
            meta.setUseCooldown(meta.useCooldown.also { it.cooldownGroup = cooldownGroup(special) })
            meta.persistentDataContainer.set(PLACEHOLDER_KEY, PersistentDataType.STRING, special.id)
        }
        KitManager.markNoDrop(stack)
        return stack
    }

    fun isPlaceholder(stack: ItemStack?): Boolean {
        return stack != null && !stack.isEmpty && stack.persistentDataContainer.has(PLACEHOLDER_KEY)
    }

    private fun placeholderOf(stack: ItemStack?): SpecialItem? {
        if (!isPlaceholder(stack)) return null
        return SpecialItem.byId(stack!!.persistentDataContainer.get(PLACEHOLDER_KEY, PersistentDataType.STRING) ?: return null)
    }

    private fun cooldownGroup(special: SpecialItem) = NamespacedKey("rushcraft", "recharge_${special.id}")

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
        val color = Combatants.colorOf(player) ?: return
        // 補充待ちのアイテムがあれば、その場所に戻す
        var reserved = inventory.contents.indexOfFirst { placeholderOf(it) == special }.takeIf { it >= 0 }
        for ((slot, stack) in KitManager.createSpecialItems(special, color)) {
            val at = reserved
            if (at != null) {
                inventory.setItem(at, stack)
                reserved = null
            } else {
                KitManager.give(player, slot, stack)
            }
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
