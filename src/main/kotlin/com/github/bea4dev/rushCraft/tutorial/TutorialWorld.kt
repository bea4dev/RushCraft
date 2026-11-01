package com.github.bea4dev.rushCraft.tutorial

import com.github.bea4dev.rushCraft.map.MapWorlds
import org.bukkit.Difficulty
import org.bukkit.GameRules
import org.bukkit.Location
import org.bukkit.World

/**
 * チュートリアル用のワールド。起動のたびに空のワールドを作り直す。
 *
 * セッションごとに離れた場所へ毎回新しく会場を建てるので、何人でも同時に受けられ、
 * 他の人のチュートリアルや試合とは干渉しない (会場は再利用せず、ワールドごと次の起動時に消す)。
 */
object TutorialWorld {
    const val WORLD_NAME = "rushcraft_tutorial"

    /** 会場どうしの間隔 (見えない・届かない距離) */
    private const val SPACING = 1024

    /** 会場の基準の高さ */
    const val BASE_Y = 100

    var world: World? = null
        private set
    private var nextSlot = 0

    fun load() {
        MapWorlds.deleteWorlds(WORLD_NAME)
        val world = MapWorlds.loadWorld(WORLD_NAME)
        world.setGameRule(GameRules.ADVANCE_TIME, false)
        world.setGameRule(GameRules.ADVANCE_WEATHER, false)
        world.setGameRule(GameRules.SPAWN_MOBS, false)
        world.setGameRule(GameRules.SPAWN_PHANTOMS, false)
        world.setGameRule(GameRules.SPAWN_PATROLS, false)
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false)
        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false)
        world.setGameRule(GameRules.SHOW_DEATH_MESSAGES, false)
        world.setGameRule(GameRules.KEEP_INVENTORY, true)
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true)
        world.difficulty = Difficulty.NORMAL
        world.time = 6000L
        world.setStorm(false)
        world.isThundering = false
        world.isAutoSave = false
        this.world = world
    }

    fun isTutorialWorld(world: World): Boolean = world == this.world

    /** 新しい会場の基準位置 */
    fun allocate(): Location {
        val world = world ?: throw IllegalStateException("Tutorial world is not loaded")
        return Location(world, (nextSlot++ * SPACING).toDouble(), BASE_Y.toDouble(), 0.0)
    }
}
