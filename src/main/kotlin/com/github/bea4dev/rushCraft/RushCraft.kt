package com.github.bea4dev.rushCraft

import com.github.bea4dev.rushCraft.command.Commands
import com.github.bea4dev.rushCraft.config.PluginConfig
import com.github.bea4dev.rushCraft.i18n.Translations
import com.github.bea4dev.rushCraft.kit.KitListener
import com.github.bea4dev.rushCraft.kit.KitManager
import com.github.bea4dev.rushCraft.kit.ShieldManager
import com.github.bea4dev.rushCraft.listener.MatchListener
import com.github.bea4dev.rushCraft.manager.BUNGEE_CHANNEL
import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.map.MapRegistry
import com.github.bea4dev.rushCraft.setup.SetupListener
import com.github.bea4dev.rushCraft.setup.SetupManager
import org.bukkit.Bukkit
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

        val config = PluginConfig.load(this)
        Translations.register(this)
        MapRegistry.load(this)
        Commands.register(this)

        val pluginManager = Bukkit.getPluginManager()
        if (config.setupMode) {
            this.logger.info("Setup mode is enabled. Matches will not be started.")
            pluginManager.registerEvents(SetupListener(), this)
            SetupManager.start()
        } else {
            server.messenger.registerOutgoingPluginChannel(this, BUNGEE_CHANNEL)
            KitManager.initialize(config.kit)
            pluginManager.registerEvents(MatchListener(), this)
            pluginManager.registerEvents(KitListener(), this)
            ShieldManager.schedule()
            MatchManager.start(config, MapRegistry.all())
        }
    }

    override fun onDisable() {
        // Plugin shutdown logic
        ShieldManager.reset(restore = true)
        Translations.unregister()
    }
}
