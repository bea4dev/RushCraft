package com.github.bea4dev.rushCraft.menu

import com.github.bea4dev.rushCraft.RushCraft
import com.github.bea4dev.rushCraft.kit.KitManager
import com.github.bea4dev.rushCraft.kit.SpecialItem
import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.match.TeamColor
import com.github.bea4dev.rushCraft.util.TextWrap
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.translation.GlobalTranslator
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

/**
 * キット (特殊アイテム) の選択画面。チェストの GUI で、選択は次のスポーンから反映される。
 *
 * - 最初のページに特殊アイテムの枠が並び、枠をクリックするとその枠のアイテム一覧のページに移る
 * - 一覧のアイテムをクリックすると枠に選ばれて最初のページに戻る (別の枠で選んでいたアイテムは入れ替え)
 *
 * GUI の文字はプレイヤーの言語でここで描画する。
 */
object KitMenu : Listener {
    /** 説明文を折り返す幅 (半角文字数) */
    private const val LORE_WIDTH = 40

    private class Menu : InventoryHolder {
        lateinit var inventoryRef: Inventory
        val actions = HashMap<Int, (Player) -> Unit>()

        override fun getInventory(): Inventory = inventoryRef
    }

    private fun tr(key: String, vararg args: Component): Component =
        Component.translatable("rushcraft.kit.menu.$key", *args)

    fun open(player: Player) {
        val match = MatchManager.match ?: return
        val team = match.getTeam(player)
        if (team == null) {
            player.sendMessage(tr("not-participant").color(NamedTextColor.RED))
            return
        }
        openSlots(player, team.color)
    }

    // ------------------------------------------------------------------
    // ページ
    // ------------------------------------------------------------------

    /** 最初のページ: 特殊アイテムの枠 */
    private fun openSlots(player: Player, color: TeamColor) {
        val slots = KitManager.slots(player)
        val menu = Menu()
        val inventory = create(player, menu, 27, tr("title"))

        inventory.setItem(
            4,
            icon(player, ItemStack(Material.BOOK), tr("title").color(NamedTextColor.GOLD), tr("description", Component.text(slots.size))),
        )

        for ((index, position) in positions(slots.size, row = 1).withIndex()) {
            val special = slots[index]
            val number = Component.text(index + 1)
            val stack = if (special != null) {
                icon(
                    player,
                    special.createItems(color).first().second,
                    tr("slot", number, Component.translatable(special.nameKey)).color(NamedTextColor.GREEN),
                    Component.translatable(special.descriptionKey),
                    tr("slot-change").color(NamedTextColor.YELLOW),
                )
            } else {
                icon(
                    player,
                    ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE),
                    tr("slot", number, tr("empty")).color(NamedTextColor.GRAY),
                    null,
                    tr("slot-choose").color(NamedTextColor.YELLOW),
                )
            }
            inventory.setItem(position, stack)
            menu.actions[position] = { openItems(it, color, index) }
        }

        inventory.setItem(22, icon(player, ItemStack(Material.BARRIER), tr("close").color(NamedTextColor.RED), null))
        menu.actions[22] = { it.closeInventory() }

        player.openInventory(inventory)
    }

    /** 枠のアイテム一覧 */
    private fun openItems(player: Player, color: TeamColor, index: Int) {
        val slots = KitManager.slots(player)
        val entries = SpecialItem.entries
        val itemRows = (entries.size + 8) / 9
        val size = (itemRows + 2) * 9
        val menu = Menu()
        val inventory = create(player, menu, size, tr("items-title", Component.text(index + 1)))

        for ((i, special) in entries.withIndex()) {
            val row = i / 9
            val inRow = minOf(9, entries.size - row * 9)
            val position = positions(inRow, row)[i % 9]
            val selectedAt = slots.indexOf(special)
            val status = when (selectedAt) {
                index -> tr("item-selected").color(NamedTextColor.GREEN)
                -1 -> tr("item-choose").color(NamedTextColor.YELLOW)
                else -> tr("item-swap", Component.text(selectedAt + 1)).color(NamedTextColor.YELLOW)
            }
            val stack = icon(
                player,
                special.createItems(color).first().second,
                Component.translatable(special.nameKey).color(if (selectedAt == index) NamedTextColor.GREEN else NamedTextColor.WHITE),
                Component.translatable(special.descriptionKey),
                status,
            )
            if (selectedAt == index) stack.editMeta { it.setEnchantmentGlintOverride(true) }
            inventory.setItem(position, stack)
            menu.actions[position] = { choose(it, color, index, special) }
        }

        val bottom = size - 9
        inventory.setItem(bottom, icon(player, ItemStack(Material.ARROW), tr("back").color(NamedTextColor.WHITE), null))
        menu.actions[bottom] = { openSlots(it, color) }
        inventory.setItem(
            bottom + 4,
            icon(player, ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE), tr("clear").color(NamedTextColor.GRAY), null),
        )
        menu.actions[bottom + 4] = { choose(it, color, index, null) }

        player.openInventory(inventory)
    }

    private fun choose(player: Player, color: TeamColor, index: Int, special: SpecialItem?) {
        KitManager.select(player, index, special)
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, 1.2f)
        player.sendActionBar(tr("saved").color(NamedTextColor.GREEN))
        openSlots(player, color)
    }

    // ------------------------------------------------------------------
    // 部品
    // ------------------------------------------------------------------

    /** 行の中央に寄せて count 個並べたときのスロット番号 */
    private fun positions(count: Int, row: Int): List<Int> {
        val gap = if (count <= 4) 2 else 1
        val width = (count - 1) * gap + 1
        val start = row * 9 + (9 - width) / 2
        return List(count) { start + it * gap }
    }

    private fun create(player: Player, menu: Menu, size: Int, title: Component): Inventory {
        val inventory = Bukkit.createInventory(menu, size, render(player, title))
        menu.inventoryRef = inventory
        return inventory
    }

    private fun render(player: Player, component: Component): Component = GlobalTranslator.render(component, player.locale())

    /**
     * GUI 用のアイコン。説明は折り返して lore にし、その下に操作の案内を付ける。
     */
    private fun icon(player: Player, stack: ItemStack, name: Component, description: Component?, vararg hints: Component): ItemStack {
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

    // ------------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: InventoryClickEvent) {
        val menu = event.view.topInventory.holder as? Menu ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val action = menu.actions[event.rawSlot] ?: return
        val player = event.whoClicked as? Player ?: return
        // クリックの処理中に別の画面を開かないよう、次の tick に回す
        Bukkit.getScheduler().runTask(RushCraft.plugin, Runnable {
            if (player.isOnline && player.openInventory.topInventory.holder === menu) action(player)
        })
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Menu) event.isCancelled = true
    }
}
