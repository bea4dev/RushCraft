package com.github.bea4dev.rushCraft.match.mode

import com.github.bea4dev.rushCraft.map.BlockPos
import com.github.bea4dev.rushCraft.map.MapDefinition
import com.github.bea4dev.rushCraft.map.MapProblem
import com.github.bea4dev.rushCraft.map.Region
import com.github.bea4dev.rushCraft.match.MatchGameModeType
import com.github.bea4dev.rushCraft.match.ModeSettings
import com.github.bea4dev.rushCraft.util.TomlFormatException
import com.github.bea4dev.rushCraft.util.TomlWriter
import com.github.bea4dev.rushCraft.util.optTable
import com.github.bea4dev.rushCraft.util.requireString
import com.github.bea4dev.rushCraft.util.tables
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.DyeColor
import org.bukkit.Material
import org.tomlj.TomlTable

/**
 * 羊毛 1 つ分の設定。
 *
 * team のチームが color の羊毛を奪取し、monument の位置に設置すると達成。
 */
class WoolDefinition(
    var team: String,
    var color: DyeColor,
    var monument: BlockPos?,
    /** 羊毛部屋。奪取するチーム (team) 以外は入れない */
    var room: Region? = null,
    /** 無限湧きの羊毛。範囲内のこの色の羊毛を壊すと、壊さずに羊毛を 1 つ配る */
    val spawners: MutableList<Region> = mutableListOf(),
) {
    val material: Material
        get() = woolMaterial(color)

    fun displayName(): Component = woolName(color)

    companion object {
        fun woolMaterial(color: DyeColor): Material = Material.valueOf("${color.name}_WOOL")

        fun woolColor(color: DyeColor): TextColor = TextColor.color(color.color.asRGB())

        fun woolName(color: DyeColor): Component {
            return Component.translatable(woolMaterial(color).translationKey(), woolColor(color))
        }

        fun parseColor(value: String): DyeColor? = DyeColor.entries.find { it.name.equals(value, ignoreCase = true) }
    }
}

class CTWSettings(val wools: MutableList<WoolDefinition>) : ModeSettings {
    override val type: MatchGameModeType
        get() = CTWType

    override fun write(writer: TomlWriter) {
        for (wool in wools) {
            writer.arrayTable("${CTWType.id}.wools")
            writer.value("team", wool.team)
            writer.value("color", wool.color.name.lowercase())
            wool.monument?.let { writer.value("monument", it.toToml()) }
            wool.room?.let { writer.value("room", it.toToml()) }
            if (wool.spawners.isNotEmpty()) {
                writer.value("spawners", wool.spawners.map { it.toToml() })
            }
        }
    }

    override fun validate(map: MapDefinition): List<MapProblem> {
        val problems = mutableListOf<MapProblem>()
        if (wools.isEmpty()) {
            problems += MapProblem.error("rushcraft.map.problem.ctw.no-wools")
        }
        for (wool in wools) {
            val team = map.team(wool.team)
            if (team == null) {
                problems += MapProblem.error("rushcraft.map.problem.ctw.unknown-team", wool.displayName(), Component.text(wool.team))
            }
            val monument = wool.monument
            if (monument == null) {
                problems += MapProblem.error("rushcraft.map.problem.ctw.no-monument", wool.displayName())
            } else if (wools.count { it.monument == monument } > 1) {
                problems += MapProblem.error("rushcraft.map.problem.ctw.duplicate-monument", Component.text(monument.toString()))
            }
            if (wools.count { it.team == wool.team && it.color == wool.color } > 1) {
                problems += MapProblem.error("rushcraft.map.problem.ctw.duplicate-wool", wool.displayName())
            }
        }
        for (team in map.teams) {
            if (wools.none { it.team == team.id }) {
                problems += MapProblem.warning("rushcraft.map.problem.ctw.team-without-wool", team.displayName())
            }
        }
        return problems
    }

    companion object {
        fun parse(table: TomlTable?): CTWSettings {
            val wools = table?.tables("wools")?.map { wool ->
                val colorName = wool.requireString("color")
                WoolDefinition(
                    team = wool.requireString("team"),
                    color = WoolDefinition.parseColor(colorName) ?: throw TomlFormatException("unknown wool color: $colorName"),
                    monument = wool.optTable("monument")?.let(BlockPos::parse),
                    room = wool.optTable("room")?.let(Region::parse),
                    spawners = wool.tables("spawners").map(Region::parse).toMutableList(),
                )
            } ?: emptyList()
            return CTWSettings(wools.toMutableList())
        }
    }
}
