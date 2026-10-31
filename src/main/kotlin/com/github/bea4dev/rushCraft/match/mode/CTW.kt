package com.github.bea4dev.rushCraft.match.mode

import com.github.bea4dev.rushCraft.RushCraft
import com.github.bea4dev.rushCraft.map.BlockPos
import com.github.bea4dev.rushCraft.map.MapDefinition
import com.github.bea4dev.rushCraft.map.Region
import com.github.bea4dev.rushCraft.match.Match
import com.github.bea4dev.rushCraft.match.MatchGameMode
import com.github.bea4dev.rushCraft.match.MatchGameModeType
import com.github.bea4dev.rushCraft.match.MatchObjective
import com.github.bea4dev.rushCraft.match.MatchTeam
import com.github.bea4dev.rushCraft.match.ModeSettings
import com.github.bea4dev.rushCraft.match.objective.CTWObjective
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.DyeColor
import org.bukkit.FireworkEffect
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.tomlj.TomlTable

object CTWType : MatchGameModeType {
    override val id: String = "ctw"
    override val nameKey: String = "rushcraft.match.ctw.name"

    override fun parseSettings(table: TomlTable?): ModeSettings = CTWSettings.parse(table)

    override fun createDefaultSettings(): ModeSettings = CTWSettings(mutableListOf())

    override fun createMatch(map: MapDefinition, world: World): Match<*> {
        return Match(CTW(map.settings as CTWSettings), map, world)
    }
}

enum class WoolState(val symbol: String) {
    /** 誰も持っておらず未設置 */
    NONE("⬜"),

    /** 奪取するチームの誰かが持っている */
    CARRIED("▒"),

    /** 設置済み */
    PLACED("⬛"),
}

class WoolObjective(
    val color: DyeColor,
    val team: MatchTeam,
    val monument: BlockPos,
    val room: Region?,
    val spawners: List<Region>,
) {
    var state = WoolState.NONE

    /** 一度でも取得されたか (取得メッセージを最初の 1 回だけ出すため) */
    var touched = false

    val material: Material = WoolDefinition.woolMaterial(color)
    val textColor: TextColor = WoolDefinition.woolColor(color)

    fun displayName(): Component = WoolDefinition.woolName(color)
}

class CTW(val settings: CTWSettings) : MatchGameMode<CTW> {
    var wools: List<WoolObjective> = emptyList()
        private set
    private var monuments: Map<BlockPos, WoolObjective> = emptyMap()
    private val celebrationFireworks = mutableSetOf<java.util.UUID>()

    override fun createObjective(): MatchObjective<CTW> {
        return CTWObjective()
    }

    override fun getNameKey(): String {
        return CTWType.nameKey
    }

    override fun onLoad(match: Match<CTW>) {
        wools = settings.wools.mapNotNull { definition ->
            val team = match.teams.find { it.id == definition.team } ?: return@mapNotNull null
            val monument = definition.monument ?: return@mapNotNull null
            WoolObjective(definition.color, team, monument, definition.room, definition.spawners.toList())
        }
        monuments = wools.associateBy { it.monument }
    }

    override fun getScoreboardLines(match: Match<CTW>): List<Component> {
        val lines = mutableListOf<Component>()
        for (team in match.teams) {
            lines += team.displayName()
            for (wool in woolsOf(team)) {
                lines += Component.text()
                    .append(Component.text("  "))
                    .append(Component.text(wool.state.symbol, wool.textColor))
                    .append(Component.space())
                    .append(Component.translatable(wool.material.translationKey(), NamedTextColor.WHITE))
                    .build()
            }
            lines += Component.empty()
        }
        return lines
    }

    override fun onTick(match: Match<CTW>) {
        if (!match.isAlive() || match.elapsedTicks % 5 != 0) return

        // 拾う・ブロックを壊すなど入手経路を問わず検出できるよう、奪取するチームのインベントリを定期的に調べる。
        // 倒されたり捨てたりして誰も持っていなければ未取得の表示に戻す。
        for (wool in wools) {
            if (wool.state == WoolState.PLACED) continue
            val carrier = wool.team.getMembers().firstOrNull { player ->
                player.gameMode == GameMode.SURVIVAL && !player.isDead &&
                        (player.inventory.contains(wool.material) || player.itemOnCursor.type == wool.material)
            }
            if (carrier == null) {
                wool.state = WoolState.NONE
            } else if (wool.state == WoolState.NONE) {
                pickUp(wool, carrier)
            }
        }
    }

