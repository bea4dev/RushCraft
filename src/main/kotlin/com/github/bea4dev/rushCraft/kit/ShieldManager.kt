package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.match.CombatTracker
import com.github.bea4dev.rushCraft.scheduler.Tickable
import io.papermc.paper.entity.TeleportFlag
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.entity.Trident
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import java.util.UUID

/**
 * 特殊アイテムの盾。
 *
 * - ガード中は耐久が徐々に減り、約 5 秒で壊れる
 * - ガードしたまま攻撃を受けると耐久が最大値の 1/2 減る
 * - 耐久はアイテムの耐久値ではなく内部で持ち、ガード中にアクションバーのゲージで表示する
 *   (ガード中にアイテムを書き換えるとガードが途切れるため)。最後に使ってから 10 秒で全回復する
 * - 壊れてから 10 秒で復活する
 * - ガードを解除してから 2 tick の間はパリィ状態になる
 *   - 一度パリィ受付をすると、0.8 秒経つかパリィに成功するまで次のパリィ受付はできない
 *   - 近接攻撃してきた敵をふっとばしてスタンさせる
 *     (ガード中に近接攻撃を受けてから 2 tick 以内にガードを解除した場合もパリィになる。通信の遅延対策)
 *   - 近くの矢・当たった矢を飛んできた方向へ弾き返す
 *   - 成功すると盾の耐久が全回復する
 * - スタン中はマーカーのアーマースタンドに座らされてふっとび、アイテムを一時的に没収され攻撃できない。
 *   着地してから 1.5 秒で解除される。
 */
object ShieldManager : Tickable {
    /** ガードし続けて壊れるまでの tick 数 */
    private const val GUARD_TICKS = 100

    /** ガード解除からパリィできる tick 数 */
    private const val PARRY_TICKS = 2

    /** パリィ受付をしてから次に受付できるまでの tick 数 (パリィに成功すると即座に解除) */
    private const val PARRY_COOLDOWN_TICKS = 16

    /** ガード中に攻撃を受けたときに減る耐久の割合 */
    private const val BLOCKED_HIT_DAMAGE_RATIO = 0.5

    /** 壊れてから復活するまでの tick 数 */
    private const val RESTORE_TICKS = 200

    /** 最後に使ってから耐久が全回復するまでの tick 数 */
    private const val RECOVER_TICKS = 200

    private const val BAR_LENGTH = 10

    /** 矢を弾き返す範囲 */
    private const val ARROW_RADIUS = 3.5

    /** ふっとびの初速 (水平・垂直とも 1/√3 倍にすると、飛距離と高さがおよそ 1/3 になる) */
    private const val KNOCKBACK_HORIZONTAL = 0.635
    private const val KNOCKBACK_VERTICAL = 0.32

    /** 着地してからスタンが続く tick 数 */
    private const val LANDED_STUN_TICKS = 30

    /** 着地しなかった場合の最大スタン tick 数 */
    private const val MAX_STUN_TICKS = 100

    /** 座ったプレイヤーの足元は、乗り物の位置より 0.6 低くなる */
    private const val SEAT_OFFSET = 0.6

    /** ガード中に受けた近接攻撃から、この tick 数以内にガードを解除してもパリィになる */
    private const val PARRY_GRACE_TICKS = 2

    private class Guard {
        var raised = false

        /** 耐久 (1.0 が満タン) */
        var durability = 1.0
        var lastUsedAt = 0L
        var parryUntil = -1L
        var parryReadyAt = 0L

        /** ガード中に最後に近接攻撃してきた敵と、その時の tick */
        var lastAttacker: UUID? = null
        var lastAttackedAt = 0
    }

    private class Stun(
        val player: Player,
        val seat: ArmorStand,
        var feet: Location,
        var velocity: Vector,
        val contents: Array<ItemStack?>,
    ) {
        var ticks = 0
        var landedTicks = -1
    }

    private val guards = HashMap<UUID, Guard>()
    private val stuns = HashMap<UUID, Stun>()
    private var tick = 0L

    // ------------------------------------------------------------------
    // ガード・パリィ
    // ------------------------------------------------------------------

    override fun tick() {
        tick++

        for (player in Bukkit.getOnlinePlayers()) {
            if (!MatchManager.isRunning(player.world) || player.isDead) {
                guards.remove(player.uniqueId)
                continue
            }
            val guard = guards.getOrPut(player.uniqueId, ::Guard)
            val raised = guardingHand(player) != null

            if (raised) {
                damageShield(player, guard, 1.0 / GUARD_TICKS)
            } else if (guard.raised) {
                // ガード解除の瞬間からパリィ受付 (連打できないようにクールタイムを置く)
                if (shieldHand(player) != null && tick >= guard.parryReadyAt) {
                    guard.parryUntil = tick + PARRY_TICKS
                    guard.parryReadyAt = tick + PARRY_COOLDOWN_TICKS
                    parryEffect(player)
                    parryLateMelee(player, guard)
                }
                guard.lastAttacker = null
            }
            guard.raised = raised
            if (!raised && guard.durability < 1.0 && tick - guard.lastUsedAt >= RECOVER_TICKS) {
                guard.durability = 1.0
            }

            if (isParrying(player)) {
                reflectNearbyArrows(player)
            }
        }

        tickStuns()
    }

