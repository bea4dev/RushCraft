package com.github.bea4dev.rushCraft.tutorial.content

import com.github.bea4dev.rushCraft.kit.InstantWall
import com.github.bea4dev.rushCraft.kit.KitManager
import com.github.bea4dev.rushCraft.kit.SpearManager
import com.github.bea4dev.rushCraft.kit.SpecialItem
import com.github.bea4dev.rushCraft.kit.TrapManager
import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.tutorial.Dummy
import com.github.bea4dev.rushCraft.tutorial.TutorialSession
import com.github.bea4dev.rushCraft.tutorial.TutorialSide
import com.github.bea4dev.rushCraft.tutorial.TutorialStep
import com.github.bea4dev.rushCraft.tutorial.wait
import com.github.bea4dev.rushCraft.tutorial.waitUntil
import net.kyori.adventure.sound.Sound
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.BlockFace
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Arrow
import org.bukkit.entity.Pose
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector

/** 練習場の範囲 (相対座標) */
private const val FIELD_MIN_X = -10
private const val FIELD_MAX_X = 10
private const val FIELD_MIN_Z = -12
private const val FIELD_MAX_Z = 10

/**
 * 即席壁: 矢を防ぐ → 敵の足元に作って打ち上げる。
 */
class WallTutorial(session: TutorialSession) : ItemTutorial(session, SpecialItem.INSTANT_WALL) {
    override val id = "wall"

    private val glass: Material = InstantWall.glassOf(TutorialSide.ALLY_COLOR)

    /** 前の段階で作った壁を消す */
    private fun clearWalls() {
        arena.clear(FIELD_MIN_X, 0, FIELD_MIN_Z, FIELD_MAX_X, 4, FIELD_MAX_Z, glass)
    }

    override fun onBlockPlace(event: BlockPlaceEvent): Boolean {
        // 即席壁の設置判定 (擬似的な BlockPlaceEvent)
        return event.itemInHand.type == glass
    }

    override fun onProjectileHit(event: ProjectileHitEvent) {
        val arrow = event.entity as? Arrow ?: return
        val block = event.hitBlock ?: return
        val shooter = arrow.shooter as? org.bukkit.entity.LivingEntity ?: return
        if (block.type == glass && session.dummyOf(shooter)?.enemy == true) session.add("wall.blocked")
    }

    override fun steps(): List<TutorialStep> = listOf(
        introStep("intro1", "intro2"),
        demoStep("demo", "demo") {
            clearWalls()
            val instructor = session.spawnInstructor(at(-2.5, 1.5, yaw = 180f))
            instructor.hold(ItemStack(glass))
            val archer = session.spawnEnemy(facingPlayer(-2.5, -8.5), health = 100.0)
            archer.hold(ItemStack(Material.BOW))
            session.label(at(-2.5, 3.0, 2.6), key("demo-label"))
            instructor.face(archer.entity)
            archer.face(instructor.entity)
            wait(20)
            // 1 本目は当たる
            archer.drawBow()
            wait(20)
            archer.shootArrow(instructor.entity.location.add(0.0, 1.2, 0.0))
            wait(30)
            // 目の前に壁を作る
            instructor.face(arena.center(-3, 0, -1))
            instructor.entity.swingMainHand()
            InstantWall.build(instructor.entity, arena.block(-3, 0, -1))
            wait(20)
            repeat(2) {
                archer.drawBow()
                wait(20)
                archer.shootArrow(instructor.entity.location.add(0.0, 1.2, 0.0))
                wait(25)
            }
            clearWalls()
        },
        practiceStep("practice1", "practice1", "wall.blocked", 2) {
            clearWalls()
            session.say(key("practice1-hint"))
            keepEnemy(facingPlayer(0.5, -9.5), health = 100.0) {
                it.hold(ItemStack(Material.BOW))
                it.brain = Brains.archer(player, interval = 40, draw = 20, damage = 2.0)
            }
        },
        practiceStep("practice2", "practice2", "action.wall_launch", 1) {
            clearWalls()
            session.say(key("practice2-hint"))
            keepEnemy(facingPlayer(0.5, -2.5), health = 100.0) { it.brain = Brains.watch(player) }
        },
    )
}

