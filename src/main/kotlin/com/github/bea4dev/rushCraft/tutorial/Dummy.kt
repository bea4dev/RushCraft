package com.github.bea4dev.rushCraft.tutorial

import com.github.bea4dev.rushCraft.kit.ShieldManager
import io.papermc.paper.entity.LookAnchor
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.attribute.Attribute
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mannequin
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.util.Vector
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * チュートリアルの人形 (Mannequin)。敵のダミーとお手本 (味方) がいる。
 *
 * AI は持たず、brain に毎 tick の動きを書く。パリィでスタンしている間は動かない。
 * クライアントにはプレイヤーとして見せる (DummyDisguise) ので、名前と体力は頭の上の文字で出す。
 */
class Dummy(val session: TutorialSession, val entity: Mannequin, val enemy: Boolean, private val name: Component) {
    /** 毎 tick の動き */
    var brain: ((Dummy) -> Unit)? = null

    /** 盾を構え続けるか (途切れたら構え直す) */
    var guarding = false

    /** プレイヤーから最後に受けた攻撃の種類 (奈落に落とされたときの倒し方に使う) */
    var lastHit: String? = null

    private val nameplate: TextDisplay = entity.world.spawn(nameplateLocation(), TextDisplay::class.java) { text ->
        text.isPersistent = false
        text.billboard = Display.Billboard.CENTER
        text.isShadowed = true
        text.backgroundColor = Color.fromARGB(0, 0, 0, 0)
        text.alignment = TextDisplay.TextAlignment.CENTER
        text.teleportDuration = 1
        text.text(name)
    }

    /** 生まれた tick からの経過 */
    var ticks = 0
        private set

    private var shownHealth = -1

    /** 最後に装備を持たせた tick */
    private var heldAt = -1

    val isAlive: Boolean
        get() = entity.isValid && !entity.isDead

    val location: Location
        get() = entity.location

    fun tick() {
        if (!isAlive) {
            nameplate.remove()
            return
        }
        ticks++
        updateNameplate()
        if (guarding && !entity.isHandRaised) entity.startUsingItem(EquipmentSlot.OFF_HAND)
        if (ShieldManager.isStunned(entity)) return
        brain?.invoke(this)
    }

    private fun nameplateLocation(): Location = entity.location.add(0.0, NAMEPLATE_HEIGHT, 0.0)

    /** 頭の上の名前 (敵は残りの体力も) を体に付いてこさせる */
    private fun updateNameplate() {
        nameplate.teleport(nameplateLocation())
        if (!enemy) return
        val health = ceil(entity.health).toInt()
        if (health == shownHealth) return
        shownHealth = health
        nameplate.text(
            Component.text().append(name).append(Component.newline())
                .append(Component.text("❤ $health", NamedTextColor.RED)).build()
        )
    }

    /**
     * 盾を構える (構え続ける)。
     *
     * 持たせた装備はエンティティの tick でクライアントに送られるが、構えのデータはその前に送られる。
     * 持たせたのと同じ tick に構えると、クライアントでは盾を持つ前に構えたことになって構えのポーズにならないので、
     * そのときは次の tick (tick()) で構える。
     */
    fun raiseShield() {
        guarding = true
        if (ticks != heldAt) entity.startUsingItem(EquipmentSlot.OFF_HAND)
    }

    /** 盾を下ろす */
    fun lowerShield() {
        guarding = false
        entity.clearActiveItem()
    }

    // ------------------------------------------------------------------
    // 動き
    // ------------------------------------------------------------------

    /** target の方を向く (体も向ける) */
    fun face(target: Location) {
        entity.lookAt(target.x, target.y, target.z, LookAnchor.EYES)
        val dx = target.x - entity.x
        val dz = target.z - entity.z
        if (dx * dx + dz * dz > 1.0E-4) entity.bodyYaw = yawOf(dx, dz)
    }

    fun face(target: LivingEntity) = face(target.eyeLocation)

    /**
     * target へ歩く。段差は飛び越える。
     * @return 着いたか
     */
    fun walk(target: Location, speed: Double = WALK_SPEED): Boolean {
        val location = entity.location
        val dx = target.x - location.x
        val dz = target.z - location.z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance < 0.3) {
            stop()
            return true
        }
        val step = speed.coerceAtMost(distance)
        val velocity = entity.velocity
        entity.velocity = Vector(dx / distance * step, velocity.y, dz / distance * step)
        val yaw = yawOf(dx, dz)
        entity.setRotation(yaw, 0f)
        entity.bodyYaw = yaw

