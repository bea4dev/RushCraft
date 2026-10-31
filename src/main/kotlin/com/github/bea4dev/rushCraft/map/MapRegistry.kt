package com.github.bea4dev.rushCraft.map

import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * plugins/RushCraft/maps/<id>/map.toml を読み込んで保持する。
 */
object MapRegistry {
    private val maps = linkedMapOf<String, MapDefinition>()

    lateinit var directory: File
        private set

    fun load(plugin: JavaPlugin) {
        directory = File(plugin.dataFolder, "maps")
        directory.mkdirs()
        maps.clear()

        val directories = directory.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        for (mapDirectory in directories) {
            if (!File(mapDirectory, MapDefinition.FILE_NAME).exists()) continue
            try {
                val map = MapDefinition.load(mapDirectory)
                maps[map.id] = map
            } catch (exception: Exception) {
                plugin.logger.warning("Failed to load map '${mapDirectory.name}': ${exception.message}")
            }
        }
        plugin.logger.info("Loaded ${maps.size} map(s): ${maps.keys.joinToString()}")
    }

    fun get(id: String): MapDefinition? = maps[id]

    fun all(): Collection<MapDefinition> = maps.values

    fun register(map: MapDefinition) {
        maps[map.id] = map
    }

    fun directoryOf(id: String): File = File(directory, id)
}
