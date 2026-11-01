package com.github.bea4dev.rushCraft.tutorial

import com.github.bea4dev.rushCraft.kit.InstantWall
import com.github.bea4dev.rushCraft.kit.ItemRecharge
import com.github.bea4dev.rushCraft.kit.KitActionEvent
import com.github.bea4dev.rushCraft.kit.KitManager
import com.github.bea4dev.rushCraft.kit.MaceManager
import com.github.bea4dev.rushCraft.kit.ShieldManager
import com.github.bea4dev.rushCraft.kit.SpearManager
import com.github.bea4dev.rushCraft.kit.SpecialItem
import com.github.bea4dev.rushCraft.kit.Swapper
import com.github.bea4dev.rushCraft.kit.TntManager
import com.github.bea4dev.rushCraft.kit.TrapManager
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.kyori.adventure.translation.GlobalTranslator
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mannequin
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import java.time.Duration

/**
 * 1 人のプレイヤーが受けている 1 つのチュートリアル。
 *
 * 段階 (TutorialStep) を順に進め、各段階の台本で「短い説明 → お手本 → 実際にやってみる」を行う。
 * やられたら (奈落に落ちた・倒された) その段階の最初からやり直す。
 */
class TutorialSession(val player: Player, val tutorial: Tutorial) {
    val arena = TutorialArena(TutorialWorld.allocate())
    val allySide = TutorialSide(this, enemy = false)
    val enemySide = TutorialSide(this, enemy = true)

    private val content: TutorialContent = tutorial.create(this)
    private val steps: List<TutorialStep> = content.steps()
    private var stepIndex = -1
    private var runner: ScriptRunner? = null

    /** 段階が変わると消すもの */
    private val stepEntities = mutableListOf<Entity>()
    private val dummies = mutableListOf<Dummy>()

    /** 会場が終わるまで残すもの (看板の文字など) */
    private val arenaEntities = mutableListOf<Entity>()

    /** 段階が終わるまで毎 tick 行う処理 (敵の復活など) */
    private val tickers = mutableListOf<() -> Unit>()

    /** 段階ごとの回数 (倒した数・パリィした数など)。段階が変わると 0 に戻る */
    private val counters = HashMap<String, Int>()

