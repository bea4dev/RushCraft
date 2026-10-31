package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.match.CombatTracker
import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

/**
 * 投げナイフ: 当たった敵を一撃で倒す。投げてから一定時間で復活する (死んだ場合はリスポーンで再装填)。
 *
 * 見た目は寝かせた火打ち石の ItemDisplay で、水平にくるくる回りながら飛ぶ。
 * パリィ中の相手には弾き返され、盾でガードされると防がれる。
 */
object ThrowingKnife : Tickable {
    /** 投げてから復活するまでの tick 数 */
    const val RECHARGE_TICKS = 600

    private const val SPEED = 1.6
    private const val GRAVITY = 0.02
    private const val DRAG = 0.99
    private const val MAX_FLIGHT_TICKS = 60

    /** 当たり判定の太さ */
    private const val HIT_RADIUS = 0.3

    /** 1 tick あたりの回転角 */
    private const val SPIN_PER_TICK = 50f

    /** 壁に刺さってから消えるまでの tick 数 */
    private const val STUCK_TICKS = 20

    private class Knife(var owner: UUID, val display: ItemDisplay, var position: Location, var velocity: Vector) {
        var ticks = 0
        var angle = 0f
        var stuckTicks = -1
    }

    private val knives = mutableListOf<Knife>()

    fun throwKnife(player: Player) {
        val eye = player.eyeLocation
        val direction = eye.direction
        val start = eye.clone().add(direction.clone().multiply(0.4)).subtract(0.0, 0.2, 0.0)
        start.yaw = 0f
        start.pitch = 0f

        val display = player.world.spawn(start, ItemDisplay::class.java) { display ->
            display.setItemStack(ItemStack(Material.FLINT))
            display.isPersistent = false
            display.teleportDuration = 1
            display.interpolationDuration = 1
            display.transformation = transformation(0f)
        }
        knives += Knife(player.uniqueId, display, start, direction.multiply(SPEED))
        ItemRecharge.schedule(player, SpecialItem.THROWING_KNIFE, RECHARGE_TICKS)

        player.world.playSound(
            Sound.sound(org.bukkit.Sound.ITEM_TRIDENT_THROW, Sound.Source.PLAYER, 1f, 1.8f),
            player.x, player.y, player.z,
        )
        player.world.playSound(
            Sound.sound(org.bukkit.Sound.ENTITY_PLAYER_ATTACK_SWEEP, Sound.Source.PLAYER, 0.6f, 2f),
            player.x, player.y, player.z,
        )
    }

    /** 寝かせた火打ち石を、水平面で angle 度回転させる */
    private fun transformation(angle: Float): Transformation {
        return Transformation(
            Vector3f(),
            Quaternionf().rotateY(Math.toRadians(angle.toDouble()).toFloat()),
            Vector3f(0.8f, 0.8f, 0.8f),
            Quaternionf().rotateX(Math.toRadians(90.0).toFloat()),
        )
    }

    override fun tick() {
        for (knife in knives.toList()) {
            if (!knife.display.isValid) {
                knives -= knife
                continue
            }
            if (knife.stuckTicks >= 0) {
                if (++knife.stuckTicks >= STUCK_TICKS) remove(knife)
                continue
            }
            if (++knife.ticks > MAX_FLIGHT_TICKS || knife.position.y < knife.position.world.minHeight - 16) {
                remove(knife)
                continue
            }
            move(knife)
        }
    }

    private fun move(knife: Knife) {
        val world = knife.position.world
        val velocity = knife.velocity
        val owner = Bukkit.getPlayer(knife.owner)

        // パリィ中の敵に向かっていれば、矢と同じように弾き返す
        val parrier = ShieldManager.findParrying(knife.position, velocity) {
            it.uniqueId != knife.owner && Combatants.isTarget(it) && (owner == null || Combatants.isEnemy(owner, it))
        }
        if (parrier != null) {
            reflect(knife, parrier)
            return
        }

        val hit = world.rayTrace(
            knife.position, velocity, velocity.length(), FluidCollisionMode.NEVER, true, HIT_RADIUS,
        ) { entity ->
            entity is Player && entity.uniqueId != knife.owner && Combatants.isTarget(entity) &&
                (owner == null || Combatants.isEnemy(owner, entity))
        }

        val victim = hit?.hitEntity as? Player
        if (victim != null) {
            hitPlayer(knife, victim, owner, hit.hitPosition)
            return
        }
        if (hit?.hitBlock != null) {
            // 壁や床に刺さる
            knife.position = hit.hitPosition.toLocation(world)
            knife.display.teleport(knife.position)
            knife.stuckTicks = 0
            world.playSound(
                Sound.sound(org.bukkit.Sound.ITEM_TRIDENT_HIT_GROUND, Sound.Source.PLAYER, 1f, 1.6f),
                knife.position.x, knife.position.y, knife.position.z,
            )
            world.spawnParticle(Particle.CRIT, knife.position, 6, 0.05, 0.05, 0.05, 0.1)
            return
        }

        knife.position.add(velocity)
        knife.display.teleport(knife.position)
        knife.angle = (knife.angle + SPIN_PER_TICK) % 360f
        knife.display.interpolationDelay = 0
        knife.display.transformation = transformation(knife.angle)

        velocity.multiply(DRAG)
        velocity.y -= GRAVITY
    }

    private fun hitPlayer(knife: Knife, victim: Player, owner: Player?, position: Vector) {
        val world = victim.world
        knife.position = position.toLocation(world)

        // パリィ中なら投げた方向へ弾き返す
        if (ShieldManager.isParrying(victim)) {
            reflect(knife, victim)
            return
        }

        // 盾を正面に構えていれば防がれる
        if (victim.isBlocking && victim.location.direction.dot(knife.velocity) < 0) {
            ShieldManager.onBlockedHit(victim)
            world.playSound(
                Sound.sound(org.bukkit.Sound.ITEM_SHIELD_BLOCK, Sound.Source.PLAYER, 1f, 1f),
                victim.x, victim.y, victim.z,
            )
            remove(knife)
            return
        }

        world.playSound(
            Sound.sound(org.bukkit.Sound.ENTITY_PLAYER_ATTACK_CRIT, Sound.Source.PLAYER, 1f, 0.8f),
            victim.x, victim.y, victim.z,
        )
        world.spawnParticle(Particle.DAMAGE_INDICATOR, victim.location.add(0.0, 1.0, 0.0), 8, 0.3, 0.4, 0.3, 0.2)
        remove(knife)
        CombatTracker.kill(victim, owner, "rushcraft.match.death.knife")
    }

    /** 投げた方向へ弾き返す (弾き返した人のナイフになる) */
    private fun reflect(knife: Knife, player: Player) {
        knife.velocity.multiply(-1)
        knife.owner = player.uniqueId
        knife.ticks = 0
        ShieldManager.parried(player)
    }

    private fun remove(knife: Knife) {
        knives -= knife
        knife.display.remove()
    }

    fun reset() {
        knives.toList().forEach(::remove)
    }

    override fun isAlive(): Boolean = true
}
