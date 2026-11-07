package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.time.Duration
import java.util.UUID

/**
 * 透明スパイ: クリック (左右どちらでも) で 10 秒間透明になる。
 *
 * - 透明になると防具を外す (覚えておいて後で返す)。透明の間は手に持っている物を含め、装備は敵から見えない (EquipmentHider)
 * - 敵が 3 ブロック以内に近づくとバレて透明が解ける。バレたときは防具が 5 秒間戻らない
 * - 時間切れで透明が解けたときは、防具はすぐに戻る
 * - 透明が解けてから一定時間で復活する (それまでは補充待ちのアイテムを持たせる)
 *
 * 透明になるのはプレイヤーとは限らない (チュートリアルのお手本)。
 */
object InvisibleSpy : Tickable {
    const val INVISIBLE_TICKS = 200

    /** 透明が解けてから復活するまでの tick 数 */
    const val RECHARGE_TICKS = 400

    /** この距離まで敵が近づくとバレる */
    const val REVEAL_RADIUS = 3.0

    /** バレてから防具が戻るまでの tick 数 */
    const val ARMOR_RETURN_TICKS = 100

    private const val BAR_LENGTH = 10

    private val ARMOR_SLOTS = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    private val COLOR = Color.fromRGB(0x9A5CC6)

    /**
     * @param armor 外した防具
     */
    private class Cloak(val entity: LivingEntity, val armor: MutableList<Pair<EquipmentSlot, ItemStack>>) {
        var invisible = true
        var ticks = 0

        /** バレてから防具が戻るまでの残り tick 数 */
        var returnIn = 0
    }

    private val cloaks = HashMap<UUID, Cloak>()

    fun isInvisible(entity: LivingEntity): Boolean = cloaks[entity.uniqueId]?.invisible == true

    /**
     * 透明になる。
     * @return 透明になったか (既に透明なら false)
     */
    fun cloak(entity: LivingEntity): Boolean {
        if (isInvisible(entity)) return false
        val equipment = entity.equipment ?: return false

        // バレて防具が戻るのを待っている間に透明になった場合は、外したままの防具も引き継ぐ
        val armor = cloaks.remove(entity.uniqueId)?.armor ?: mutableListOf()
        for (slot in ARMOR_SLOTS) {
            val item = equipment.getItem(slot)
            if (item.isEmpty) continue
            armor += slot to item.clone()
            equipment.setItem(slot, null)
        }
        cloaks[entity.uniqueId] = Cloak(entity, armor)

        // 時間は自前で数える (効果の時間は念のための上限)
        entity.addPotionEffect(PotionEffect(PotionEffectType.INVISIBILITY, INVISIBLE_TICKS + 20, 0, false, false, true))
        EquipmentHider.hide(entity, viewersOf(entity))
        if (entity is Player) entity.arrowsInBody = 0

        val location = entity.location.add(0.0, 1.0, 0.0)
        entity.world.spawnParticle(Particle.DUST, location, 30, 0.35, 0.6, 0.35, 0.0, Particle.DustOptions(COLOR, 1.2f))
        entity.world.playSound(
            Sound.sound(org.bukkit.Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, Sound.Source.PLAYER, 0.8f, 1.2f),
            entity.x, entity.y, entity.z,
        )
        KitActionEvent.call(entity, KitActionEvent.Action.SPY_CLOAK)
        return true
    }

    /** 装備を見せるプレイヤー (本人と味方) */
    private fun viewersOf(entity: LivingEntity): Set<UUID> {
        val side = Combatants.sideOf(entity)
        return Bukkit.getOnlinePlayers()
            .filter { it == entity || (side != null && Combatants.sideOf(it) == side) }
            .map { it.uniqueId }
            .toSet()
    }

    // ------------------------------------------------------------------
    // tick
    // ------------------------------------------------------------------

    override fun tick() {
        for (cloak in cloaks.values.toList()) {
            val entity = cloak.entity
            if (!entity.isValid || entity.isDead || !Combatants.isTarget(entity)) {
                clear(entity)
                continue
            }
            if (cloak.invisible) tickInvisible(cloak) else tickStripped(cloak)
        }
    }

    private fun tickInvisible(cloak: Cloak) {
        val entity = cloak.entity
        cloak.ticks++
        // 体に刺さった矢で居場所がわからないようにする
        if (entity is Player && entity.arrowsInBody > 0) entity.arrowsInBody = 0
        // 途中で敵味方が変わった人にも対応する
        EquipmentHider.hide(entity, viewersOf(entity))

        val spotter = findSpotter(entity)
        when {
            spotter != null -> reveal(cloak, spotter)
            cloak.ticks >= INVISIBLE_TICKS || !entity.hasPotionEffect(PotionEffectType.INVISIBILITY) -> expire(cloak)
            entity is Player -> showGauge(entity, 1.0 - cloak.ticks.toDouble() / INVISIBLE_TICKS)
        }
    }

    private fun tickStripped(cloak: Cloak) {
        val entity = cloak.entity
        // スタン中はアイテムを没収しているので、解除されてから返す
        if (ShieldManager.isStunned(entity)) return
        if (--cloak.returnIn > 0) {
            if (entity is Player && cloak.returnIn % 10 == 0) {
                entity.sendActionBar(
                    Component.translatable(
                        "rushcraft.kit.invisible_spy.waiting", NamedTextColor.RED, Component.text((cloak.returnIn + 19) / 20),
                    )
                )
            }
            return
        }
        cloaks.remove(entity.uniqueId)
        returnArmor(cloak, announce = true)
    }

