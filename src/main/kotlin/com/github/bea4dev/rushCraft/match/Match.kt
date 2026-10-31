package com.github.bea4dev.rushCraft.match

import com.github.bea4dev.rushCraft.RushCraft
import com.github.bea4dev.rushCraft.kit.KitManager
import com.github.bea4dev.rushCraft.map.MapDefinition
import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import java.util.UUID

class Match<G : MatchGameMode<G>>(val mode: G, val map: MapDefinition, val world: World) : Tickable {
    val teams: List<MatchTeam> = map.teams.map(::MatchTeam)
    private val objective: MatchObjective<G> = mode.createObjective()
    private val listener: Listener?
    private val kills = HashMap<UUID, Int>()
    private var isRunning = false

    var isFinished = false
        private set
    var elapsedTicks = 0
        private set
    var winners: List<MatchTeam> = emptyList()
        private set

    init {
        mode.onLoad(this)
        listener = mode.createListener(this)?.also {
            Bukkit.getPluginManager().registerEvents(it, RushCraft.plugin)
        }
    }

    fun getMembers(): List<Player> {
        return teams.flatMap { team -> team.getMembers() }
    }

    fun getTeam(player: Player): MatchTeam? = teams.find { player in it }

    fun getScoreboardLines(): List<Component> = mode.getScoreboardLines(this)

    /**
     * 参加者がその位置に入れるか。他チームのスポーン部屋とルール固有の制限 (羊毛部屋など) を見る。
     */
    fun canEnter(team: MatchTeam, location: Location): Boolean {
        val otherSpawnRoom = teams.any { other ->
            other != team && other.definition.spawnRooms.any { it.contains(location) }
        }
        return !otherSpawnRoom && mode.canEnter(this, team, location)
    }

    fun start() {
        isRunning = true
        for (team in teams) {
            for (player in team.getMembers()) {
                spawn(player, team)
            }
        }
        mode.onStart(this)
        schedule()
    }

    /**
     * 試合を終了する。勝者が空、または全チームの場合は引き分け。
     */
    fun end(winners: List<MatchTeam>) {
        if (!isRunning) return
        isRunning = false
        isFinished = true
        this.winners = winners
        mode.onEnd(this)
        win(winners)
    }

    fun dispose() {
        isRunning = false
        listener?.let(HandlerList::unregisterAll)
    }

    fun respawnLocation(team: MatchTeam): Location {
        val spawn = team.definition.spawns.randomOrNull() ?: map.spawn
        return spawn.toLocation(world)
    }

    /**
     * プレイヤーをチームのスポーンに送り、キットを配る。
     */
    fun spawn(player: Player, team: MatchTeam, teleport: Boolean = true) {
        player.gameMode = GameMode.SURVIVAL
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.foodLevel = 20
        player.saturation = 5F
        player.fireTicks = 0
        player.fallDistance = 0F
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        KitManager.equip(player, map, team)
        if (teleport) {
            player.teleport(respawnLocation(team))
        }
    }

    fun addKill(player: Player): Int {
        val count = (kills[player.uniqueId] ?: 0) + 1
        kills[player.uniqueId] = count
        return count
    }

    private fun win(winners: List<MatchTeam>) {
        val isDraw = winners.isEmpty() || winners.size == teams.size
        val message = if (isDraw) {
            Component.translatable("rushcraft.match.finished.draw", NamedTextColor.GREEN)
        } else {
            Component.translatable(
                "rushcraft.match.finished.winner",
                NamedTextColor.GREEN,
                Component.join(JoinConfiguration.commas(true), winners.map { it.displayName() }),
            )
        }

        for (player in Bukkit.getOnlinePlayers()) {
            player.sendMessage(message)
            player.showTitle(Title.title(message, Component.empty()))

            val team = getTeam(player)
            val sound = if (isDraw || team == null || team in winners) {
                org.bukkit.Sound.ENTITY_PLAYER_LEVELUP
            } else {
                org.bukkit.Sound.ENTITY_WITHER_DEATH
            }
            player.playSound(Sound.sound(sound, Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())
        }
    }

    override fun tick() {
        elapsedTicks++
        mode.onTick(this)
        if (!isRunning) return

        val winners = objective.getWinners(this)
        if (winners.isNotEmpty()) {
            end(winners)
            return
        }

        // 時間内に勝負がつかなければ引き分け (全チーム勝利扱い)
        if (map.timeLimit > 0 && elapsedTicks >= map.timeLimit * 20) {
            end(teams)
        }
    }

    override fun isAlive(): Boolean {
        return this.isRunning
    }
}