    /**
     * location 付近でパリィ中で、velocity で飛んでくるものが向かってきているプレイヤー
     * (投げナイフや TNT を矢と同じように弾き返すため)。
     */
    fun findParrying(location: Location, velocity: Vector, filter: (Player) -> Boolean): Player? {
        if (velocity.lengthSquared() < 0.01) return null
        return location.world.getNearbyEntitiesByType(Player::class.java, location, ARROW_RADIUS).firstOrNull { player ->
            val toPlayer = player.location.add(0.0, 1.0, 0.0).toVector().subtract(location.toVector())
            isParrying(player) && filter(player) && velocity.dot(toPlayer) > 0
        }
    }

    /**
     * ガード中に近接攻撃を受けた (KitListener から呼ばれる)。
     * 直後にガードを解除すればパリィになるよう覚えておく。
     */
    fun onGuardedMelee(victim: Player, attacker: Player) {
        if (guardingHand(victim) == null) return
        val guard = guards[victim.uniqueId] ?: return
        guard.lastAttacker = attacker.uniqueId
        guard.lastAttackedAt = Bukkit.getCurrentTick()
    }

    /** ガード中に受けた近接攻撃の直後にガードを解除したら、その攻撃をパリィしたことにする */
    private fun parryLateMelee(player: Player, guard: Guard) {
        val attacker = guard.lastAttacker?.let(Bukkit::getPlayer) ?: return
        // ガード解除は次の tick の処理で気付くので、その 1 tick 分を足す
        if (Bukkit.getCurrentTick() - guard.lastAttackedAt > PARRY_GRACE_TICKS + 1) return
        if (!Combatants.isTarget(attacker) || attacker.world != player.world) return
        parryMelee(player, attacker)
    }

    fun isParrying(player: Player): Boolean {
        val guard = guards[player.uniqueId] ?: return false
        return tick < guard.parryUntil
    }

    /** 特殊アイテムの盾を構えている手 */
    private fun guardingHand(player: Player): EquipmentSlot? {
        if (!player.isHandRaised) return null
        val hand = player.handRaised
        return if (KitManager.specialOf(player.inventory.getItem(hand)) == SpecialItem.SHIELD) hand else null
    }

    /** 特殊アイテムの盾を持っている手 (オフハンド優先) */
    private fun shieldHand(player: Player): EquipmentSlot? {
        val inventory = player.inventory
        return when {
            KitManager.specialOf(inventory.itemInOffHand) == SpecialItem.SHIELD -> EquipmentSlot.OFF_HAND
            KitManager.specialOf(inventory.itemInMainHand) == SpecialItem.SHIELD -> EquipmentSlot.HAND
            else -> null
        }
    }

    /**
     * ガードしたまま攻撃を受けた (KitListener から呼ばれる)。
     */
    fun onBlockedHit(player: Player) {
        if (guardingHand(player) == null) return
        val guard = guards[player.uniqueId] ?: return
        damageShield(player, guard, BLOCKED_HIT_DAMAGE_RATIO)
    }

    private fun damageShield(player: Player, guard: Guard, amount: Double) {
        guard.durability -= amount
        guard.lastUsedAt = tick
        if (guard.durability > 1.0E-6) {
            showGauge(player, guard.durability)
            return
        }

        val hand = guardingHand(player) ?: shieldHand(player) ?: return
        player.inventory.setItem(hand, null)
        player.clearActiveItem()
        // 壊れたことによるガード解除ではパリィ受付をしない
        guard.raised = false
        guard.durability = 1.0
        player.world.playSound(
            Sound.sound(org.bukkit.Sound.ITEM_SHIELD_BREAK, Sound.Source.PLAYER, 1f, 1f),
            player.x, player.y, player.z,
        )
        player.sendActionBar(
            Component.translatable("rushcraft.kit.shield.broken", NamedTextColor.RED, Component.text(RESTORE_TICKS / 20))
        )
        ItemRecharge.schedule(player, SpecialItem.SHIELD, RESTORE_TICKS)
    }

