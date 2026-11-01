package com.github.bea4dev.rushCraft.tutorial

import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.menu.ChestMenu
import com.github.bea4dev.rushCraft.util.Dialogs
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * チュートリアルの一覧 (チェストの GUI) と、チュートリアル中・クリア後のダイアログ。
 */
object TutorialMenu {
    private fun tr(key: String, vararg args: Component): Component =
        Component.translatable("rushcraft.tutorial.menu.$key", *args)

    /** 一覧。上に CTW のルール、その下に特殊アイテムが並ぶ */
    fun open(player: Player) {
        if (!TutorialManager.isEnabled) {
            player.sendMessage(Component.translatable("rushcraft.tutorial.unavailable", NamedTextColor.RED))
            return
        }
        // チュートリアル中はやり直し・やめるのダイアログ
        if (TutorialManager.isInTutorial(player)) {
            openInTutorial(player)
            return
        }
        if (!MatchManager.canStartTutorial(player)) {
            player.sendMessage(Component.translatable("rushcraft.tutorial.participating", NamedTextColor.RED))
            return
        }

        val menu = ChestMenu.Holder()
        val inventory = ChestMenu.create(player, menu, 45, tr("title"))
        inventory.setItem(4, ChestMenu.icon(player, ItemStack(Material.BOOK), tr("title").color(NamedTextColor.GOLD), tr("description")))

        val items = Tutorial.entries.filter { it.special != null }
        val placements = listOf(Tutorial.CTW to 13) +
            items.take(5).zip(ChestMenu.positions(minOf(5, items.size), row = 2)) +
            items.drop(5).zip(ChestMenu.positions(items.size - 5, row = 3))
        for ((tutorial, slot) in placements) {
            inventory.setItem(slot, icon(player, tutorial))
            menu.action(slot) {
                it.closeInventory()
                TutorialManager.start(it, tutorial)
            }
        }

        inventory.setItem(40, ChestMenu.icon(player, ItemStack(Material.BARRIER), tr("close").color(NamedTextColor.RED), null))
        menu.action(40) { it.closeInventory() }
        player.openInventory(inventory)
    }

    private fun icon(player: Player, tutorial: Tutorial): ItemStack {
        val completed = TutorialManager.isCompleted(player, tutorial)
        val status = if (completed) tr("completed").color(NamedTextColor.GREEN) else tr("not-completed").color(NamedTextColor.GRAY)
        val name = if (tutorial == Tutorial.CTW) {
            tutorial.displayName().color(NamedTextColor.GOLD).append(Component.text(" ")).append(tr("recommended").color(NamedTextColor.YELLOW))
        } else {
            tutorial.displayName().color(NamedTextColor.WHITE)
        }
        val stack = ChestMenu.icon(
            player,
            ItemStack(tutorial.icon),
            name,
            Component.translatable(tutorial.summaryKey),
            status,
            tr("start").color(NamedTextColor.YELLOW),
        )
        if (completed) stack.editMeta { it.setEnchantmentGlintOverride(true) }
        return stack
    }

    /** チュートリアル中のメニュー (手持ちのアイテムから開く) */
    private fun openInTutorial(player: Player) {
        val session = TutorialManager.session(player) ?: return
        Dialogs.show(
            player,
            tr("in-tutorial-title"),
            listOf(tr("in-tutorial", session.tutorial.displayName().color(NamedTextColor.YELLOW))),
            buttons = listOf(
                Dialogs.button(tr("retry").color(NamedTextColor.YELLOW)) { _, p -> TutorialManager.retry(p) },
                Dialogs.button(tr("restart").color(NamedTextColor.YELLOW)) { _, p -> TutorialManager.start(p, session.tutorial) },
                Dialogs.button(tr("others").color(NamedTextColor.AQUA)) { _, p ->
                    TutorialManager.leave(p)
                    open(p)
                },
                Dialogs.button(tr("leave").color(NamedTextColor.RED)) { _, p -> TutorialManager.leave(p) },
            ),
            exit = Dialogs.closeButton(tr("continue")),
        )
    }

    /** クリアして戻ったとき */
    fun showCompleted(player: Player, tutorial: Tutorial) {
        val next = TutorialManager.nextOf(player, tutorial)
        val body = listOfNotNull(
            tr("completed-body", tutorial.displayName().color(NamedTextColor.YELLOW)),
            next?.let { tr("next-body", it.displayName().color(NamedTextColor.AQUA)) },
        )
        val buttons = listOfNotNull(
            next?.let { Dialogs.button(tr("next", it.displayName()).color(NamedTextColor.GREEN)) { _, p -> TutorialManager.start(p, it) } },
            Dialogs.button(tr("list").color(NamedTextColor.AQUA)) { _, p -> open(p) },
        )
        Dialogs.show(player, tr("completed-title"), body, buttons = buttons, exit = Dialogs.closeButton(tr("close")))
    }
}
