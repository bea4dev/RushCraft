package com.github.bea4dev.rushCraft.menu

import com.github.bea4dev.rushCraft.RushCraft
import com.github.bea4dev.rushCraft.util.TextWrap
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.translation.GlobalTranslator
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

/**
 * チェストの GUI の共通部品 (キット選択・チュートリアル一覧)。
 *
 * GUI の文字はプレイヤーの言語でここで描画する。
 */
object ChestMenu : Listener {
    /** 説明文を折り返す幅 (半角文字数) */
    private const val LORE_WIDTH = 40

    class Holder : InventoryHolder {
        lateinit var inventoryRef: Inventory
        val actions = HashMap<Int, (Player, ClickType) -> Unit>()

        override fun getInventory(): Inventory = inventoryRef

        /** クリックの種類を問わない操作 */
        fun action(slot: Int, action: (Player) -> Unit) {
            actions[slot] = { player, _ -> action(player) }
        }
    }

    fun create(player: Player, holder: Holder, size: Int, title: Component): Inventory {
        val inventory = Bukkit.createInventory(holder, size, render(player, title))
        holder.inventoryRef = inventory
        return inventory
    }

    fun render(player: Player, component: Component): Component = GlobalTranslator.render(component, player.locale())

    /** 行の中央に寄せて count 個並べたときのスロット番号 */
    fun positions(count: Int, row: Int): List<Int> {
        val gap = if (count <= 4) 2 else 1
        val width = (count - 1) * gap + 1
        val start = row * 9 + (9 - width) / 2
        return List(count) { start + it * gap }
    }

    /**
     * GUI 用のアイコン。説明は折り返して lore にし、その下に操作の案内を付ける。
     */
    fun icon(player: Player, stack: ItemStack, name: Component, description: Component?, vararg hints: Component): ItemStack {
        stack.editMeta { meta ->
            meta.itemName(render(player, name).decoration(TextDecoration.ITALIC, false))
            meta.displayName(null)
            val lore = mutableListOf<Component>()
            if (description != null) {
                val text = PlainTextComponentSerializer.plainText().serialize(render(player, description))
                lore += TextWrap.wrap(text, LORE_WIDTH).map { Component.text(it, NamedTextColor.GRAY) }
            }
            if (hints.isNotEmpty()) {
                if (lore.isNotEmpty()) lore += Component.empty()
                lore += hints.map { render(player, it) }
            }
            meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
            // 攻撃力などの表示は説明と重なるので隠す
            meta.addItemFlags(*ItemFlag.entries.toTypedArray())
        }
        return stack
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val action = holder.actions[event.rawSlot] ?: return
        val player = event.whoClicked as? Player ?: return
        val click = event.click
        // クリックの処理中に別の画面を開かないよう、次の tick に回す
        Bukkit.getScheduler().runTask(RushCraft.plugin, Runnable {
            if (player.isOnline && player.openInventory.topInventory.holder === holder) action(player, click)
        })
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder) event.isCancelled = true
    }
}
