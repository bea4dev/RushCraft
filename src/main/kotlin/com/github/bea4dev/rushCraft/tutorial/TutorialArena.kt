package com.github.bea4dev.rushCraft.tutorial

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector

/**
 * 1 つのチュートリアルの会場。座標は基準位置 (origin) からの相対座標で扱う。
 *
 * y = 0 が床の上面 (立つ高さ) になるように作る。床のブロックは y = -1。
 */
class TutorialArena(val origin: Location) {
    val world: World = origin.world

    /** 会場の範囲。この中だけで特殊アイテムが働き、終わったらこの中のエンティティを片付ける */
    val bounds: BoundingBox = BoundingBox(
        origin.x - 80, origin.y - 48, origin.z - 80,
        origin.x + 112, origin.y + 64, origin.z + 80,
    )

    /** この高さより下に落ちたら奈落に落ちたことにする */
    val voidY: Double = origin.y - 20

    fun contains(location: Location): Boolean = location.world == world && bounds.contains(location.toVector())

    fun at(x: Double, y: Double, z: Double, yaw: Float = 0f, pitch: Float = 0f): Location =
        Location(world, origin.x + x, origin.y + y, origin.z + z, yaw, pitch)

    /** ブロックの中心 (床の上に立つ位置) */
    fun center(x: Int, y: Int, z: Int, yaw: Float = 0f): Location = at(x + 0.5, y.toDouble(), z + 0.5, yaw)

    fun block(x: Int, y: Int, z: Int): Block = world.getBlockAt(origin.blockX + x, origin.blockY + y, origin.blockZ + z)

    fun relative(block: Block): Vector = Vector(block.x - origin.blockX, block.y - origin.blockY, block.z - origin.blockZ)

    // ------------------------------------------------------------------
    // 建築
    // ------------------------------------------------------------------

    fun set(x: Int, y: Int, z: Int, material: Material) {
        block(x, y, z).setType(material, false)
    }

    fun set(x: Int, y: Int, z: Int, data: BlockData) {
        block(x, y, z).setBlockData(data, false)
    }

    fun fill(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, material: Material) {
        for (x in minOf(x1, x2)..maxOf(x1, x2)) {
            for (y in minOf(y1, y2)..maxOf(y1, y2)) {
                for (z in minOf(z1, z2)..maxOf(z1, z2)) {
                    set(x, y, z, material)
                }
            }
        }
    }

    /** 箱の外周の壁だけ */
    fun walls(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, material: Material) {
        fill(x1, y1, z1, x2, y2, z1, material)
        fill(x1, y1, z2, x2, y2, z2, material)
        fill(x1, y1, z1, x1, y2, z2, material)
        fill(x2, y1, z1, x2, y2, z2, material)
    }

    /** 範囲内の material のブロックを空気にする (即席壁の片付けなど) */
    fun clear(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, vararg materials: Material) {
        for (x in minOf(x1, x2)..maxOf(x1, x2)) {
            for (y in minOf(y1, y2)..maxOf(y1, y2)) {
                for (z in minOf(z1, z2)..maxOf(z1, z2)) {
                    val block = block(x, y, z)
                    if (materials.isEmpty() || block.type in materials) block.setType(Material.AIR, false)
                }
            }
        }
    }

    fun stairs(x: Int, y: Int, z: Int, material: Material, facing: BlockFace, top: Boolean = false) {
        val data = material.createBlockData() as Stairs
        data.facing = facing
        data.half = if (top) org.bukkit.block.data.Bisected.Half.TOP else org.bukkit.block.data.Bisected.Half.BOTTOM
        set(x, y, z, data)
    }

    fun slab(x: Int, y: Int, z: Int, material: Material, top: Boolean = false) {
        val data = material.createBlockData() as Slab
        data.type = if (top) Slab.Type.TOP else Slab.Type.BOTTOM
        set(x, y, z, data)
    }

    fun facing(x: Int, y: Int, z: Int, material: Material, facing: BlockFace) {
        val data = material.createBlockData()
        if (data is Directional) data.facing = facing
        set(x, y, z, data)
    }

    /**
     * 練習場の床。草ブロックの床を石レンガで縁取り、手前 (z が大きい側) を味方の赤、奥を敵の青で塗る。
     */
    fun trainingFloor(x1: Int, z1: Int, x2: Int, z2: Int) {
        fill(x1, -3, z1, x2, -3, z2, Material.STONE)
        fill(x1, -2, z1, x2, -2, z2, Material.DIRT)
        fill(x1, -1, z1, x2, -1, z2, Material.GRASS_BLOCK)
        walls(x1, -1, z1, x2, -1, z2, Material.STONE_BRICKS)
        walls(x1, -2, z1, x2, -2, z2, Material.STONE_BRICKS)
        fill(x1 + 1, -1, z2 - 1, x2 - 1, -1, z2 - 1, Material.RED_CONCRETE)
        fill(x1 + 1, -1, z1 + 1, x2 - 1, -1, z1 + 1, Material.BLUE_CONCRETE)
        for ((x, z) in listOf(x1 to z1, x1 to z2, x2 to z1, x2 to z2)) {
            fill(x, 0, z, x, 1, z, Material.OAK_LOG)
            set(x, 2, z, Material.LANTERN)
        }
    }

    /** 宙に浮いた小さな島 (上面が y) */
    fun island(cx: Int, y: Int, cz: Int, radius: Int, top: Material = Material.GRASS_BLOCK) {
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                if (x * x + z * z > radius * radius + radius) continue
                set(cx + x, y - 1, cz + z, top)
                set(cx + x, y - 2, cz + z, Material.DIRT)
                if (x * x + z * z <= (radius - 1) * (radius - 1)) set(cx + x, y - 3, cz + z, Material.STONE)
            }
        }
    }
}