    private val bossBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS)

    /** やられたときに戻る場所 */
    var checkpoint: Location = content.spawn

    /** プレイヤーを導く目印 (パーティクルの線) */
    var guide: Location? = null

    var tour: CameraTour? = null
        private set

    private var pendingDown: String? = null
    private var ticks = 0

    var isCompleted = false
        private set

    // ------------------------------------------------------------------
    // 開始・終了
    // ------------------------------------------------------------------

    fun start() {
        DummyDisguise.inject(player)
        content.build()
        resetPlayer()
        player.teleport(content.spawn)
        player.showBossBar(bossBar)
        goTo(0)
    }

    private fun resetPlayer() {
        player.gameMode = GameMode.SURVIVAL
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.foodLevel = 20
        player.saturation = 5f
        player.fireTicks = 0
        player.fallDistance = 0f
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.inventory.clear()
        player.setItemOnCursor(null)
        player.closeInventory()
    }

    /** 終わったとき (やめた・クリアした・抜けた)。会場を片付ける */
    fun dispose() {
        tour?.finish()
        tour = null
        player.hideBossBar(bossBar)
        cleanupStep()
        arenaEntities.forEach(Entity::remove)
        arenaEntities.clear()
        // 会場に残ったもの (矢・トライデント・表示など) を消す
        for (entity in arena.world.getNearbyEntities(arena.bounds)) {
            if (entity !is Player) entity.remove()
        }
        ShieldManager.release(player)
        TntManager.onLeave(player)
        TntManager.clearArea(arena.world, arena.bounds)
        InstantWall.clearArea(arena.world, arena.bounds)
        TrapManager.clearArea(arena.world, arena.bounds)
        Swapper.removeThrown(player)
        MaceManager.clear(player)
        SpearManager.clear(player)
        ItemRecharge.clear(player)
        if (player.isOnline) {
            player.inventory.clear()
            player.clearTitle()
        }
    }

    // ------------------------------------------------------------------
    // 段階
    // ------------------------------------------------------------------

    private fun goTo(index: Int) {
        cleanupStep()
        stepIndex = index
        counters.clear()
        runner = ScriptRunner(steps[index].script)
    }

    private fun nextStep() {
        if (stepIndex + 1 < steps.size) {
            goTo(stepIndex + 1)
        } else {
            complete()
        }
    }

    /** やられたときなど。その段階 (か retryFrom の段階) の最初からやり直す */
    fun retry() {
        val retryFrom = steps.getOrNull(stepIndex)?.retryFrom
        val index = retryFrom?.let { id -> steps.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: stepIndex
        goTo(index)
    }

    private fun cleanupStep() {
        dummies.forEach { dummy ->
            TntManager.onLeave(dummy.entity)
            TutorialManager.unregister(dummy.entity)
            dummy.remove()
        }
        // 倒れる演出の途中のダミーも消す
        stepEntities.filterIsInstance<Mannequin>().forEach(DummyDisguise::remove)
        dummies.clear()
        stepEntities.forEach(Entity::remove)
        stepEntities.clear()
        tickers.clear()
        TntManager.clearArea(arena.world, arena.bounds)
        InstantWall.clearArea(arena.world, arena.bounds)
        guide = null
    }

    private fun complete() {
        isCompleted = true
        TutorialManager.markCompleted(player, tutorial)
        bossBar.name(Component.translatable("rushcraft.tutorial.complete.bar", NamedTextColor.GREEN))
        bossBar.progress(1f)
        bossBar.color(BossBar.Color.GREEN)
        player.showTitle(
            Title.title(
                Component.translatable("rushcraft.tutorial.complete.title", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
                tutorial.displayName().color(NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500)),
            )
        )
        player.playSound(Sound.sound(org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())
        player.world.spawnParticle(Particle.TOTEM_OF_UNDYING, player.location.add(0.0, 1.0, 0.0), 60, 0.5, 0.8, 0.5, 0.4)
        cleanupStep()
        runner = ScriptRunner {
            wait(60)
            TutorialManager.finish(player)
        }
    }

    // ------------------------------------------------------------------
    // 毎 tick
    // ------------------------------------------------------------------

    fun tick() {
        ticks++
        pendingDown?.let {
            pendingDown = null
            down(it)
        }

        tour?.let { tour ->
            tour.tick()
            if (tour.isFinished) this.tour = null
        }
        if (tour == null && player.location.y < arena.voidY) onDown("rushcraft.tutorial.down.void")
        // 突進などで減った満腹度を戻す
        if (player.foodLevel < 20) player.foodLevel = 20

        dummies.removeIf { dummy ->
            if (dummy.isAlive && dummy.location.y < arena.voidY) {
                // 奈落に落とされた: 最後に攻撃したのがプレイヤーなら、その攻撃で倒したことにする
                onDummyDeath(dummy, dummy.lastHit ?: "other")
                TntManager.onLeave(dummy.entity)
                dummy.remove()
            }
            dummy.tick()
            if (dummy.isAlive) return@removeIf false
            TutorialManager.unregister(dummy.entity)
            // 倒れる演出が終わってから消す
            stepEntities += dummy.entity
            true
        }
        tickers.toList().forEach { it() }
        if (ticks % 4 == 0) drawGuide()

        val runner = runner ?: return
        runner.tick()
        if (runner.isFinished && this.runner === runner && !isCompleted) nextStep()
    }

    /** 目印に向かうパーティクルの線と、目印の光の柱 (このプレイヤーにだけ見える) */
    private fun drawGuide() {
        val target = guide ?: return
        if (tour != null || target.world != player.world) return
        val from = player.location.add(0.0, 0.3, 0.0).toVector()
        val line = target.toVector().subtract(from)
        val length = line.length()
        if (length > 1.5) {
            val step = line.normalize()
            val dust = Particle.DustOptions(Color.fromRGB(0xFFD54F), 0.9f)
            val offset = (ticks / 4 % 4) * 0.25
            var distance = 1.0 + offset
            while (distance < minOf(length, 14.0)) {
                val point = from.clone().add(step.clone().multiply(distance))
                player.spawnParticle(Particle.DUST, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0, dust)
                distance += 1.0
            }
        }
        for (i in 0 until 6) {
            player.spawnParticle(Particle.END_ROD, target.x, target.y + i * 0.5, target.z, 1, 0.05, 0.0, 0.05, 0.0)
        }
    }

    // ------------------------------------------------------------------
    // やられたとき
    // ------------------------------------------------------------------

    /** 倒された・奈落に落ちた (次の tick にやり直す。それまでのダメージは受けない) */
    fun onDown(reasonKey: String) {
        if (pendingDown == null && !isCompleted) pendingDown = reasonKey
    }

    val isDown: Boolean
        get() = pendingDown != null

    private fun down(reasonKey: String) {
        ShieldManager.release(player)
        TntManager.onLeave(player)
        MaceManager.clear(player)
        SpearManager.clear(player)
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.fireTicks = 0
        player.fallDistance = 0f
        player.velocity = org.bukkit.util.Vector()
        player.teleport(checkpoint)
        player.showTitle(
            Title.title(
                Component.translatable("rushcraft.tutorial.down.title", NamedTextColor.RED),
                Component.translatable(reasonKey, NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1500), Duration.ofMillis(400)),
            )
        )
        player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_PLAYER_HURT, Sound.Source.PLAYER, 1f, 0.8f), Sound.Emitter.self())
        content.onDown()
        retry()
    }

    // ------------------------------------------------------------------
    // イベント (TutorialListener から)
    // ------------------------------------------------------------------

    fun onDummyDeath(dummy: Dummy, cause: String) {
        if (!dummy.enemy) return
        add("kill")
        add("kill.$cause")
        player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, Sound.Source.PLAYER, 1f, 2f), Sound.Emitter.self())
    }

    fun onKitAction(event: KitActionEvent) {
        if (event.actor != player) return
        add("action.${event.action.name.lowercase()}")
    }

    fun dummyOf(entity: Entity): Dummy? = dummies.find { it.entity == entity }

    fun isCombatant(entity: LivingEntity): Boolean {
        if (entity == player) return tour == null && !isDown
        return dummyOf(entity)?.isAlive == true
    }

    fun sideOf(entity: LivingEntity): TutorialSide? {
        if (entity == player) return allySide
        val dummy = dummyOf(entity) ?: return null
        return if (dummy.enemy) enemySide else allySide
    }

    fun onBlockBreak(event: org.bukkit.event.block.BlockBreakEvent) = content.onBlockBreak(event)

    fun onBlockPlace(event: org.bukkit.event.block.BlockPlaceEvent) = content.onBlockPlace(event)

    fun onProjectileHit(event: org.bukkit.event.entity.ProjectileHitEvent) = content.onProjectileHit(event)

    // ------------------------------------------------------------------
    // 台本から使う部品
    // ------------------------------------------------------------------

    fun count(key: String): Int = counters[key] ?: 0

    fun add(key: String, amount: Int = 1) {
        counters[key] = count(key) + amount
    }

    fun render(component: Component): Component = GlobalTranslator.render(component, player.locale())

    /** チャットに短い説明を出す */
    fun say(key: String, vararg args: Component) {
        player.sendMessage(
            Component.text()
                .append(Component.translatable("rushcraft.tutorial.prefix", NamedTextColor.GOLD))
                .append(Component.space())
                .append(Component.translatable(key, NamedTextColor.WHITE, *args))
                .build()
        )
    }

    fun title(titleKey: String?, subtitleKey: String?, stayTicks: Int = 50) {
        player.showTitle(
            Title.title(
                titleKey?.let { Component.translatable(it, NamedTextColor.GOLD) } ?: Component.empty(),
                subtitleKey?.let { Component.translatable(it, NamedTextColor.WHITE) } ?: Component.empty(),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(stayTicks * 50L), Duration.ofMillis(400)),
            )
        )
    }

    fun actionBar(key: String, color: NamedTextColor = NamedTextColor.YELLOW) {
        player.sendActionBar(Component.translatable(key, color))
    }

    /** ボスバーに今の目標を出す */
    fun objective(key: String, vararg args: Component) {
        bossBar.name(Component.translatable(key, NamedTextColor.WHITE, *args))
        bossBar.progress(1f)
        bossBar.color(BossBar.Color.YELLOW)
    }

    /** ボスバーに今の目標と進み具合を出す */
    fun objective(key: String, current: Int, total: Int) {
        bossBar.name(
            Component.text()
                .append(Component.translatable(key, NamedTextColor.WHITE))
                .append(Component.text("  ${current.coerceAtMost(total)} / $total", NamedTextColor.YELLOW))
                .build()
        )
        bossBar.progress((current.toFloat() / total).coerceIn(0f, 1f))
        bossBar.color(BossBar.Color.BLUE)
    }

    /** お手本を見ている間のボスバー */
    fun watching(key: String) {
        bossBar.name(Component.translatable(key, NamedTextColor.AQUA))
        bossBar.progress(1f)
        bossBar.color(BossBar.Color.PURPLE)
    }

    /** できた！ (段階の終わり) */
    fun success() {
        player.showTitle(
            Title.title(
                Component.empty(),
                Component.translatable("rushcraft.tutorial.success", NamedTextColor.GREEN),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1000), Duration.ofMillis(300)),
            )
        )
        player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, Sound.Source.MASTER, 0.8f, 1.4f), Sound.Emitter.self())
    }

    /** 1 回できたときの小さな音 */
    fun ding(current: Int) {
        player.playSound(
            Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, (1.0 + current * 0.2).toFloat().coerceAtMost(2f)),
            Sound.Emitter.self(),
        )
    }

    fun playSound(sound: org.bukkit.Sound, pitch: Float = 1f, volume: Float = 1f) {
        player.playSound(Sound.sound(sound, Sound.Source.MASTER, volume, pitch), Sound.Emitter.self())
    }

    /** インベントリを空にして items と チュートリアルのメニューを配る */
    fun equip(items: List<Pair<Int, ItemStack>>) {
        val inventory = player.inventory
        inventory.clear()
        for ((slot, stack) in items) inventory.setItem(slot, stack)
        val used = items.map { it.first }.toSet()
        val menuSlot = (8 downTo 0).firstOrNull { it !in used } ?: 17
        inventory.setItem(menuSlot, KitManager.createUiItem(KitManager.UiItem.LEAVE_TUTORIAL))
    }

    /** 特殊アイテム (味方の色) */
    fun special(special: SpecialItem): List<Pair<Int, ItemStack>> = KitManager.createSpecialItems(special, allySide.color)

    fun teleport(location: Location) {
        player.velocity = org.bukkit.util.Vector()
        player.fallDistance = 0f
        player.teleport(location)
    }

    fun startTour(keyframes: List<CameraTour.Keyframe>): CameraTour {
        val tour = CameraTour(player, keyframes)
        this.tour = tour
        tour.start()
        return tour
    }

    /** 段階が終わるまでの敵のダミー */
    fun spawnEnemy(location: Location, health: Double = 20.0, nameKey: String = "rushcraft.tutorial.dummy.enemy"): Dummy {
        return spawnDummy(location, enemy = true, health, nameKey)
    }

    /** 段階が終わるまでのお手本 (倒されない) */
    fun spawnInstructor(location: Location): Dummy {
        return spawnDummy(location, enemy = false, 20.0, "rushcraft.tutorial.dummy.instructor").also {
            it.entity.isInvulnerable = true
        }
    }

    private fun spawnDummy(location: Location, enemy: Boolean, health: Double, nameKey: String): Dummy {
        val color = if (enemy) NamedTextColor.BLUE else NamedTextColor.GREEN
        val name = render(Component.translatable(nameKey, color))
        // 腕振りなどが見えるよう、クライアントにはプレイヤーとして送る (名前は頭の上の文字で出す)
        DummyDisguise.hideNames(player.scoreboard)
        val entity = location.world.spawn(location, Mannequin::class.java) { mannequin ->
            mannequin.isPersistent = false
            mannequin.isImmovable = false
            mannequin.customName(name)
            mannequin.isCustomNameVisible = false
            mannequin.description = null
            Dummy.setMaxHealth(mannequin, health)
            Dummy.dress(mannequin, (if (enemy) enemySide else allySide).color.dye.color)
            DummyDisguise.add(mannequin)
        }
        val dummy = Dummy(this, entity, enemy, name)
        dummies += dummy
        TutorialManager.register(entity, this)
        return dummy
    }

    /** 段階が終わると消える (arena = true なら会場が終わるまで残る) 浮かぶ文字 */
    fun label(location: Location, key: String, vararg args: Component, arena: Boolean = false, size: Float = 1f): TextDisplay {
        val display = location.world.spawn(location, TextDisplay::class.java) { text ->
            text.isPersistent = false
            text.text(render(Component.translatable(key, NamedTextColor.WHITE, *args)))
            text.billboard = Display.Billboard.CENTER
            text.isShadowed = true
            text.backgroundColor = Color.fromARGB(96, 0, 0, 0)
            text.alignment = TextDisplay.TextAlignment.CENTER
            text.transformation = text.transformation.apply { scale.set(size) }
        }
        if (arena) arenaEntities += display else stepEntities += display
        return display
    }

    /** 段階が終わるまで毎 tick 行う */
    fun everyTick(action: () -> Unit) {
        tickers += action
    }

    /** 段階が終わると消すエンティティとして覚える */
    fun track(entity: Entity) {
        stepEntities += entity
    }

    fun dummies(): List<Dummy> = dummies.toList()

    fun enemies(): List<Dummy> = dummies.filter { it.enemy && it.isAlive }
}
