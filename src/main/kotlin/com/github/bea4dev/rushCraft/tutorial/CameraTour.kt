package com.github.bea4dev.rushCraft.tutorial

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import java.time.Duration
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * カメラで会場を見て回る演出。
 *
 * プレイヤーを観戦モードにして、見えないアーマースタンドの視点に入れて動かす
 * (クライアントがエンティティの動きを補間するので、プレイヤーを直接テレポートするより滑らか)。
 * スニークで飛ばせる。
 *
 * @param subtitleKey そのキーフレームに着いたときに出す字幕
 */
class CameraTour(private val player: Player, private val keyframes: List<Keyframe>) {
    class Keyframe(val position: Location, val look: Location, val moveTicks: Int, val holdTicks: Int, val subtitleKey: String? = null) {
        val yaw: Float
        val pitch: Float

        init {
            val dx = look.x - position.x
            val dy = look.y - position.y
            val dz = look.z - position.z
            yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
            pitch = Math.toDegrees(-atan2(dy, sqrt(dx * dx + dz * dz))).toFloat()
        }
    }

    private var camera: ArmorStand? = null
    private var index = 0
    private var ticks = 0
    private var previousGameMode = player.gameMode

    var isFinished = false
        private set

    fun start() {
        val first = keyframes.first()
        previousGameMode = player.gameMode
        player.gameMode = GameMode.SPECTATOR
        player.teleport(withRotation(first.position, first.yaw, first.pitch))
        camera = first.position.world.spawn(withRotation(first.position, first.yaw, first.pitch), ArmorStand::class.java) { stand ->
            stand.isMarker = true
            stand.isInvisible = true
            stand.setGravity(false)
            stand.isInvulnerable = true
            stand.isSilent = true
            stand.isPersistent = false
        }
        showSubtitle(first)
    }

    fun tick() {
        if (isFinished) return
        val camera = camera ?: return
        if (!camera.isValid || !player.isOnline) {
            finish()
            return
        }
        // クライアントがエンティティを知ってから視点に入れる (外れたら入れ直す)
        if (ticks >= 2 && player.spectatorTarget != camera) player.spectatorTarget = camera

        val current = keyframes[index]
        val next = keyframes.getOrNull(index + 1)
        ticks++
        if (next == null) {
            if (ticks >= current.holdTicks) finish()
            return
        }
        val moving = ticks - current.holdTicks
        if (moving <= 0) return
        if (moving >= next.moveTicks) {
            index++
            ticks = 0
            camera.teleport(withRotation(next.position, next.yaw, next.pitch))
            showSubtitle(next)
            return
        }
        // ゆっくり動き出して、ゆっくり止まる
        val t = moving.toDouble() / next.moveTicks
        val eased = t * t * (3 - 2 * t)
        val position = current.position.clone().add(next.position.toVector().subtract(current.position.toVector()).multiply(eased))
        val yaw = current.yaw + wrap(next.yaw - current.yaw) * eased.toFloat()
        val pitch = current.pitch + (next.pitch - current.pitch) * eased.toFloat()
        camera.teleport(withRotation(position, yaw, pitch))
    }

    private fun showSubtitle(keyframe: Keyframe) {
        val key = keyframe.subtitleKey ?: return
        val stay = (keyframe.holdTicks + (keyframes.getOrNull(keyframes.indexOf(keyframe) + 1)?.moveTicks ?: 0)) * 50L
        player.showTitle(
            Title.title(
                Component.empty(),
                Component.translatable(key, NamedTextColor.WHITE),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(stay), Duration.ofMillis(300)),
            )
        )
        player.sendActionBar(Component.translatable("rushcraft.tutorial.tour-skip", NamedTextColor.GRAY))
    }

    /** スニークで飛ばす */
    fun skip() {
        finish()
    }

    fun finish() {
        if (isFinished) return
        isFinished = true
        if (player.isOnline) {
            player.spectatorTarget = null
            player.gameMode = previousGameMode.takeIf { it != GameMode.SPECTATOR } ?: GameMode.SURVIVAL
            player.clearTitle()
        }
        camera?.remove()
        camera = null
    }

    private fun withRotation(location: Location, yaw: Float, pitch: Float): Location {
        return location.clone().also {
            it.yaw = yaw
            it.pitch = pitch
        }
    }

    private fun wrap(angle: Float): Float {
        var value = angle % 360f
        if (value > 180f) value -= 360f
        if (value < -180f) value += 360f
        return value
    }
}
