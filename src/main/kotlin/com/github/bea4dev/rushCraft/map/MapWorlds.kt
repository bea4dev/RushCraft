package com.github.bea4dev.rushCraft.map

import com.github.bea4dev.rushCraft.RushCraft
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.GameRules
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.generator.BiomeProvider
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import org.bukkit.block.Biome
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 試合用・編集用ワールドのコピー / 読み込み / 破棄。
 *
 * 試合用ワールドは起動時にテンプレート (maps/<id>/) からローテーション分だけコピーし、
 * 試合が終わるたびに破棄する。名前に固定の接頭辞を付けておき、
 * 起動時に前回の残りを消せるようにしている。
 */
object MapWorlds {
    const val MATCH_PREFIX = "rushcraft_match_"
    const val EDIT_PREFIX = "rushcraft_edit_"

    // ワールドのコピー時に除外するもの (ワールド直下のみ)
    private val SKIP_ROOT_FILES = setOf("uid.dat", "session.lock", MapDefinition.FILE_NAME)
    private val SKIP_ROOT_DIRS = setOf("playerdata", "advancements", "stats")

    val container: File
        get() = Bukkit.getWorldContainer()

    fun matchWorldName(index: Int, mapId: String): String = "$MATCH_PREFIX${index}_$mapId"

    fun editWorldName(mapId: String): String = "$EDIT_PREFIX$mapId"

    /**
     * 読み込まれていない、接頭辞が一致するワールドフォルダを全て削除する。
     */
    fun deleteWorlds(prefix: String) {
        val folders = container.listFiles()?.filter { it.isDirectory && it.name.startsWith(prefix) } ?: return
        for (folder in folders) {
            if (Bukkit.getWorld(folder.name) != null) continue
            if (!folder.deleteRecursively()) {
                RushCraft.plugin.logger.warning("Failed to delete old world: ${folder.name}")
            }
        }
    }

    fun copyWorld(source: File, target: File) {
        val sourcePath = source.toPath()
        val targetPath = target.toPath()
        Files.createDirectories(targetPath)

        Files.walk(sourcePath).use { stream ->
            for (path in stream) {
                val relative = sourcePath.relativize(path)
                val relativeName = relative.toString()
                if (relativeName.isEmpty()) continue

                val root = relative.getName(0).toString()
                if (root in SKIP_ROOT_DIRS) continue
                if (relative.nameCount == 1 && root in SKIP_ROOT_FILES) continue

                val destination = targetPath.resolve(relativeName)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /**
     * テンプレートのワールドデータ (map.toml 以外) を削除する。
     */
    fun clearTemplate(directory: File) {
        directory.listFiles()?.filter { it.name != MapDefinition.FILE_NAME }?.forEach { it.deleteRecursively() }
    }

    fun loadWorld(name: String): World {
        return WorldCreator(name)
            .generator(VoidGenerator)
            .biomeProvider(VoidGenerator.Biomes)
            .createWorld()
            ?: throw IllegalStateException("Failed to load world: $name")
    }

    fun applyMatchRules(world: World, map: MapDefinition) {
        world.setGameRule(GameRules.ADVANCE_TIME, !map.lockTime)
        world.setGameRule(GameRules.ADVANCE_WEATHER, false)
        world.setGameRule(GameRules.SPAWN_MOBS, false)
        world.setGameRule(GameRules.SPAWN_PHANTOMS, false)
        world.setGameRule(GameRules.SPAWN_PATROLS, false)
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false)
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true)
        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false)
        world.setGameRule(GameRules.KEEP_INVENTORY, false)
        world.difficulty = Difficulty.NORMAL
        world.time = map.time
        world.setStorm(false)
        world.isThundering = false
        world.isAutoSave = false
    }

    fun applyEditRules(world: World) {
        world.setGameRule(GameRules.ADVANCE_TIME, false)
        world.setGameRule(GameRules.ADVANCE_WEATHER, false)
        world.setGameRule(GameRules.SPAWN_MOBS, false)
        world.setGameRule(GameRules.SPAWN_PHANTOMS, false)
        world.setGameRule(GameRules.SPAWN_PATROLS, false)
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false)
        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false)
        world.setStorm(false)
        world.isThundering = false
    }

    /**
     * ワールドを保存せずにアンロードし、フォルダを削除する。
     */
    fun unloadAndDelete(world: World) {
        val folder = world.worldFolder
        if (!Bukkit.unloadWorld(world, false)) {
            RushCraft.plugin.logger.warning("Failed to unload world: ${world.name} (it will be deleted on next startup)")
            return
        }
        Bukkit.getScheduler().runTaskAsynchronously(RushCraft.plugin, Runnable {
            if (!folder.deleteRecursively()) {
                RushCraft.plugin.logger.warning("Failed to delete world: ${folder.name}")
            }
        })
    }
}

/**
 * 何も生成しないジェネレーター。
 * マップ外の未生成チャンクに地形が生成されないようにする。
 */
object VoidGenerator : ChunkGenerator() {
    object Biomes : BiomeProvider() {
        override fun getBiome(worldInfo: WorldInfo, x: Int, y: Int, z: Int): Biome = Biome.PLAINS

        override fun getBiomes(worldInfo: WorldInfo): List<Biome> = listOf(Biome.PLAINS)
    }
}
