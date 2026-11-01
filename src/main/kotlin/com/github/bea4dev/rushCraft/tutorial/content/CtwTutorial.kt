package com.github.bea4dev.rushCraft.tutorial.content

import com.github.bea4dev.rushCraft.kit.KitManager
import com.github.bea4dev.rushCraft.kit.SpecialItem
import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.map.BlockPos
import com.github.bea4dev.rushCraft.match.mode.WoolDefinition
import com.github.bea4dev.rushCraft.match.mode.WoolState
import com.github.bea4dev.rushCraft.tutorial.CameraTour
import com.github.bea4dev.rushCraft.tutorial.TutorialContent
import com.github.bea4dev.rushCraft.tutorial.TutorialSession
import com.github.bea4dev.rushCraft.tutorial.TutorialSide
import com.github.bea4dev.rushCraft.tutorial.TutorialStep
import com.github.bea4dev.rushCraft.tutorial.wait
import com.github.bea4dev.rushCraft.tutorial.waitUntil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Color
import org.bukkit.DyeColor
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.ItemStack
import java.time.Duration

/**
 * CTW のルール。
 *
 * 試合のマップと同じ作り (羊毛部屋は敵陣の横、台座は色付きガラスで挟んだ隙間) の小さな会場で、
 * 会場を見て回る → 橋を架けて敵陣へ → 守る敵をかわして羊毛を奪う → 自陣の台座に置く、の順に進める。
 *
 * 会場 (相対座標, x が東):
 * - 自陣 (赤): x -8..6。西の端に台座の祠
 * - 道: x 7..14、切れ目 x 15..20 (6 ブロック)、x 21..27
 * - 敵陣 (青): x 28..44。東の端に敵のスポーン部屋、北に羊毛部屋 (x 29..37, z -16..-7)
 */
class CtwTutorial(session: TutorialSession) : TutorialContent(session) {
    private val player: Player
        get() = session.player

    private val woolColor = DyeColor.PINK
    private val wool: Material = WoolDefinition.woolMaterial(woolColor)
    private val woolGlass = Material.PINK_STAINED_GLASS

    /** 台座 (羊毛を置く隙間) */
    private val monument = BlockPos(-6, 1, 0)

    /** 無限湧きの羊毛 (壊しても無くならない) */
    private val spawnerX = 32..34
    private val spawnerY = 1..3
    private val spawnerZ = -15

    private val placed = mutableSetOf<BlockPos>()
    private var woolPlaced = false

    override val spawn: Location
        get() = arena.center(0, 0, 0, yaw = -90f)

    private fun key(name: String): String = "rushcraft.tutorial.ctw.$name"

    // ------------------------------------------------------------------
    // 会場
    // ------------------------------------------------------------------

    override fun build() {
        base(-8, 6, Material.RED_CONCRETE)
        base(28, 44, Material.BLUE_CONCRETE)
        shrine()
        path(7, 14)
        path(21, 27)
        woolRoom()
        enemySpawnRoom()

        // 説明の文字は、近づいてくる側 (正面) に寄せて浮かべる
        session.label(arena.at(-3.4, 2.4, 0.5), key("label-monument"), WoolDefinition.woolName(woolColor), arena = true)
        session.label(arena.at(33.5, 3.4, -5.0), key("label-room"), WoolDefinition.woolName(woolColor), arena = true)
        session.label(arena.at(37.3, 2.6, 0.5), key("label-spawn"), arena = true)
        session.label(arena.at(13.8, 1.8, 0.5), key("label-gap"), arena = true)
    }

    /** 陣地の床 (草の床を石レンガで縁取り、内側をチームの色で囲む) */
    private fun base(x1: Int, x2: Int, ring: Material) {
        arena.fill(x1, -3, -6, x2, -3, 6, Material.STONE)
        arena.fill(x1, -2, -6, x2, -2, 6, Material.DIRT)
        arena.fill(x1, -1, -6, x2, -1, 6, Material.GRASS_BLOCK)
        arena.walls(x1, -2, -6, x2, -1, 6, Material.STONE_BRICKS)
        arena.walls(x1 + 1, -1, -5, x2 - 1, -1, 5, ring)
    }

