package com.github.bea4dev.rushCraft.manager

import com.github.bea4dev.rushCraft.RushCraft
import com.github.bea4dev.rushCraft.config.LobbyMode
import com.github.bea4dev.rushCraft.config.PluginConfig
import com.github.bea4dev.rushCraft.config.RestartMethod
import com.github.bea4dev.rushCraft.kit.KitManager
import com.github.bea4dev.rushCraft.kit.InstantWall
import com.github.bea4dev.rushCraft.kit.ItemRecharge
import com.github.bea4dev.rushCraft.kit.MaceManager
import com.github.bea4dev.rushCraft.kit.SpearManager
import com.github.bea4dev.rushCraft.kit.ShieldManager
import com.github.bea4dev.rushCraft.kit.ThrowingKnife
import com.github.bea4dev.rushCraft.kit.TntManager
import com.github.bea4dev.rushCraft.kit.TrapManager
import com.github.bea4dev.rushCraft.map.MapDefinition
import com.github.bea4dev.rushCraft.map.MapWorlds
import com.github.bea4dev.rushCraft.match.CombatTracker
import com.github.bea4dev.rushCraft.match.Match
import com.github.bea4dev.rushCraft.match.MatchTeam
import com.github.bea4dev.rushCraft.menu.KitMenu
import com.github.bea4dev.rushCraft.menu.TeamMenu
import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.translation.GlobalTranslator
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

const val BUNGEE_CHANNEL = "BungeeCord"

object MatchManager : Tickable {
    private lateinit var config: PluginConfig

    var state: MatchState = MatchState.Closing
        private set
    var match: Match<*>? = null
        private set
    var bukkit: BukkitMatchManager? = null
        private set

    private val rotation = mutableListOf<RotationEntry>()
    private var rotationIndex = -1


    val isEnabled: Boolean
        get() = match != null

    class RotationEntry(val map: MapDefinition, val worldName: String)

    /**
     * ローテーションを組み、必要な数だけマップをコピーして最初の試合を準備する。
     */
    fun start(config: PluginConfig, maps: Collection<MapDefinition>) {
        this.config = config
        val logger = RushCraft.plugin.logger

        // 前回起動時の試合ワールドを消す
        MapWorlds.deleteWorlds(MapWorlds.MATCH_PREFIX)

        val playable = selectMaps(maps)
        if (playable.isEmpty()) {
            logger.severe("No playable maps found. Create a map in setup mode (setup-mode = true in config.toml).")
            return
        }

        planRotation(playable)
        for (entry in rotation) {
            logger.info("Preparing map '${entry.map.id}' as '${entry.worldName}'")
            MapWorlds.copyWorld(entry.map.directory, MapWorlds.container.resolve(entry.worldName))
        }

        loadNext()
        schedule()
    }

    private fun selectMaps(maps: Collection<MapDefinition>): List<MapDefinition> {
        val logger = RushCraft.plugin.logger
        val candidates = if (config.rotationMaps.isEmpty()) {
            maps.toList()
        } else {
            config.rotationMaps.mapNotNull { id ->
                maps.find { it.id == id } ?: run {
                    logger.warning("Rotation map '$id' was not found")
                    null
                }
            }
        }

        return candidates.filter { map ->
            val errors = map.validate().filter { it.error }
            if (errors.isNotEmpty()) {
                logger.warning("Map '${map.id}' was skipped because it has ${errors.size} problem(s). Fix it in setup mode.")
            }
            errors.isEmpty()
        }
    }

    private fun planRotation(maps: List<MapDefinition>) {
        rotation.clear()
        val pool = ArrayDeque<MapDefinition>()
        var last: MapDefinition? = null
        for (index in 0 until config.rotationCount) {
            if (pool.isEmpty()) {
                pool.addAll(if (config.shuffle) maps.shuffled() else maps)
                // 同じマップが連続しないようにする
                if (pool.size > 1 && pool.first() == last) {
                    pool.addLast(pool.removeFirst())
                }
            }
            val map = pool.removeFirst()
            last = map
            rotation += RotationEntry(map, MapWorlds.matchWorldName(index, map.id))
        }
        RushCraft.plugin.logger.info("Rotation: ${rotation.joinToString { it.map.id }}")
    }

    private fun loadNext() {
        val previous = match
        previous?.dispose()

        rotationIndex++
        val entry = rotation[rotationIndex]
        val world = MapWorlds.loadWorld(entry.worldName)
        MapWorlds.applyMatchRules(world, entry.map)

        val newMatch = entry.map.type.createMatch(entry.map, world)
        match = newMatch
        bukkit = createBukkitMatchManager(newMatch)
        state = MatchState.Waiting(config.waitingSeconds, -1)

        // 試合ごとに全員がチーム選択画面から参加し直す
        for (player in Bukkit.getOnlinePlayers()) {
            bukkit?.showTo(player)
            setTeam(player, null)
            prepareLobby(player, teleport = true)
            openTeamMenuLater(player)
        }
        updateScoreboard()

        previous?.let { MapWorlds.unloadAndDelete(it.world) }
    }

