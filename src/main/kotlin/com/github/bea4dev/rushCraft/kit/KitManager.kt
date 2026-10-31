package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.config.KitConfig
import com.github.bea4dev.rushCraft.map.MapDefinition
import com.github.bea4dev.rushCraft.match.MatchTeam
import com.github.bea4dev.rushCraft.match.TeamColor
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

    /** 特殊アイテムの枠ごとの選択 (null は空き) */
    private val selections = HashMap<UUID, List<SpecialItem?>>()

    fun initialize(config: KitConfig) {
        this.config = config
    }

    // ------------------------------------------------------------------
    // 特殊アイテムの選択
    // ------------------------------------------------------------------

    /** 枠ごとの選択 (長さは maxSpecialItems) */
    fun slots(player: Player): List<SpecialItem?> {
        val slots = selections[player.uniqueId] ?: SpecialItem.entries.take(config.maxSpecialItems)
        return List(config.maxSpecialItems) { slots.getOrNull(it) }
    }

    /** 選択中の特殊アイテム (枠の順) */
    fun selection(player: Player): List<SpecialItem> = slots(player).filterNotNull().distinct()

    /**
     * index 番目の枠に special を選ぶ。既に別の枠で選んでいる場合は、その枠と入れ替える。
     */
    fun select(player: Player, index: Int, special: SpecialItem?) {
        val slots = slots(player).toMutableList()
        if (index !in slots.indices) return
        val other = if (special != null) slots.indexOf(special) else -1
        if (other >= 0 && other != index) slots[other] = slots[index]
        slots[index] = special
        selections[player.uniqueId] = slots
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
            items += createSpecialItems(special, team.color)
        }
        items += map.kit.createItems(team.color).onEach { (_, stack) ->
            if (!map.kit.dropOnDeath) markNoDrop(stack)
        }

        for ((slot, stack) in items) {
            give(player, slot, stack)
        }
    }

    /** 特殊アイテムを作る (設定に応じて死亡時にドロップしない印を付ける) */
    fun createSpecialItems(special: SpecialItem, color: TeamColor?): List<Pair<Int, ItemStack>> {
        return special.createItems(color).onEach { (_, stack) ->
            if (!config.specialDropOnDeath) markNoDrop(stack)
        }
    }

    /** slot が空いていればそこに、埋まっていれば空いている場所に入れる */
    fun give(player: Player, slot: Int, stack: ItemStack) {
        val inventory = player.inventory
        val current = inventory.getItem(slot)
        if (current == null || current.isEmpty) {
            inventory.setItem(slot, stack)
        } else {
            inventory.addItem(stack)
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
