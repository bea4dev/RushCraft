package com.github.bea4dev.rushCraft.tutorial.content

import com.github.bea4dev.rushCraft.kit.SpecialItem
import com.github.bea4dev.rushCraft.tutorial.TutorialSession
import com.github.bea4dev.rushCraft.tutorial.TutorialStep
import com.github.bea4dev.rushCraft.tutorial.wait
import com.github.bea4dev.rushCraft.tutorial.waitUntil
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * クラシックな鉄剣: クールダウンが無いので連打できる。
 */
class SwordTutorial(session: TutorialSession) : ItemTutorial(session, SpecialItem.CLASSIC_SWORD) {
    override val id = "sword"

    override fun steps(): List<TutorialStep> = listOf(
        introStep("intro1", "intro2"),
        demoStep("demo", "demo") {
            val instructor = session.spawnInstructor(at(-2.5, 1.5, yaw = 180f))
            instructor.hold(session.special(special).first().second)
            val enemy = session.spawnEnemy(facingPlayer(-2.5, -3.5), health = 20.0)
            session.label(at(-2.5, -2.0, 2.8), key("demo-label"))
            wait(20)
            // 近づいて連打する (無敵時間があるので 0.5 秒ごと)
            var hits = 0
            waitUntil {
                if (!enemy.isAlive) return@waitUntil true
                if (instructor.distanceTo(enemy.entity) > 2.2) {
                    instructor.walk(enemy.location, 0.2)
                } else {
                    instructor.stop()
                    if (instructor.ticks % 10 == 0) {
                        instructor.entity.swingMainHand()
                        enemy.entity.damage(6.0, instructor.entity)
                        hits++
                    }
                }
                instructor.face(enemy.entity)
                hits > 8
            }
        },
        practiceStep("practice1", "practice1", "kill", 3) {
            session.guide = null
            session.spawnEnemy(facingPlayer(-4.5, -2.5)).brain = Brains.watch(player)
            session.spawnEnemy(facingPlayer(0.5, -5.5)).brain = Brains.watch(player)
            session.spawnEnemy(facingPlayer(4.5, -2.5)).brain = Brains.strafe(at(2.5, -2.5), at(7.5, -2.5), player = player)
        },
        practiceStep("practice2", "practice2", "kill", 2) {
            for (x in listOf(-3.5, 3.5)) {
                session.spawnEnemy(facingPlayer(x, -6.5), health = 16.0).brain = Brains.melee(player, damage = 2.0, interval = 30)
            }
        },
    )
}

/**
 * クラシックな弓: 遠くの敵を狙い撃つ。
 */
class BowTutorial(session: TutorialSession) : ItemTutorial(session, SpecialItem.CLASSIC_BOW) {
    override val id = "bow"

    override fun buildExtra() {
        // 奥に浮かぶ的の台
        for ((x, z, height) in listOf(Triple(-6, -17, 2), Triple(0, -21, 4), Triple(6, -17, 2))) {
            arena.fill(x - 1, height - 1, z - 1, x + 1, height - 1, z + 1, Material.STONE_BRICKS)
            arena.set(x, height - 2, z, Material.STONE_BRICK_WALL)
        }
        // 動く的の通り道
        arena.fill(-6, 0, -27, 6, 0, -26, Material.STONE_BRICKS)
        arena.fill(-6, 1, -28, 6, 1, -28, Material.STONE_BRICK_WALL)
    }

    private fun bow(): List<Pair<Int, ItemStack>> = session.special(special).map { (slot, stack) ->
        if (stack.type == Material.ARROW) slot to stack.clone().also { it.amount = 64 } else slot to stack
    }

    /** 矢が少なくなったら足す */
    private fun refillArrows() {
        session.everyTick {
            val inventory = player.inventory
            if (!inventory.contains(Material.ARROW, 16)) inventory.addItem(ItemStack(Material.ARROW, 32))
        }
    }

    override fun steps(): List<TutorialStep> = listOf(
        introStep("intro1", "intro2"),
        demoStep("demo", "demo") {
            val instructor = session.spawnInstructor(at(-2.5, 3.5, yaw = 180f))
            instructor.hold(ItemStack(Material.BOW))
            val target = session.spawnEnemy(facingPlayer(-5.5, -16.5, 2.0), health = 4.0)
            session.label(at(-5.5, -15.0, 4.8), key("demo-label"))
            instructor.face(target.entity)
            wait(20)
            instructor.drawBow()
            wait(25)
            instructor.face(target.entity)
            instructor.shootArrow(target.entity.location.add(0.0, 1.0, 0.0), speed = 2.5, damage = 2.0)
            waitUntil { !target.isAlive || instructor.ticks > 140 }
        },
        practiceStep("practice1", "practice1", "kill.arrow", 3, items = ::bow) {
            refillArrows()
            session.spawnEnemy(facingPlayer(-5.5, -16.5, 2.0), health = 6.0).brain = Brains.watch(player)
            session.spawnEnemy(facingPlayer(0.5, -20.5, 4.0), health = 6.0).brain = Brains.watch(player)
            session.spawnEnemy(facingPlayer(6.5, -16.5, 2.0), health = 6.0).brain = Brains.watch(player)
        },
        practiceStep("practice2", "practice2", "kill.arrow", 2, items = ::bow) {
            refillArrows()
            keepEnemy(facingPlayer(0.5, -26.5, 1.0), health = 6.0) {
                it.brain = Brains.strafe(at(-5.5, -26.5, 1.0), at(6.5, -26.5, 1.0), speed = 0.12, player = player)
            }
        },
    )
}
