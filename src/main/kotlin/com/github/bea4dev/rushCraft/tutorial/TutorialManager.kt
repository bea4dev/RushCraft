package com.github.bea4dev.rushCraft.tutorial

import com.github.bea4dev.rushCraft.manager.MatchManager
import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * チュートリアルのセッションを管理する。
 *
 * 試合の状態に関係なく (待機中・試合中の観戦者・試合後) いつでも受けられる。
 * 試合に参加している人は待機中だけ受けられ、試合が始まると自動で終わって試合に入る。
 */
object TutorialManager : Tickable {
    /** チュートリアル中のアイテムの復活までの最大 tick 数 (何度も試せるように短くする) */
    private const val RECHARGE_TICKS = 40

    private val COMPLETED_KEY = NamespacedKey("rushcraft", "tutorials_completed")

    private val sessions = HashMap<UUID, TutorialSession>()

    /** ダミーなど、プレイヤー以外のセッションの参加者 */
    private val members = HashMap<UUID, TutorialSession>()

    val isEnabled: Boolean
        get() = TutorialWorld.world != null

    fun session(player: Player): TutorialSession? = sessions[player.uniqueId]

    fun isInTutorial(player: Player): Boolean = player.uniqueId in sessions

    fun sessionOf(entity: Entity): TutorialSession? = sessions[entity.uniqueId] ?: members[entity.uniqueId]

    fun register(entity: Entity, session: TutorialSession) {
        members[entity.uniqueId] = session
    }

    fun unregister(entity: Entity) {
        members.remove(entity.uniqueId)
    }

    /** 進行中のチュートリアルの会場の中か */
    fun isActiveArea(location: Location): Boolean {
        val world = TutorialWorld.world ?: return false
        if (location.world != world) return false
        return sessions.values.any { it.arena.contains(location) }
    }

    fun sessionAt(location: Location): TutorialSession? {
        val world = TutorialWorld.world ?: return null
        if (location.world != world) return null
        return sessions.values.find { it.arena.contains(location) }
    }

    fun rechargeTicks(player: Player, ticks: Int): Int {
        return if (isInTutorial(player)) ticks.coerceAtMost(RECHARGE_TICKS) else ticks
    }

    // ------------------------------------------------------------------
    // 開始・終了
    // ------------------------------------------------------------------

    /**
     * チュートリアルを始める。受けているチュートリアルがあれば、それをやめて始め直す。
     */
    fun start(player: Player, tutorial: Tutorial): Boolean {
        if (!isEnabled || MatchManager.match == null) {
            player.sendMessage(Component.translatable("rushcraft.tutorial.unavailable", NamedTextColor.RED))
            return false
        }
        if (!MatchManager.canStartTutorial(player)) {
            player.sendMessage(Component.translatable("rushcraft.tutorial.participating", NamedTextColor.RED))
            return false
        }
        sessions.remove(player.uniqueId)?.dispose()

        val session = TutorialSession(player, tutorial)
        sessions[player.uniqueId] = session
        try {
            session.start()
        } catch (exception: Exception) {
            sessions.remove(player.uniqueId)
            session.dispose()
            MatchManager.returnFromTutorial(player)
            throw exception
        }
        player.sendMessage(
            Component.translatable("rushcraft.tutorial.started", NamedTextColor.GREEN, tutorial.displayName().color(NamedTextColor.YELLOW))
        )
        return true
    }

    /**
     * チュートリアルをやめる。
     * @param restore 試合の待機場所や観戦に戻すか (試合に参加するときなどは呼び出し側で送る)
     * @return チュートリアル中だったか
     */
    fun leave(player: Player, restore: Boolean = true): Boolean {
        val session = sessions.remove(player.uniqueId) ?: return false
        session.dispose()
        if (restore && player.isOnline) MatchManager.returnFromTutorial(player)
        return true
    }

    /** クリアして戻ったとき。次のチュートリアルを案内する */
    fun finish(player: Player) {
        val session = sessions[player.uniqueId] ?: return
        leave(player)
        TutorialMenu.showCompleted(player, session.tutorial)
    }

    /** 今の段階を最初からやり直す */
    fun retry(player: Player) {
        sessions[player.uniqueId]?.retry()
    }

    fun shutdown() {
        for (player in sessions.values.map { it.player }) {
            leave(player)
        }
    }

    // ------------------------------------------------------------------
    // クリアの記録 (プレイヤーデータに保存する)
    // ------------------------------------------------------------------

    fun isCompleted(player: Player, tutorial: Tutorial): Boolean = tutorial.id in completed(player)

    private fun completed(player: Player): Set<String> {
        val value = player.persistentDataContainer.get(COMPLETED_KEY, PersistentDataType.STRING) ?: return emptySet()
        return value.split(',').filter { it.isNotEmpty() }.toSet()
    }

    fun markCompleted(player: Player, tutorial: Tutorial) {
        val ids = completed(player) + tutorial.id
        player.persistentDataContainer.set(COMPLETED_KEY, PersistentDataType.STRING, ids.joinToString(","))
    }

    /** まだクリアしていない次のチュートリアル */
    fun nextOf(player: Player, tutorial: Tutorial): Tutorial? {
        val done = completed(player)
        val entries = Tutorial.entries
        val start = entries.indexOf(tutorial)
        return (1 until entries.size).map { entries[(start + it) % entries.size] }.firstOrNull { it.id !in done }
    }

    // ------------------------------------------------------------------
    // tick
    // ------------------------------------------------------------------

    override fun tick() {
        for (session in sessions.values.toList()) {
            if (!session.player.isOnline) {
                leave(session.player, restore = false)
                continue
            }
            session.tick()
        }
    }

    override fun isAlive(): Boolean = true
}