    private fun showGauge(player: Player, durability: Double) {
        val filled = Math.ceil(BAR_LENGTH * durability).toInt().coerceIn(0, BAR_LENGTH)
        val color = when {
            durability > 0.5 -> NamedTextColor.GREEN
            durability > 0.25 -> NamedTextColor.YELLOW
            else -> NamedTextColor.RED
        }
        player.sendActionBar(
            Component.text()
                .append(Component.translatable("rushcraft.kit.shield.gauge", NamedTextColor.GRAY))
                .append(Component.text(" "))
                .append(Component.text("■".repeat(filled), color))
                .append(Component.text("■".repeat(BAR_LENGTH - filled), NamedTextColor.DARK_GRAY))
                .build()
        )
    }

    private fun repair(player: Player) {
        guards[player.uniqueId]?.durability = 1.0
    }

    private fun parryEffect(player: Player) {
        val front = player.eyeLocation.add(player.location.direction.setY(0).normalize().multiply(1.2))
        front.y -= 0.4
        player.world.spawnParticle(Particle.SWEEP_ATTACK, front, 1)
        player.world.playSound(
            Sound.sound(org.bukkit.Sound.ENTITY_PLAYER_ATTACK_SWEEP, Sound.Source.PLAYER, 1f, 1.2f),
            player.x, player.y, player.z,
        )
    }

    /**
     * パリィ成功。盾の耐久を全回復する。
     */
    fun parried(player: Player) {
        repair(player)
        guards[player.uniqueId]?.let {
            // 成功したらクールタイムを解除する
            it.parryReadyAt = 0L
        }
        player.world.playSound(
            Sound.sound(org.bukkit.Sound.BLOCK_ANVIL_LAND, Sound.Source.PLAYER, 0.8f, 1.8f),
            player.x, player.y, player.z,
        )
        player.world.spawnParticle(Particle.CRIT, player.location.add(0.0, 1.0, 0.0), 20, 0.4, 0.4, 0.4, 0.3)
        // 盾の前で光らせる
        val front = player.eyeLocation.add(player.location.direction.multiply(0.8)).subtract(0.0, 0.3, 0.0)
        player.world.spawnParticle(Particle.FLASH, front, 1, 0.0, 0.0, 0.0, 0.0, Color.WHITE)
        player.sendActionBar(Component.translatable("rushcraft.kit.shield.parry", NamedTextColor.GOLD))
    }

    /**
     * 近接攻撃をパリィした。攻撃は無効化し、相手をふっとばしてスタンさせる。
     */
    fun parryMelee(victim: Player, attacker: Player) {
        parried(victim)
        // ふっとばして奈落に落とした場合もキルになるようにする
        CombatTracker.record(attacker, victim)
        stun(attacker, victim.location)
    }

    /**
     * 矢をパリィした (当たった矢)。
     */
    fun parryArrow(victim: Player, arrow: AbstractArrow) {
        reflect(arrow, victim)
        parried(victim)
    }

    private fun reflectNearbyArrows(player: Player) {
        val center = player.location.add(0.0, 1.0, 0.0)
        var reflected = false
        for (arrow in player.world.getNearbyEntitiesByType(AbstractArrow::class.java, center, ARROW_RADIUS)) {
            if (arrow.isInBlock || arrow.shooter == player) continue
            val velocity = arrow.velocity
            if (velocity.lengthSquared() < 0.01) continue
            // こちらに向かってきている矢だけ
            val toPlayer = center.toVector().subtract(arrow.location.toVector())
            if (velocity.dot(toPlayer) <= 0) continue
            reflect(arrow, player)
            reflected = true
        }
        if (reflected) parried(player)
    }

    private fun reflect(arrow: AbstractArrow, player: Player) {
        arrow.velocity = arrow.velocity.multiply(-1)
        if (arrow is Trident && Swapper.isSwapper(arrow)) {
            // 弾き返したスワッパーはパリィした人のものとして飛び、落ちたら元の持ち主に戻る
            Swapper.onReflect(arrow, player)
        } else {
            arrow.shooter = player
        }
    }

    // ------------------------------------------------------------------
    // スタン
    // ------------------------------------------------------------------

    fun isStunned(player: Player): Boolean = player.uniqueId in stuns

    /**
     * from の位置から遠ざかる方向にふっとばしてスタンさせる。
     */
    fun stun(target: Player, from: Location) {
        if (isStunned(target) || target.isDead) return

        val direction = target.location.toVector().subtract(from.toVector()).setY(0)
        if (direction.lengthSquared() < 1.0E-4) {
            direction.copy(from.direction.setY(0))
        }
        val velocity = direction.normalize().multiply(KNOCKBACK_HORIZONTAL).setY(KNOCKBACK_VERTICAL)

        // アイテムを一時的に没収する
        val inventory = target.inventory
        val contents = inventory.contents.map { it?.clone() }.toTypedArray()
        target.setItemOnCursor(null)
        target.closeInventory()
        inventory.clear()

        val feet = target.location
        val seat = target.world.spawn(feet.clone().add(0.0, SEAT_OFFSET, 0.0), ArmorStand::class.java) { stand ->
            stand.isMarker = true
            stand.isInvisible = true
            stand.setGravity(false)
            stand.isInvulnerable = true
            stand.isSilent = true
            stand.isPersistent = false
            stand.setBasePlate(false)
        }
        stuns[target.uniqueId] = Stun(target, seat, feet, velocity, contents)
        seat.addPassenger(target)

        target.world.playSound(
            Sound.sound(org.bukkit.Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, Sound.Source.PLAYER, 1f, 0.8f),
            target.x, target.y, target.z,
        )
        target.sendActionBar(Component.translatable("rushcraft.kit.shield.stunned", NamedTextColor.RED))
    }

