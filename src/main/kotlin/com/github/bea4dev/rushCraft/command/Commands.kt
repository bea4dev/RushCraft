package com.github.bea4dev.rushCraft.command

import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.map.MapRegistry
import com.github.bea4dev.rushCraft.map.MapWorlds
import com.github.bea4dev.rushCraft.match.MatchGameModeType
import com.github.bea4dev.rushCraft.match.MatchGameModeTypes
import com.github.bea4dev.rushCraft.match.mode.CTWType
import com.github.bea4dev.rushCraft.menu.KitMenu
import com.github.bea4dev.rushCraft.setup.SetupManager
import com.github.bea4dev.rushCraft.setup.SetupMenu
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

object Commands {
    fun register(plugin: JavaPlugin) {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            registrar.register("join", "Join a team", JoinCommand)
            registrar.register("spectate", "Spectate the match", listOf("obs"), SpectateCommand)
            registrar.register("kit", "Select your kit", KitCommand)
            registrar.register("rushcraft", "RushCraft admin command", listOf("rc"), AdminCommand)
            registrar.register("rcmap", "RushCraft map setup", SetupCommand)
        }
    }

    private fun CommandSourceStack.player(): Player? {
        val player = executor as? Player ?: sender as? Player
        if (player == null) {
            sender.sendMessage(Component.translatable("rushcraft.command.player-only", NamedTextColor.RED))
        }
        return player
    }

    private fun CommandSender.usage(usage: String) {
        sendMessage(Component.translatable("rushcraft.command.usage", NamedTextColor.RED, Component.text(usage)))
    }

    private fun filter(candidates: Iterable<String>, input: String): List<String> {
        return candidates.filter { it.startsWith(input, ignoreCase = true) }
    }

    private fun requireMatch(sender: CommandSender): Boolean {
        if (!MatchManager.isEnabled) {
            sender.sendMessage(Component.translatable("rushcraft.command.no-match", NamedTextColor.RED))
            return false
        }
        return true
    }

    /** チームは選べず、人数の少ないチームに自動で参加する */
    private object JoinCommand : BasicCommand {
        override fun execute(commandSourceStack: CommandSourceStack, args: Array<String>) {
            val player = commandSourceStack.player() ?: return
            if (!requireMatch(player)) return
            MatchManager.join(player)
        }
    }

    private object SpectateCommand : BasicCommand {
        override fun execute(commandSourceStack: CommandSourceStack, args: Array<String>) {
            val player = commandSourceStack.player() ?: return
            if (!requireMatch(player)) return
            MatchManager.spectate(player)
        }
    }

    private object KitCommand : BasicCommand {
        override fun execute(commandSourceStack: CommandSourceStack, args: Array<String>) {
            val player = commandSourceStack.player() ?: return
            if (!requireMatch(player)) return
            KitMenu.open(player)
        }
    }

    private object AdminCommand : BasicCommand {
        override fun execute(commandSourceStack: CommandSourceStack, args: Array<String>) {
            val sender = commandSourceStack.sender
            if (!requireMatch(sender)) return
            when (args.firstOrNull()?.lowercase()) {
                "start" -> {
                    val seconds = args.getOrNull(1)?.toIntOrNull() ?: 5
                    val key = if (MatchManager.forceStart(seconds.coerceAtLeast(0))) "started" else "not-waiting"
                    sender.sendMessage(Component.translatable("rushcraft.command.admin.$key"))
                }

                "end" -> {
                    val key = if (MatchManager.forceEnd()) "ended" else "not-running"
                    sender.sendMessage(Component.translatable("rushcraft.command.admin.$key"))
                }

                "status" -> {
                    val match = MatchManager.match ?: return
                    sender.sendMessage(
                        Component.translatable(
                            "rushcraft.command.admin.status",
                            Component.text(match.map.name),
                            Component.text(MatchManager.state::class.simpleName ?: "?"),
                            Component.text(match.getMembers().size),
                        )
                    )
                }

                else -> sender.usage("/rushcraft <start [seconds]|end|status>")
            }
        }

        override fun suggest(commandSourceStack: CommandSourceStack, args: Array<String>): Collection<String> {
            if (args.size > 1) return emptyList()
            return filter(listOf("start", "end", "status"), args.firstOrNull() ?: "")
        }

        override fun permission(): String = "rushcraft.admin"
    }

    private object SetupCommand : BasicCommand {
        private val SUBCOMMANDS = listOf("list", "create", "import", "edit", "menu", "save", "leave", "discard", "tools")

        override fun execute(commandSourceStack: CommandSourceStack, args: Array<String>) {
            val sender = commandSourceStack.sender
            if (!SetupManager.isEnabled) {
                sender.sendMessage(Component.translatable("rushcraft.setup.disabled", NamedTextColor.RED))
                return
            }
            val player = commandSourceStack.player() ?: return

            when (args.firstOrNull()?.lowercase()) {
                "list" -> SetupManager.list(player)
                "create" -> {
                    val id = args.getOrNull(1) ?: return player.usage("/rcmap create <id> [gamemode]")
                    val type = parseType(player, args.getOrNull(2)) ?: return
                    SetupManager.create(player, id, type)
                }

                "import" -> {
                    val world = args.getOrNull(1)
                    val id = args.getOrNull(2)
                    if (world == null || id == null) return player.usage("/rcmap import <world folder> <id> [gamemode]")
                    val type = parseType(player, args.getOrNull(3)) ?: return
                    SetupManager.import(player, world, id, type)
                }

                "edit" -> {
                    val id = args.getOrNull(1) ?: return player.usage("/rcmap edit <id>")
                    SetupManager.edit(player, id)
                }

                "menu" -> {
                    val session = SetupManager.session(player)
                        ?: return player.sendMessage(Component.translatable("rushcraft.setup.not-editing", NamedTextColor.RED))
                    SetupMenu.openMain(player, session)
                }

                "save" -> SetupManager.save(player)
                "leave" -> SetupManager.leave(player)
                "discard" -> {
                    val id = args.getOrNull(1) ?: return player.usage("/rcmap discard <id>")
                    SetupManager.discard(player, id)
                }

                "tools" -> SetupManager.giveTools(player)
                else -> player.usage("/rcmap <${SUBCOMMANDS.joinToString("|")}>")
            }
        }

        private fun parseType(player: Player, id: String?): MatchGameModeType? {
            if (id == null) return CTWType
            return MatchGameModeTypes.get(id) ?: run {
                player.sendMessage(Component.translatable("rushcraft.setup.unknown-gamemode", NamedTextColor.RED, Component.text(id)))
                null
            }
        }

        override fun suggest(commandSourceStack: CommandSourceStack, args: Array<String>): Collection<String> {
            val input = args.lastOrNull() ?: ""
            if (args.size <= 1) return filter(SUBCOMMANDS, input)
            val subcommand = args[0].lowercase()
            val mapIds = MapRegistry.all().map { it.id }
            val modes = MatchGameModeTypes.all().map { it.id }
            return when {
                args.size == 2 && subcommand in listOf("edit", "discard") -> filter(mapIds, input)
                args.size == 2 && subcommand == "import" -> filter(worldFolders(), input)
                args.size == 3 && subcommand == "create" -> filter(modes, input)
                args.size == 4 && subcommand == "import" -> filter(modes, input)
                else -> emptyList()
            }
        }

        private fun worldFolders(): List<String> {
            return MapWorlds.container.listFiles()
                ?.filter { it.isDirectory && it.resolve("level.dat").exists() }
                ?.filter { !it.name.startsWith(MapWorlds.MATCH_PREFIX) && !it.name.startsWith(MapWorlds.EDIT_PREFIX) }
                ?.map { it.name }
                ?: emptyList()
        }

        override fun permission(): String = "rushcraft.setup"
    }
}