    private fun path(x1: Int, x2: Int) {
        arena.fill(x1, -1, -1, x2, -1, 1, Material.STONE_BRICKS)
        arena.fill(x1, -2, 0, x2, -2, 0, Material.STONE_BRICKS)
    }

    /** 自陣の台座の祠。台座は色付きガラスで上下を挟んだ隙間で、前に看板がある (マップと同じ作り) */
    private fun shrine() {
        for (z in listOf(-2, 2)) {
            arena.fill(-7, 0, z, -7, 2, z, Material.OAK_LOG)
            arena.fill(-5, 0, z, -5, 2, z, Material.OAK_LOG)
            arena.fill(-6, 0, z, -6, 2, z, Material.STRIPPED_OAK_WOOD)
        }
        arena.fill(-7, 0, -1, -7, 2, 1, Material.STRIPPED_OAK_WOOD)
        arena.fill(-6, 0, -1, -6, 0, 1, Material.OAK_PLANKS)
        arena.fill(-6, 2, -1, -6, 2, 1, Material.OAK_PLANKS)
        arena.set(monument.x, monument.y - 1, monument.z, woolGlass)
        arena.set(monument.x, monument.y + 1, monument.z, woolGlass)
        for (x in -7..-5) for (z in -2..2) arena.slab(x, 3, z, Material.OAK_SLAB)

        // 看板 (プレイヤーの言語で書く)
        arena.facing(-5, 0, 0, Material.OAK_WALL_SIGN, BlockFace.EAST)
        (arena.block(-5, 0, 0).state as? Sign)?.let { sign ->
            val front = sign.getSide(Side.FRONT)
            front.line(1, session.render(WoolDefinition.woolName(woolColor)))
            front.line(2, session.render(Component.translatable(key("sign"))))
            sign.isWaxed = true
            sign.update(true, false)
        }
    }

    /** 敵陣の横の羊毛部屋。正面に入口があり、奥の壁に羊毛が並ぶ */
    private fun woolRoom() {
        arena.fill(29, -3, -16, 37, -3, -7, Material.STONE)
        arena.fill(29, -2, -16, 37, -2, -7, Material.DIRT)
        arena.fill(29, -1, -16, 37, -1, -7, Material.STONE)
        arena.fill(31, -1, -14, 35, -1, -9, Material.GRASS_BLOCK)
        arena.walls(29, 0, -16, 37, 4, -7, Material.STONE_BRICKS)
        // チームの色の帯と窓 (マップと同じく羊毛の色)
        arena.walls(29, 1, -16, 37, 1, -7, Material.PINK_CONCRETE)
        for (z in -14..-9) {
            arena.set(29, 3, z, woolGlass)
            arena.set(37, 3, z, woolGlass)
        }
        for (x in 31..35) arena.set(x, 3, -16, woolGlass)
        for (x in 29..37) arena.stairs(x, 5, -7, Material.STONE_BRICK_STAIRS, BlockFace.NORTH)
        // 入口
        arena.fill(32, 0, -7, 34, 2, -7, Material.AIR)
        // 無限湧きの羊毛
        for (x in spawnerX) for (y in spawnerY) arena.set(x, y, spawnerZ, wool)
        for (x in spawnerX) arena.set(x, 4, spawnerZ, Material.SPRUCE_FENCE)
    }

    /** 敵のスポーン部屋 (入れない) */
    private fun enemySpawnRoom() {
        arena.walls(39, 0, -3, 43, 3, 3, Material.BLUE_STAINED_GLASS)
        arena.fill(39, 4, -3, 43, 4, 3, Material.BLUE_STAINED_GLASS)
    }

    // ------------------------------------------------------------------
    // ブロック
    // ------------------------------------------------------------------

