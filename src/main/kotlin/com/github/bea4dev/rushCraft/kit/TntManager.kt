package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.match.CombatTracker
import com.github.bea4dev.rushCraft.match.TeamColor
import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.random.Random

/**
 * TNT: 右クリックで着火して目の前に構え、クリック (左右どちらでも) で投げる。
 *
 * - 爆発までの時間はランダム。TNT の位置から鳴る音がだんだん速く・高くなって爆発する
 * - 投げた TNT は近くの敵に強制的に持たせる。持っている人は爆発で即死し、周り 2.5 ブロック以内も即死
 *   (投げた人のチームの味方は巻き込まない)
 * - 持たされた人も、時間内ならクリックで投げ返せる。投げた人がその TNT の新しい持ち主になる
 * - 誰にも当たらなければ、持ち主 (最後に投げた人) の元へ飛んで戻ってくる (奈落に落ちても戻ってくる)。
 *   投げている間・戻る途中に時間切れになったときは、戻りきってから爆発する (爆発直前に投げても避けられない)
 * - 持ち主の手を離れている間は、持ち主と TNT をパーティクルの線で結ぶ
 * - 爆発してから一定時間で復活する (死んだ場合はリスポーンで再装填)
 */
object TntManager : Tickable {
    private const val MIN_FUSE_TICKS = 80
    private const val MAX_FUSE_TICKS = 160

    private const val THROW_SPEED = 1.1
    private const val THROW_UPWARD = 0.15
    private const val GRAVITY = 0.05
    private const val DRAG = 0.98
    private const val MAX_FLIGHT_TICKS = 30

    /** 着地してから戻り始めるまでの tick 数 */
    private const val LANDED_TICKS = 6

    /** 投げた TNT を持たせる距離 */
    private const val CATCH_RADIUS = 1.3

    /** 戻るときの速さ (だんだん速くなる) */
    private const val RETURN_START_SPEED = 0.3
    private const val RETURN_MAX_SPEED = 1.0
    private const val RETURN_ACCELERATION = 0.06

    /** 残り時間がこれ以下だと投げられない (投げ続けて爆発を先延ばしにできないようにする) */
    private const val THROW_LOCK_FUSE = 5

    /** 持ってからこの tick 数の間は投げられない (着火や受け取りと同じクリックで投げてしまわないように) */
    private const val THROW_DELAY_TICKS = 4

    /** 爆発してから復活するまでの tick 数 */
    private const val RECHARGE_TICKS = 300

    /** 爆発で即死する範囲 */
    private const val KILL_RADIUS = 2.5

    /** 爆発に巻き込まれて誘爆する範囲 */
    private const val CHAIN_RADIUS = 3.5

    /** 表示の大きさ */
    private const val SCALE = 0.45f

    private enum class State { HELD, FLYING, RETURNING, RESTING }

    /**
     * @param origin 着火した人 (爆発すると、この人の TNT が時間で復活する)
     * @param owner 持ち主 (最後に投げた人)。外れたときに戻る先で、キルもこの人のものになる
     */
    private class Tnt(
        var origin: UUID?,
        var owner: UUID,
        var color: TeamColor?,
        var display: BlockDisplay,
        var position: Location,
        val totalFuse: Int,
    ) {
        var fuse = totalFuse
        var state = State.HELD
        var holder: UUID? = owner
        var velocity = Vector()
        var stateTicks = 0

        /** 着地してからの tick 数 (飛んでいる間は -1) */
        var landedTicks = -1
        var returnSpeed = RETURN_START_SPEED
        var nextBeep = 0
        var glowTicks = 0

        /** 持ち主が死んだ・抜けた (もう戻らない) */
        var orphan = false

        fun owner(): Player? = if (orphan) null else Bukkit.getPlayer(owner)?.takeIf { Combatants.isTarget(it) }

        fun holder(): Player? = holder?.let(Bukkit::getPlayer)

        val progress: Double
            get() = 1.0 - fuse.toDouble() / totalFuse
    }

    private val tnts = mutableListOf<Tnt>()

    // ------------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------------

    /** 自分が着火した TNT がまだ爆発していないか (投げている途中や、相手に投げ返された後も含む) */
    fun isActive(player: Player): Boolean = tnts.any { it.origin == player.uniqueId }

    /** TNT を持っているか (自分のものでも、持たされたものでも) */
    fun isHolding(player: Player): Boolean = tnts.any { it.state == State.HELD && it.holder == player.uniqueId }

