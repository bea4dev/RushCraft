package com.github.bea4dev.rushCraft.tutorial.content

import com.github.bea4dev.rushCraft.kit.ShieldManager
import com.github.bea4dev.rushCraft.kit.SpecialItem
import com.github.bea4dev.rushCraft.tutorial.Dummy
import com.github.bea4dev.rushCraft.tutorial.TutorialSession
import com.github.bea4dev.rushCraft.tutorial.TutorialStep
import com.github.bea4dev.rushCraft.tutorial.wait
import com.github.bea4dev.rushCraft.tutorial.waitUntil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Material
import org.bukkit.entity.Arrow
import org.bukkit.inventory.ItemStack
import java.time.Duration

/**
 * 盾: ガード → 飛び道具のパリィ → 近接攻撃のパリィ → 投げナイフのパリィ。
 */
class ShieldTutorial(session: TutorialSession) : ItemTutorial(session, SpecialItem.SHIELD) {
    override val id = "shield"

    /** お手本に盾を構えさせる */
    private fun raiseShield(instructor: Dummy) {
        instructor.hold(null, session.special(special).first().second)
        instructor.raiseShield()
    }

    /** お手本のパリィ: 構えを解いて光らせる */
    private fun parry(instructor: Dummy) {
        instructor.lowerShield()
        ShieldManager.playParryEffects(instructor.entity)
    }

    override fun steps(): List<TutorialStep> = listOf(
        // 最初はガードだけ (パリィは後で説明する。先に話すと、防ぐ練習でパリィを試して混乱する)
        introStep("intro1"),

        // ガード
        practiceStep("guard", "guard", "action.guard", 3) {
            session.say(key("guard-hint"))
            // パリィで倒したり落としたりしても、また出てくる
            keepEnemy(facingPlayer(0.5, -6.5), health = 100.0) {
                it.hold(ItemStack(Material.BOW))
                it.brain = Brains.archer(player, interval = 45, draw = 20)
            }
        },

        // ここからパリィ
        step("parry-intro") {
            session.teleport(spawn)
            session.equip(emptyList())
            session.watching("rushcraft.tutorial.reading")
            player.showTitle(
                Title.title(
                    Component.translatable(key("parry-title"), NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
                    Component.translatable(key("parry-subtitle"), NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(500)),
                )
            )
            wait(30)
            session.say(key("parry-intro"))
            wait(60)
        },

        // 飛び道具のパリィ
        demoStep("demo-projectile", "demo-projectile") {
            val instructor = session.spawnInstructor(at(-2.5, 1.5, yaw = 180f))
            val archer = session.spawnEnemy(facingPlayer(-2.5, -9.5), health = 100.0)
            archer.hold(ItemStack(Material.BOW))
            raiseShield(instructor)
            session.label(at(-2.5, 3.0, 2.6), key("demo-projectile-label"))
            instructor.face(archer.entity)
            archer.face(instructor.entity)
            wait(20)
            repeat(2) {
                archer.drawBow()
                wait(20)
                archer.face(instructor.entity)
                val arrow = archer.shootArrow(instructor.entity.location.add(0.0, 1.2, 0.0), speed = PARRY_ARROW_SPEED)
                // 矢が目の前に来た瞬間に構えを解いて弾き返す
                waitUntil { !arrow.isValid || arrow.location.distance(instructor.entity.location.add(0.0, 1.2, 0.0)) < 2.6 }
                if (arrow.isValid) reflect(arrow, instructor)
                wait(30)
                raiseShield(instructor)
                wait(10)
            }
        },
        practiceStep("parry-projectile", "parry-projectile", "action.parry_projectile", 2) {
            session.say(key("parry-projectile-hint"))
            // 練習なので、試合よりゆっくり飛ぶ矢を遠くから射る (お手本よりは少し強く)
            keepEnemy(facingPlayer(0.5, -9.5), health = 100.0) {
                it.hold(ItemStack(Material.BOW))
                it.brain = Brains.archer(player, interval = 55, draw = 30, speed = PRACTICE_ARROW_SPEED)
            }
        },

        // 近接攻撃のパリィ
        demoStep("demo-melee", "demo-melee") {
            val instructor = session.spawnInstructor(at(-2.5, 1.5, yaw = 180f))
            val attacker = session.spawnEnemy(facingPlayer(-2.5, -4.5), health = 100.0)
            attacker.hold(ItemStack(Material.IRON_SWORD))
            raiseShield(instructor)
            session.label(at(-2.5, 3.0, 2.6), key("demo-melee-label"))
            wait(10)
            // 近づいてきて、殴る合図を出す
            waitUntil {
                instructor.face(attacker.entity)
                attacker.face(instructor.entity)
                attacker.walk(instructor.location.add(0.0, 0.0, -1.8), 0.15)
            }
            attacker.warn()
            wait(10)
            attacker.entity.swingMainHand()
            parry(instructor)
            ShieldManager.stun(attacker.entity, instructor.location)
            session.label(attacker.location.add(0.0, 2.6, 0.0), key("demo-stun-label"))
            wait(50)
        },
        practiceStep("parry-melee", "parry-melee", "action.parry_melee", 2) {
            session.say(key("parry-melee-hint"))
            keepEnemy(facingPlayer(0.5, -6.5), health = 100.0) {
                it.hold(ItemStack(Material.IRON_SWORD))
                it.brain = Brains.melee(player, damage = 2.0, interval = 40, windup = 14)
            }
        },

        // 投げナイフのパリィ (当たると即死)
        practiceStep("parry-knife", "parry-knife", "action.parry_projectile", 1) {
            session.say(key("parry-knife-hint"))
            keepEnemy(facingPlayer(0.5, -8.5), health = 20.0) {
                it.hold(ItemStack(Material.FLINT))
                it.brain = Brains.knifeThrower(player, interval = 70, windup = 25)
            }
        },
        step("finish") {
            session.equip(emptyList())
            session.say(key("finish"))
            session.actionBar(key("finish-bar"), NamedTextColor.GOLD)
            wait(60)
        },
    )

    companion object {
        /** パリィの練習で飛んでくる矢の速さ (1 tick あたりのブロック数) */
        private const val PARRY_ARROW_SPEED = 1.0

        /** パリィの練習で敵が射る矢の速さ (お手本より少し強い) */
        private const val PRACTICE_ARROW_SPEED = 1.3
    }

    private fun reflect(arrow: Arrow, instructor: Dummy) {
        arrow.velocity = arrow.velocity.multiply(-1)
        arrow.shooter = instructor.entity
        parry(instructor)
    }
}