/**
 * メイス: チャージして高台に飛び乗る → 落ちながら敵を叩く。
 */
class MaceTutorial(session: TutorialSession) : ItemTutorial(session, SpecialItem.MACE) {
    override val id = "mace"

    /** 高台 (相対座標)。上面が y = PLATFORM_TOP */
    private val platformX = -3..2
    private val platformZ = -6..-1
    private val platformTop = 4

    override fun buildExtra() {
        for (x in platformX) for (z in platformZ) {
            arena.fill(x, 0, z, x, platformTop - 2, z, Material.STONE_BRICKS)
            arena.set(x, platformTop - 1, z, Material.POLISHED_ANDESITE)
        }
        for (x in platformX) for (z in platformZ) {
            if (x == platformX.first || x == platformX.last || z == platformZ.first || z == platformZ.last) {
                arena.set(x, platformTop - 1, z, Material.GOLD_BLOCK)
            }
        }
    }

    private fun onPlatform(): Boolean {
        val location = player.location
        val x = location.x - arena.origin.x
        val y = location.y - arena.origin.y
        val z = location.z - arena.origin.z
        return player.isOnGround && y >= platformTop - 0.1 &&
            x >= platformX.first && x <= platformX.last + 1 && z >= platformZ.first && z <= platformZ.last + 1
    }

    override fun steps(): List<TutorialStep> = listOf(
        introStep("intro1", "intro2"),
        // お手本は右側で行うので、そちらを向かせておく
        demoStep("demo", "demo", look = at(7.5, -0.5, 1.5)) {
            val start = at(7.5, 5.5, yaw = 180f)
            val instructor = session.spawnInstructor(start)
            instructor.hold(ItemStack(Material.MACE))
            val enemy = session.spawnEnemy(facingPlayer(7.5, -6.5), health = 20.0)
            enemy.brain = Brains.watch(player)
            session.label(at(7.5, -5.0, 2.8), key("demo-label"))
            instructor.face(enemy.entity)
            wait(20)
            // しゃがんでチャージ
            instructor.entity.setPose(Pose.SNEAKING, true)
            for (tick in 0 until 25) {
                val ring = instructor.location.add(0.0, 0.1 + tick * 0.06, 0.0)
                instructor.entity.world.spawnParticle(Particle.DUST, ring, 6, 0.4, 0.0, 0.4, 0.0, Particle.DustOptions(Color.ORANGE, 1f))
                wait(1)
            }
            instructor.entity.world.playSound(
                Sound.sound(org.bukkit.Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, Sound.Source.PLAYER, 1f, 2f),
                instructor.entity.x, instructor.entity.y, instructor.entity.z,
            )
            wait(10)
            instructor.entity.setPose(Pose.STANDING, false)
            leap(instructor, enemy.location)
            // 叩きつけ
            val source = DamageSource.builder(DamageType.MACE_SMASH).withCausingEntity(instructor.entity).withDirectEntity(instructor.entity).build()
            instructor.entity.swingMainHand()
            if (enemy.isAlive) enemy.entity.damage(40.0, source)
            val world = instructor.entity.world
            val impact = instructor.location
            world.spawnParticle(Particle.EXPLOSION, impact, 2, 0.5, 0.1, 0.5, 0.0)
            world.spawnParticle(Particle.BLOCK, impact, 60, 1.2, 0.1, 1.2, 0.2, Material.GRASS_BLOCK.createBlockData())
            world.playSound(Sound.sound(org.bukkit.Sound.ITEM_MACE_SMASH_GROUND_HEAVY, Sound.Source.PLAYER, 1f, 1f), impact.x, impact.y, impact.z)
            wait(40)
        },
        practiceStep("practice1", "practice1", "mace.platform", 1) {
            session.say(key("practice1-hint"))
            session.guide = at(-0.5, -3.5, platformTop + 0.2)
            waitUntil {
                if (onPlatform()) session.add("mace.platform")
                session.count("mace.platform") > 0
            }
            session.guide = null
        },
        practiceStep("practice2", "practice2", "hit.mace_smash", 1) {
            session.say(key("practice2-hint"))
            for (x in listOf(-4.5, 3.5)) {
                keepEnemy(facingPlayer(x, -3.5), health = 12.0) { it.brain = Brains.watch(player) }
            }
        },
    )