        // 目の前が 1 ブロックの段差なら跳ぶ
        if (entity.isOnGround) {
            val front = location.clone().add(dx / distance * 0.7, 0.0, dz / distance * 0.7)
            if (front.block.type.isSolid && !front.clone().add(0.0, 1.0, 0.0).block.type.isSolid) {
                entity.velocity = entity.velocity.setY(0.42)
            }
        }
        return false
    }

    fun stop() {
        val velocity = entity.velocity
        entity.velocity = Vector(0.0, velocity.y, 0.0)
    }

    fun teleport(location: Location) {
        entity.teleport(location)
    }

    fun distanceTo(target: LivingEntity): Double = entity.location.distance(target.location)

    // ------------------------------------------------------------------
    // 攻撃
    // ------------------------------------------------------------------

    /** 殴る (攻撃の種類は近接攻撃なので、盾でパリィできる) */
    fun meleeHit(target: LivingEntity, damage: Double) {
        entity.swingMainHand()
        val source = DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(entity).withDirectEntity(entity).build()
        target.damage(damage, source)
    }

    /** 殴る前の予備動作 (頭の上に出す合図) */
    fun warn() {
        entity.world.spawnParticle(Particle.ANGRY_VILLAGER, entity.location.add(0.0, 2.3, 0.0), 1)
        entity.world.playSound(
            Sound.sound(org.bukkit.Sound.ENTITY_PLAYER_ATTACK_WEAK, Sound.Source.HOSTILE, 1f, 0.6f),
            entity.x, entity.y, entity.z,
        )
    }

    /** 弓を引く構え */
    fun drawBow() {
        entity.startUsingItem(EquipmentSlot.HAND)
    }

    fun releaseItem() {
        entity.clearActiveItem()
    }

    /**
     * target へ矢を射る (重力の分だけ少し上を狙う)。
     */
    fun shootArrow(target: Location, speed: Double = 1.6, damage: Double = 1.0): Arrow {
        releaseItem()
        val eye = entity.eyeLocation
        val delta = target.toVector().subtract(eye.toVector())
        val horizontal = sqrt(delta.x * delta.x + delta.z * delta.z)
        val flightTicks = horizontal / speed
        delta.y += 0.5 * ARROW_GRAVITY * flightTicks * flightTicks
        val arrow = entity.launchProjectile(Arrow::class.java, delta.normalize().multiply(speed))
        arrow.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
        arrow.damage = damage
        entity.world.playSound(
            Sound.sound(org.bukkit.Sound.ENTITY_ARROW_SHOOT, Sound.Source.HOSTILE, 1f, 1f),
            entity.x, entity.y, entity.z,
        )
        session.track(arrow)
        return arrow
    }

    // ------------------------------------------------------------------
    // 見た目
    // ------------------------------------------------------------------

    fun hold(main: ItemStack?, off: ItemStack? = null) {
        heldAt = ticks
        entity.equipment.setItemInMainHand(main)
        entity.equipment.setItemInOffHand(off)
    }

    fun remove() {
        DummyDisguise.remove(entity)
        nameplate.remove()
        entity.remove()
    }

    companion object {
        const val WALK_SPEED = 0.2

        /** 頭の上の名前の高さ */
        private const val NAMEPLATE_HEIGHT = 2.1
        private const val ARROW_GRAVITY = 0.05

        fun yawOf(dx: Double, dz: Double): Float = Math.toDegrees(atan2(-dx, dz)).toFloat()

        /** 陣営の色の革防具を着せる */
        fun dress(entity: Mannequin, color: Color) {
            fun leather(material: Material) = ItemStack(material).also { stack ->
                stack.editMeta(LeatherArmorMeta::class.java) { it.setColor(color) }
            }
            val equipment = entity.equipment
            equipment.helmet = leather(Material.LEATHER_HELMET)
            equipment.chestplate = leather(Material.LEATHER_CHESTPLATE)
            equipment.leggings = leather(Material.LEATHER_LEGGINGS)
            equipment.boots = leather(Material.LEATHER_BOOTS)
            // 倒されたときのドロップは TutorialListener で消す
        }

        fun setMaxHealth(entity: Mannequin, health: Double) {
            entity.getAttribute(Attribute.MAX_HEALTH)?.baseValue = health
            entity.health = health
        }
    }
}
