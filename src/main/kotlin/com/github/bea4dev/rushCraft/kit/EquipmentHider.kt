package com.github.bea4dev.rushCraft.kit

import com.mojang.datafixers.util.Pair
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * エンティティの装備 (手に持っている物・防具) を、一部のプレイヤーにだけ見えなくする (透明スパイ)。
 *
 * 各プレイヤーの送信パケットのうち、隠しているエンティティの装備のパケット (出現時のバンドルの中も含む) を
 * 空の装備に書き換える。サーバー側の装備はそのままなので、当たり判定などは変わらない。
 *
 * パケットの書き換えは通信のスレッドで行うので、誰に見せるかはメインスレッドで決めて ConcurrentHashMap に置く。
 */
object EquipmentHider {
    private const val HANDLER_NAME = "rushcraft_equipment_hider"

    private val SLOTS = listOf(
        EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
        EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD,
    )

    /** 隠しているエンティティの番号 → 装備を見せるプレイヤー */
    private val visibleTo = ConcurrentHashMap<Int, Set<UUID>>()

    /** 空の装備を送ったプレイヤー (見せ直すときに本当の装備を送る)。メインスレッドだけで使う */
    private val blanked = HashMap<UUID, MutableSet<UUID>>()

    /**
     * entity の装備を viewers 以外のプレイヤーから隠す (毎 tick 呼んで見せる相手を更新してよい)。
     * 既に装備が見えているプレイヤーには空の装備を送り直す。
     */
    fun hide(entity: LivingEntity, viewers: Set<UUID>) {
        visibleTo[entity.entityId] = viewers
        val sent = blanked.getOrPut(entity.uniqueId, ::HashSet)
        val empty = SLOTS.associateWith { ItemStack.empty() }
        for (player in entity.world.players) {
            if (player.uniqueId in viewers || player.uniqueId in sent) continue
            player.sendEquipmentChange(entity, empty)
            sent += player.uniqueId
        }
    }

    /** 隠すのをやめ、隠していたプレイヤーに本当の装備を送り直す */
    fun show(entity: LivingEntity) {
        visibleTo.remove(entity.entityId)
        val sent = blanked.remove(entity.uniqueId) ?: return
        if (!entity.isValid) return
        val equipment = entity.equipment ?: return
        val actual = SLOTS.associateWith { equipment.getItem(it) }
        for (uuid in sent) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            if (player.world == entity.world) player.sendEquipmentChange(entity, actual)
        }
    }

    fun reset() {
        visibleTo.clear()
        blanked.clear()
    }

    // ------------------------------------------------------------------
    // パケットの書き換え
    // ------------------------------------------------------------------

    fun inject(player: Player) {
        val channel = (player as CraftPlayer).handle.connection.connection.channel ?: return
        val viewer = player.uniqueId
        channel.eventLoop().execute {
            val pipeline = channel.pipeline()
            if (pipeline.get(HANDLER_NAME) == null && pipeline.get("packet_handler") != null) {
                pipeline.addBefore("packet_handler", HANDLER_NAME, Handler(viewer))
            }
        }
    }

    private class Handler(private val viewer: UUID) : ChannelDuplexHandler() {
        override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise) {
            val rewritten = if (visibleTo.isEmpty()) null else (msg as? Packet<*>)?.let { rewrite(it, viewer) }
            super.write(ctx, rewritten ?: msg, promise)
        }
    }

    /** 書き換えたパケット (書き換えなければ null) */
    @Suppress("UNCHECKED_CAST")
    private fun rewrite(packet: Packet<*>, viewer: UUID): Packet<*>? {
        return when (packet) {
            is ClientboundSetEquipmentPacket -> {
                val viewers = visibleTo[packet.entity] ?: return null
                if (viewer in viewers) return null
                ClientboundSetEquipmentPacket(
                    packet.entity,
                    packet.slots.map { Pair.of(it.first, net.minecraft.world.item.ItemStack.EMPTY) },
                )
            }

            is ClientboundBundlePacket -> {
                var changed = false
                val packets = packet.subPackets().map { sub ->
                    val rewritten = rewrite(sub, viewer)
                    if (rewritten != null) changed = true
                    (rewritten ?: sub) as Packet<in ClientGamePacketListener>
                }
                if (changed) ClientboundBundlePacket(packets) else null
            }

            else -> null
        }
    }
}
