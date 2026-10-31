package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.match.CombatTracker
import com.github.bea4dev.rushCraft.match.MatchTeam
import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID

/**
 * トラップ: 置いた感圧板を敵が踏むと爆発し、周り 2.5 ブロック以内の敵を即死させる。
 *
 * - 自分や味方が踏んでも何も起きない (感圧板も反応しない)
 * - スポーン時に 10 個配られる。置けるのは 1 人 10 個までで、11 個目を置くと最初に置いたものから爆発して解除される
 * - 感圧板を壊すと何もドロップせず、トラップは解除される
 * - 感圧板の見た目はマップごとに変えられる (map.toml の [kit] trap-plate)
 */
object TrapManager : Tickable {
    const val MAX_TRAPS = 10

    private const val KILL_RADIUS = 2.5

    /** 感圧板が残っているかを確かめる間隔 */
    private const val CHECK_INTERVAL = 20

    private class Trap(val block: Block, val owner: UUID, val team: MatchTeam?)

    /** 置いた順 */
    private val traps = LinkedHashMap<Block, Trap>()
    private var ticks = 0

    fun place(player: Player, block: Block) {
        traps[block] = Trap(block, player.uniqueId, MatchManager.match?.getTeam(player))
        // 上限を超えたら古いものから爆発させる
        val own = traps.values.filter { it.owner == player.uniqueId }
        own.take((own.size - MAX_TRAPS).coerceAtLeast(0)).forEach(::explode)
    }

    fun isTrap(block: Block): Boolean = block in traps

    /**
     * 感圧板を踏んだ。敵なら爆発させる。
     * @return トラップだったか (トラップなら感圧板は反応させない)
     */
    fun onStep(player: Player, block: Block): Boolean {
        val trap = traps[block] ?: return false
        if (Combatants.isTarget(player) && isEnemy(trap, player)) explode(trap)
        return true
    }

    /** 壊されたなどで感圧板が無くなった */
    fun remove(block: Block) {
        traps.remove(block)
    }

    private fun isEnemy(trap: Trap, player: Player): Boolean {
        if (player.uniqueId == trap.owner) return false
        val team = MatchManager.match?.getTeam(player) ?: return false
        return trap.team == null || team != trap.team
    }

    private fun explode(trap: Trap) {
        traps.remove(trap.block)
        val block = trap.block
        if (Tag.PRESSURE_PLATES.isTagged(block.type)) block.setType(Material.AIR, false)

        val center = block.location.add(0.5, 0.2, 0.5)
        val world = center.world
        world.spawnParticle(Particle.EXPLOSION, center, 3, 0.4, 0.2, 0.4, 0.0)
        world.playSound(Sound.sound(org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, Sound.Source.BLOCK, 2f, 1.2f), center.x, center.y, center.z)

        // 敵にだけダメージを与える
        val owner = Bukkit.getPlayer(trap.owner)
        for (player in world.getNearbyEntitiesByType(Player::class.java, center, KILL_RADIUS + 2.0)) {
            if (!Combatants.isTarget(player) || !isEnemy(trap, player)) continue
            if (distanceToBody(player, center.toVector()) > KILL_RADIUS) continue
            CombatTracker.kill(player, owner, "rushcraft.match.death.trap")
        }
    }

    private fun distanceToBody(player: Player, point: Vector): Double {
        val box = player.boundingBox
        val x = point.x.coerceIn(box.minX, box.maxX)
        val y = point.y.coerceIn(box.minY, box.maxY)
        val z = point.z.coerceIn(box.minZ, box.maxZ)
        return Vector(x, y, z).distance(point)
    }

    override fun tick() {
        if (++ticks % CHECK_INTERVAL != 0) return
        // 爆発などで感圧板が無くなっていたら解除する
        traps.values.removeIf { !Tag.PRESSURE_PLATES.isTagged(it.block.type) || !MatchManager.isRunning(it.block.world) }
    }

    fun reset() {
        traps.clear()
    }

    override fun isAlive(): Boolean = true
}
