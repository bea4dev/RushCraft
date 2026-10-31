package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.match.TeamColor
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.ShieldMeta

/**
 * 全マップ共通で選べる特殊アイテム。
 */
enum class SpecialItem(val id: String, val icon: Material) {
    /** 攻撃のクールダウンが無い鉄剣 */
    CLASSIC_SWORD("classic_sword", Material.IRON_SWORD),

    /** 射撃ダメージ増加 I だけの弓 (矢付き) */
    CLASSIC_BOW("classic_bow", Material.BOW),

    /** ガードで耐久が減り、ガード解除の直後にパリィできる盾 (壊れても 10 秒で復活) */
    SHIELD("shield", Material.SHIELD),

    /** 投げて当てた敵と位置を入れ替えるトライデント (忠誠で戻ってくる) */
    SWAPPER("swapper", Material.TRIDENT),

    /** 当たった敵を一撃で倒す投げナイフ (1 回のスポーンにつき 1 回) */
    THROWING_KNIFE("throwing_knife", Material.FLINT),

    /** 着火して構え、投げると近くの敵に持たせる TNT (爆発してから時間で復活) */
    TNT("tnt", Material.TNT),

    /** 置いた場所に高さ 3 ブロックのガラスの壁を作る (使ってから時間で復活) */
    INSTANT_WALL("instant_wall", Material.GLASS),

    /** しゃがんでチャージすると高く飛び上がれるメイス */
    MACE("mace", Material.MACE),

    /** 突進付きの鉄の槍。3 回使うと壊れ、時間で復活する */
    SPEAR("spear", Material.IRON_SPEAR),

    /** 敵が踏むと爆発する感圧板 (見た目はマップごとに変えられる) */
    TRAP("trap", Material.STONE_PRESSURE_PLATE);

    val nameKey: String
        get() = "rushcraft.kit.special.$id"

    val descriptionKey: String
        get() = "rushcraft.kit.special.$id-description"

    /**
     * 配るアイテムと置きたいスロットの組 (スロットが埋まっていれば空いている場所に入る)。
     */
    fun createItems(color: TeamColor?): List<Pair<Int, ItemStack>> {
        return when (this) {
            CLASSIC_SWORD -> listOf(
                0 to named(ItemStack(Material.IRON_SWORD)).also { stack ->
                    stack.editMeta { meta ->
                        // 独自の属性を付けると既定の属性は消えるので、攻撃力も指定し直す (素手 1 + 5 = 鉄剣の 6)
                        meta.addAttributeModifier(
                            Attribute.ATTACK_DAMAGE,
                            AttributeModifier(key("classic_damage"), 5.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND),
                        )
                        // 攻撃速度を最大近くまで上げてクールダウンを実質なくす
                        meta.addAttributeModifier(
                            Attribute.ATTACK_SPEED,
                            AttributeModifier(key("classic_speed"), 1000.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND),
                        )
                    }
                },
            )

            CLASSIC_BOW -> listOf(
                1 to named(ItemStack(Material.BOW)).also { stack ->
                    stack.editMeta { meta -> meta.addEnchant(Enchantment.POWER, 1, true) }
                },
                9 to ItemStack(Material.ARROW, 32),
            )

            SHIELD -> listOf(
                40 to named(ItemStack(Material.SHIELD)).also { stack ->
                    if (color != null) {
                        stack.editMeta(ShieldMeta::class.java) { meta -> meta.baseColor = color.dye }
                    }
                },
            )

            SWAPPER -> listOf(
                2 to named(ItemStack(Material.TRIDENT)).also { stack ->
                    stack.editMeta { meta -> meta.addEnchant(Enchantment.LOYALTY, 3, true) }
                },
            )

            THROWING_KNIFE -> listOf(3 to named(ItemStack(Material.FLINT)))

            TNT -> listOf(4 to named(ItemStack(Material.TNT)))

            INSTANT_WALL -> listOf(5 to named(ItemStack(color?.let(InstantWall::glassOf) ?: Material.GLASS)))

            MACE -> listOf(6 to named(ItemStack(Material.MACE)))

            SPEAR -> listOf(
                7 to named(ItemStack(Material.IRON_SPEAR)).also { stack ->
                    stack.editMeta(Damageable::class.java) { meta ->
                        meta.addEnchant(Enchantment.LUNGE, 2, true)
                        // 耐久は使った回数として自前で減らす
                        meta.setMaxDamage(SpearManager.MAX_USES)
                    }
                },
            )

            TRAP -> {
                val plate = MatchManager.match?.map?.kit?.trapPlateMaterial() ?: Material.STONE_PRESSURE_PLATE
                listOf(8 to named(ItemStack(plate, TrapManager.MAX_TRAPS)))
            }
        }.onEach { (_, stack) -> KitManager.markSpecial(stack, this) }
    }

    private fun named(stack: ItemStack): ItemStack {
        stack.editMeta { meta ->
            meta.itemName(Component.translatable(nameKey).decoration(TextDecoration.ITALIC, false))
        }
        return stack
    }

    companion object {
        private fun key(name: String): NamespacedKey = NamespacedKey("rushcraft", name)

        fun byId(id: String): SpecialItem? = entries.find { it.id == id }
    }
}
