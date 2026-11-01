package com.github.bea4dev.rushCraft.tutorial

import com.github.bea4dev.rushCraft.kit.SpecialItem
import com.github.bea4dev.rushCraft.tutorial.content.BowTutorial
import com.github.bea4dev.rushCraft.tutorial.content.CtwTutorial
import com.github.bea4dev.rushCraft.tutorial.content.KnifeTutorial
import com.github.bea4dev.rushCraft.tutorial.content.MaceTutorial
import com.github.bea4dev.rushCraft.tutorial.content.ShieldTutorial
import com.github.bea4dev.rushCraft.tutorial.content.SpearTutorial
import com.github.bea4dev.rushCraft.tutorial.content.SwapperTutorial
import com.github.bea4dev.rushCraft.tutorial.content.SwordTutorial
import com.github.bea4dev.rushCraft.tutorial.content.TntTutorial
import com.github.bea4dev.rushCraft.tutorial.content.TrapTutorial
import com.github.bea4dev.rushCraft.tutorial.content.WallTutorial
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.ProjectileHitEvent

/**
 * チュートリアルの種類。CTW のルールと、特殊アイテムごとに 1 つずつ。
 */
enum class Tutorial(val id: String, val special: SpecialItem?, private val factory: (TutorialSession) -> TutorialContent) {
    CTW("ctw", null, ::CtwTutorial),
    CLASSIC_SWORD("classic_sword", SpecialItem.CLASSIC_SWORD, ::SwordTutorial),
    CLASSIC_BOW("classic_bow", SpecialItem.CLASSIC_BOW, ::BowTutorial),
    SHIELD("shield", SpecialItem.SHIELD, ::ShieldTutorial),
    SWAPPER("swapper", SpecialItem.SWAPPER, ::SwapperTutorial),
    THROWING_KNIFE("throwing_knife", SpecialItem.THROWING_KNIFE, ::KnifeTutorial),
    TNT("tnt", SpecialItem.TNT, ::TntTutorial),
    INSTANT_WALL("instant_wall", SpecialItem.INSTANT_WALL, ::WallTutorial),
    MACE("mace", SpecialItem.MACE, ::MaceTutorial),
    SPEAR("spear", SpecialItem.SPEAR, ::SpearTutorial),
    TRAP("trap", SpecialItem.TRAP, ::TrapTutorial);

    val icon: Material
        get() = special?.icon ?: Material.PINK_WOOL

    fun displayName(): Component {
        return Component.translatable(special?.nameKey ?: "rushcraft.tutorial.ctw.name")
    }

    /** 一覧に出す短い説明 */
    val summaryKey: String
        get() = "rushcraft.tutorial.summary.$id"

    fun create(session: TutorialSession): TutorialContent = factory(session)

    companion object {
        fun byId(id: String): Tutorial? = entries.find { it.id.equals(id, ignoreCase = true) }

        fun of(special: SpecialItem): Tutorial? = entries.find { it.special == special }
    }
}

/**
 * チュートリアルの中身。会場を建て、段階 (台本) を並べる。
 */
abstract class TutorialContent(val session: TutorialSession) {
    val arena: TutorialArena
        get() = session.arena

    /** 最初に立つ場所 */
    abstract val spawn: Location

    abstract fun build()

    abstract fun steps(): List<TutorialStep>

    /** プレイヤーがブロックを壊そうとした。true を返すと壊させる */
    open fun onBlockBreak(event: BlockBreakEvent): Boolean = false

    /** プレイヤーがブロックを置こうとした。true を返すと置かせる */
    open fun onBlockPlace(event: BlockPlaceEvent): Boolean = false

    /** 会場の中で飛び道具が何かに当たった */
    open fun onProjectileHit(event: ProjectileHitEvent) {}

    /** やられたとき (やり直す前) */
    open fun onDown() {}

    protected fun step(id: String, retryFrom: String? = null, script: Script) = TutorialStep(id, retryFrom, script)
}
