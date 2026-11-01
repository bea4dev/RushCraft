package com.github.bea4dev.rushCraft.tutorial

import com.mojang.authlib.GameProfile
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.world.entity.Avatar
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.GameType
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Mannequin
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * チュートリアルの人形 (Mannequin) を、クライアントにはプレイヤーとして見せる。
 *
 * Mannequin はクライアントで腕振りのアニメーションが進まず、剣を振っても動かない。
 * 送るパケットだけを書き換えてプレイヤーの見た目にすると、腕振り・盾の構え・しゃがみが
 * 本物のプレイヤーと同じように描画される (サーバー側は Mannequin のままなので、当たり判定や物理は変わらない)。
 *
 * - 出現: プレイヤー情報 (タブには出さない) を送ってから、プレイヤーとして出現させる
 * - データ: Mannequin にしか無い項目 (プロフィール・説明など) は送らない
 * - 出現時の装備: データより先に送る (下記)
 * - 消滅: プレイヤー情報も消す
 *
 * 名前はプレイヤー名として出てしまうので、名前を隠すチームに入れ、代わりに TextDisplay で表示する (Dummy)。
 */
object DummyDisguise {
    /** プレイヤーとして送るときの名前 (名前を隠すチームに入れる) */
    const val PROFILE_NAME = "rc_dummy"

    private const val HANDLER_NAME = "rushcraft_dummy_disguise"
    private const val TEAM_NAME = "rc_dummies"

    /** これより後の番号のデータは Mannequin にしか無い */
    private val LAST_SHARED_DATA_ID = Avatar.DATA_PLAYER_MODE_CUSTOMISATION.id()

    /** 書き換えるエンティティの番号と UUID */
    private val disguised = ConcurrentHashMap<Int, UUID>()

    /** 消したエンティティ (消滅のパケットが後から届くので、しばらく覚えておく) */
    private val removed = ConcurrentHashMap<Int, Pair<UUID, Long>>()

    fun add(entity: Mannequin) {
        disguised[entity.entityId] = entity.uniqueId
    }

    fun remove(entity: Mannequin) {
        val uuid = disguised.remove(entity.entityId) ?: return
        removed[entity.entityId] = uuid to System.currentTimeMillis()
        // 古いものは忘れる
        val expired = System.currentTimeMillis() - 60_000
        removed.entries.removeIf { it.value.second < expired }
    }

    /** 名前を隠すチーム (試合ごとにスコアボードが変わるので、その都度用意する) */
    fun hideNames(scoreboard: Scoreboard) {
        val team = scoreboard.getTeam(TEAM_NAME) ?: scoreboard.registerNewTeam(TEAM_NAME).also {
            it.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER)
        }
        if (!team.hasEntry(PROFILE_NAME)) team.addEntry(PROFILE_NAME)
    }

    // ------------------------------------------------------------------
    // パケットの書き換え
    // ------------------------------------------------------------------

    fun inject(player: Player) {
        val channel = (player as CraftPlayer).handle.connection.connection.channel ?: return
        channel.eventLoop().execute {
            val pipeline = channel.pipeline()
            if (pipeline.get(HANDLER_NAME) == null && pipeline.get("packet_handler") != null) {
                pipeline.addBefore("packet_handler", HANDLER_NAME, Handler())
            }
        }
    }

    private class Handler : ChannelDuplexHandler() {
        override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise) {
            val packets = (msg as? Packet<*>)?.let(::rewrite)
            if (packets == null) {
                super.write(ctx, msg, promise)
                return
            }
            // 足したパケットを先に送り、最後のパケットに元の promise を渡す
            for (packet in packets.dropLast(1)) ctx.write(packet)
            super.write(ctx, packets.last(), promise)
        }
    }

    /** 書き換えたパケット (書き換えなければ null) */
    @Suppress("UNCHECKED_CAST")
    private fun rewrite(packet: Packet<*>): List<Packet<*>>? {
        return when (packet) {
            is ClientboundBundlePacket -> {
                var changed = false
                val packets = mutableListOf<Packet<in ClientGamePacketListener>>()
                for (sub in packet.subPackets()) {
                    val rewritten = rewrite(sub)
                    if (rewritten != null) changed = true
                    packets += (rewritten ?: listOf(sub)) as List<Packet<in ClientGamePacketListener>>
                }
                if (equipmentFirst(packets)) changed = true
                if (changed) listOf(ClientboundBundlePacket(packets)) else null
            }

            is ClientboundAddEntityPacket -> {
                if (packet.type != EntityType.MANNEQUIN) return null
                val uuid = disguised[packet.id] ?: return null
                val entry = ClientboundPlayerInfoUpdatePacket.Entry(
                    uuid, GameProfile(uuid, PROFILE_NAME), false, 0, GameType.SURVIVAL, null, true, 0, null,
                )
                val info = ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), entry)
                val spawn = ClientboundAddEntityPacket(
                    packet.id, packet.uuid, packet.x, packet.y, packet.z, packet.xRot, packet.yRot,
                    EntityType.PLAYER, 0, packet.movement, packet.yHeadRot.toDouble(),
                )
                listOf(info, spawn)
            }

            is ClientboundSetEntityDataPacket -> {
                if (!disguised.containsKey(packet.id) && !removed.containsKey(packet.id)) return null
                val items = packet.packedItems.filter { it.id <= LAST_SHARED_DATA_ID }
                if (items.size == packet.packedItems.size) null else listOf(ClientboundSetEntityDataPacket(packet.id, items))
            }

            is ClientboundRemoveEntitiesPacket -> {
                val uuids = packet.entityIds.mapNotNull { id -> disguised[id] ?: removed[id]?.first }
                if (uuids.isEmpty()) null else listOf(packet, ClientboundPlayerInfoRemovePacket(uuids))
            }

            else -> null
        }
    }

    /**
     * 出現時の装備をデータより先に送るように並べ替える (並べ替えたら true)。
     *
     * サーバーは出現時に データ → 装備 の順に送る。クライアントは「アイテムを使っている」データを受け取った瞬間に
     * 手に持っている物を使い始めるので、出現と同時に盾を構えると、まだ盾を持っていない状態で構えたことになり、
     * 構えのポーズにならない (次に構え直すまで直らない)。
     */
    private fun equipmentFirst(packets: MutableList<Packet<in ClientGamePacketListener>>): Boolean {
        var moved = false
        for (index in packets.indices) {
            val equipment = packets[index] as? ClientboundSetEquipmentPacket ?: continue
            if (!disguised.containsKey(equipment.entity)) continue
            val data = packets.indexOfFirst { it is ClientboundSetEntityDataPacket && it.id == equipment.entity }
            if (data < 0 || data > index) continue
            packets.removeAt(index)
            packets.add(data, equipment)
            moved = true
        }
        return moved
    }
}
