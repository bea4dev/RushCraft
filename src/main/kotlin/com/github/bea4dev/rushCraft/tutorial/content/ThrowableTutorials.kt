package com.github.bea4dev.rushCraft.tutorial.content

import com.github.bea4dev.rushCraft.kit.SpecialItem
import com.github.bea4dev.rushCraft.kit.ThrowingKnife
import com.github.bea4dev.rushCraft.kit.TntManager
import com.github.bea4dev.rushCraft.tutorial.Dummy
import com.github.bea4dev.rushCraft.tutorial.TutorialSession
import com.github.bea4dev.rushCraft.tutorial.TutorialStep
import com.github.bea4dev.rushCraft.tutorial.wait
import com.github.bea4dev.rushCraft.tutorial.waitUntil
import org.bukkit.Material
import org.bukkit.entity.Trident
import org.bukkit.inventory.ItemStack
import kotlin.math.sqrt

/**
 * スワッパー: 向こうの島の敵と入れ替わって渡り、もう一度入れ替わって戻る。
 */
class SwapperTutorial(session: TutorialSession) : ItemTutorial(session, SpecialItem.SWAPPER) {
    override val id = "swapper"

    /** 島の中心 (練習場の奥、奈落を挟んだ先) */
    private val islandX = 0
    private val islandZ = -21

    override fun buildExtra() {
        arena.island(islandX, 0, islandZ, 4)
        arena.set(islandX, -1, islandZ, Material.GOLD_BLOCK)
    }

    private fun onIsland(): Boolean {
        val location = player.location
        val dx = location.x - arena.origin.x - (islandX + 0.5)
        val dz = location.z - arena.origin.z - (islandZ + 0.5)
        return sqrt(dx * dx + dz * dz) < 5.5 && player.isOnGround
    }

    private fun onField(): Boolean = player.location.z - arena.origin.z > -12.5 && player.isOnGround

    /** お手本がスワッパーを投げる (少し上を狙う) */
    private fun throwSwapper(thrower: Dummy, target: Dummy): Trident {
        thrower.face(target.entity)
        thrower.entity.swingMainHand()
        val from = thrower.entity.eyeLocation
        val delta = target.entity.location.add(0.0, 1.0, 0.0).toVector().subtract(from.toVector())
        val horizontal = sqrt(delta.x * delta.x + delta.z * delta.z)
        val speed = 2.2
        val ticks = horizontal / speed
        delta.y += 0.5 * 0.05 * ticks * ticks
        val trident = thrower.entity.launchProjectile(Trident::class.java, delta.normalize().multiply(speed))
        trident.itemStack = session.special(special).first().second
        session.track(trident)
        return trident
    }

    override fun steps(): List<TutorialStep> = listOf(
        introStep("intro1", "intro2"),
        demoStep("demo", "demo") {
            val instructor = session.spawnInstructor(at(-1.5, -9.5, yaw = 180f))
            instructor.hold(session.special(special).first().second)
            val enemy = session.spawnEnemy(facingPlayer(islandX - 0.5, islandZ + 0.5), health = 100.0)
            session.label(at(islandX + 0.5, islandZ + 2.0, 3.0), key("demo-label"))
            wait(30)
            val trident = throwSwapper(instructor, enemy)
            waitUntil { !trident.isValid || trident.isInBlock || trident.hasDealtDamage() || instructor.ticks > 120 }
            trident.remove()
            wait(40)
        },
        practiceStep("practice1", "practice1", "action.swap", 1) {
            session.guide = at(islandX + 0.5, islandZ + 0.5, 0.2)
            keepEnemy(facingPlayer(islandX + 0.5, islandZ + 0.5), health = 1000.0) { it.brain = Brains.watch(player) }
            waitUntil { session.count("action.swap") >= 1 && onIsland() }
            session.guide = null
        },
        step("practice2", retryFrom = "practice1") {
            // 島に渡った状態から、もう一度入れ替わって戻る
            session.title("rushcraft.tutorial.try", key("practice2"))
            session.say(key("practice2"))
            session.say(key("practice2-hint"))
            keepEnemy(facingPlayer(0.5, 4.5), health = 1000.0) { it.brain = Brains.watch(player) }
            session.guide = spawn
            progress("practice2", "action.swap", 1)
            waitUntil { onField() }
            session.guide = null
            session.success()
            wait(40)
        },
    )
}

/**
 * 投げナイフ: 当たった敵を一撃で倒す。
 */
