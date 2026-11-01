package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID

/**
 * メイス: 持ったまましゃがむとチャージし、チャージが終わってからしゃがみを解除すると、やや前方へ高く飛び上がる。
 * チャージ中にダメージを受けるとチャージが解除される (しゃがみ直すまでチャージできない)。
 *
 * 7 ブロック以上飛び上がった場合、落ち始めて少しすると下向きに加速し、動きに緩急を付ける。
 * 落下ダメージは普通に受ける (外したときのリスク)。
 */
object MaceManager : Tickable {
    private const val CHARGE_TICKS = 25
    private const val LEAP_FORWARD = 1.1
    private const val LEAP_UPWARD = 1.35

    /** 下向きに加速するのに必要な高さ */
    private const val SLAM_MIN_HEIGHT = 7.0

    /** 落ち始めてから下向きに加速するまでの tick 数 */
    private const val SLAM_DELAY_TICKS = 4
    private const val SLAM_VELOCITY = -1.6

    /** 着地しなかった場合に飛び上がりの状態を終える tick 数 */
    private const val MAX_LEAP_TICKS = 100

    private const val BAR_LENGTH = 10

    private class Leap(var previous: Location) {
        val startY = previous.y
        var maxY = previous.y
        var ticks = 0
        var fallingTicks = -1
        var slammed = false
    }

    private val charges = HashMap<UUID, Int>()

    /** ダメージでチャージが解除された (しゃがみを解除するまでチャージしない) */
    private val interrupted = mutableSetOf<UUID>()
    private val leaps = HashMap<UUID, Leap>()

    override fun tick() {
        for (player in Bukkit.getOnlinePlayers()) {
            val uuid = player.uniqueId
            if (!Combatants.canUse(player)) {
                charges.remove(uuid)
                leaps.remove(uuid)
                continue
            }
            leaps[uuid]?.let { tickLeap(player, it) }
            tickCharge(player)
        }
    }

    private fun tickCharge(player: Player) {
        val uuid = player.uniqueId
        val holding = KitManager.specialOf(player.inventory.itemInMainHand) == SpecialItem.MACE
        val charge = charges[uuid]

        if (uuid in interrupted) {
            if (!player.isSneaking) interrupted.remove(uuid)
            return
        }

        if (holding && player.isSneaking && player.isOnGround && uuid !in leaps) {
            val next = (charge ?: 0) + 1
            charges[uuid] = next
            if (next == CHARGE_TICKS) {
                player.world.playSound(
                    Sound.sound(org.bukkit.Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, Sound.Source.PLAYER, 1f, 2f),
                    player.x, player.y, player.z,
                )
            }
            showIndicator(player, next.coerceAtMost(CHARGE_TICKS))
            return
        }

        if (charge == null) return
        charges.remove(uuid)
        // チャージを終えてからしゃがみを解除したら飛び上がる
        if (holding && !player.isSneaking && charge >= CHARGE_TICKS) {
            leap(player)
        } else {
            player.sendActionBar(Component.empty())
        }
    }

    private fun showIndicator(player: Player, charge: Int) {
        val filled = BAR_LENGTH * charge / CHARGE_TICKS
        val ready = charge >= CHARGE_TICKS
        val bar = Component.text()
            .append(Component.text("■".repeat(filled), if (ready) NamedTextColor.GREEN else NamedTextColor.GOLD))
            .append(Component.text("■".repeat(BAR_LENGTH - filled), NamedTextColor.DARK_GRAY))
        if (ready) {
            bar.append(Component.text(" ")).append(Component.translatable("rushcraft.kit.mace.ready", NamedTextColor.GREEN))
        }
        player.sendActionBar(bar.build())
    }

    private fun leap(player: Player) {
        val forward = player.location.direction.setY(0)
        if (forward.lengthSquared() > 1.0E-4) forward.normalize().multiply(LEAP_FORWARD)
        player.velocity = forward.setY(LEAP_UPWARD)
        leaps[player.uniqueId] = Leap(player.location)
        KitActionEvent.call(player, KitActionEvent.Action.MACE_LEAP)
        player.world.playSound(
            Sound.sound(org.bukkit.Sound.ENTITY_BREEZE_JUMP, Sound.Source.PLAYER, 1f, 1f),
            player.x, player.y, player.z,
        )
        player.sendActionBar(Component.empty())
    }

    private fun tickLeap(player: Player, leap: Leap) {
        val location = player.location
        val delta = location.toVector().subtract(leap.previous.toVector())
        leap.previous = location
        leap.ticks++
        leap.maxY = maxOf(leap.maxY, location.y)

        if ((leap.ticks > 2 && player.isOnGround) || leap.ticks > MAX_LEAP_TICKS) {
            leaps.remove(player.uniqueId)
            return
        }

        if (leap.slammed || leap.maxY - leap.startY < SLAM_MIN_HEIGHT) return
        if (leap.fallingTicks < 0) {
            if (delta.y < 0) leap.fallingTicks = 0
            return
        }
        // 落ち始めて少ししてから下向きに加速する
        if (++leap.fallingTicks >= SLAM_DELAY_TICKS) {
            leap.slammed = true
            player.velocity = Vector(delta.x, SLAM_VELOCITY, delta.z)
            player.world.playSound(
                Sound.sound(org.bukkit.Sound.ENTITY_BREEZE_WIND_BURST, Sound.Source.PLAYER, 1f, 0.8f),
                player.x, player.y, player.z,
            )
        }
    }

    /** ダメージを受けたらチャージを解除する */
    fun onDamaged(player: Player) {
        if (charges.remove(player.uniqueId) == null) return
        interrupted += player.uniqueId
        player.sendActionBar(Component.translatable("rushcraft.kit.mace.interrupted", NamedTextColor.RED))
        player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, Sound.Source.PLAYER, 1f, 0.6f), Sound.Emitter.self())
    }

    fun clear(player: Player) {
        interrupted.remove(player.uniqueId)
        charges.remove(player.uniqueId)
        leaps.remove(player.uniqueId)
    }

    fun reset() {
        interrupted.clear()
        charges.clear()
        leaps.clear()
    }

    override fun isAlive(): Boolean = true
}