    /** 3 ブロック以内にいる敵 */
    private fun findSpotter(entity: LivingEntity): LivingEntity? {
        val location = entity.location
        return entity.world.getNearbyLivingEntities(location, REVEAL_RADIUS).firstOrNull { other ->
            other != entity && other.location.distanceSquared(location) <= REVEAL_RADIUS * REVEAL_RADIUS &&
                Combatants.isTarget(other) && Combatants.isEnemy(entity, other)
        }
    }

    private fun showGauge(player: Player, remaining: Double) {
        val filled = Math.ceil(BAR_LENGTH * remaining).toInt().coerceIn(0, BAR_LENGTH)
        player.sendActionBar(
            Component.text()
                .append(Component.translatable("rushcraft.kit.invisible_spy.gauge", NamedTextColor.GRAY))
                .append(Component.text(" "))
                .append(Component.text("■".repeat(filled), NamedTextColor.LIGHT_PURPLE))
                .append(Component.text("■".repeat(BAR_LENGTH - filled), NamedTextColor.DARK_GRAY))
                .build()
        )
    }

    // ------------------------------------------------------------------
    // 透明が解ける
    // ------------------------------------------------------------------

    /** 透明をやめる (防具はまだ返さない) */
    private fun uncloak(cloak: Cloak) {
        val entity = cloak.entity
        cloak.invisible = false
        entity.removePotionEffect(PotionEffectType.INVISIBILITY)
        EquipmentHider.show(entity)
        if (entity is Player) ItemRecharge.schedule(entity, SpecialItem.INVISIBLE_SPY, RECHARGE_TICKS)
    }

    /** 敵に見つかった。防具はしばらく戻らない */
    private fun reveal(cloak: Cloak, spotter: LivingEntity) {
        val entity = cloak.entity
        uncloak(cloak)
        cloak.returnIn = ARMOR_RETURN_TICKS

        val location = entity.location.add(0.0, 1.0, 0.0)
        entity.world.spawnParticle(Particle.DUST, location, 40, 0.4, 0.7, 0.4, 0.0, Particle.DustOptions(COLOR, 1.5f))
        entity.world.playSound(
            Sound.sound(org.bukkit.Sound.BLOCK_AMETHYST_CLUSTER_BREAK, Sound.Source.PLAYER, 1.2f, 1.0f),
            entity.x, entity.y, entity.z,
        )
        if (entity is Player) {
            // 見つかったことがすぐわかるよう、画面の中央にも出す
            entity.showTitle(
                Title.title(
                    Component.empty(),
                    Component.translatable("rushcraft.kit.invisible_spy.revealed-subtitle", NamedTextColor.RED),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1200), Duration.ofMillis(400)),
                )
            )
            entity.sendActionBar(
                Component.translatable(
                    "rushcraft.kit.invisible_spy.revealed", NamedTextColor.RED, Component.text(ARMOR_RETURN_TICKS / 20),
                )
            )
        }
        if (spotter is Player) {
            spotter.sendActionBar(Component.translatable("rushcraft.kit.invisible_spy.spotted", NamedTextColor.GOLD))
        }
        KitActionEvent.call(entity, KitActionEvent.Action.SPY_REVEALED, spotter)
    }

    /** 時間切れ。防具はすぐに戻る */
    private fun expire(cloak: Cloak) {
        val entity = cloak.entity
        uncloak(cloak)
        cloaks.remove(entity.uniqueId)
        returnArmor(cloak, announce = false)
        if (entity is Player) {
            entity.sendActionBar(Component.translatable("rushcraft.kit.invisible_spy.expired", NamedTextColor.YELLOW))
        }
        entity.world.playSound(
            Sound.sound(org.bukkit.Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, Sound.Source.PLAYER, 0.6f, 0.8f),
            entity.x, entity.y, entity.z,
        )
    }

    /** 外した防具を返す (同じ場所に何か着ていたらインベントリへ) */
    private fun returnArmor(cloak: Cloak, announce: Boolean) {
        val entity = cloak.entity
        val equipment = entity.equipment ?: return
        if (cloak.armor.isEmpty()) return
        for ((slot, item) in cloak.armor) {
            if (equipment.getItem(slot).isEmpty) {
                equipment.setItem(slot, item)
            } else if (entity is Player) {
                entity.inventory.addItem(item).values.forEach { entity.world.dropItemNaturally(entity.location, it) }
            }
        }
        entity.world.playSound(
            Sound.sound(org.bukkit.Sound.ITEM_ARMOR_EQUIP_LEATHER, Sound.Source.PLAYER, 1f, 1f),
            entity.x, entity.y, entity.z,
        )
        if (announce && entity is Player) {
            entity.sendActionBar(Component.translatable("rushcraft.kit.invisible_spy.armor-returned", NamedTextColor.GREEN))
        }
    }

    // ------------------------------------------------------------------
    // 後片付け
    // ------------------------------------------------------------------

    /** 透明と防具の預かりをやめる (防具は返さない。死んだときやチュートリアルのやり直しなど) */
    fun clear(entity: LivingEntity) {
        val cloak = cloaks.remove(entity.uniqueId) ?: return
        if (cloak.invisible && entity.isValid) entity.removePotionEffect(PotionEffectType.INVISIBILITY)
        EquipmentHider.show(entity)
    }

    /** 死んだとき、外していた防具を返す (ドロップに加えるため) */
    fun takeArmor(player: Player): List<ItemStack> {
        val cloak = cloaks[player.uniqueId] ?: return emptyList()
        clear(player)
        return cloak.armor.map { it.second }
    }

    fun reset() {
        cloaks.values.toList().forEach { clear(it.entity) }
        EquipmentHider.reset()
    }

    override fun isAlive(): Boolean = true
}
