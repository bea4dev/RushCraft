package com.github.bea4dev.rushCraft.menu

import com.github.bea4dev.rushCraft.kit.KitManager
import com.github.bea4dev.rushCraft.kit.SpecialItem
import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.match.TeamColor
import com.github.bea4dev.rushCraft.tutorial.Tutorial
import com.github.bea4dev.rushCraft.tutorial.TutorialManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * キット (特殊アイテム) の選択画面。チェストの GUI で、選択は次のスポーンから反映される。
 *
 * - 最初のページに特殊アイテムの枠が並び、枠をクリックするとその枠のアイテム一覧のページに移る
 * - 一覧のアイテムをクリックすると枠に選ばれて最初のページに戻る (別の枠で選んでいたアイテムは入れ替え)
 * - 一覧のアイテムをシフトクリックすると、そのアイテムのチュートリアルを始める (受けられるときだけ)
 */
object KitMenu {
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
        val menu = ChestMenu.Holder()
        val inventory = ChestMenu.create(player, menu, 27, tr("title"))

        inventory.setItem(
            4,
            ChestMenu.icon(player, ItemStack(Material.BOOK), tr("title").color(NamedTextColor.GOLD), tr("description", Component.text(slots.size))),
        )

        for ((index, position) in ChestMenu.positions(slots.size, row = 1).withIndex()) {
            val special = slots[index]
            val number = Component.text(index + 1)
            val stack = if (special != null) {
                ChestMenu.icon(
                    player,
                    special.createItems(color).first().second,
                    tr("slot", number, Component.translatable(special.nameKey)).color(NamedTextColor.GREEN),
                    Component.translatable(special.descriptionKey),
                    tr("slot-change").color(NamedTextColor.YELLOW),
                )
            } else {
                ChestMenu.icon(
                    player,
                    ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE),
                    tr("slot", number, tr("empty")).color(NamedTextColor.GRAY),
                    null,
                    tr("slot-choose").color(NamedTextColor.YELLOW),
                )
            }
            inventory.setItem(position, stack)
            menu.action(position) { openItems(it, color, index) }
        }

        inventory.setItem(22, ChestMenu.icon(player, ItemStack(Material.BARRIER), tr("close").color(NamedTextColor.RED), null))
        menu.action(22) { it.closeInventory() }

        player.openInventory(inventory)
    }

    /** 枠のアイテム一覧 */
    private fun openItems(player: Player, color: TeamColor, index: Int) {
        val slots = KitManager.slots(player)
        val entries = SpecialItem.entries
        val itemRows = (entries.size + 8) / 9
        val size = (itemRows + 2) * 9
        val menu = ChestMenu.Holder()
        val inventory = ChestMenu.create(player, menu, size, tr("items-title", Component.text(index + 1)))
        val canTutorial = MatchManager.canStartTutorial(player)

        for ((i, special) in entries.withIndex()) {
            val row = i / 9
            val inRow = minOf(9, entries.size - row * 9)
            val position = ChestMenu.positions(inRow, row)[i % 9]
            val selectedAt = slots.indexOf(special)
            val status = when (selectedAt) {
                index -> tr("item-selected").color(NamedTextColor.GREEN)
                -1 -> tr("item-choose").color(NamedTextColor.YELLOW)
                else -> tr("item-swap", Component.text(selectedAt + 1)).color(NamedTextColor.YELLOW)
            }
            val tutorial = Tutorial.of(special)
            val hints = listOfNotNull(
                status,
                if (tutorial != null && canTutorial) tr("item-tutorial").color(NamedTextColor.AQUA) else null,
            )
            val stack = ChestMenu.icon(
                player,
                special.createItems(color).first().second,
                Component.translatable(special.nameKey).color(if (selectedAt == index) NamedTextColor.GREEN else NamedTextColor.WHITE),
                Component.translatable(special.descriptionKey),
                *hints.toTypedArray(),
            )
            if (selectedAt == index) stack.editMeta { it.setEnchantmentGlintOverride(true) }
            inventory.setItem(position, stack)
            menu.actions[position] = { p, click ->
                if (click.isShiftClick && tutorial != null && canTutorial) {
                    p.closeInventory()
                    TutorialManager.start(p, tutorial)
                } else {
                    choose(p, color, index, special)
                }
            }
        }

        val bottom = size - 9
        inventory.setItem(bottom, ChestMenu.icon(player, ItemStack(Material.ARROW), tr("back").color(NamedTextColor.WHITE), null))
        menu.action(bottom) { openSlots(it, color) }
        inventory.setItem(
            bottom + 4,
            ChestMenu.icon(player, ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE), tr("clear").color(NamedTextColor.GRAY), null),
        )
        menu.action(bottom + 4) { choose(it, color, index, null) }

        player.openInventory(inventory)
    }

    private fun choose(player: Player, color: TeamColor, index: Int, special: SpecialItem?) {
        KitManager.select(player, index, special)
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, 1.2f)
        player.sendActionBar(tr("saved").color(NamedTextColor.GREEN))
        openSlots(player, color)
    }
}
