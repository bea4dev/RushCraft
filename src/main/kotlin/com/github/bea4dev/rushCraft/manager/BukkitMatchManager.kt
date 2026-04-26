package com.github.bea4dev.rushCraft.manager

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team

class BukkitMatchManager(
    private val scoreboard: Scoreboard,
    title: Component = Component.text("Match", NamedTextColor.YELLOW),
    tabScoreTitle: Component = Component.text("Kills", NamedTextColor.RED),
) {
    companion object {
        private const val SIDEBAR_OBJECTIVE = "sidebar"
        private const val TAB_OBJECTIVE = "tab_score"
        private const val MAX_LINES = 15
    }

    private val sidebarObjective = scoreboard.registerNewObjective(
        SIDEBAR_OBJECTIVE,
        Criteria.DUMMY,
        title,
    )

    private val tabObjective = scoreboard.registerNewObjective(
        TAB_OBJECTIVE,
        Criteria.DUMMY,
        tabScoreTitle,
    )

    private val entries = List(MAX_LINES) { i ->
        // 各行の entry は一意であればよい。
        // 画面に出る文字列は Team の prefix に置く。
        "§${i.toString(16)}"
    }

    init {
        sidebarObjective.displaySlot = DisplaySlot.SIDEBAR
        tabObjective.displaySlot = DisplaySlot.PLAYER_LIST

        for (i in 0 until MAX_LINES) {
            val team = scoreboard.registerNewTeam("line_$i")
            team.addEntry(entries[i])
            team.prefix(Component.empty())
            team.suffix(Component.empty())
        }
    }

    /**
     * この Match 用 Scoreboard をプレイヤーに表示する。
     */
    fun showTo(player: Player) {
        player.scoreboard = scoreboard
    }

    fun showTo(players: Iterable<Player>) {
        players.forEach(::showTo)
    }

    /**
     * Entity のチーム色を設定する。
     *
     * Player にも Mob にも使える。
     *
     * この色チームは:
     * - ネームタグ色つき
     * - 当たり判定なし
     * - 同じチームの透明メンバーを見られる
     */
    fun setEntityColor(entity: Entity, color: NamedTextColor) {
        val team = getOrCreateColorTeam(color)
        team.addEntry(entity.scoreboardEntry())
    }

    /**
     * Player の Tab 上のスコアを設定する。
     *
     * キル数表示などに使う。
     */
    fun setTabScore(player: Player, score: Int) {
        tabObjective.getScore(player.name).score = score
    }

    fun clearTabScore(player: Player) {
        scoreboard.resetScores(player.name)
    }

    /**
     * スコアボードを List<Component> として丸ごと更新する。
     */
    fun setLines(lines: List<Component>) {
        val limited = lines.take(MAX_LINES)

        for (i in 0 until MAX_LINES) {
            val entry = entries[i]
            val team = scoreboard.getTeam("line_$i") ?: continue

            if (i < limited.size) {
                team.prefix(limited[i])
                team.suffix(Component.empty())
                sidebarObjective.getScore(entry).score = MAX_LINES - i
            } else {
                team.prefix(Component.empty())
                team.suffix(Component.empty())
                scoreboard.resetScores(entry)
            }
        }
    }

    /**
     * 1行だけ更新する。
     *
     * bukkitMatch[2] = Component.text("Red: 3", NamedTextColor.RED)
     */
    operator fun set(index: Int, line: Component) {
        require(index in 0 until MAX_LINES) {
            "Scoreboard line index must be 0..${MAX_LINES - 1}"
        }

        val entry = entries[index]
        val team = scoreboard.getTeam("line_$index") ?: return

        team.prefix(line)
        team.suffix(Component.empty())
        sidebarObjective.getScore(entry).score = MAX_LINES - index
    }

    fun setTitle(title: Component) {
        sidebarObjective.displayName(title)
    }

    private fun getOrCreateColorTeam(color: NamedTextColor): Team {
        val teamName = "c_${color.toString().lowercase()}".take(16)

        return scoreboard.getTeam(teamName)
            ?: scoreboard.registerNewTeam(teamName).also { team ->
                team.color(color)

                // ネームタグ・Tab上の名前色を確実に色付きにしたい場合、
                // color() だけではなく prefix() も設定しておくと扱いやすい。
                team.prefix(Component.text("", color))

                team.setCanSeeFriendlyInvisibles(true)

                team.setOption(
                    Team.Option.COLLISION_RULE,
                    Team.OptionStatus.NEVER,
                )
            }
    }

    private fun Entity.scoreboardEntry(): String {
        return when (this) {
            is Player -> name
            else -> uniqueId.toString()
        }
    }
}