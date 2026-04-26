package com.github.bea4dev.rushCraft.scheduler

import com.github.bea4dev.rushCraft.RushCraft
import org.bukkit.scheduler.BukkitRunnable

interface Tickable {
    fun tick()
    fun isAlive(): Boolean
    fun schedule() {
        object: BukkitRunnable() {
            override fun run() {
                if (!this@Tickable.isAlive()) {
                    this.cancel()
                    return
                }
                this@Tickable.tick()
            }
        }.runTaskTimer(RushCraft.plugin, 0L, 1L)
    }
}