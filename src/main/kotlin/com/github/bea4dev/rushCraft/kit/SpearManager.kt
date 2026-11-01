package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.meta.Damageable
import org.bukkit.util.Vector
import java.util.UUID

/**
 * 槍: 突進のエンチャントで前方へ飛び込む。3 回使うと壊れ、時間で復活する。
 *
 * 突進は使うたびにアイテムの耐久を減らすので、その耐久ダメージを「1 回使った」として数える
 * (バニラの耐久ダメージは打ち消し、1 回につき 1 だけ減らす)。
 * 攻撃を当てやすくするため、使ってから 0.8 秒間は使った人の周りの敵にもダメージを与える (1 回につき 1 人 1 度まで)。
 */
object SpearManager : Tickable {
    const val MAX_USES = 3
    const val RECHARGE_TICKS = 400

    /** 周りの敵にダメージを与え続ける tick 数 */
    private const val ACTIVE_TICKS = 16

    /** この tick 数以内の耐久ダメージは同じ 1 回として数える */
    private const val USE_DEBOUNCE_TICKS = 4

    private const val DAMAGE = 6.0
    private const val HIT_RADIUS = 1.6

    /** 判定の中心を前方へずらす距離 */
    private const val FORWARD_OFFSET = 0.6

    private class Active(var ticks: Int) {
        val hit = mutableSetOf<UUID>()
    }

    private val lastUse = HashMap<UUID, Int>()
    private val actives = HashMap<UUID, Active>()

    /**
     * 槍に耐久ダメージが入った (PlayerItemDamageEvent)。バニラの耐久ダメージの代わりに使った回数を数える。
     */
    fun onUse(player: Player) {
        val now = Bukkit.getCurrentTick()
        val last = lastUse[player.uniqueId]
        if (last != null && now - last <= USE_DEBOUNCE_TICKS) return
        lastUse[player.uniqueId] = now

        activate(player)
        KitActionEvent.call(player, KitActionEvent.Action.SPEAR_LUNGE)

        val inventory = player.inventory
        val stack = inventory.itemInMainHand
        if (KitManager.specialOf(stack) != SpecialItem.SPEAR) return
        var broken = false
        stack.editMeta(Damageable::class.java) { meta ->
            meta.damage += 1
            broken = meta.damage >= MAX_USES
        }
        if (!broken) {
            inventory.setItemInMainHand(stack)
            return
        }
        ItemRecharge.useUp(player, SpecialItem.SPEAR)
        player.world.playSound(
            Sound.sound(org.bukkit.Sound.ENTITY_ITEM_BREAK, Sound.Source.PLAYER, 1f, 1f),
            player.x, player.y, player.z,
        )
        player.sendActionBar(
            Component.translatable("rushcraft.kit.spear.broken", NamedTextColor.RED, Component.text(RECHARGE_TICKS / 20))
        )
        ItemRecharge.schedule(player, SpecialItem.SPEAR, RECHARGE_TICKS)
    }

    /** 周りの敵にダメージを与え始める (チュートリアルのお手本も使う) */
    fun activate(user: LivingEntity) {
        actives[user.uniqueId] = Active(ACTIVE_TICKS)
    }

    override fun tick() {
        val iterator = actives.entries.iterator()
        while (iterator.hasNext()) {
            val (uuid, active) = iterator.next()
            val player = Bukkit.getEntity(uuid) as? LivingEntity
            if (player == null || !Combatants.isTarget(player) || ShieldManager.isStunned(player) || --active.ticks < 0) {
                iterator.remove()
                continue
            }
            hitAround(player, active)
        }
    }

    private fun hitAround(player: LivingEntity, active: Active) {
        val forward = player.location.direction.setY(0)
        if (forward.lengthSquared() > 1.0E-4) forward.normalize().multiply(FORWARD_OFFSET)
        val center = player.location.add(0.0, 1.0, 0.0).add(forward)

        for (target in player.world.getNearbyEntitiesByType(LivingEntity::class.java, center, HIT_RADIUS + 1.0)) {
            if (target == player || target.uniqueId in active.hit) continue
            if (!Combatants.isTarget(target) || !Combatants.isEnemy(player, target)) continue
            if (distanceToBody(target, center.toVector()) > HIT_RADIUS) continue
            active.hit += target.uniqueId
            // 近接攻撃として扱う (盾でパリィできる)
            val type = if (player is Player) DamageType.PLAYER_ATTACK else DamageType.MOB_ATTACK
            val source = DamageSource.builder(type).withCausingEntity(player).withDirectEntity(player).build()
            target.damage(DAMAGE, source)
        }
    }

    private fun distanceToBody(player: LivingEntity, point: Vector): Double {
        val box = player.boundingBox
        val x = point.x.coerceIn(box.minX, box.maxX)
        val y = point.y.coerceIn(box.minY, box.maxY)
        val z = point.z.coerceIn(box.minZ, box.maxZ)
        return Vector(x, y, z).distance(point)
    }

    fun clear(player: Player) {
        lastUse.remove(player.uniqueId)
        actives.remove(player.uniqueId)
    }

    fun reset() {
        lastUse.clear()
        actives.clear()
    }

    override fun isAlive(): Boolean = true
}
