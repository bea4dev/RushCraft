package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.match.TeamColor
import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import kotlin.math.roundToInt

/**
 * 即席壁: 置いた場所に、プレイヤーの向きと直交する幅 5・高さ 3 のガラスの壁を作る。
 *
 * - 2 tick ごとに 1 段ずつ積み上がる
 * - 各列は地形に合わせて 1 ブロックまで上下にずれる
 * - ブロックが置かれる場所にいるプレイヤー (とチュートリアルのダミー) は上に吹き飛ばされる
 * - 置けるかどうかは通常の設置と同じ判定 (建築禁止エリア・スポーン部屋など)
 * - 使ってから一定時間で復活する
 *
 * 作るのはプレイヤーとは限らない (チュートリアルのお手本)。
 */
object InstantWall : Tickable {
    const val RECHARGE_TICKS = 300

    private const val HALF_WIDTH = 2
    private const val HEIGHT = 3
    private const val LAYER_INTERVAL = 2
    private const val LAUNCH_VELOCITY = 1.2

    private class Wall(val builder: LivingEntity, val columns: List<Block>, val material: Material) {
        var ticks = 0
        var layer = 0

        /** 途中で塞がっていた列はそれ以上積まない */
        val blocked = BooleanArray(columns.size)
    }

    private val walls = mutableListOf<Wall>()

    fun glassOf(color: TeamColor): Material = Material.valueOf("${color.dye.name}_STAINED_GLASS")

    /**
     * clicked の face 側を中心に壁を作り始める。
     * @return 作り始めたか
     */
    fun build(player: LivingEntity, base: Block): Boolean {
        val color = Combatants.colorOf(player) ?: return false
        val forward = snapDirection(player.location.direction)
        // 向きと直交する方向に並べる
        val side = Vector(-forward.z, 0.0, forward.x)

        val columns = (-HALF_WIDTH..HALF_WIDTH).mapNotNull { offset ->
            columnBase(base.getRelative(side.blockX * offset, 0, side.blockZ * offset))
        }
        if (columns.isEmpty()) return false

        walls += Wall(player, columns, glassOf(color))
        KitActionEvent.call(player, KitActionEvent.Action.WALL_BUILT)
        return true
    }

    /** 水平方向を 8 方位に丸めた (x, z) が -1..1 のベクトル */
    private fun snapDirection(direction: Vector): Vector {
        val angle = Math.atan2(direction.z, direction.x)
        val step = (angle / (Math.PI / 4)).roundToInt()
        val snapped = step * (Math.PI / 4)
        return Vector(Math.cos(snapped).roundToInt().toDouble(), 0.0, Math.sin(snapped).roundToInt().toDouble())
    }

    /** 地形に合わせて列の一番下の位置を決める (置けなければ null) */
    private fun columnBase(block: Block): Block? {
        if (!isReplaceable(block)) {
            // 段差は 1 ブロックまで登る
            val above = block.getRelative(0, 1, 0)
            return above.takeIf(::isReplaceable)
        }
        // 1 ブロックの段差は下りる
        val below = block.getRelative(0, -1, 0)
        if (isReplaceable(below) && !isReplaceable(below.getRelative(0, -1, 0))) return below
        return block
    }

    private fun isReplaceable(block: Block): Boolean = block.type.isAir || block.isReplaceable

    override fun tick() {
        for (wall in walls.toList()) {
            if (wall.ticks++ % LAYER_INTERVAL != 0) continue
            placeLayer(wall)
            if (++wall.layer >= HEIGHT || !Combatants.isArena(wall.columns.first().location)) walls -= wall
        }
    }

    private fun placeLayer(wall: Wall) {
        var placed = false
        for ((index, base) in wall.columns.withIndex()) {
            if (wall.blocked[index]) continue
            val block = base.getRelative(0, wall.layer, 0)
            if (!isReplaceable(block) || !canPlace(wall.builder, block, wall.material)) {
                wall.blocked[index] = true
                continue
            }
            launchPlayers(wall, block)
            block.setType(wall.material, false)
            placed = true
        }
        if (placed) {
            val center = wall.columns[wall.columns.size / 2].getRelative(0, wall.layer, 0).location.add(0.5, 0.5, 0.5)
            center.world.playSound(Sound.sound(org.bukkit.Sound.BLOCK_GLASS_PLACE, Sound.Source.BLOCK, 1f, 0.8f + wall.layer * 0.2f), center.x, center.y, center.z)
        }
    }

    /** 通常の設置と同じ保護を通す (お手本のダミーは範囲内ならどこでも置ける) */
    private fun canPlace(builder: LivingEntity, block: Block, material: Material): Boolean {
        val player = builder as? Player ?: return builder.isValid && Combatants.isArena(block.location)
        if (!player.isOnline) return false
        val event = BlockPlaceEvent(
            block, block.state, block.getRelative(0, -1, 0), ItemStack(material), player, true, EquipmentSlot.HAND,
        )
        Bukkit.getPluginManager().callEvent(event)
        return !event.isCancelled && event.canBuild()
    }

    /** ブロックが置かれる場所にいるプレイヤー (とダミー) を上に吹き飛ばす */
    private fun launchPlayers(wall: Wall, block: Block) {
        val box = BoundingBox.of(block)
        for (entity in block.world.getNearbyEntitiesByType(LivingEntity::class.java, block.location.add(0.5, 0.5, 0.5), 2.0)) {
            if (!entity.boundingBox.overlaps(box) || entity.isDead) continue
            if (entity !is Player && !Combatants.isTarget(entity)) continue
            entity.velocity = entity.velocity.setY(LAUNCH_VELOCITY)
            if (entity != wall.builder && Combatants.isEnemy(wall.builder, entity)) {
                KitActionEvent.call(wall.builder, KitActionEvent.Action.WALL_LAUNCH, entity)
            }
        }
    }

    /** 範囲内で作っている途中の壁を止める */
    fun clearArea(world: World, box: BoundingBox) {
        walls.removeIf { wall -> wall.columns.first().world == world && box.contains(wall.columns.first().location.toVector()) }
    }

    fun reset() {
        walls.clear()
    }

    override fun isAlive(): Boolean = true
}
