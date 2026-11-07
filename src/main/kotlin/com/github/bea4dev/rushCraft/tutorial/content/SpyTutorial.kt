package com.github.bea4dev.rushCraft.tutorial.content

import com.github.bea4dev.rushCraft.kit.InvisibleSpy
import com.github.bea4dev.rushCraft.kit.SpecialItem
import com.github.bea4dev.rushCraft.tutorial.TutorialSession
import com.github.bea4dev.rushCraft.tutorial.TutorialSide
import com.github.bea4dev.rushCraft.tutorial.TutorialStep
import com.github.bea4dev.rushCraft.tutorial.wait
import com.github.bea4dev.rushCraft.tutorial.waitUntil
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta

/**
 * 透明スパイ: 透明になって見張りの横をすり抜ける → 透明のまま近づいて倒す。
 *
 * 練習場の手前 (z = 3) に線を引き、そこから先は見張りの範囲にする。奥 (z = -10..-8) の金のブロックがゴール。
 */
class SpyTutorial(session: TutorialSession) : ItemTutorial(session, SpecialItem.INVISIBLE_SPY) {
    override val id = "spy"

    override fun buildExtra() {
        // 見張りの範囲の線とゴール
        arena.fill(-9, -1, LINE_Z, 9, -1, LINE_Z, Material.WHITE_CONCRETE)
        arena.fill(-1, -1, -10, 1, -1, -8, Material.GOLD_BLOCK)
    }

    private val goal: Location
        get() = at(0.5, -8.5)

    private fun relativeZ(): Double = player.location.z - arena.origin.z

    /** 味方の色の革防具 (透明になると外れることがわかるように着せる) */
    private fun armor(): List<Pair<Int, ItemStack>> {
        fun leather(material: Material) = ItemStack(material).also { stack ->
            stack.editMeta(LeatherArmorMeta::class.java) { it.setColor(TutorialSide.ALLY_COLOR.dye.color) }
        }
        return listOf(
            36 to leather(Material.LEATHER_BOOTS),
            37 to leather(Material.LEATHER_LEGGINGS),
            38 to leather(Material.LEATHER_CHESTPLATE),
            39 to leather(Material.LEATHER_HELMET),
        )
    }

    override fun steps(): List<TutorialStep> = listOf(
        introStep("intro1", "intro2", "intro3"),
        demoStep("demo", "demo") {
            val instructor = session.spawnInstructor(at(-6.5, 5.5, yaw = 180f))
            instructor.hold(ItemStack(Material.AMETHYST_SHARD))
            val guard = session.spawnEnemy(facingPlayer(3.5, -3.5))
            guard.brain = Brains.watch(player)
            wait(30)
            instructor.entity.swingMainHand()
            instructor.hold(null)
            InvisibleSpy.cloak(instructor.entity)
            session.label(at(-6.5, -2.0, 2.4), key("demo-sneak-label"))

            // 見張りから離れたまま奥へ
            val far = at(-6.5, -7.5)
            instructor.brain = { it.walk(far, 0.2) }
            waitUntil { instructor.location.distanceSquared(far) < 0.5 || instructor.ticks > 200 }
            wait(10)

            // 近づきすぎるとバレる
            session.say(key("demo2"))
            instructor.brain = { it.walk(guard.location, 0.15) }
            waitUntil { !InvisibleSpy.isInvisible(instructor.entity) || instructor.ticks > 400 }
            instructor.brain = { it.face(guard.entity) }
            session.label(guard.location.add(0.0, 2.7, 0.0), key("demo-revealed-label"))
            wait(50)
        },
        step("practice1") {
            session.teleport(spawn)
            session.equip(session.special(special) + armor())
            session.title("rushcraft.tutorial.try", key("practice1"))
            session.say(key("practice1"))
            session.say(key("practice1-hint"))
            session.objective(key("practice1"))
            session.label(at(0.5, LINE_Z + 0.5, 1.0), key("line-label"))
            session.label(goal.clone().add(0.0, 1.5, 0.0), key("goal-label"))
            session.guide = goal

            // 左右に行ったり来たりする見張り (向きを逆にして、すき間ができるようにする)
            session.spawnEnemy(at(-8.5, -1.5)).brain = Brains.patrol(listOf(at(8.5, -1.5), at(-8.5, -1.5)), speed = 0.1)
            session.spawnEnemy(at(8.5, -5.5)).brain = Brains.patrol(listOf(at(-8.5, -5.5), at(8.5, -5.5)), speed = 0.1)

            session.everyTick {
                when {
                    session.count("action.spy_revealed") > 0 -> session.onDown("rushcraft.tutorial.down.spotted")
                    !InvisibleSpy.isInvisible(player) && relativeZ() < LINE_Z -> session.onDown("rushcraft.tutorial.down.seen")
                }
            }
            waitUntil { InvisibleSpy.isInvisible(player) && player.location.distanceSquared(goal) < 2.5 }
            session.success()
            wait(40)
        },
        step("practice2") {
            session.teleport(spawn)
            session.equip(session.special(special) + session.special(SpecialItem.CLASSIC_SWORD) + armor())
            session.title("rushcraft.tutorial.try", key("practice2"))
            session.say(key("practice2"))
            session.say(key("practice2-hint"))

            // 見える相手は持ち場から見張り、バレたら殴りかかってくる
            val home = at(0.5, -6.5, yaw = 180f)
            val lookout = at(0.5, -12.0, y = 1.6)
            keepEnemy(home, health = 12.0, delay = 40) { guard ->
                val fight = Brains.melee(player, damage = 4.0, home = home, leash = 6.0)
                guard.brain = { dummy ->
                    if (InvisibleSpy.isInvisible(player)) {
                        dummy.walk(home)
                        dummy.face(lookout)
                    } else {
                        fight(dummy)
                    }
                }
            }
            progress("practice2", "kill.melee", 1)
            session.success()
            wait(40)
        },
        step("finish") {
            session.equip(emptyList())
            session.say(key("finish"))
            wait(60)
        },
    )

    companion object {
        /** 見張りの範囲の線 (これより奥に透明でないまま入ると見つかる) */
        private const val LINE_Z = 3
    }
}
