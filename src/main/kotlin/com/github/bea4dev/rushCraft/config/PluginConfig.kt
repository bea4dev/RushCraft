package com.github.bea4dev.rushCraft.config

import com.github.bea4dev.rushCraft.util.bool
import com.github.bea4dev.rushCraft.util.int
import com.github.bea4dev.rushCraft.util.optTable
import com.github.bea4dev.rushCraft.util.string
import com.github.bea4dev.rushCraft.util.strings
import org.bukkit.plugin.java.JavaPlugin
import org.tomlj.Toml
import org.tomlj.TomlTable
import java.io.File

enum class LobbyMode {
    /** BungeeCord / Velocity のプラグインメッセージ (Connect) で転送する */
    PROXY,

    /** Transfer パケットで転送する (転送先で accepts-transfers=true が必要) */
    TRANSFER,

    /** 転送しない */
    NONE,
}

enum class RestartMethod {
    /** spigot.yml の restart-script を使って再起動する */
    RESTART,

    /** サーバーを停止する (外部のプロセスマネージャーが再起動する想定) */
    SHUTDOWN,
}

class LobbyConfig(
    val mode: LobbyMode,
    val server: String,
    val host: String,
    val port: Int,
)

class KitConfig(
    /** 同時に選べる特殊アイテムの数 */
    val maxSpecialItems: Int,
    /** 特殊アイテムを死亡時にドロップするか */
    val specialDropOnDeath: Boolean,
    /** 試合中にキット選択アイテムを置くインベントリのスロット */
    val selectorSlot: Int,
)

class PluginConfig(
    val setupMode: Boolean,
    val minPlayers: Int,
    val keepParticipants: Boolean,
    val waitingSeconds: Int,
    val finishedSeconds: Int,
    val rotationCount: Int,
    val rotationMaps: List<String>,
    val shuffle: Boolean,
    val lobby: LobbyConfig,
    val restartMethod: RestartMethod,
    val restartDelaySeconds: Int,
    val kit: KitConfig,
) {
    companion object {
        private const val FILE_NAME = "config.toml"

        fun load(plugin: JavaPlugin): PluginConfig {
            val file = File(plugin.dataFolder, FILE_NAME)
            if (!file.exists()) {
                plugin.saveResource(FILE_NAME, false)
            }

            val result = Toml.parse(file.toPath())
            result.errors().forEach { plugin.logger.warning("[$FILE_NAME] $it") }

            val match = result.optTable("match") ?: EMPTY
            val rotation = result.optTable("rotation") ?: EMPTY
            val lobby = result.optTable("lobby") ?: EMPTY
            val restart = result.optTable("restart") ?: EMPTY
            val kit = result.optTable("kit") ?: EMPTY

            return PluginConfig(
                setupMode = result.bool("setup-mode", false),
                minPlayers = match.int("min-players", 2).coerceAtLeast(1),
                keepParticipants = match.bool("keep-participants", true),
                waitingSeconds = match.int("waiting-seconds", 30).coerceAtLeast(1),
                finishedSeconds = match.int("finished-seconds", 10).coerceAtLeast(1),
                rotationCount = rotation.int("count", 5).coerceAtLeast(1),
                rotationMaps = rotation.strings("maps"),
                shuffle = rotation.bool("shuffle", true),
                lobby = LobbyConfig(
                    mode = parseEnum(plugin, lobby.string("mode", "proxy"), LobbyMode.PROXY),
                    server = lobby.string("server", "lobby"),
                    host = lobby.string("host", "127.0.0.1"),
                    port = lobby.int("port", 25565),
                ),
                restartMethod = parseEnum(plugin, restart.string("method", "restart"), RestartMethod.RESTART),
                restartDelaySeconds = restart.int("delay-seconds", 5).coerceAtLeast(1),
                kit = KitConfig(
                    maxSpecialItems = kit.int("max-special-items", 3).coerceAtLeast(0),
                    specialDropOnDeath = kit.bool("special-drop-on-death", false),
                    selectorSlot = kit.int("selector-slot", 17).coerceIn(0, 35),
                ),
            )
        }

        private val EMPTY: TomlTable = Toml.parse("")

        private inline fun <reified E : Enum<E>> parseEnum(plugin: JavaPlugin, value: String, default: E): E {
            return enumValues<E>().find { it.name.equals(value, ignoreCase = true) } ?: run {
                plugin.logger.warning("[$FILE_NAME] unknown value '$value', using '${default.name.lowercase()}'")
                default
            }
        }
    }
}
