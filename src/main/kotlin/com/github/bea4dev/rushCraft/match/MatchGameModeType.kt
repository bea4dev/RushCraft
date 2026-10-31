package com.github.bea4dev.rushCraft.match

import com.github.bea4dev.rushCraft.map.MapDefinition
import com.github.bea4dev.rushCraft.map.MapProblem
import com.github.bea4dev.rushCraft.match.mode.CTWType
import com.github.bea4dev.rushCraft.util.TomlWriter
import org.bukkit.World
import org.tomlj.TomlTable

/**
 * マップの TOML に書かれたルール (gamemode) の種類。
 *
 * マップとルールは 1:1 で、ルール固有の設定は map.toml の `[<id>]` テーブルに書く。
 */
interface MatchGameModeType {
    val id: String
    val nameKey: String

    /** `[<id>]` テーブルを読み込む (テーブルが無い場合は null) */
    fun parseSettings(table: TomlTable?): ModeSettings

    fun createDefaultSettings(): ModeSettings

    fun createMatch(map: MapDefinition, world: World): Match<*>
}

interface ModeSettings {
    val type: MatchGameModeType

    /** `[<id>]` 以下を書き出す */
    fun write(writer: TomlWriter)

    fun validate(map: MapDefinition): List<MapProblem>
}

object MatchGameModeTypes {
    private val types: Map<String, MatchGameModeType> = listOf<MatchGameModeType>(CTWType).associateBy { it.id }

    fun get(id: String): MatchGameModeType? = types[id.lowercase()]

    fun all(): Collection<MatchGameModeType> = types.values
}