    /**
     * TNT アイテムを右クリックした。アイテムを使って着火し、目の前に構える。
     */
    fun ignite(player: Player) {
        if (isActive(player) || isHolding(player)) return
        val stack = player.inventory.itemInMainHand
        if (KitManager.specialOf(stack) != SpecialItem.TNT) return
        stack.amount--
        player.inventory.setItemInMainHand(stack)

        val position = holdPosition(player)
        val display = spawnDisplay(position)
        val color = MatchManager.match?.getTeam(player)?.color
        tnts += Tnt(player.uniqueId, player.uniqueId, color, display, position, Random.nextInt(MIN_FUSE_TICKS, MAX_FUSE_TICKS + 1))

        playSound(position, org.bukkit.Sound.ENTITY_TNT_PRIMED, 1f, 1f)
        player.sendActionBar(Component.translatable("rushcraft.kit.tnt.ignited", NamedTextColor.RED))
    }

    private fun spawnDisplay(position: Location): BlockDisplay {
        return position.world.spawn(position, BlockDisplay::class.java) { display ->
            display.block = Material.TNT.createBlockData()
            display.isPersistent = false
            display.teleportDuration = 1
            display.glowColorOverride = Color.WHITE
            // ブロックの角ではなく中心が position に来るようにする
            display.transformation = Transformation(
                Vector3f(-SCALE / 2, -SCALE / 2, -SCALE / 2),
                Quaternionf(),
                Vector3f(SCALE, SCALE, SCALE),
                Quaternionf(),
            )
        }
    }

    /**
     * 持っている TNT を投げる (左右どちらのクリックでも)。投げた人が新しい持ち主になる。
     * @return 投げたか
     */
    fun throwHeld(player: Player): Boolean {
        val tnt = tnts.find { it.state == State.HELD && it.holder == player.uniqueId } ?: return false
        if (tnt.stateTicks < THROW_DELAY_TICKS || tnt.fuse <= THROW_LOCK_FUSE) return false
        if (!Combatants.canUse(player)) return false

        tnt.owner = player.uniqueId
        tnt.color = MatchManager.match?.getTeam(player)?.color
        tnt.orphan = false
        tnt.state = State.FLYING
        tnt.stateTicks = 0
        tnt.landedTicks = -1
        tnt.holder = null
        tnt.velocity = player.eyeLocation.direction.multiply(THROW_SPEED).add(Vector(0.0, THROW_UPWARD, 0.0))
        playSound(tnt.position, org.bukkit.Sound.ENTITY_WITCH_THROW, 1f, 0.8f)
        return true
    }

    /** 戻ってきた TNT を持ち主の目の前に構えさせる */
    private fun giveBack(player: Player, tnt: Tnt) {
        tnt.state = State.HELD
        tnt.holder = player.uniqueId
        tnt.stateTicks = 0

        playSound(tnt.position, org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1f, 0.7f)
        player.sendActionBar(Component.translatable("rushcraft.kit.tnt.returned", NamedTextColor.RED))
    }

    // ------------------------------------------------------------------
    // 毎 tick の処理
    // ------------------------------------------------------------------

    override fun tick() {
        for (tnt in tnts.toList()) {
            // 誘爆で既に消えている
            if (tnt !in tnts) continue
            if (!MatchManager.isRunning(tnt.position.world)) {
                remove(tnt)
                continue
            }
            tnt.stateTicks++

            when (tnt.state) {
                State.HELD -> tickHeld(tnt)
                State.FLYING -> tickFlying(tnt)
                State.RETURNING -> tickReturning(tnt)
                State.RESTING -> {}
            }
            if (tnt !in tnts) continue

            // 奈落の底で表示が消されないよう、深く落ちすぎないようにする
            val world = tnt.position.world
            if (tnt.position.y < world.minHeight - 32) tnt.position.y = world.minHeight - 32.0
            if (!tnt.display.isValid) tnt.display = spawnDisplay(tnt.position)

            tnt.display.teleport(tnt.position)
            drawLink(tnt)
            tickFuse(tnt)
        }
    }

    private fun tickHeld(tnt: Tnt) {
        val holder = tnt.holder()
        if (holder == null || !Combatants.isTarget(holder) || holder.world != tnt.position.world) {
            // 持っている人が倒れた場合はその場に落とす
            drop(tnt)
            return
        }
        tnt.position = holdPosition(holder)
        if (holder.uniqueId != tnt.owner && tnt.stateTicks % 20 == 1) {
            holder.sendActionBar(Component.translatable("rushcraft.kit.tnt.caught", NamedTextColor.RED))
        }
    }

    private fun drop(tnt: Tnt) {
        tnt.state = State.FLYING
        tnt.stateTicks = 0
        tnt.landedTicks = -1
        tnt.holder = null
        tnt.velocity = Vector()
    }