    private fun createBukkitMatchManager(match: Match<*>): BukkitMatchManager {
        val title = Component.translatable(match.mode.getNameKey(), NamedTextColor.AQUA)
            .decorate(TextDecoration.BOLD)
        val tabScoreTitle = Component.text("Kills", NamedTextColor.YELLOW)
        return BukkitMatchManager(Bukkit.getScoreboardManager().newScoreboard, title, tabScoreTitle)
    }

    fun tickSeconds() {
        val match = match ?: return
        when (val state = state) {
            is MatchState.Waiting -> {
                val playerCount = match.getMembers().size
                val required = config.minPlayers

                if (!state.forced && playerCount < required) {
                    if (state.previousPlayerCount != playerCount) {
                        broadcast(
                            Component.translatable(
                                "rushcraft.match.waiting.need",
                                Component.text(required - playerCount).color(NamedTextColor.RED)
                            ).color(NamedTextColor.GREEN)
                        )
                    }
                    state.previousPlayerCount = playerCount
                    state.seconds = config.waitingSeconds
                    return
                }
                state.previousPlayerCount = playerCount

                val seconds = state.seconds
                val showSeconds = seconds > 0 && (seconds % 10 == 0 || seconds <= 5)
                val playSound = when (seconds) {
                    3, 2, 1 -> 1.0
                    0 -> 2.0
                    else -> null
                }

                if (showSeconds) {
                    broadcast(
                        Component.translatable(
                            "rushcraft.match.waiting.countdown",
                            Component.text(seconds).color(NamedTextColor.RED)
                        ).color(NamedTextColor.GREEN)
                    )
                }

                if (playSound != null) {
                    Bukkit.getOnlinePlayers().forEach {
                        it.playSound(
                            Sound.sound(
                                org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING,
                                Sound.Source.MASTER,
                                1.0F,
                                playSound.toFloat()
                            )
                        )
                    }
                }

                if (seconds <= 0) {
                    Bukkit.getOnlinePlayers().forEach {
                        it.sendMessage(Component.text("######################").color(NamedTextColor.GREEN))
                        it.sendMessage(Component.translatable("rushcraft.match.start").color(NamedTextColor.GOLD))
                        it.sendMessage(Component.text("######################").color(NamedTextColor.GREEN))
                    }
                    startMatch(match)
                    return
                }

                state.seconds--
            }

            is MatchState.Running -> {}

            is MatchState.Finished -> {
                val seconds = state.seconds
                val hasNext = rotationIndex + 1 < rotation.size
                val showSeconds = seconds > 0 && (seconds % 10 == 0 || seconds <= 5)

                if (showSeconds) {
                    val key = if (hasNext) "rushcraft.match.finished.countdown" else "rushcraft.match.finished.lobby"
                    broadcast(
                        Component.translatable(key, Component.text(seconds).color(NamedTextColor.RED))
                            .color(NamedTextColor.GREEN)
                    )
                }

                if (seconds <= 0) {
                    if (hasNext) {
                        loadNext()
                    } else {
                        closeRotation()
                    }
                    return
                }

                state.seconds--
            }

            is MatchState.Closing -> {}
        }
    }

    private fun startMatch(match: Match<*>) {
        state = MatchState.Running
        match.start()
        for (player in Bukkit.getOnlinePlayers()) {
            if (match.getTeam(player) == null) {
                makeSpectator(player)
            }
        }
    }

    private fun finishMatch() {
        state = MatchState.Finished(config.finishedSeconds)
        ShieldManager.reset(restore = false)
        ThrowingKnife.reset()
        TntManager.reset()
        InstantWall.reset()
        MaceManager.reset()
        SpearManager.reset()
        TrapManager.reset()
        ItemRecharge.reset()
        CombatTracker.reset()
        for (player in Bukkit.getOnlinePlayers()) {
            prepareLobby(player, teleport = false)
        }
    }