class KnifeTutorial(session: TutorialSession) : ItemTutorial(session, SpecialItem.THROWING_KNIFE) {
    override val id = "knife"

    override fun steps(): List<TutorialStep> = listOf(
        introStep("intro1", "intro2"),
        demoStep("demo", "demo") {
            val instructor = session.spawnInstructor(at(-2.5, 2.5, yaw = 180f))
            instructor.hold(ItemStack(Material.FLINT))
            val enemy = session.spawnEnemy(facingPlayer(-2.5, -6.5), health = 20.0)
            session.label(at(-2.5, -5.0, 2.8), key("demo-label"))
            instructor.face(enemy.entity.location.add(0.0, 1.2, 0.0))
            wait(30)
            instructor.face(enemy.entity.location.add(0.0, 1.2, 0.0))
            instructor.entity.swingMainHand()
            ThrowingKnife.throwKnife(instructor.entity)
            waitUntil { !enemy.isAlive || instructor.ticks > 120 }
        },
        practiceStep("practice1", "practice1", "kill.knife", 3) {
            session.spawnEnemy(facingPlayer(-4.5, -3.5)).brain = Brains.watch(player)
            session.spawnEnemy(facingPlayer(0.5, -8.5)).brain = Brains.watch(player)
            session.spawnEnemy(facingPlayer(5.5, -5.5)).brain = Brains.watch(player)
        },
        practiceStep("practice2", "practice2", "kill.knife", 2) {
            session.say(key("practice2-hint"))
            keepEnemy(facingPlayer(-6.5, -6.5), health = 20.0) {
                it.brain = Brains.strafe(at(-7.5, -6.5), at(7.5, -6.5), speed = 0.15, player = player)
            }
        },
        step("finish") {
            session.equip(emptyList())
            session.say(key("finish"))
            wait(60)
        },
    )
}

/**
 * 粘着 TNT: 敵に投げつける → 押し付けられた TNT を投げ返す。
 */
class TntTutorial(session: TutorialSession) : ItemTutorial(session, SpecialItem.TNT) {
    override val id = "tnt"

    override fun steps(): List<TutorialStep> = listOf(
        introStep("intro1", "intro2", "intro3"),
        demoStep("demo", "demo") {
            val instructor = session.spawnInstructor(at(-2.5, 2.5, yaw = 180f))
            val enemy = session.spawnEnemy(facingPlayer(-2.5, -3.5), health = 20.0)
            enemy.brain = Brains.watch(player)
            session.label(at(-2.5, -2.0, 2.8), key("demo-label"))
            instructor.face(enemy.entity)
            wait(20)
            TntManager.spawnHeld(instructor.entity, fuse = 90)
            wait(25)
            instructor.face(enemy.entity.location.add(0.0, 1.5, 0.0))
            instructor.entity.swingMainHand()
            TntManager.throwHeld(instructor.entity)
            waitUntil { !enemy.isAlive || instructor.ticks > 200 }
            TntManager.onLeave(instructor.entity)
        },
        practiceStep("practice1", "practice1", "kill.tnt", 2) {
            session.say(key("practice1-hint"))
            session.spawnEnemy(facingPlayer(-3.5, -2.5)).brain = Brains.watch(player)
            session.spawnEnemy(facingPlayer(4.5, -5.5)).brain = Brains.strafe(at(1.5, -5.5), at(7.5, -5.5), speed = 0.1, player = player)
        },
        step("practice2") {
            // 敵が投げてくる TNT を投げ返す
            session.teleport(spawn)
            session.equip(emptyList())
            session.title("rushcraft.tutorial.try", key("practice2"))
            session.say(key("practice2"))
            session.say(key("practice2-hint"))
            session.objective(key("practice2"), 0, 1)
            // 着火した敵が自分の TNT で爆死すれば成功 (自爆扱いなのでキルにはならない)
            while (session.count("kill.tnt-self") < 1) {
                val enemy = session.spawnEnemy(facingPlayer(0.5, 1.5), health = 20.0)
                enemy.brain = Brains.watch(player)
                wait(30)
                TntManager.spawnHeld(enemy.entity, fuse = 150)
                wait(30)
                enemy.face(player.eyeLocation.subtract(0.0, 0.4, 0.0))
                enemy.entity.swingMainHand()
                TntManager.throwHeld(enemy.entity)
                waitUntil { !enemy.isAlive }
                wait(20)
                enemy.remove()
            }
            session.objective(key("practice2"), 1, 1)
            session.success()
            wait(40)
        },
    )

}