    private fun relative(block: org.bukkit.block.Block): BlockPos {
        val vector = arena.relative(block)
        return BlockPos(vector.blockX, vector.blockY, vector.blockZ)
    }

    private fun isSpawner(position: BlockPos): Boolean =
        position.z == spawnerZ && position.x in spawnerX && position.y in spawnerY

    private fun isProtected(position: BlockPos): Boolean {
        val shrine = position.x in -7..-5 && position.z in -2..2 && position.y in 0..3
        val spawnRoom = position.x in 39..43 && position.z in -3..3 && position.y in 0..4
        return shrine || spawnRoom || position.y > 12 || position.y < -8
    }

    override fun onBlockBreak(event: BlockBreakEvent): Boolean {
        val position = relative(event.block)
        if (isSpawner(position) && event.block.type == wool) {
            // 壊さずに羊毛を 1 つ配る (試合の無限湧きと同じ)
            player.inventory.addItem(ItemStack(wool))
            session.playSound(org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1.2f)
            return false
        }
        // 自分で置いたブロックだけ壊せる
        return placed.remove(position)
    }

    override fun onBlockPlace(event: BlockPlaceEvent): Boolean {
        val position = relative(event.blockPlaced)
        if (position == monument) {
            if (event.blockPlaced.type != wool || woolPlaced) {
                session.actionBar(key("monument-wrong"), NamedTextColor.RED)
                return false
            }
            woolPlaced = true
            return true
        }
        if (isProtected(position)) return false
        placed += position
        return true
    }

    // ------------------------------------------------------------------
    // 段階
    // ------------------------------------------------------------------

    /** マップのキットと、クラシックな鉄剣 (ブロックが無ければ板材も) */
    private fun kit(): List<Pair<Int, ItemStack>> {
        val items = mutableListOf<Pair<Int, ItemStack>>()
        items += KitManager.createSpecialItems(SpecialItem.CLASSIC_SWORD, TutorialSide.ALLY_COLOR)
        val used = items.map { it.first }.toMutableSet()
        for ((slot, stack) in MatchManager.match?.map?.kit?.createItems(TutorialSide.ALLY_COLOR) ?: emptyList()) {
            if (slot in used) continue
            items += slot to stack
            used += slot
        }
        if (items.none { it.second.type.isBlock && it.second.type.isSolid }) {
            val slot = (1..7).first { it !in used }
            items += slot to ItemStack(Material.OAK_PLANKS, 64)
        }
        return items
    }

    private fun hasWool(): Boolean = player.inventory.contains(wool) || player.itemOnCursor.type == wool

    private fun relativeX(): Double = player.location.x - arena.origin.x

    private fun woolObjective(objective: String, state: WoolState) {
        session.objective(
            key(objective),
            Component.text(state.symbol, WoolDefinition.woolColor(woolColor)).append(Component.space())
                .append(WoolDefinition.woolName(woolColor)),
        )
    }

    private fun keyframe(position: Location, look: Location, move: Int, hold: Int, subtitle: String) =
        CameraTour.Keyframe(position, look, move, hold, key(subtitle))

