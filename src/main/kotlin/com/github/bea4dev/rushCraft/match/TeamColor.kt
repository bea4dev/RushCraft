package com.github.bea4dev.rushCraft.match

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.DyeColor

enum class TeamColor(val text: NamedTextColor, val dye: DyeColor) {
    RED(NamedTextColor.RED, DyeColor.RED),
    BLUE(NamedTextColor.BLUE, DyeColor.BLUE),
    AQUA(NamedTextColor.AQUA, DyeColor.LIGHT_BLUE),
    YELLOW(NamedTextColor.YELLOW, DyeColor.YELLOW),
    GREEN(NamedTextColor.GREEN, DyeColor.LIME),
    ORANGE(NamedTextColor.GOLD, DyeColor.ORANGE),
    PURPLE(NamedTextColor.DARK_PURPLE, DyeColor.PURPLE),
    PINK(NamedTextColor.LIGHT_PURPLE, DyeColor.PINK);

    val translationKey: String
        get() = "rushcraft.team.${name.lowercase()}"

    fun displayName(): Component {
        return Component.translatable(translationKey, text)
    }

    companion object {
        fun parse(value: String): TeamColor? {
            return entries.find { it.name.equals(value, ignoreCase = true) }
        }
    }
}