    override fun createListener(match: Match<CTW>): Listener {
        return CTWListener(match)
    }

    fun woolsOf(team: MatchTeam): List<WoolObjective> = wools.filter { it.team == team }

    /** 羊毛部屋には、その羊毛を奪取するチームしか入れない */
    override fun canEnter(match: Match<CTW>, team: MatchTeam, location: Location): Boolean {
        return wools.none { wool -> wool.team != team && wool.room?.contains(location) == true }
    }

    /** このブロックが無限湧きの羊毛であれば、その羊毛 */
    fun spawnerAt(block: Block): WoolObjective? {
        return wools.find { wool -> block.type == wool.material && wool.spawners.any { it.contains(block) } }
    }

    fun woolAt(block: Block): WoolObjective? = monuments[BlockPos.of(block)]

    /** この素材が目標になっている羊毛 */
    fun objectivesOf(material: Material): List<WoolObjective> = wools.filter { it.material == material }

    /**
     * そのチームがこの羊毛を扱えないか (他チームの目標の羊毛か)
     */
    fun isProtectedFrom(material: Material, team: MatchTeam?): Boolean {
        val objectives = objectivesOf(material)
        return objectives.isNotEmpty() && objectives.none { it.team == team }
    }

    private fun pickUp(wool: WoolObjective, player: Player) {
        wool.state = WoolState.CARRIED
        if (wool.touched) return
        wool.touched = true
        Bukkit.broadcast(
            Component.translatable(
                "rushcraft.match.ctw.touched",
                NamedTextColor.GRAY,
                player.displayName(),
                wool.displayName(),
            )
        )
        for (member in wool.team.getMembers()) {
            member.playSound(
                Sound.sound(org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, Sound.Source.MASTER, 1f, 1f),
                Sound.Emitter.self(),
            )
        }
    }

    fun place(wool: WoolObjective, player: Player) {
        wool.state = WoolState.PLACED
        Bukkit.broadcast(
            Component.translatable(
                "rushcraft.match.ctw.placed",
                NamedTextColor.GRAY,
                player.displayName(),
                wool.displayName(),
            )
        )
        launchFirework(wool, player.world)
    }

    /**
     * モニュメントから花火を打ち上げる。
     * 打ち上げ音と爆発音は、全プレイヤーにモニュメントの位置から大きな音量で鳴らす。
     */
    private fun launchFirework(wool: WoolObjective, world: World) {
        val location = wool.monument.toCenter(world).add(0.0, 1.0, 0.0)
        val firework = world.spawn(location, Firework::class.java) { entity ->
            entity.fireworkMeta = entity.fireworkMeta.apply {
                power = 1
                addEffect(
                    FireworkEffect.builder()
                        .with(FireworkEffect.Type.BALL_LARGE)
                        .withColor(wool.color.color)
                        .withFade(Color.WHITE)
                        .flicker(true)
                        .trail(true)
                        .build()
                )
            }
        }
        celebrationFireworks += firework.uniqueId

        playLoud(org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, location)
    }

    /** 花火が爆発したとき (CTWListener から呼ばれる) */
    fun onFireworkExplode(firework: Firework) {
        if (!isCelebrationFirework(firework)) return
        playLoud(org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, firework.location)
        playLoud(org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, firework.location)
        // 爆発ダメージの判定は爆発イベントの後に来るので、少し待ってから忘れる
        Bukkit.getScheduler().runTaskLater(RushCraft.plugin, Runnable {
            celebrationFireworks -= firework.uniqueId
        }, 5L)
    }

    /** 設置演出の花火か (この花火ではプレイヤーにダメージを与えない) */
    fun isCelebrationFirework(firework: Firework): Boolean = firework.uniqueId in celebrationFireworks

    private fun playLoud(sound: org.bukkit.Sound, location: Location) {
        // 音量 1 を超えると聞こえる距離が 16 ブロック × 音量 に広がる
        val adventureSound = Sound.sound(sound, Sound.Source.MASTER, LOUD_VOLUME, 1f)
        for (player in location.world.players) {
            player.playSound(adventureSound, location.x, location.y, location.z)
        }
    }

    companion object {
        private const val LOUD_VOLUME = 16f
    }
}