    /**
     * ローテーション終了。全員をロビーへ送ってから再起動する。
     */
    private fun closeRotation() {
        state = MatchState.Closing
        val plugin = RushCraft.plugin
        plugin.logger.info("Rotation finished. Sending players to the lobby and restarting.")

        for (player in Bukkit.getOnlinePlayers()) {
            sendToLobby(player)
        }

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                // 切断画面のメッセージは翻訳されないため、ここで描画する
                val message = Component.translatable("rushcraft.match.restart", NamedTextColor.YELLOW)
                player.kick(GlobalTranslator.render(message, player.locale()))
            }
            when (config.restartMethod) {
                RestartMethod.RESTART -> Bukkit.getServer().restart()
                RestartMethod.SHUTDOWN -> Bukkit.shutdown()
            }
        }, config.restartDelaySeconds * 20L)
    }

    private fun sendToLobby(player: Player) {
        val lobby = config.lobby
        player.sendMessage(Component.translatable("rushcraft.match.lobby", NamedTextColor.GREEN))
        when (lobby.mode) {
            LobbyMode.PROXY -> {
                val bytes = ByteArrayOutputStream()
                DataOutputStream(bytes).use {
                    it.writeUTF("Connect")
                    it.writeUTF(lobby.server)
                }
                player.sendPluginMessage(RushCraft.plugin, BUNGEE_CHANNEL, bytes.toByteArray())
            }

            LobbyMode.TRANSFER -> player.transfer(lobby.host, lobby.port)
            LobbyMode.NONE -> {}
        }
    }

    // ------------------------------------------------------------------
    // プレイヤー
    // ------------------------------------------------------------------

    fun onJoin(player: Player) {
        if (match == null) return
        bukkit?.showTo(player)
        setTeam(player, null)

        when (state) {
            is MatchState.Waiting -> prepareLobby(player, teleport = true)
            is MatchState.Running -> makeSpectator(player)
            is MatchState.Finished -> prepareLobby(player, teleport = true)
            is MatchState.Closing -> {
                prepareLobby(player, teleport = true)
                sendToLobby(player)
                return
            }
        }
        if (state is MatchState.Waiting || state is MatchState.Running) {
            openTeamMenuLater(player)
        }
    }

    /** ログイン直後は画面の準備ができていないので、少し待ってからチーム選択画面を出す */
    private fun openTeamMenuLater(player: Player) {
        Bukkit.getScheduler().runTaskLater(RushCraft.plugin, Runnable {
            val state = state
            if (player.isOnline && match?.getTeam(player) == null &&
                (state is MatchState.Waiting || state is MatchState.Running)
            ) {
                TeamMenu.open(player)
            }
        }, 20L)
    }

    fun onQuit(player: Player) {
        val match = match ?: return
        match.getTeam(player)?.leave(player)
        bukkit?.clearEntityColor(player)
    }

    private fun setTeam(player: Player, team: MatchTeam?) {
        val match = match ?: return
        match.getTeam(player)?.leave(player)
        if (team == null) {
            bukkit?.clearEntityColor(player)
            player.displayName(null)
            player.playerListName(null)
            return
        }
        team.join(player)
        bukkit?.setEntityColor(player, team.color.text)
        val name = Component.text(player.name, team.color.text)
        player.displayName(name)
        player.playerListName(name)
    }

    /**
     * 待機中・試合後の状態にする (クリエイティブ・破壊不可)。
     */
    private fun prepareLobby(player: Player, teleport: Boolean) {
        val match = match ?: return
        if (teleport) {
            player.teleport(match.map.spawn.toLocation(match.world))
        }
        player.gameMode = GameMode.CREATIVE
        player.inventory.clear()
        KitManager.giveLobbyItems(player, match.getTeam(player) != null)
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.foodLevel = 20
        player.fireTicks = 0
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
    }

    private fun makeSpectator(player: Player) {
        val match = match ?: return
        player.inventory.clear()
        player.gameMode = GameMode.SPECTATOR
        player.teleport(match.map.spawn.toLocation(match.world))
        player.sendMessage(
            Component.translatable("rushcraft.match.spectating", NamedTextColor.GRAY)
                .append(Component.space())
                .append(
                    Component.translatable("rushcraft.match.spectating-join", NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(Component.translatable("rushcraft.match.spectating-join-hover")))
                        .clickEvent(ClickEvent.runCommand("/join"))
                )
        )
    }

    /**
     * /join コマンド。teamId が null なら人数の少ないチーム。
     */
    /**
     * 人数の少ないチームに自動で参加する (チームは選べない)。
     */
    fun join(player: Player) {
        val match = match ?: return
        val state = state
        if (state !is MatchState.Waiting && state !is MatchState.Running) {
            player.sendMessage(Component.translatable("rushcraft.command.join.closed", NamedTextColor.RED))
            return
        }

        val current = match.getTeam(player)
        if (current != null) {
            player.sendMessage(Component.translatable("rushcraft.command.join.already", NamedTextColor.RED, current.displayName()))
            return
        }
        // 同じ人数のチームが複数あればランダムに選ぶ
        val target = match.teams.filter { !it.isFull }.shuffled().minByOrNull { it.size }
        if (target == null) {
            player.sendMessage(Component.translatable("rushcraft.command.join.full", NamedTextColor.RED))
            return
        }

        setTeam(player, target)
        player.sendMessage(Component.translatable("rushcraft.command.join.joined", NamedTextColor.GREEN, target.displayName()))
        if (state is MatchState.Running) {
            match.spawn(player, target)
        } else {
            prepareLobby(player, teleport = false)
        }
        // 参加したらキット選択画面を出す
        KitMenu.open(player)
    }

    /**
     * /spectate コマンド。
     */
    fun spectate(player: Player) {
        val match = match ?: return
        if (match.getTeam(player) == null) {
            player.sendMessage(Component.translatable("rushcraft.command.spectate.already", NamedTextColor.RED))
            return
        }
        ShieldManager.release(player)
        setTeam(player, null)
        player.sendMessage(Component.translatable("rushcraft.command.spectate.done", NamedTextColor.GREEN))
        when (state) {
            is MatchState.Running -> makeSpectator(player)
            is MatchState.Waiting -> prepareLobby(player, teleport = false)
            else -> {}
        }
    }

    // ------------------------------------------------------------------
    // 管理用
    // ------------------------------------------------------------------

    /** 人数に関係なくカウントダウンを始める */
    fun forceStart(seconds: Int): Boolean {
        val state = state as? MatchState.Waiting ?: return false
        state.forced = true
        state.seconds = seconds
        return true
    }

    /** 試合を引き分けで終了する */
    fun forceEnd(): Boolean {
        val match = match ?: return false
        if (state !is MatchState.Running) return false
        match.end(emptyList())
        return true
    }

    // ------------------------------------------------------------------
    // 保護・判定
    // ------------------------------------------------------------------

    fun isMatchWorld(world: World): Boolean = match?.world == world

    /**
     * 待機中・試合後など、ワールドを一切変更できない状態か。
     */
    fun isProtected(world: World): Boolean = isMatchWorld(world) && state !is MatchState.Running

    fun isRunning(world: World): Boolean = isMatchWorld(world) && state is MatchState.Running

    fun isParticipant(player: Player): Boolean = match?.getTeam(player) != null

    // ------------------------------------------------------------------
    // tick
    // ------------------------------------------------------------------

    private fun updateScoreboard() {
        val match = match ?: return
        val bukkit = bukkit ?: return

        val lines = mutableListOf<Component>()
        lines += match.getScoreboardLines()
        lines += statusLine(match)
        lines += Component.text(match.map.name, NamedTextColor.GRAY)
        bukkit.setLines(lines)
    }

    private fun statusLine(match: Match<*>): Component {
        return when (val state = state) {
            is MatchState.Waiting -> {
                val missing = config.minPlayers - match.getMembers().size
                if (!state.forced && missing > 0) {
                    Component.translatable(
                        "rushcraft.match.status.need",
                        NamedTextColor.YELLOW,
                        Component.text(missing, NamedTextColor.RED),
                    )
                } else {
                    Component.translatable(
                        "rushcraft.match.status.starting",
                        NamedTextColor.GREEN,
                        Component.text(state.seconds, NamedTextColor.RED),
                    )
                }
            }

            is MatchState.Running -> {
                val elapsed = match.elapsedTicks / 20
                val limit = match.map.timeLimit
                if (limit > 0) {
                    Component.translatable(
                        "rushcraft.match.status.remaining",
                        NamedTextColor.WHITE,
                        Component.text(formatTime(limit - elapsed), NamedTextColor.GREEN),
                    )
                } else {
                    Component.translatable(
                        "rushcraft.match.status.elapsed",
                        NamedTextColor.WHITE,
                        Component.text(formatTime(elapsed), NamedTextColor.GREEN),
                    )
                }
            }

            is MatchState.Finished, is MatchState.Closing ->
                Component.translatable("rushcraft.match.status.finished", NamedTextColor.GOLD)
        }
    }

    private fun formatTime(seconds: Int): String {
        val value = seconds.coerceAtLeast(0)
        return "%02d:%02d".format(value / 60, value % 60)
    }

    private fun broadcast(message: Component) {
        Bukkit.getOnlinePlayers().forEach { it.sendMessage(message) }
    }

    private var t = 0

    override fun tick() {
        val match = match ?: return

        if (state is MatchState.Running) {
            // 参加者が全員いなくなったら引き分けで終了する
            if (match.isAlive() && match.getMembers().isEmpty()) {
                RushCraft.plugin.logger.info("All players left. Ending the match.")
                match.end(match.teams)
            }
            if (!match.isAlive()) {
                finishMatch()
            }
        }

        if (t % 20 == 0) {
            tickSeconds()
        }
        if (t % 10 == 0) {
            updateScoreboard()
        }
        t++
    }

    override fun isAlive(): Boolean {
        return true
    }

    sealed interface MatchState {
        class Waiting(var seconds: Int, var previousPlayerCount: Int, var forced: Boolean = false) : MatchState
        object Running : MatchState
        class Finished(var seconds: Int) : MatchState

        /** ローテーション終了後、再起動待ち */
        object Closing : MatchState
    }
}
