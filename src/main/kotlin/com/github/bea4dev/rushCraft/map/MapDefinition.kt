package com.github.bea4dev.rushCraft.map

import com.github.bea4dev.rushCraft.kit.KitManager
import com.github.bea4dev.rushCraft.match.MatchGameModeType
import com.github.bea4dev.rushCraft.match.MatchGameModeTypes
import com.github.bea4dev.rushCraft.match.ModeSettings
import com.github.bea4dev.rushCraft.match.TeamColor
import com.github.bea4dev.rushCraft.util.TomlFormatException
import com.github.bea4dev.rushCraft.util.TomlWriter
import com.github.bea4dev.rushCraft.util.bool
import com.github.bea4dev.rushCraft.util.int
import com.github.bea4dev.rushCraft.util.optInt
import com.github.bea4dev.rushCraft.util.optLong
import com.github.bea4dev.rushCraft.util.optString
import com.github.bea4dev.rushCraft.util.optTable
import com.github.bea4dev.rushCraft.util.requireString
import com.github.bea4dev.rushCraft.util.string
import com.github.bea4dev.rushCraft.util.strings
import com.github.bea4dev.rushCraft.util.tables
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Tag
import org.tomlj.Toml
import java.io.File

class MapProblem(val error: Boolean, val message: Component) {
    companion object {
        fun error(key: String, vararg args: Component) = MapProblem(true, Component.translatable(key, *args))

        fun warning(key: String, vararg args: Component) = MapProblem(false, Component.translatable(key, *args))
    }
}

class TeamDefinition(
    var id: String,
    var color: TeamColor,
    /** null の場合は色ごとの翻訳名を使う */
    var name: String?,
    /** 0 で無制限 */
    var maxPlayers: Int,
    val spawns: MutableList<SpawnPoint>,
    /** スポーン部屋。このチームしか入れず、誰も建築できない */
    val spawnRooms: MutableList<Region> = mutableListOf(),
) {
    fun displayName(): Component {
        return name?.let { Component.text(it, color.text) } ?: color.displayName()
    }
}

/**
 * maps/<id>/map.toml の内容。
 *
 * マップディレクトリはそのままワールドフォルダ (level.dat, region/ ...) を兼ねる。
 */