    /**
     * お手本の飛び上がり。高く跳んで、落ち始めてから速く落ちて target に着地する (毎 tick 動かす)。
     */
    private suspend fun SequenceScope<com.github.bea4dev.rushCraft.tutorial.TutorialWait>.leap(dummy: Dummy, target: Location) {
        val start = dummy.location
        val entity = dummy.entity
        entity.setGravity(false)
        entity.world.playSound(Sound.sound(org.bukkit.Sound.ENTITY_BREEZE_JUMP, Sound.Source.PLAYER, 1f, 1f), start.x, start.y, start.z)
        val upTicks = 16
        val downTicks = 7
        val apex = 9.0
        val horizontal = target.toVector().subtract(start.toVector()).setY(0)
        for (tick in 1..upTicks + downTicks) {
            val position = if (tick <= upTicks) {
                val t = tick.toDouble() / upTicks
                start.clone().add(horizontal.clone().multiply(0.7 * t)).add(0.0, apex * (1 - (1 - t) * (1 - t)), 0.0)
            } else {
                val t = (tick - upTicks).toDouble() / downTicks
                if (tick == upTicks + 1) {
                    entity.world.playSound(Sound.sound(org.bukkit.Sound.ENTITY_BREEZE_WIND_BURST, Sound.Source.PLAYER, 1f, 0.8f), entity.x, entity.y, entity.z)
                }
                start.clone().add(horizontal.clone().multiply(0.7 + 0.3 * t)).add(0.0, apex * (1 - t * t), 0.0)
            }
            position.yaw = start.yaw
            entity.teleport(position)
            entity.velocity = Vector()
            wait(1)
        }
        entity.setGravity(true)
    }
}

/**
 * 槍: 突進して離れた敵に攻撃を当てる。
 */
class SpearTutorial(session: TutorialSession) : ItemTutorial(session, SpecialItem.SPEAR) {
    override val id = "spear"

    override fun steps(): List<TutorialStep> = listOf(
        introStep("intro1", "intro2"),
        demoStep("demo", "demo") {
            val instructor = session.spawnInstructor(at(-2.5, 1.5, yaw = 180f))
            instructor.hold(session.special(special).first().second)
            val enemy = session.spawnEnemy(facingPlayer(-2.5, -2.5), health = 6.0)
            session.label(at(-2.5, -1.0, 2.8), key("demo-label"))
            instructor.face(enemy.entity)
            wait(30)
            // 突進して、その後しばらく周りの敵に当たる
            instructor.entity.swingMainHand()
            instructor.entity.velocity = Vector(0.0, 0.15, -1.25)
            instructor.entity.world.playSound(
                Sound.sound(org.bukkit.Sound.ITEM_SPEAR_LUNGE_2, Sound.Source.PLAYER, 1f, 1f),
                instructor.entity.x, instructor.entity.y, instructor.entity.z,
            )
            SpearManager.activate(instructor.entity)
            waitUntil { !enemy.isAlive || instructor.ticks > 100 }
        },
        practiceStep("practice1", "practice1", "kill", 3) {
            session.say(key("practice1-hint"))
            session.spawnEnemy(facingPlayer(-4.5, 1.5), health = 6.0).brain = Brains.watch(player)
            session.spawnEnemy(facingPlayer(0.5, -1.5), health = 6.0).brain = Brains.watch(player)
            session.spawnEnemy(facingPlayer(5.5, 1.5), health = 6.0).brain = Brains.watch(player)
        },
        practiceStep("practice2", "practice2", "kill", 2) {
            session.say(key("practice2-hint"))
            keepEnemy(facingPlayer(-5.5, -3.5), health = 6.0) {
                it.brain = Brains.strafe(at(-6.5, -3.5), at(6.5, -3.5), speed = 0.13, player = player)
            }
        },
    )
}

