package com.github.bea4dev.rushCraft.menu

import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.util.Dialogs
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * 参加画面。チームは選べず、人数の少ないチームに自動で振り分けられる。
 */
object TeamMenu {
    private fun tr(key: String, vararg args: Component): Component =
        Component.translatable("rushcraft.team-menu.$key", *args)

    fun open(player: Player) {
        val match = MatchManager.match ?: return
        val current = match.getTeam(player)

        // 各チームの人数
        val teams = Component.join(
            JoinConfiguration.separator(Component.text("  ")),
            match.teams.map { team ->
                val max = team.definition.maxPlayers
                val count = if (max > 0) "${team.size}/$max" else team.size.toString()
                team.displayName().append(Component.text(" $count", NamedTextColor.GRAY))
            },
        )
        val body = listOfNotNull(
            tr(
                "description",
                Component.text(match.map.name, NamedTextColor.WHITE),
                Component.translatable(match.map.type.nameKey, NamedTextColor.AQUA),
            ),
            teams,
            current?.let { tr("current", it.displayName()) },
        )

        val buttons = listOfNotNull(
            if (current == null) Dialogs.button(tr("join").color(NamedTextColor.GREEN)) { _, p -> MatchManager.join(p) } else null,
            Dialogs.button(tr("spectate").color(NamedTextColor.GRAY)) { _, p -> MatchManager.spectate(p) },
        )

        Dialogs.show(player, tr("title"), body, buttons = buttons, exit = Dialogs.closeButton(tr("close")))
    }
}