    private fun tickFlying(tnt: Tnt) {
        val world = tnt.position.world
        val owner = Bukkit.getPlayer(tnt.owner)

        // 近くの敵に持たせる
        val catcher = world.getNearbyEntitiesByType(Player::class.java, tnt.position, CATCH_RADIUS)
            .filter { it.uniqueId != tnt.owner && Combatants.isTarget(it) }
            .filter { owner == null || Combatants.isEnemy(owner, it) }
            .minByOrNull { it.location.add(0.0, 1.0, 0.0).distanceSquared(tnt.position) }
        if (catcher != null) {
            tnt.state = State.HELD
            tnt.holder = catcher.uniqueId
            tnt.stateTicks = 0
            playSound(tnt.position, org.bukkit.Sound.BLOCK_SLIME_BLOCK_PLACE, 1.2f, 1f)
            catcher.sendActionBar(Component.translatable("rushcraft.kit.tnt.caught", NamedTextColor.RED))
            return
        }

        if (tnt.landedTicks >= 0) {
            // 着地して少し待ってから戻る
            if (++tnt.landedTicks >= LANDED_TICKS) startReturn(tnt)
            return
        }
        if (tnt.stateTicks > MAX_FLIGHT_TICKS || tnt.position.y < world.minHeight) {
            startReturn(tnt)
            return
        }

        val velocity = tnt.velocity
        val length = velocity.length()
        if (length > 1.0E-6) {
            val hit = world.rayTraceBlocks(tnt.position, velocity, length + SCALE / 2, FluidCollisionMode.NEVER, true)
            if (hit != null) {
                // 当たった面の手前で止まる
                tnt.position = hit.hitPosition.toLocation(world).subtract(velocity.clone().normalize().multiply(SCALE / 2))
                tnt.velocity = Vector()
                tnt.landedTicks = 0
                playSound(tnt.position, org.bukkit.Sound.BLOCK_WOOL_HIT, 1f, 0.8f)
                return
            }
        }
        tnt.position.add(velocity)
        velocity.multiply(DRAG)
        velocity.y -= GRAVITY
    }

    private fun startReturn(tnt: Tnt) {
        if (tnt.owner() == null) {
            tnt.state = State.RESTING
            return
        }
        tnt.state = State.RETURNING
        tnt.stateTicks = 0
        tnt.returnSpeed = RETURN_START_SPEED
        // 忠誠のトライデントが戻るときの音
        playSound(tnt.position, org.bukkit.Sound.ITEM_TRIDENT_RETURN, 1.5f, 1.3f)
    }

    private fun tickReturning(tnt: Tnt) {
        val owner = tnt.owner()
        if (owner == null || owner.world != tnt.position.world) {
            tnt.state = State.RESTING
            return
        }
        // 壁は無視して持ち主の手元へまっすぐ飛ぶ
        val target = holdPosition(owner)
        val toTarget = target.toVector().subtract(tnt.position.toVector())
        val distance = toTarget.length()
        // スタン中はアイテムを没収しているので、解除されるまで近くで待つ
        if (ShieldManager.isStunned(owner) && distance <= 2.0) return
        if (distance <= tnt.returnSpeed + 0.3) {
            tnt.position = target
            giveBack(owner, tnt)
            return
        }
        tnt.position.add(toTarget.multiply(tnt.returnSpeed / distance))
        tnt.returnSpeed = (tnt.returnSpeed + RETURN_ACCELERATION).coerceAtMost(RETURN_MAX_SPEED)
        tnt.position.world.spawnParticle(Particle.FIREWORK, tnt.position, 1, 0.0, 0.0, 0.0, 0.0)
    }

    private fun tickFuse(tnt: Tnt) {
        // 投げている間・戻る途中は爆発せず、持ち主の手元に戻ってから爆発する
        val away = tnt.state == State.FLYING || tnt.state == State.RETURNING
        if (!away || tnt.fuse > 1) {
            tnt.fuse--
        }
        if (tnt.fuse <= 0) {
            explode(tnt)
            return
        }

        val world = tnt.position.world
        world.spawnParticle(Particle.SMOKE, tnt.position.clone().add(0.0, SCALE / 2.0, 0.0), 1, 0.0, 0.0, 0.0, 0.0)

        // だんだん速く・高くなる音。鳴るときに白く光らせる
        val progress = tnt.progress
        if (--tnt.nextBeep <= 0) {
            tnt.nextBeep = (16 - 13 * progress).toInt().coerceAtLeast(2)
            playSound(tnt.position, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.5f, (0.6 + 1.4 * progress).toFloat())
            tnt.glowTicks = 2
            tnt.display.isGlowing = true
        } else if (tnt.glowTicks > 0 && --tnt.glowTicks == 0) {
            tnt.display.isGlowing = false
        }
    }

