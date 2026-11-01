package com.github.bea4dev.rushCraft.tutorial.content

import com.github.bea4dev.rushCraft.kit.SpecialItem
import com.github.bea4dev.rushCraft.tutorial.Dummy
import com.github.bea4dev.rushCraft.tutorial.Script
import com.github.bea4dev.rushCraft.tutorial.TutorialContent
import com.github.bea4dev.rushCraft.tutorial.TutorialSession
import com.github.bea4dev.rushCraft.tutorial.TutorialStep
import com.github.bea4dev.rushCraft.tutorial.TutorialWait
import com.github.bea4dev.rushCraft.tutorial.wait
import com.github.bea4dev.rushCraft.tutorial.waitUntil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Duration

/**
 * 特殊アイテムのチュートリアルの共通部分。
 *
 * 練習場 (草の床) の手前にプレイヤーが立ち、奥で お手本 → 自分でやってみる、の順に進める。
 * プレイヤーは z = 7 に北向き (-z) で立つ。
 */
abstract class ItemTutorial(session: TutorialSession, protected val special: SpecialItem) : TutorialContent(session) {
    protected val player: Player
        get() = session.player

    override val spawn: Location
        get() = arena.center(0, 0, 7, yaw = 180f)

    /** 翻訳キーの id (rushcraft.tutorial.<id>.<name>) */
    protected abstract val id: String

    protected fun key(name: String): String = "rushcraft.tutorial.$id.$name"

    override fun build() {
        arena.trainingFloor(-10, -12, 10, 10)
        buildExtra()
    }

    protected open fun buildExtra() {}

    // ------------------------------------------------------------------
    // よく使う段階
    // ------------------------------------------------------------------

    /**
     * 最初の説明。アイテムの名前を大きく出し、短い説明を順にチャットに出す。
     */
    protected fun introStep(vararg lines: String): TutorialStep = step("intro") {
        session.teleport(spawn)
        session.checkpoint = spawn
        session.equip(emptyList())
        session.watching("rushcraft.tutorial.reading")
        player.showTitle(
            Title.title(
                Component.translatable(special.nameKey, NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
                Component.translatable(key("subtitle"), NamedTextColor.WHITE),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(500)),
            )
        )
        wait(30)
        for (line in lines) {
            session.say(key(line))
            wait(50)
        }
    }

    /**
     * お手本。プレイヤーは見ているだけ (アイテムは持たない)。
     */
    protected fun demoStep(id: String, lineKey: String, look: Location? = null, script: Script): TutorialStep = step(id) {
        session.teleport(look?.let(::spawnLookingAt) ?: spawn)
        session.equip(emptyList())
        session.watching("rushcraft.tutorial.watch")
        session.say(key(lineKey))
        wait(20)
        script()
        wait(30)
    }

    /**
     * 自分でやってみる。counter が total に届いたらできた。
     */
    protected fun practiceStep(
        id: String,
        objective: String,
        counter: String,
        total: Int,
        retryFrom: String? = null,
        items: () -> List<Pair<Int, ItemStack>> = { session.special(special) },
        setup: Script = {},
    ): TutorialStep = step(id, retryFrom) {
        session.teleport(spawn)
        session.equip(items())
        session.title("rushcraft.tutorial.try", key(objective))
        session.say(key(objective))
        setup()
        progress(objective, counter, total)
        session.success()
        wait(40)
    }

    /** counter が total に届くまで、ボスバーに進み具合を出しながら待つ */
    protected suspend fun SequenceScope<TutorialWait>.progress(objective: String, counter: String, total: Int) {
        var last = -1
        waitUntil {
            val current = session.count(counter)
            if (current != last) {
                if (last >= 0 && current > last) session.ding(current)
                last = current
                session.objective(key(objective), current, total)
            }
            current >= total
        }
    }

    /**
     * 倒されても少し経つと同じ場所に復活する敵 (段階が終わるまで)。
     */
    protected fun keepEnemy(location: Location, health: Double, delay: Int = 30, configure: (Dummy) -> Unit) {
        var dummy = session.spawnEnemy(location, health).also(configure)
        var deadTicks = 0
        session.everyTick {
            if (dummy.isAlive) return@everyTick
            if (++deadTicks < delay) return@everyTick
            deadTicks = 0
            dummy = session.spawnEnemy(location, health).also(configure)
            location.world.spawnParticle(Particle.CLOUD, location.clone().add(0.0, 1.0, 0.0), 12, 0.3, 0.5, 0.3, 0.02)
        }
    }

    /** 最初に立つ場所から target の方を向く */
    protected fun spawnLookingAt(target: Location): Location {
        val location = spawn
        location.direction = target.toVector().subtract(location.clone().add(0.0, 1.62, 0.0).toVector())
        return location
    }

    /** 練習場の位置 */
    protected fun at(x: Double, z: Double, y: Double = 0.0, yaw: Float = 0f): Location = arena.at(x, y, z, yaw)

    /** プレイヤーの方 (南) を向いた位置 */
    protected fun facingPlayer(x: Double, z: Double, y: Double = 0.0): Location = arena.at(x, y, z, 0f)
}
