package com.github.bea4dev.rushCraft.menu

import com.github.bea4dev.rushCraft.kit.KitManager
import com.github.bea4dev.rushCraft.kit.SpecialItem
import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.util.Dialogs
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.translation.GlobalTranslator
import org.bukkit.entity.Player

/**
 * キット (特殊アイテム) の選択画面。選択は次のスポーンから反映される。
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

        val max = KitManager.config.maxSpecialItems
        val selected = KitManager.selection(player)

        val body = mutableListOf<DialogBody>()
        body += DialogBody.plainMessage(tr("description", Component.text(max, NamedTextColor.YELLOW)), Dialogs.BODY_WIDTH)
        for (special in SpecialItem.entries) {
            // Dialog 内のアイテム名は翻訳されないので、プレイヤーの言語で描画した名前を付けておく
            val icon = special.createItems(team.color).first().second
            icon.editMeta { meta ->
                val name = Component.translatable(special.nameKey).decoration(TextDecoration.ITALIC, false)
                meta.itemName(GlobalTranslator.render(name, player.locale()))
            }
            body += DialogBody.item(icon)
                .description(DialogBody.plainMessage(Component.translatable(special.descriptionKey), 240))
                .showTooltip(true)
                .build()
        }

        val inputs = SpecialItem.entries.map { special ->
            DialogInput.bool(special.id, Component.translatable(special.nameKey))
                .initial(special in selected)
                .build()
        }

        val confirm = Dialogs.button(tr("confirm").color(NamedTextColor.GREEN)) { view, p ->
            val chosen = SpecialItem.entries.filter { view.getBoolean(it.id) == true }
            if (chosen.size > max) {
                p.sendMessage(tr("too-many", Component.text(max)).color(NamedTextColor.RED))
                open(p)
                return@button
            }
            KitManager.select(p, chosen)
            p.sendMessage(tr("saved").color(NamedTextColor.GREEN))
        }

        Dialogs.showBodies(player, tr("title"), body, inputs, listOf(confirm), exit = Dialogs.closeButton(tr("close")), columns = 1)
    }
}