class MapDefinition(
    val id: String,
    val directory: File,
    var name: String,
    var version: String,
    val authors: MutableList<String>,
    val type: MatchGameModeType,
    /** 秒。0 で無制限 */
    var timeLimit: Int,
    /** この高さより上には建築できない。null で無制限 */
    var maxBuildHeight: Int?,
    /** 待機中・観戦者のスポーン */
    var spawn: SpawnPoint,
    var time: Long,
    val teams: MutableList<TeamDefinition>,
    val kit: KitDefinition,
    val noBuildRegions: MutableList<Region>,
    val settings: ModeSettings,
) {
    val file: File
        get() = File(directory, FILE_NAME)

    fun team(id: String): TeamDefinition? = teams.find { it.id == id }

    fun validate(): List<MapProblem> {
        val problems = mutableListOf<MapProblem>()
        if (teams.isEmpty()) {
            problems += MapProblem.error("rushcraft.map.problem.no-teams")
        }
        for (team in teams) {
            if (team.spawns.isEmpty()) {
                problems += MapProblem.error("rushcraft.map.problem.no-spawns", team.displayName())
            }
            if (teams.count { it.id == team.id } > 1) {
                problems += MapProblem.error("rushcraft.map.problem.duplicate-team", Component.text(team.id))
            }
            if (teams.count { it.color == team.color } > 1) {
                problems += MapProblem.error("rushcraft.map.problem.duplicate-color", team.displayName())
            }
        }
        if (teams.size == 1) {
            problems += MapProblem.warning("rushcraft.map.problem.single-team")
        }
        kit.trapPlate?.let { plate ->
            val material = Material.matchMaterial(plate)
            if (material == null || !Tag.PRESSURE_PLATES.isTagged(material)) {
                problems += MapProblem.error("rushcraft.map.problem.invalid-trap-plate", Component.text(plate))
            }
        }
        // 特殊アイテムと合わせてホットバーに入り切るか (特殊アイテムは 1 つにつき 1 枠使うとして数える)
        val hotbarItems = kit.items.count { it.slot in 0..8 }
        val specials = KitManager.config.maxSpecialItems
        if (hotbarItems + specials > 9) {
            problems += MapProblem.warning(
                "rushcraft.map.problem.hotbar-overflow", Component.text(hotbarItems), Component.text(specials)
            )
        }
        problems += settings.validate(this)
        return problems
    }

    fun save() {
        val writer = TomlWriter()
        writer.comment("RushCraft map definition ($id)")
        writer.value("name", name)
        writer.value("version", version)
        writer.value("authors", authors.toList())
        writer.value("gamemode", type.id)
        writer.comment("試合時間 (秒)。0 で無制限")
        writer.value("time-limit", timeLimit)
        maxBuildHeight?.let { writer.value("max-build-height", it) }
        writer.comment("待機中・観戦者のスポーン")
        writer.value("spawn", spawn.toToml())

        writer.table("world")
        writer.comment("時刻 (試合中は常にこの時刻で止まる)")
        writer.value("time", time)

        writer.table("kit")
        writer.comment("革防具をチーム色に染めるか")
        writer.value("team-colored-armor", kit.teamColoredArmor)
        writer.comment("キットのアイテムを死亡時にドロップするか")
        writer.value("drop-on-death", kit.dropOnDeath)
        writer.comment("特殊アイテムのトラップに使う感圧板 (例: \"minecraft:polished_blackstone_pressure_plate\")。省略時は石の感圧板")
        kit.trapPlate?.let { writer.value("trap-plate", it) }
        writer.value("items", kit.items.map { it.toToml() })

        for (team in teams) {
            writer.arrayTable("teams")
            writer.value("id", team.id)
            writer.value("color", team.color.name.lowercase())
            team.name?.let { writer.value("name", it) }
            writer.value("max-players", team.maxPlayers)
            writer.value("spawns", team.spawns.map { it.toToml() })
            if (team.spawnRooms.isNotEmpty()) {
                writer.value("spawn-rooms", team.spawnRooms.map { it.toToml() })
            }
        }

        for (region in noBuildRegions) {
            writer.arrayTable("no-build")
            region.toToml().forEach { (key, value) -> writer.value(key, value) }
        }

        settings.write(writer)

        directory.mkdirs()
        file.writeText(writer.toString())
    }

    companion object {
        const val FILE_NAME = "map.toml"
        val ID_PATTERN = Regex("[a-z0-9_-]{1,32}")

        fun load(directory: File): MapDefinition {
            val id = directory.name
            if (!ID_PATTERN.matches(id)) {
                throw TomlFormatException("map directory name must match ${ID_PATTERN.pattern}: $id")
            }

            val result = Toml.parse(File(directory, FILE_NAME).toPath())
            if (result.hasErrors()) {
                throw TomlFormatException(result.errors().joinToString("\n"))
            }

            val gamemode = result.requireString("gamemode")
            val type = MatchGameModeTypes.get(gamemode) ?: throw TomlFormatException("unknown gamemode: $gamemode")

            val world = result.optTable("world")
            val kit = result.optTable("kit")

            val teams = result.tables("teams").map { table ->
                val teamId = table.requireString("id")
                val colorName = table.requireString("color")
                TeamDefinition(
                    id = teamId,
                    color = TeamColor.parse(colorName) ?: throw TomlFormatException("unknown team color: $colorName"),
                    name = table.optString("name"),
                    maxPlayers = table.int("max-players", 0).coerceAtLeast(0),
                    spawns = table.tables("spawns").map(SpawnPoint::parse).toMutableList(),
                    spawnRooms = table.tables("spawn-rooms").map(Region::parse).toMutableList(),
                )
            }

            return MapDefinition(
                id = id,
                directory = directory,
                name = result.string("name", id),
                version = result.string("version", "1.0.0"),
                authors = result.strings("authors").toMutableList(),
                type = type,
                timeLimit = result.int("time-limit", 0).coerceAtLeast(0),
                maxBuildHeight = result.optInt("max-build-height"),
                spawn = result.optTable("spawn")?.let(SpawnPoint::parse) ?: SpawnPoint.DEFAULT,
                time = world?.optLong("time") ?: 6000L,
                teams = teams.toMutableList(),
                kit = KitDefinition(
                    items = kit?.tables("items")?.map(KitItem::parse)?.toMutableList() ?: mutableListOf(),
                    teamColoredArmor = kit?.bool("team-colored-armor", true) ?: true,
                    dropOnDeath = kit?.bool("drop-on-death", false) ?: false,
                    trapPlate = kit?.optString("trap-plate"),
                ),
                noBuildRegions = result.tables("no-build").map(Region::parse).toMutableList(),
                settings = type.parseSettings(result.optTable(type.id)),
            )
        }

        fun createDefault(id: String, directory: File, type: MatchGameModeType): MapDefinition {
            return MapDefinition(
                id = id,
                directory = directory,
                name = id,
                version = "1.0.0",
                authors = mutableListOf(),
                type = type,
                timeLimit = 0,
                maxBuildHeight = null,
                spawn = SpawnPoint.DEFAULT,
                time = 6000L,
                teams = mutableListOf(
                    TeamDefinition("red", TeamColor.RED, null, 0, mutableListOf()),
                    TeamDefinition("blue", TeamColor.BLUE, null, 0, mutableListOf()),
                ),
                kit = KitDefinition.createDefault(),
                noBuildRegions = mutableListOf(),
                settings = type.createDefaultSettings(),
            )
        }

        fun problemColor(problem: MapProblem): NamedTextColor = if (problem.error) NamedTextColor.RED else NamedTextColor.YELLOW
    }
}
