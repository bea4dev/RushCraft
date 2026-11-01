package com.github.bea4dev.rushCraft.listener

import com.github.bea4dev.rushCraft.RushCraft
import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.match.CombatTracker
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack

/**
 * 試合全体に関わるイベント (参加・退出・リスポーン・ワールド保護)。
 *
 * 待機中と試合後は OP を含め誰もワールドを変更できない。
 * 試合中は参加者のみ、建築禁止エリア・スポーン部屋・建築高度制限の外で変更できる。
 * 試合中は他チームのスポーン部屋 (とルール固有の部屋) に入れない。
 */
class MatchListener : Listener {
    companion object {
        private val KILL_SOUND = Sound.sound(org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, Sound.Source.PLAYER, 1f, 2f)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        MatchManager.onJoin(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        CombatTracker.clear(event.player)
        MatchManager.onQuit(event.player)
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val match = MatchManager.match ?: return
        val player = event.player
        val team = match.getTeam(player)

        if (team != null && MatchManager.isRunning(match.world)) {
            event.respawnLocation = match.respawnLocation(team)
            Bukkit.getScheduler().runTask(RushCraft.plugin, Runnable {
                if (player.isOnline && match.getTeam(player) == team && match.isAlive()) {
                    match.spawn(player, team, teleport = false)
                }
            })
        } else {
            event.respawnLocation = match.map.spawn.toLocation(match.world)
        }
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        val match = MatchManager.match ?: return
        val victim = event.player
        val special = CombatTracker.takeSpecialDeath(victim)
        val lastHit = CombatTracker.lastHit(victim)
        CombatTracker.clear(victim)
        if (!MatchManager.isRunning(victim.world)) return

        // 奈落に突き落とした場合は、バニラのキル判定 (5 秒) が切れていても最後に攻撃したプレイヤーのキルにする
        val isVoid = victim.lastDamageCause?.cause == EntityDamageEvent.DamageCause.VOID
        val hit = lastHit?.takeIf { it.attacker.world == victim.world && match.getTeam(it.attacker) != null }
        val knocker = if (isVoid && special == null) victim.killer ?: hit?.attacker else null

        val specialKiller = special?.killer?.let(Bukkit::getPlayer)
        // 特殊アイテムでの死亡は記録した人だけのキルにする (TNT の自爆を、直前に攻撃した人のキルにしない)
        val killer = if (special != null) specialKiller else victim.killer ?: knocker
        // 倒した人の武器 (最後に攻撃してきたのが同じ人ならそのときの武器)
        val weapon = killer?.let { if (hit?.attacker == it) hit.weapon else CombatTracker.weaponOf(it) }

        // キルログはどれも「倒された人 → 倒した人 → 武器」の順に並べる
        val message = when {
            // 投げナイフ・TNT などの即死
            special != null -> Component.translatable(
                special.messageKey,
                victim.displayName(),
                specialKiller?.displayName() ?: Component.text("?"),
            )

            knocker != null -> Component.translatable(
                "rushcraft.match.death.void", victim.displayName(), knocker.displayName(), weaponName(weapon)
            )

            killer != null -> Component.translatable(
                "rushcraft.match.death.kill", victim.displayName(), killer.displayName(), weaponName(weapon)
            )

            // 自滅など。バニラの死亡メッセージはメインスコアボードのチームで名前を装飾するため、
            // 試合用スコアボードのチーム色が付かない。表示名に置き換える。
            else -> event.deathMessage()?.replaceText { it.matchLiteral(victim.name).once().replacement(victim.displayName()) }
        }
        event.deathMessage(null)
        if (message != null) broadcastDeath(message, listOfNotNull(victim, killer))

        if (killer == null || killer == victim || match.getTeam(killer) == null) return
        val kills = match.addKill(killer)
        MatchManager.bukkit?.setTabScore(killer, kills)
        // 倒した本人にだけ経験値の音を鳴らす
        killer.playSound(KILL_SOUND, Sound.Emitter.self())
    }

    private fun weaponName(weapon: ItemStack?): Component {
        return weapon?.displayName() ?: Component.translatable("rushcraft.match.death.fist")
    }

    /** キルログを流す。倒された人・倒した人には太字で出す */
    private fun broadcastDeath(message: Component, involved: List<Player>) {
        val bold = message.decorate(TextDecoration.BOLD)
        for (player in Bukkit.getOnlinePlayers()) {
            player.sendMessage(if (player in involved) bold else message)
        }
        Bukkit.getConsoleSender().sendMessage(message)
    }

    /** 敵からの攻撃を記録しておく (奈落に突き落とした判定用) */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCombat(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val damager = event.damager
        val attacker = when (damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return
        val match = MatchManager.match ?: return
        if (!MatchManager.isRunning(victim.world)) return
        val team = match.getTeam(attacker) ?: return
        if (match.getTeam(victim) == team) return
        CombatTracker.record(victim, attacker, CombatTracker.weaponOf(damager))
    }

    // ------------------------------------------------------------------
    // 保護
    // ------------------------------------------------------------------

    private fun canModify(player: Player, block: Block): Boolean {
        val world = block.world
        if (!MatchManager.isMatchWorld(world)) return true
        if (MatchManager.isProtected(world)) return false
        if (!MatchManager.isParticipant(player)) return false
        return isBuildable(block)
    }

    private fun isBuildable(block: Block): Boolean {
        val map = MatchManager.match?.map ?: return true
        val maxHeight = map.maxBuildHeight
        if (maxHeight != null && block.y > maxHeight) return false
        if (map.teams.any { team -> team.spawnRooms.any { it.contains(block) } }) return false
        return map.noBuildRegions.none { it.contains(block) }
    }

    private fun deny(event: Cancellable, player: Player, block: Block) {
        event.isCancelled = true
        if (!MatchManager.isProtected(block.world)) {
            player.sendActionBar(Component.translatable("rushcraft.match.build.denied", NamedTextColor.RED))
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!canModify(event.player, event.block)) deny(event, event.player, event.block)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!canModify(event.player, event.blockPlaced)) deny(event, event.player, event.blockPlaced)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        val block = event.blockClicked.getRelative(event.blockFace)
        if (!canModify(event.player, block)) deny(event, event.player, block)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (!canModify(event.player, event.blockClicked)) deny(event, event.player, event.blockClicked)
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onInteract(event: PlayerInteractEvent) {
        if (MatchManager.isProtected(event.player.world)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (MatchManager.isProtected(event.player.world)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onArmorStand(event: PlayerArmorStandManipulateEvent) {
        if (MatchManager.isProtected(event.player.world)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        val world = event.entity.world
        if (!MatchManager.isMatchWorld(world)) return
        val remover = event.remover as? Player
        if (remover == null) {
            if (MatchManager.isProtected(world) || !isBuildable(event.entity.location.block)) event.isCancelled = true
            return
        }
        if (!canModify(remover, event.entity.location.block)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        if (MatchManager.isProtected(event.entity.world)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onFoodLevelChange(event: FoodLevelChangeEvent) {
        if (MatchManager.isProtected(event.entity.world)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (MatchManager.isProtected(event.player.world)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val world = event.entity.world
        if (MatchManager.isProtected(world)) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        filterExplosion(event.entity.world, event.blockList(), event)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        filterExplosion(event.block.world, event.blockList(), event)
    }

    private fun filterExplosion(world: World, blocks: MutableList<Block>, event: Cancellable) {
        if (!MatchManager.isMatchWorld(world)) return
        if (MatchManager.isProtected(world)) {
            event.isCancelled = true
            return
        }
        blocks.removeIf { !isBuildable(it) }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (!MatchManager.isMatchWorld(event.block.world)) return
        if (event.blocks.any { !isBuildable(it) || !isBuildable(it.getRelative(event.direction)) }) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (!MatchManager.isMatchWorld(event.block.world)) return
        if (event.blocks.any { !isBuildable(it) || !isBuildable(it.getRelative(event.direction)) }) {
            event.isCancelled = true
        }
    }

    // ------------------------------------------------------------------
    // 立ち入り制限 (スポーン部屋・羊毛部屋)
    // ------------------------------------------------------------------

    private fun canEnter(player: Player, location: Location): Boolean {
        val match = MatchManager.match ?: return true
        if (!MatchManager.isRunning(location.world)) return true
        // 観戦者はどこにでも入れる
        val team = match.getTeam(player) ?: return true
        return match.canEnter(team, location)
    }

    @EventHandler(ignoreCancelled = true)
    fun onEnterRoom(event: PlayerMoveEvent) {
        if (!event.hasChangedBlock()) return
        val player = event.player
        // 既に入れない場所にいる場合 (チーム変更直後など) は外に出られるようにする
        if (canEnter(player, event.to) || !canEnter(player, event.from)) return
        event.isCancelled = true
        player.sendActionBar(Component.translatable("rushcraft.match.room.denied", NamedTextColor.RED))
    }

    @EventHandler(ignoreCancelled = true)
    fun onTeleportIntoRoom(event: PlayerTeleportEvent) {
        if (event.cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL &&
            event.cause != PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT
        ) return
        if (!canEnter(event.player, event.to)) {
            event.isCancelled = true
            event.player.sendActionBar(Component.translatable("rushcraft.match.room.denied", NamedTextColor.RED))
        }
    }

    /** 待機中・試合後に奈落へ落ちたらスポーンに戻す */
    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val world = event.to.world ?: return
        if (!MatchManager.isProtected(world)) return
        if (event.to.y < world.minHeight - 16) {
            val match = MatchManager.match ?: return
            event.player.teleport(match.map.spawn.toLocation(match.world))
        }
    }
}
