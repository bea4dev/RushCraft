package com.github.bea4dev.rushCraft.tutorial.content

import com.github.bea4dev.rushCraft.kit.Combatants
import com.github.bea4dev.rushCraft.kit.ThrowingKnife
import com.github.bea4dev.rushCraft.tutorial.Dummy
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * ダミーの動き。Dummy.brain に入れて使う。
 */
object Brains {
    /** その場でプレイヤーの方を向く */
    fun watch(player: Player): (Dummy) -> Unit = { it.face(player) }

    /** a と b の間を行ったり来たりする (動く的) */
    fun strafe(a: Location, b: Location, speed: Double = 0.14, player: Player? = null): (Dummy) -> Unit {
        var toB = true
        return { dummy ->
            if (dummy.walk(if (toB) b else a, speed)) toB = !toB
            player?.let { dummy.entity.lookAt(it.eyeLocation, io.papermc.paper.entity.LookAnchor.EYES) }
        }
    }

    /** points を順に歩いて回る */
    fun patrol(points: List<Location>, speed: Double = 0.12): (Dummy) -> Unit {
        var index = 0
        return { dummy ->
            if (dummy.walk(points[index], speed)) index = (index + 1) % points.size
        }
    }

    /**
     * プレイヤーに近づいて殴る。殴る前に合図を出し (windup tick)、その間に盾でパリィできる。
     * @param leash home からこれ以上離れない (null なら追いかけ続ける)
     */
    fun melee(
        player: Player,
        damage: Double,
        interval: Int = 30,
        windup: Int = 12,
        reach: Double = 2.4,
        speed: Double = 0.17,
        home: Location? = null,
        leash: Double? = null,
        canChase: () -> Boolean = { true },
    ): (Dummy) -> Unit {
        var cooldown = interval / 2
        var winding = -1
        return { dummy ->
            val distance = dummy.distanceTo(player)
            val active = Combatants.isTarget(player) && canChase()
            if (winding >= 0) {
                dummy.stop()
                dummy.face(player)
                if (++winding >= windup) {
                    winding = -1
                    cooldown = interval
                    if (active && distance <= reach + 0.8) dummy.meleeHit(player, damage) else dummy.entity.swingMainHand()
                }
            } else {
                val tooFar = home != null && leash != null && player.location.distance(home) > leash
                when {
                    !active || tooFar -> if (home == null || dummy.walk(home, speed)) dummy.face(player)
                    distance > reach -> {
                        dummy.walk(player.location, speed)
                        dummy.face(player)
                    }

                    else -> {
                        dummy.stop()
                        dummy.face(player)
                    }
                }
                if (--cooldown <= 0 && active && !tooFar && distance <= reach) {
                    winding = 0
                    dummy.warn()
                }
            }
        }
    }

    /**
     * 弓を引いて (draw tick) から矢を射る。引いている間が合図になる。
     */
    fun archer(
        player: Player,
        interval: Int = 50,
        draw: Int = 25,
        damage: Double = 1.0,
        speed: Double = 1.6,
        target: () -> Location = { player.location.add(0.0, 1.1, 0.0) },
    ): (Dummy) -> Unit {
        var timer = interval / 2
        var drawing = -1
        return { dummy ->
            dummy.face(player)
            if (drawing >= 0) {
                if (++drawing >= draw) {
                    drawing = -1
                    timer = interval
                    if (Combatants.isTarget(player)) dummy.shootArrow(target(), speed, damage) else dummy.releaseItem()
                }
            } else if (--timer <= 0 && Combatants.isTarget(player)) {
                drawing = 0
                dummy.drawBow()
            }
        }
    }

    /** 合図を出してから投げナイフを投げる */
    fun knifeThrower(player: Player, interval: Int = 70, windup: Int = 20): (Dummy) -> Unit {
        var timer = interval / 2
        var winding = -1
        return { dummy ->
            dummy.face(player)
            if (winding >= 0) {
                if (winding % 5 == 0) dummy.warn()
                if (++winding >= windup) {
                    winding = -1
                    timer = interval
                    if (Combatants.isTarget(player)) {
                        dummy.entity.swingMainHand()
                        ThrowingKnife.throwKnife(dummy.entity)
                    }
                }
            } else if (--timer <= 0 && Combatants.isTarget(player)) {
                winding = 0
            }
        }
    }
}