    override fun steps(): List<TutorialStep> = listOf(
        step("intro") {
            session.teleport(spawn)
            session.checkpoint = spawn
            session.equip(emptyList())
            session.watching("rushcraft.tutorial.reading")
            player.showTitle(
                Title.title(
                    Component.translatable(key("name"), NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
                    Component.translatable(key("subtitle"), NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(500)),
                )
            )
            wait(30)
            session.say(key("intro1"))
            wait(50)
            session.say(key("intro2"))
            wait(30)
            session.watching(key("tour-bar"))
            val tour = session.startTour(
                listOf(
                    keyframe(arena.at(1.0, 3.5, 5.0), arena.at(-6.0, 1.5, 0.5), 0, 80, "tour-base"),
                    keyframe(arena.at(11.0, 6.0, 6.0), arena.at(18.0, -1.0, 0.5), 50, 75, "tour-bridge"),
                    keyframe(arena.at(30.0, 9.0, 4.0), arena.at(33.5, 1.0, -11.0), 55, 75, "tour-room"),
                    keyframe(arena.at(33.5, 4.0, -6.0), arena.at(33.5, 2.0, -15.0), 45, 75, "tour-wool"),
                    keyframe(arena.at(38.0, 6.0, 9.0), arena.at(41.5, 1.5, 0.5), 55, 75, "tour-spawn"),
                    keyframe(arena.at(1.0, 3.5, 5.0), arena.at(-6.0, 1.5, 0.5), 65, 80, "tour-goal"),
                )
            )
            waitUntil { tour.isFinished }
            session.teleport(spawn)
            wait(10)
        },
        step("go") {
            session.equip(kit())
            session.objective(key("go"))
            session.title("rushcraft.tutorial.try", key("go"))
            session.say(key("go"))
            session.say(key("go-hint"))
            session.guide = arena.at(28.5, 0.2, 0.5)
            waitUntil { relativeX() > 21 && player.isOnGround }
            session.checkpoint = arena.center(23, 0, 0, yaw = -90f)
            session.actionBar("rushcraft.tutorial.checkpoint", NamedTextColor.GREEN)
            waitUntil { relativeX() >= 28 }
            session.guide = null
            session.success()
            wait(30)
        },
        step("capture") {
            session.equip(kit())
            woolObjective("capture-room", WoolState.NONE)
            session.say(key("capture1"))
            wait(30)
            session.say(key("capture2"))
            // 羊毛部屋の前を守る敵 (羊毛部屋には入ってこない)
            val home = arena.center(33, 0, -4, yaw = 180f)
            session.spawnEnemy(home, health = 16.0).also {
                it.hold(ItemStack(Material.IRON_SWORD))
                it.brain = Brains.melee(
                    player, damage = 3.0, interval = 26, windup = 10, home = home, leash = 9.0,
                    canChase = { !inWoolRoom(player.location) },
                )
            }
            session.guide = arena.at(33.5, 1.0, -14.0)
            waitUntil { hasWool() }

            session.title(null, key("got-wool"))
            session.say(key("got-wool"))
            woolObjective("capture-return", WoolState.CARRIED)
            session.guide = arena.at(-5.5, 1.2, 0.5)
            waitUntil { woolPlaced }

            session.guide = null
            woolObjective("capture-done", WoolState.PLACED)
            celebrate()
            player.showTitle(
                Title.title(
                    Component.translatable(key("placed-title"), NamedTextColor.GOLD),
                    Component.translatable(key("placed-subtitle"), NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500)),
                )
            )
            wait(60)
        },
        step("finish") {
            session.say(key("finish1"))
            wait(50)
            session.say(key("finish2"))
            wait(70)
        },
    )

    private fun inWoolRoom(location: Location): Boolean {
        val x = location.x - arena.origin.x
        val z = location.z - arena.origin.z
        return x >= 30 && x < 37 && z >= -15 && z < -7
    }

    /** 台座から花火を打ち上げる (この花火ではダメージを受けない) */
    private fun celebrate() {
        val location = monument.toCenter(arena.world).add(arena.origin.x, arena.origin.y + 1.0, arena.origin.z)
        val firework = arena.world.spawn(location, Firework::class.java) { entity ->
            entity.fireworkMeta = entity.fireworkMeta.apply {
                power = 1
                addEffect(
                    FireworkEffect.builder()
                        .with(FireworkEffect.Type.BALL_LARGE)
                        .withColor(woolColor.color)
                        .withFade(Color.WHITE)
                        .flicker(true)
                        .trail(true)
                        .build()
                )
            }
        }
        session.track(firework)
        session.playSound(org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_LAUNCH)
        session.playSound(org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f, 0.6f)
    }

    override fun onDown() {
        if (hasWool()) session.say(key("wool-lost"))
    }
}
