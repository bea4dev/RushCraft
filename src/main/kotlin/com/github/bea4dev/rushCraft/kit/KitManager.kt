package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.config.KitConfig
import com.github.bea4dev.rushCraft.map.MapDefinition
import com.github.bea4dev.rushCraft.match.MatchTeam
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * キットの配布、特殊アイテムの選択、GUI を開くためのアイテムを扱う。
 */
object KitManager {
    /** 死亡時にドロップしないアイテム */
    private val NO_DROP_KEY = NamespacedKey("rushcraft", "no_drop")
    private val SPECIAL_KEY = NamespacedKey("rushcraft", "special")
    private val UI_KEY = NamespacedKey("rushcraft", "ui")

    enum class UiItem(val material: Material, val nameKey: String) {
        TEAM_SELECTOR(Material.COMPASS, "rushcraft.kit.ui.team-selector"),
        KIT_SELECTOR(Material.CHEST, "rushcraft.kit.ui.kit-selector"),
    }

    lateinit var config: KitConfig
        private set

    private val selections = HashMap<UUID, List<SpecialItem>>()

    fun initialize(config: KitConfig) {
        this.config = config
    }

    // ------------------------------------------------------------------
    // 特殊アイテムの選択
    // ------------------------------------------------------------------

    fun selection(player: Player): List<SpecialItem> {
        return selections[player.uniqueId] ?: SpecialItem.entries.take(config.maxSpecialItems)
    }

    fun select(player: Player, items: List<SpecialItem>) {
        selections[player.uniqueId] = items.distinct().sortedBy { it.ordinal }.take(config.maxSpecialItems)
    }

    // ------------------------------------------------------------------
    // 配布
    // ------------------------------------------------------------------

    /**
     * インベントリを空にして、選択中の特殊アイテム・マップのキット・キット選択アイテムを配る。
     */
    fun equip(player: Player, map: MapDefinition, team: MatchTeam) {
        val inventory = player.inventory
        inventory.clear()

        inventory.setItem(config.selectorSlot, createUiItem(UiItem.KIT_SELECTOR))

        val items = mutableListOf<Pair<Int, ItemStack>>()
        for (special in selection(player)) {
            items += special.createItems(team.color).onEach { (_, stack) ->
                if (!config.specialDropOnDeath) markNoDrop(stack)
            }
        }
        items += map.kit.createItems(team.color).onEach { (_, stack) ->
            if (!map.kit.dropOnDeath) markNoDrop(stack)
        }

        for ((slot, stack) in items) {
            val current = inventory.getItem(slot)
            if (current == null || current.isEmpty) {
                inventory.setItem(slot, stack)
            } else {
                inventory.addItem(stack)
            }
        }
    }

    /**
     * 待機中・試合後に配るアイテム。
     */
    fun giveLobbyItems(player: Player, participant: Boolean) {
        val inventory = player.inventory
        inventory.setItem(0, createUiItem(UiItem.TEAM_SELECTOR))
        if (participant) {
            inventory.setItem(1, createUiItem(UiItem.KIT_SELECTOR))
        }
    }

    // ------------------------------------------------------------------
    // アイテムの判定
    // ------------------------------------------------------------------

    fun markNoDrop(stack: ItemStack) {
        stack.editPersistentDataContainer { it.set(NO_DROP_KEY, PersistentDataType.BOOLEAN, true) }
    }

    fun markSpecial(stack: ItemStack, special: SpecialItem) {
        stack.editPersistentDataContainer { it.set(SPECIAL_KEY, PersistentDataType.STRING, special.id) }
    }

    fun isNoDrop(stack: ItemStack?): Boolean {
        if (stack == null || stack.isEmpty) return false
        val container = stack.persistentDataContainer
        return container.has(NO_DROP_KEY) || container.has(UI_KEY)
    }

    fun specialOf(stack: ItemStack?): SpecialItem? {
        if (stack == null || stack.isEmpty) return null
        val id = stack.persistentDataContainer.get(SPECIAL_KEY, PersistentDataType.STRING) ?: return null
        return SpecialItem.byId(id)
    }

    fun uiItemOf(stack: ItemStack?): UiItem? {
        if (stack == null || stack.isEmpty) return null
        val id = stack.persistentDataContainer.get(UI_KEY, PersistentDataType.STRING) ?: return null
        return UiItem.entries.find { it.name == id }
    }

    fun createUiItem(type: UiItem): ItemStack {
        val stack = ItemStack(type.material)
        stack.editMeta { meta ->
            meta.itemName(Component.translatable(type.nameKey, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false))
            meta.lore(
                listOf(
                    Component.translatable("${type.nameKey}-lore", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                )
            )
            meta.persistentDataContainer.set(UI_KEY, PersistentDataType.STRING, type.name)
        }
        return stack
    }
}
