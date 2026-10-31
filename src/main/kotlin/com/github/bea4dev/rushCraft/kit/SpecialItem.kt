package com.github.bea4dev.rushCraft.kit

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
import org.bukkit.inventory.meta.ShieldMeta

/**
 * 全マップ共通で選べる特殊アイテム。
 */
enum class SpecialItem(val id: String, val icon: Material) {
    /** 攻撃のクールダウンが無い鉄剣 */
    CLASSIC_SWORD("classic_sword", Material.IRON_SWORD),

    /** 射撃ダメージ増加 I だけの弓 (矢付き) */
    CLASSIC_BOW("classic_bow", Material.BOW),

    /** ガードで耐久が減り、ガード解除の直後にパリィできる盾 */
    SHIELD("shield", Material.SHIELD);

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
