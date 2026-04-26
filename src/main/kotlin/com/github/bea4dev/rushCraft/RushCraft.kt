package com.github.bea4dev.rushCraft

import org.bukkit.plugin.java.JavaPlugin

class RushCraft : JavaPlugin() {
    companion object {
        lateinit var plugin: RushCraft
            private set
    }

    override fun onEnable() {
        // Plugin startup logic
        plugin = this
        this.logger.info("Starting RushCraft")
    }

    override fun onDisable() {
        // Plugin shutdown logic
    }
}
