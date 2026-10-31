package com.github.bea4dev.rushCraft.kit

import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.entity.Trident
import org.bukkit.util.Vector
import java.util.UUID

/**
 * スワッパー: 投げたトライデントが当たった敵と位置を入れ替える。
 * 奈落に落ちたときも、忠誠と同じように持ち主の元へ戻ってくる。
 */
object Swapper : Tickable {
    private val thrown = mutableSetOf<Trident>()

    /** パリィで弾き返されたスワッパーと、元の持ち主 */
    private val reflected = HashMap<Trident, UUID>()

    fun track(trident: Trident) {
        thrown += trident
    }

    /** パリィで弾き返された。当たるか落ちるまではパリィした人が投げたものとして扱う */
    fun onReflect(trident: Trident, player: Player) {
        val owner = trident.ownerUniqueId ?: return
        reflected.putIfAbsent(trident, owner)
        trident.shooter = player
    }

    override fun tick() {
        thrown.removeIf { !it.isValid }
        // 弾き返されたスワッパーは、当たるか落ちたら元の持ち主の元へ戻るようにする
        reflected.entries.removeIf { (trident, owner) ->
            if (!trident.isValid) return@removeIf true
            if (!trident.isInBlock && !trident.hasDealtDamage()) return@removeIf false
            Bukkit.getPlayer(owner)?.let { trident.shooter = it }
            true
        }
        for (trident in thrown) {
            // 奈落の底で消される前に、忠誠で戻る状態にする
            if (trident.y < trident.world.minHeight && !trident.hasDealtDamage()) {
                trident.setHasDealtDamage(true)
                trident.velocity = Vector()
            }
        }
    }

    override fun isAlive(): Boolean = true

    fun isSwapper(trident: Trident): Boolean = KitManager.specialOf(trident.itemStack) == SpecialItem.SWAPPER

    fun swap(thrower: Player, target: Player) {
        if (!Combatants.isTarget(thrower) || !Combatants.isTarget(target)) return
        if (thrower.world != target.world || !Combatants.isEnemy(thrower, target)) return
        // スタン中はアーマースタンドに乗っているので動かせない
        if (ShieldManager.isStunned(thrower) || ShieldManager.isStunned(target)) return

        val match = MatchManager.match ?: return
        val throwerTeam = match.getTeam(thrower) ?: return
        val targetTeam = match.getTeam(target) ?: return
        val throwerLocation = thrower.location
        val targetLocation = target.location

        // スポーン部屋や羊毛部屋に入れてしまわないようにする
        if (!match.canEnter(throwerTeam, targetLocation) || !match.canEnter(targetTeam, throwerLocation)) {
            thrower.sendActionBar(Component.translatable("rushcraft.kit.swapper.denied", NamedTextColor.RED))
            return
        }

        // 向きはそれぞれのまま位置だけを入れ替える
        thrower.teleport(withRotation(targetLocation, throwerLocation))
        target.teleport(withRotation(throwerLocation, targetLocation))

        for (location in listOf(throwerLocation, targetLocation)) {
            location.world.spawnParticle(Particle.PORTAL, location.clone().add(0.0, 1.0, 0.0), 40, 0.3, 0.6, 0.3, 0.5)
            location.world.playSound(
                Sound.sound(org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, Sound.Source.PLAYER, 1f, 1.2f),
                location.x, location.y, location.z,
            )
        }
        // 持っている TNT もそのまま入れ替わり、所有者も入れ替わる
        TntManager.onSwap(thrower, target)
        thrower.sendActionBar(Component.translatable("rushcraft.kit.swapper.swapped", NamedTextColor.AQUA))
        target.sendActionBar(Component.translatable("rushcraft.kit.swapper.swapped", NamedTextColor.AQUA))
    }

    private fun withRotation(position: Location, rotation: Location): Location {
        return position.clone().also {
            it.yaw = rotation.yaw
            it.pitch = rotation.pitch
        }
    }

    /** 持ち主が死んだときなどに、投げたままのスワッパーを消す (リスポーンで新しく配られるため) */
    fun removeThrown(player: Player) {
        for (trident in player.world.getEntitiesByClass(Trident::class.java)) {
            val owner = reflected[trident] ?: trident.ownerUniqueId
            if (owner == player.uniqueId && isSwapper(trident)) trident.remove()
        }
    }
}