    private fun tickStuns() {
        for (stun in stuns.values.toList()) {
            val player = stun.player
            if (!player.isOnline || player.isDead || !stun.seat.isValid || !MatchManager.isRunning(player.world)) {
                endStun(stun, restore = player.isOnline && !player.isDead)
                continue
            }

            stun.ticks++
            if (stun.landedTicks >= 0) {
                stun.landedTicks++
                if (stun.landedTicks >= LANDED_STUN_TICKS) endStun(stun, restore = true)
                continue
            }
            if (stun.ticks >= MAX_STUN_TICKS || stun.feet.y < player.world.minHeight - 16) {
                endStun(stun, restore = true)
                continue
            }
            move(stun)
            player.world.spawnParticle(Particle.CRIT, player.location.add(0.0, 1.0, 0.0), 2, 0.2, 0.2, 0.2, 0.0)
        }
    }

    /** ふっとびの 1 tick 分の移動 (地形との衝突も見る) */
    private fun move(stun: Stun) {
        val world = stun.feet.world
        val velocity = stun.velocity
        var next = stun.feet.clone().add(velocity)

        // 頭の高さで壁に当たったら水平方向の速度を消す
        val horizontal = velocity.clone().setY(0)
        if (horizontal.lengthSquared() > 1.0E-6) {
            val head = stun.feet.clone().add(0.0, 1.5, 0.0)
            val wall = world.rayTraceBlocks(head, horizontal, horizontal.length() + 0.3, FluidCollisionMode.NEVER, true)
            if (wall != null) {
                velocity.x = 0.0
                velocity.z = 0.0
                next = stun.feet.clone().add(0.0, velocity.y, 0.0)
            }
        }

        val delta = next.toVector().subtract(stun.feet.toVector())
        val length = delta.length()
        if (length > 1.0E-6) {
            val hit = world.rayTraceBlocks(stun.feet, delta, length, FluidCollisionMode.NEVER, true)
            if (hit != null) {
                val position = hit.hitPosition
                when (hit.hitBlockFace) {
                    org.bukkit.block.BlockFace.UP -> {
                        // 着地
                        next = Location(world, position.x, position.y, position.z)
                        stun.landedTicks = 0
                        velocity.zero()
                    }

                    org.bukkit.block.BlockFace.DOWN -> {
                        next = stun.feet.clone()
                        velocity.y = 0.0
                    }

                    else -> {
                        next = stun.feet.clone().add(0.0, velocity.y.coerceAtMost(0.0), 0.0)
                        velocity.x = 0.0
                        velocity.z = 0.0
                    }
                }
            }
        }

        next.yaw = stun.player.location.yaw
        next.pitch = stun.player.location.pitch
        stun.feet = next
        stun.seat.teleport(next.clone().add(0.0, SEAT_OFFSET, 0.0), TeleportFlag.EntityState.RETAIN_PASSENGERS)

        // 重力と空気抵抗
        velocity.y -= 0.08
        velocity.multiply(0.98)
    }

    private fun endStun(stun: Stun, restore: Boolean) {
        stuns.remove(stun.player.uniqueId)
        val player = stun.player
        stun.seat.eject()
        stun.seat.remove()
        if (restore) {
            player.inventory.contents = stun.contents
            player.teleport(stun.feet.clone().add(0.0, 0.1, 0.0))
        }
    }

    /**
     * スタン中に死んだ場合、没収していたアイテムを返す (ドロップに加えるため)。
     */
    fun takeStunnedItems(player: Player): List<ItemStack> {
        val stun = stuns[player.uniqueId] ?: return emptyList()
        endStun(stun, restore = false)
        return stun.contents.filterNotNull().filter { !it.isEmpty }
    }

    /** スタンを解除してアイテムを返す (ログアウト時など) */
    fun release(player: Player) {
        stuns[player.uniqueId]?.let { endStun(it, restore = true) }
        guards.remove(player.uniqueId)
    }

    /** 試合終了時など。スタンをすべて解除する */
    fun reset(restore: Boolean) {
        stuns.values.toList().forEach { endStun(it, restore) }
        guards.clear()
    }

    override fun isAlive(): Boolean = true
}
