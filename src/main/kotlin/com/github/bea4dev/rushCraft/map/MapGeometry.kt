package com.github.bea4dev.rushCraft.map

import com.github.bea4dev.rushCraft.util.optDouble
import com.github.bea4dev.rushCraft.util.optTable
import com.github.bea4dev.rushCraft.util.requireDouble
import com.github.bea4dev.rushCraft.util.requireInt
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.tomlj.TomlTable
import kotlin.math.max
import kotlin.math.min

data class BlockPos(val x: Int, val y: Int, val z: Int) {
    fun toLocation(world: World): Location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())

    fun toCenter(world: World): Location = Location(world, x + 0.5, y + 0.5, z + 0.5)

    fun toToml(): Map<String, Any> = linkedMapOf("x" to x, "y" to y, "z" to z)

    override fun toString(): String = "$x, $y, $z"

    companion object {
        fun of(block: Block): BlockPos = BlockPos(block.x, block.y, block.z)

        fun of(location: Location): BlockPos = BlockPos(location.blockX, location.blockY, location.blockZ)

        fun parse(table: TomlTable): BlockPos {
            return BlockPos(table.requireInt("x"), table.requireInt("y"), table.requireInt("z"))
        }
    }
}

data class SpawnPoint(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float) {
    fun toLocation(world: World): Location = Location(world, x, y, z, yaw, pitch)

    fun distanceSquared(location: Location): Double {
        val dx = x - location.x
        val dy = y - location.y
        val dz = z - location.z
        return dx * dx + dy * dy + dz * dz
    }

    fun toToml(): Map<String, Any> = linkedMapOf(
        "x" to x,
        "y" to y,
        "z" to z,
        "yaw" to yaw.toDouble(),
        "pitch" to pitch.toDouble(),
    )

    override fun toString(): String = "%.1f, %.1f, %.1f".format(x, y, z)

    companion object {
        val DEFAULT = SpawnPoint(0.5, 64.0, 0.5, 0F, 0F)

        fun of(location: Location): SpawnPoint {
            // ブロックの中心か半ブロック単位に揃えておくと扱いやすい
            fun snap(value: Double): Double = Math.round(value * 2) / 2.0
            fun snapAngle(value: Float): Float = Math.round(value / 15F) * 15F
            return SpawnPoint(
                snap(location.x),
                snap(location.y),
                snap(location.z),
                snapAngle(location.yaw),
                snapAngle(location.pitch),
            )
        }

        fun parse(table: TomlTable): SpawnPoint {
            return SpawnPoint(
                table.requireDouble("x"),
                table.requireDouble("y"),
                table.requireDouble("z"),
                (table.optDouble("yaw") ?: 0.0).toFloat(),
                (table.optDouble("pitch") ?: 0.0).toFloat(),
            )
        }
    }
}

data class Region(val min: BlockPos, val max: BlockPos) {
    fun contains(x: Int, y: Int, z: Int): Boolean {
        return x in min.x..max.x && y in min.y..max.y && z in min.z..max.z
    }

    fun contains(block: Block): Boolean = contains(block.x, block.y, block.z)

    fun contains(location: Location): Boolean = contains(location.blockX, location.blockY, location.blockZ)

    /** 1 ブロックだけの範囲は `{ x, y, z }`、それ以外は `{ min, max }` で書き出す */
    fun toToml(): Map<String, Any> {
        return if (min == max) min.toToml() else linkedMapOf("min" to min.toToml(), "max" to max.toToml())
    }

    override fun toString(): String = if (min == max) "($min)" else "($min) - ($max)"

    companion object {
        /** `{ min = {..}, max = {..} }` または 1 ブロックの `{ x, y, z }` */
        fun parse(table: TomlTable): Region {
            val min = table.optTable("min")
            val max = table.optTable("max")
            if (min != null && max != null) {
                return of(BlockPos.parse(min), BlockPos.parse(max))
            }
            val pos = BlockPos.parse(table)
            return Region(pos, pos)
        }

        fun of(a: BlockPos, b: BlockPos): Region {
            return Region(
                BlockPos(min(a.x, b.x), min(a.y, b.y), min(a.z, b.z)),
                BlockPos(max(a.x, b.x), max(a.y, b.y), max(a.z, b.z)),
            )
        }
    }
}
