package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.match.CombatTracker
import com.github.bea4dev.rushCraft.match.TeamColor
import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.time.Duration
import java.util.UUID
import kotlin.random.Random

/**
 * TNT: 右クリックで着火して目の前に構え、クリック (左右どちらでも) で投げる。
 *
 * - 爆発までの時間はランダム。TNT の位置から鳴る音がだんだん速く・高くなって爆発する
 * - 投げた TNT は近くの敵に強制的に持たせる。持っている人は爆発で即死し、周り 2.5 ブロック以内も即死
 * - 爆発で倒れた人は、着火した人の敵なら必ず着火した人のキルになる (着火した人本人が倒れたら自爆)
 * - 着火した人の味方は、最後に投げたのが着火した人の敵 (投げ返された) のときだけ巻き込み、
 *   その場合は最後に投げた人のキルになる
 * - 持たされた人も、時間内ならクリックで投げ返せる。投げた人がその TNT の新しい持ち主 (戻る先) になる
 * - スワッパーで位置を入れ替えると、持っている TNT も入れ替わり、所有者も入れ替わる
 * - 飛んでいる TNT はパリィで弾き返せる (弾き返した人が新しい持ち主になる)
 * - 誰にも当たらなければ、持ち主 (最後に投げた人) の元へ飛んで戻ってくる (奈落に落ちても戻ってくる)。
 *   投げている間・戻る途中に時間切れになったときは、戻りきってから爆発する (爆発直前に投げても避けられない)
 * - 持ち主の手を離れている間は、持ち主と TNT をパーティクルの線で結ぶ
 * - 爆発してから一定時間で復活する (死んだ場合はリスポーンで再装填)
 *
 * 持つ人・持ち主はプレイヤーとは限らない (チュートリアルのダミーやお手本)。
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

    /** 着火したときに出す操作説明のサブタイトルを表示しておく tick 数 */
    private const val HINT_TICKS = 30

    private enum class State { HELD, FLYING, RETURNING, RESTING }

    /**
     * @param igniter 着火した人。爆発で倒れた人は、この人のキル (本人なら自爆) になる
     * @param origin 着火した人 (爆発すると、この人の TNT が時間で復活する。倒れた・抜けたら null)
     * @param originSide 着火した人の陣営 (Combatants.sideOf)
     * @param owner 持ち主 (最後に投げた人)。外れたときに戻る先
     */
    private class Tnt(
        val igniter: UUID,
        var origin: UUID?,
        val originSide: Any?,
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

        fun owner(): LivingEntity? = if (orphan) null else (Bukkit.getEntity(owner) as? LivingEntity)?.takeIf { Combatants.isTarget(it) }

        fun holder(): LivingEntity? = holder?.let(Bukkit::getEntity) as? LivingEntity

        val progress: Double
            get() = 1.0 - fuse.toDouble() / totalFuse
    }

    private val tnts = mutableListOf<Tnt>()

    /** 操作説明のサブタイトルを出した tick (投げたら消す) */
    private val hints = HashMap<UUID, Int>()

    // ------------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------------

    /** 自分が着火した TNT がまだ爆発していないか (投げている途中や、相手に投げ返された後も含む) */
    fun isActive(player: LivingEntity): Boolean = tnts.any { it.origin == player.uniqueId }

    /** TNT を持っているか (自分のものでも、持たされたものでも) */
    fun isHolding(player: LivingEntity): Boolean = tnts.any { it.state == State.HELD && it.holder == player.uniqueId }

    /**
     * TNT アイテムを右クリックした。アイテムを使って着火し、目の前に構える。
     */
    fun ignite(player: Player) {
        if (isActive(player) || isHolding(player)) return
        val stack = player.inventory.itemInMainHand
        if (KitManager.specialOf(stack) != SpecialItem.TNT) return
        ItemRecharge.useUp(player, SpecialItem.TNT)

        spawnHeld(player)
        player.sendActionBar(Component.translatable("rushcraft.kit.tnt.ignited", NamedTextColor.RED))
        showHint(player, "rushcraft.kit.tnt.ignited-subtitle")
    }

    /** 押し付けられた。どうすればいいかをサブタイトルで知らせる (投げたら消す) */
    private fun caught(holder: LivingEntity) {
        holder.sendActionBar(Component.translatable("rushcraft.kit.tnt.caught", NamedTextColor.RED))
        if (holder is Player) showHint(holder, "rushcraft.kit.tnt.caught-subtitle")
    }

    private fun showHint(player: Player, key: String) {
        player.showTitle(
            Title.title(
                Component.empty(),
                Component.translatable(key, NamedTextColor.RED),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(HINT_TICKS * 50L), Duration.ofMillis(250)),
            )
        )
        hints[player.uniqueId] = Bukkit.getCurrentTick()
    }

    /**
     * 着火した TNT を持たせる (アイテムは使わない)。チュートリアルのダミーやお手本にも使う。
     * @param fuse 爆発までの tick 数 (null ならランダム)
     */
    fun spawnHeld(holder: LivingEntity, fuse: Int? = null) {
        val position = holdPosition(holder)
        val display = spawnDisplay(position)
        val totalFuse = fuse ?: Random.nextInt(MIN_FUSE_TICKS, MAX_FUSE_TICKS + 1)
        tnts += Tnt(holder.uniqueId, holder.uniqueId, Combatants.sideOf(holder), holder.uniqueId, Combatants.colorOf(holder), display, position, totalFuse)
        playSound(position, org.bukkit.Sound.ENTITY_TNT_PRIMED, 1f, 1f)
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
    fun throwHeld(player: LivingEntity): Boolean {
        val tnt = tnts.find { it.state == State.HELD && it.holder == player.uniqueId } ?: return false
        if (tnt.stateTicks < THROW_DELAY_TICKS || tnt.fuse <= THROW_LOCK_FUSE) return false
        if (!Combatants.isTarget(player) || ShieldManager.isStunned(player)) return false

        val throwingBack = tnt.owner != player.uniqueId
        tnt.owner = player.uniqueId
        tnt.color = Combatants.colorOf(player)
        tnt.orphan = false
        tnt.state = State.FLYING
        tnt.stateTicks = 0
        tnt.landedTicks = -1
        tnt.holder = null
        tnt.velocity = player.eyeLocation.direction.multiply(THROW_SPEED).add(Vector(0.0, THROW_UPWARD, 0.0))
        playSound(tnt.position, org.bukkit.Sound.ENTITY_WITCH_THROW, 1f, 0.8f)
        // 投げたら操作説明はもう要らない (まだ表示中のときだけ消す)
        val hinted = hints.remove(player.uniqueId)
        if (hinted != null && Bukkit.getCurrentTick() - hinted < HINT_TICKS) player.clearTitle()
        if (throwingBack) KitActionEvent.call(player, KitActionEvent.Action.TNT_THROWN_BACK)
        return true
    }

    /** 戻ってきた TNT を持ち主の目の前に構えさせる */
    private fun giveBack(player: LivingEntity, tnt: Tnt) {
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
            if (!Combatants.isArena(tnt.position)) {
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
        if (!isHarmed(tnt, holder)) {
            // 爆発で倒れない人 (着火した人の味方で、最後に投げたのも味方) が持ったまま敵に突っ込めないようにする
            // (スワッパーやパリィで渡った場合)。着火した人の元へ飛んで戻らせる
            tnt.owner = tnt.igniter
            tnt.orphan = false
            val igniter = tnt.owner()
            tnt.orphan = igniter == null
            tnt.color = igniter?.let(Combatants::colorOf) ?: tnt.color
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
        val owner = Bukkit.getEntity(tnt.owner) as? LivingEntity

        // パリィ中の敵に向かっていれば、矢と同じように弾き返す
        val parrier = ShieldManager.findParrying(tnt.position, tnt.velocity) {
            it.uniqueId != tnt.owner && Combatants.isTarget(it) && (owner == null || Combatants.isEnemy(owner, it))
        }
        if (parrier != null) {
            tnt.velocity.multiply(-1)
            tnt.owner = parrier.uniqueId
            tnt.color = Combatants.colorOf(parrier)
            tnt.orphan = false
            tnt.stateTicks = 0
            ShieldManager.parried(parrier)
            return
        }

        // 近くの敵に持たせる
        val catcher = world.getNearbyEntitiesByType(LivingEntity::class.java, tnt.position, CATCH_RADIUS)
            .filter { it.uniqueId != tnt.owner && Combatants.isTarget(it) }
            .filter { (owner == null || Combatants.isEnemy(owner, it)) && isHarmed(tnt, it) }
            .minByOrNull { it.location.add(0.0, 1.0, 0.0).distanceSquared(tnt.position) }
        if (catcher != null) {
            tnt.state = State.HELD
            tnt.holder = catcher.uniqueId
            tnt.stateTicks = 0
            playSound(tnt.position, org.bukkit.Sound.BLOCK_SLIME_BLOCK_PLACE, 1.2f, 1f)
            caught(catcher)
            owner?.let { KitActionEvent.call(it, KitActionEvent.Action.TNT_STUCK, catcher) }
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

        // 誰が投げたかに関係なく、着火した人の TNT として巻き込む
        val victims = linkedSetOf<LivingEntity>()
        tnt.holder()?.let { if (isHarmed(tnt, it)) victims += it }
        for (entity in world.getNearbyEntitiesByType(LivingEntity::class.java, center, KILL_RADIUS + 2.0)) {
            if (isHarmed(tnt, entity) && distanceToBody(entity, center) <= KILL_RADIUS) victims += entity
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

        // 着火した人が倒れたら自爆 (誰のキルにもしない)。着火した人の味方は投げ返した敵のキル、
        // それ以外 (着火した人の敵) は必ず着火した人のキル
        val igniter = Bukkit.getEntity(tnt.igniter) as? LivingEntity
        val thrower = Bukkit.getEntity(tnt.owner) as? LivingEntity
        for (victim in victims) {
            when {
                victim.uniqueId == tnt.igniter -> CombatTracker.kill(victim, null, "rushcraft.match.death.tnt-self")
                isIgniterSide(tnt, victim) -> CombatTracker.kill(victim, thrower, "rushcraft.match.death.tnt")
                else -> CombatTracker.kill(victim, igniter, "rushcraft.match.death.tnt")
            }
        }
        chained.forEach(::explode)
    }

    /**
     * 爆発で倒れる人か。着火した人本人と、着火した人の敵は巻き込む。
     * 着火した人の味方は、最後に投げたのが着火した人の敵 (投げ返された) のときだけ巻き込む。
     */
    private fun isHarmed(tnt: Tnt, entity: LivingEntity): Boolean {
        if (!Combatants.isTarget(entity)) return false
        if (entity.uniqueId == tnt.igniter) return true
        if (!isIgniterSide(tnt, entity)) return true
        val thrower = Bukkit.getEntity(tnt.owner) as? LivingEntity ?: return false
        return !isIgniterSide(tnt, thrower)
    }

    /** 着火した人本人か、その味方か */
    private fun isIgniterSide(tnt: Tnt, entity: LivingEntity): Boolean {
        if (entity.uniqueId == tnt.igniter) return true
        val igniter = Bukkit.getEntity(tnt.igniter) as? LivingEntity
        if (igniter != null && Combatants.sideOf(igniter) != null) return !Combatants.isEnemy(igniter, entity)
        // 着火した人が抜けた: 着火したときの陣営で判断する
        val side = tnt.originSide ?: return false
        return Combatants.sideOf(entity) == side
    }

    private fun distanceToBody(player: LivingEntity, point: Location): Double {
        val box = player.boundingBox
        val x = point.x.coerceIn(box.minX, box.maxX)
        val y = point.y.coerceIn(box.minY, box.maxY)
        val z = point.z.coerceIn(box.minZ, box.maxZ)
        return Vector(x, y, z).distance(point.toVector())
    }

    /** 目の前に構える位置 */
    private fun holdPosition(player: LivingEntity): Location {
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
     * スワッパーで a と b の位置が入れ替わった。持っている TNT はその場に残るので持つ人が入れ替わり、
     * 所有者も入れ替わる。
     */
    fun onSwap(a: LivingEntity, b: LivingEntity) {
        val ids = mapOf(a.uniqueId to b.uniqueId, b.uniqueId to a.uniqueId)
        for (tnt in tnts) {
            if (tnt.orphan) continue
            val holder = tnt.holder
            if (tnt.state == State.HELD && holder != null && holder in ids) {
                tnt.holder = ids.getValue(holder)
                // 入れ替わった直後のクリックで投げてしまわないようにする
                tnt.stateTicks = 0
            }
            ids[tnt.owner]?.let { newOwner ->
                tnt.owner = newOwner
                tnt.color = (Bukkit.getEntity(newOwner) as? LivingEntity)?.let(Combatants::colorOf)
            }
            if (tnt.state == State.HELD && holder in ids && tnt.holder != tnt.owner) {
                tnt.holder()?.let(::caught)
            }
        }
    }

    /**
     * プレイヤーが倒れた・抜けた。持っていた TNT はその場に落とし、
     * 持ち主の TNT は戻らないようにする。着火した TNT はリスポーンで新しく配られるので、復活の対象から外す。
     */
    fun onLeave(player: LivingEntity) {
        hints.remove(player.uniqueId)
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

    /** 範囲内の TNT を爆発させずに消す (チュートリアルのやり直しなど) */
    fun clearArea(world: World, box: BoundingBox) {
        tnts.filter { it.position.world == world && box.contains(it.position.toVector()) }.forEach(::remove)
    }

    fun reset() {
        tnts.toList().forEach(::remove)
        hints.clear()
    }

    override fun isAlive(): Boolean = true
}