    /** 持ち主の手を離れている間は、持ち主と TNT を線で結ぶ */
    private fun drawLink(tnt: Tnt) {
        if (tnt.state == State.HELD && tnt.holder == tnt.owner) return
        if (tnt.stateTicks % 2 != 0) return
        val owner = tnt.owner() ?: return
        if (owner.world != tnt.position.world) return

        val from = owner.location.add(0.0, 1.0, 0.0).toVector()
        val to = tnt.position.toVector()
        val line = to.clone().subtract(from)
        val length = line.length()
        if (length < 0.5) return
        val points = (length / 0.5).toInt().coerceAtMost(80)
        val step = line.multiply(1.0 / points)
        val color = tnt.color?.text?.let { Color.fromRGB(it.value()) } ?: Color.WHITE
        val dust = Particle.DustOptions(color, 0.6f)
        val point = from.clone()
        for (i in 0..points) {
            owner.world.spawnParticle(Particle.DUST, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0, dust)
            point.add(step)
        }
    }

    private fun explode(tnt: Tnt) {
        if (tnt !in tnts) return
        remove(tnt)
        val center = tnt.position
        val world = center.world
        world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1)
        playSound(center, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 4f, 1f)

        val owner = Bukkit.getPlayer(tnt.owner)
        val ownerTeam = owner?.let { MatchManager.match?.getTeam(it) }
        val victims = linkedSetOf<Player>()
        tnt.holder()?.let { if (Combatants.isTarget(it)) victims += it }
        for (player in world.getNearbyEntitiesByType(Player::class.java, center, KILL_RADIUS + 2.0)) {
            if (!Combatants.isTarget(player)) continue
            // 持ち主のチームの味方は巻き込まない (持ち主本人は巻き込む)
            if (player != owner && ownerTeam != null && MatchManager.match?.getTeam(player) == ownerTeam) continue
            if (distanceToBody(player, center) <= KILL_RADIUS) victims += player
        }

        // 着火した人の TNT を時間で復活させる
        tnt.origin?.let(Bukkit::getPlayer)?.let { ItemRecharge.schedule(it, SpecialItem.TNT, RECHARGE_TICKS) }

        // 近くの TNT と、巻き込まれる人が持っている TNT も一緒に爆発させる (誘爆)。
        // 倒れた人の TNT を落とすと持ち主の元へ戻ってしまうので、倒す前に決めておく
        val victimIds = victims.map { it.uniqueId }.toSet()
        val chained = tnts.filter { other ->
            other.position.world == world &&
                (other.position.distance(center) <= CHAIN_RADIUS || other.holder in victimIds)
        }

        for (victim in victims) {
            val key = if (victim == owner) "rushcraft.match.death.tnt-self" else "rushcraft.match.death.tnt"
            CombatTracker.kill(victim, owner?.takeIf { it != victim }, key)
        }
        chained.forEach(::explode)
    }

    private fun distanceToBody(player: Player, point: Location): Double {
        val box = player.boundingBox
        val x = point.x.coerceIn(box.minX, box.maxX)
        val y = point.y.coerceIn(box.minY, box.maxY)
        val z = point.z.coerceIn(box.minZ, box.maxZ)
        return Vector(x, y, z).distance(point.toVector())
    }

    /** 目の前に構える位置 */
    private fun holdPosition(player: Player): Location {
        val eye = player.eyeLocation
        val position = eye.clone().add(eye.direction.multiply(0.8)).subtract(0.0, 0.3, 0.0)
        position.yaw = 0f
        position.pitch = 0f
        return position
    }

    private fun playSound(location: Location, sound: org.bukkit.Sound, volume: Float, pitch: Float) {
        location.world.playSound(Sound.sound(sound, Sound.Source.PLAYER, volume, pitch), location.x, location.y, location.z)
    }

    // ------------------------------------------------------------------
    // 後片付け
    // ------------------------------------------------------------------

    /**
     * プレイヤーが倒れた・抜けた。持っていた TNT はその場に落とし、
     * 持ち主の TNT は戻らないようにする。着火した TNT はリスポーンで新しく配られるので、復活の対象から外す。
     */
    fun onLeave(player: Player) {
        for (tnt in tnts) {
            if (tnt.holder == player.uniqueId) drop(tnt)
            if (tnt.owner == player.uniqueId) {
                tnt.orphan = true
                if (tnt.state == State.RETURNING) tnt.state = State.RESTING
            }
            if (tnt.origin == player.uniqueId) tnt.origin = null
        }
    }

    private fun remove(tnt: Tnt) {
        tnts -= tnt
        tnt.display.remove()
    }

    fun reset() {
        tnts.toList().forEach(::remove)
    }

    override fun isAlive(): Boolean = true
}