/**
 * トラップ: 敵の通り道に置く → 自分で踏んでも爆発しないことを確かめる。
 */
class TrapTutorial(session: TutorialSession) : ItemTutorial(session, SpecialItem.TRAP) {
    override val id = "trap"

    private val plate: Material
        get() = MatchManager.match?.map?.kit?.trapPlateMaterial() ?: Material.STONE_PRESSURE_PLATE

    private val pathZ = -4

    override fun buildExtra() {
        // 敵の通り道
        arena.fill(-9, -1, pathZ - 1, 9, -1, pathZ + 1, Material.POLISHED_ANDESITE)
        arena.fill(-9, 0, pathZ - 2, 9, 0, pathZ - 2, Material.OAK_FENCE)
        for (x in listOf(-9, 9)) arena.facing(x, 0, pathZ - 2, Material.OAK_FENCE, BlockFace.NORTH)
    }

    private fun patrolPoints(): List<Location> = listOf(at(-8.5, pathZ + 0.5), at(8.5, pathZ + 0.5))

    override fun onBlockPlace(event: BlockPlaceEvent): Boolean {
        if (KitManager.specialOf(event.itemInHand) != SpecialItem.TRAP) return false
        val relative = arena.relative(event.blockPlaced)
        return relative.blockY == 0 && relative.blockX in FIELD_MIN_X + 1 until FIELD_MAX_X &&
            relative.blockZ in FIELD_MIN_Z + 1 until FIELD_MAX_Z
    }

    override fun onBlockBreak(event: BlockBreakEvent): Boolean = TrapManager.isTrap(event.block)

    /** 前の段階で置いたトラップを消す */
    private fun clearTraps() {
        arena.clear(FIELD_MIN_X, 0, FIELD_MIN_Z, FIELD_MAX_X, 0, FIELD_MAX_Z, plate)
        TrapManager.clearArea(arena.world, arena.bounds)
    }

    /** 置き切ったら配り直す (5 個しか持てないので) */
    private fun refillTraps() {
        session.everyTick {
            if (!player.inventory.contains(plate)) session.special(special).forEach { (slot, stack) -> player.inventory.setItem(slot, stack) }
        }
    }

    override fun steps(): List<TutorialStep> = listOf(
        introStep("intro1", "intro2"),
        demoStep("demo", "demo") {
            clearTraps()
            val instructor = session.spawnInstructor(at(0.5, 0.5, yaw = 180f))
            instructor.hold(ItemStack(plate))
            session.label(at(0.5, pathZ + 2.0, 1.2), key("demo-path-label"))
            wait(20)
            instructor.face(arena.center(0, 0, pathZ))
            instructor.entity.swingMainHand()
            arena.set(0, 0, pathZ, plate)
            TrapManager.place(instructor.entity, arena.block(0, 0, pathZ))
            instructor.entity.world.playSound(
                Sound.sound(org.bukkit.Sound.BLOCK_STONE_PLACE, Sound.Source.BLOCK, 1f, 1f),
                instructor.entity.x, instructor.entity.y, instructor.entity.z,
            )
            wait(20)
            val enemy = session.spawnEnemy(at(-8.5, pathZ + 0.5, yaw = -90f), health = 20.0)
            enemy.brain = Brains.patrol(patrolPoints(), speed = 0.12)
            waitUntil { !enemy.isAlive || enemy.ticks > 200 }
            clearTraps()
        },
        practiceStep("practice1", "practice1", "kill.trap", 2) {
            clearTraps()
            refillTraps()
            session.say(key("practice1-hint"))
            keepEnemy(at(-8.5, pathZ + 0.5, yaw = -90f), health = 20.0, delay = 40) {
                it.brain = Brains.patrol(patrolPoints(), speed = 0.12)
            }
        },
        practiceStep("practice2", "practice2", "trap.stepped", 1) {
            session.say(key("practice2-hint"))
        },
    )
}
