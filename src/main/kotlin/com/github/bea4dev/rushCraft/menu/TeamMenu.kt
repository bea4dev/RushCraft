package com.github.bea4dev.rushCraft.menu

import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.util.Dialogs
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * チーム選択画面。
 */
object TeamMenu {
    private fun tr(key: String, vararg args: Component): Component =
        Component.translatable("rushcraft.team-menu.$key", *args)

    fun open(player: Player) {
        val match = MatchManager.match ?: return
        val current = match.getTeam(player)

        val body = listOf(
            tr(
                "description",
                Component.text(match.map.name, NamedTextColor.WHITE),
                Component.translatable(match.map.type.nameKey, NamedTextColor.AQUA),
            ),
        )

        val buttons = match.teams.map { team ->
            val max = team.definition.maxPlayers
            val count = if (max > 0) "${team.size}/$max" else team.size.toString()
            val label = Component.text()
                .append(if (team == current) Component.text("▶ ", NamedTextColor.WHITE) else Component.empty())
                .append(team.displayName())
                .append(Component.text(" ($count)", NamedTextColor.GRAY))
                .build()
            Dialogs.button(label) { _, p -> MatchManager.join(p, team.id) }
        } + listOf(
            Dialogs.button(tr("auto").color(NamedTextColor.GREEN)) { _, p -> MatchManager.join(p, null) },
            Dialogs.button(tr("spectate").color(NamedTextColor.GRAY)) { _, p -> MatchManager.spectate(p) },
        )

        Dialogs.show(player, tr("title"), body, buttons = buttons, exit = Dialogs.closeButton(tr("close")))
    }
}
