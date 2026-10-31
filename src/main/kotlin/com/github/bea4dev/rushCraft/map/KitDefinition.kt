package com.github.bea4dev.rushCraft.map

import com.github.bea4dev.rushCraft.match.TeamColor
import com.github.bea4dev.rushCraft.util.TomlFormatException
import com.github.bea4dev.rushCraft.util.int
import com.github.bea4dev.rushCraft.util.requireInt
import com.github.bea4dev.rushCraft.util.requireString
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.tomlj.TomlTable

/**
 * キットのアイテム 1 つ。
 *
 * slot は PlayerInventory の番号 (0-8: ホットバー, 9-35: インベントリ,
 * 36: ブーツ, 37: レギンス, 38: チェストプレート, 39: ヘルメット, 40: オフハンド)。
 * item は `minecraft:iron_sword[minecraft:enchantments={sharpness:1}]` のような
 * /give と同じ形式の文字列。
 */
data class KitItem(val slot: Int, val item: String, val amount: Int) {
    fun createItemStack(): ItemStack {
        val stack = Bukkit.getItemFactory().createItemStack(item)
        stack.amount = amount
        return stack
    }

    fun toToml(): Map<String, Any> {
        val map = linkedMapOf<String, Any>("slot" to slot, "item" to item)
        if (amount != 1) {
            map["amount"] = amount
        }
        return map
    }

    companion object {
        const val INVENTORY_SIZE = 41

        fun parse(table: TomlTable): KitItem {
            val slot = table.requireInt("slot")
            if (slot !in 0 until INVENTORY_SIZE) {
                throw TomlFormatException("kit slot must be 0..${INVENTORY_SIZE - 1}: $slot")
            }
            val item = table.requireString("item")
            try {
                Bukkit.getItemFactory().createItemStack(item)
            } catch (exception: IllegalArgumentException) {
                throw TomlFormatException("invalid kit item '$item': ${exception.message}")
            }
            return KitItem(slot, item, table.int("amount", 1).coerceIn(1, 99))
        }

        fun of(slot: Int, stack: ItemStack): KitItem {
            val components = stack.itemMeta?.asComponentString ?: ""
            return KitItem(slot, stack.type.key.asString() + components, stack.amount)
        }
    }
}

class KitDefinition(
    val items: MutableList<KitItem>,
    var teamColoredArmor: Boolean,
    /** キットのアイテムを死亡時にドロップするか */
    var dropOnDeath: Boolean = false,
    /** 特殊アイテムのトラップに使う感圧板 (マップに馴染む見た目にできる)。null なら既定の石の感圧板 */
    var trapPlate: String? = null,
) {
    /** トラップに使う感圧板。設定が無いか感圧板でなければ既定の石の感圧板 */
    fun trapPlateMaterial(): Material {
        val material = trapPlate?.let(Material::matchMaterial)
        return material?.takeIf { Tag.PRESSURE_PLATES.isTagged(it) } ?: Material.STONE_PRESSURE_PLATE
    }

    /**
     * スロットとアイテムの組を作る。
     */
    fun createItems(color: TeamColor?): List<Pair<Int, ItemStack>> {
        return items.mapNotNull { item ->
            val stack = try {
                item.createItemStack()
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
            if (teamColoredArmor && color != null) {
                stack.editMeta(LeatherArmorMeta::class.java) { meta -> meta.setColor(color.dye.color) }
            }
            item.slot to stack
        }
    }

    /**
     * プレイヤーのインベントリを空にしてキットだけを配る (セットアップでの確認用)。
     */
    fun apply(player: Player, color: TeamColor?) {
        val inventory = player.inventory
        inventory.clear()
        for ((slot, stack) in createItems(color)) {
            inventory.setItem(slot, stack)
        }
    }

    /**
     * プレイヤーの現在のインベントリをキットとして取り込む。
     */
    fun copyFrom(player: Player, filter: (ItemStack) -> Boolean = { true }) {
        items.clear()
        val inventory = player.inventory
        for (slot in 0 until KitItem.INVENTORY_SIZE) {
            val stack = inventory.getItem(slot) ?: continue
            if (stack.isEmpty || !filter(stack)) continue
            items += KitItem.of(slot, stack)
        }
    }

    companion object {
        fun createDefault(): KitDefinition {
            return KitDefinition(
                // 武器は全マップ共通の特殊アイテムから選ぶ
                mutableListOf(
                    KitItem(2, "minecraft:iron_pickaxe", 1),
                    KitItem(3, "minecraft:iron_axe", 1),
                    KitItem(4, "minecraft:oak_planks", 64),
                    KitItem(5, "minecraft:oak_planks", 64),
                    KitItem(6, "minecraft:glass", 32),
                    KitItem(7, "minecraft:golden_apple", 1),
                    KitItem(8, "minecraft:cooked_beef", 32),
                    KitItem(36, "minecraft:leather_boots", 1),
                    KitItem(37, "minecraft:leather_leggings", 1),
                    KitItem(38, "minecraft:iron_chestplate", 1),
                    KitItem(39, "minecraft:leather_helmet", 1),
                ),
                teamColoredArmor = true,
            )
        }
    }
}
