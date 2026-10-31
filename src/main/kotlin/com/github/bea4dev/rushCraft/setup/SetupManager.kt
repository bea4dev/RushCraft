package com.github.bea4dev.rushCraft.setup

import com.github.bea4dev.rushCraft.RushCraft
import com.github.bea4dev.rushCraft.map.BlockPos
import com.github.bea4dev.rushCraft.map.MapDefinition
import com.github.bea4dev.rushCraft.map.MapRegistry
import com.github.bea4dev.rushCraft.map.MapWorlds
import com.github.bea4dev.rushCraft.map.Region
import com.github.bea4dev.rushCraft.map.SpawnPoint
import com.github.bea4dev.rushCraft.match.MatchGameModeType
import com.github.bea4dev.rushCraft.match.mode.CTWSettings
import com.github.bea4dev.rushCraft.scheduler.Tickable
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.util.UUID

/**
 * マップのセットアップ (setup-mode = true のときのみ有効)。
 *
 * テンプレート maps/<id>/ を rushcraft_edit_<id> にコピーして編集し、
 * 保存時にテンプレートへ書き戻す。編集用ワールドは再起動しても残るので、
 * 保存前の作業内容は失われない (/rcmap discard で破棄できる)。
 */
object SetupManager : Tickable {
    class EditSession(val map: MapDefinition, var world: World) {
        var dirty = false
    }

    class Selection {
        var pos1: BlockPos? = null
        var pos2: BlockPos? = null

        fun region(): Region? {
            val a = pos1 ?: return null
            val b = pos2 ?: return null
            return Region.of(a, b)
        }
    }

    enum class Tool(val material: Material, val nameKey: String) {
        MENU(Material.NETHER_STAR, "rushcraft.setup.tool.menu"),
        WAND(Material.BLAZE_ROD, "rushcraft.setup.tool.wand"),
    }

    private val toolKey = NamespacedKey(RushCraft.plugin, "setup_tool")
    private val sessions = HashMap<String, EditSession>()
    private val selections = HashMap<UUID, Selection>()

    var isEnabled = false
        private set

    fun start() {
        isEnabled = true
        schedule()
    }

    fun session(player: Player): EditSession? = sessions.values.find { it.world == player.world }

    fun selection(player: Player): Selection = selections.getOrPut(player.uniqueId, ::Selection)

    // ------------------------------------------------------------------
    // コマンド
    // ------------------------------------------------------------------

    fun create(player: Player, id: String, type: MatchGameModeType) {
        if (!MapDefinition.ID_PATTERN.matches(id)) {
            player.sendMessage(Component.translatable("rushcraft.setup.invalid-id", NamedTextColor.RED, Component.text(id)))
            return
        }
        if (MapRegistry.get(id) != null || MapRegistry.directoryOf(id).exists()) {
            player.sendMessage(Component.translatable("rushcraft.setup.exists", NamedTextColor.RED, Component.text(id)))
            return
        }
        val map = MapDefinition.createDefault(id, MapRegistry.directoryOf(id), type)
        map.save()
        MapRegistry.register(map)
        player.sendMessage(Component.translatable("rushcraft.setup.created", NamedTextColor.GREEN, Component.text(id)))
        edit(player, id)
    }

    /**
     * 既存のワールドフォルダ (サーバーのワールドコンテナ内) をマップとして取り込む。
     */
    fun import(player: Player, worldName: String, id: String, type: MatchGameModeType) {
        if (!MapDefinition.ID_PATTERN.matches(id)) {
            player.sendMessage(Component.translatable("rushcraft.setup.invalid-id", NamedTextColor.RED, Component.text(id)))
            return
        }
        if (MapRegistry.get(id) != null || MapRegistry.directoryOf(id).exists()) {
            player.sendMessage(Component.translatable("rushcraft.setup.exists", NamedTextColor.RED, Component.text(id)))
            return
        }
        val source = File(MapWorlds.container, worldName)
        if (!File(source, "level.dat").exists()) {
            player.sendMessage(Component.translatable("rushcraft.setup.import.not-found", NamedTextColor.RED, Component.text(worldName)))
            return
        }

        val loaded = Bukkit.getWorld(worldName)
        loaded?.save()

        val map = MapDefinition.createDefault(id, MapRegistry.directoryOf(id), type)
        loaded?.let { map.spawn = SpawnPoint.of(it.spawnLocation) }
        MapWorlds.copyWorld(source, map.directory)
        map.save()
        MapRegistry.register(map)
        player.sendMessage(Component.translatable("rushcraft.setup.imported", NamedTextColor.GREEN, Component.text(worldName), Component.text(id)))
        edit(player, id)
    }

    fun edit(player: Player, id: String) {
        val map = MapRegistry.get(id) ?: run {
            player.sendMessage(Component.translatable("rushcraft.setup.not-found", NamedTextColor.RED, Component.text(id)))
            return
        }
        val session = sessions.getOrPut(id) {
            val name = MapWorlds.editWorldName(id)
            val folder = File(MapWorlds.container, name)
            if (!folder.exists()) {
                MapWorlds.copyWorld(map.directory, folder)
            }
            val world = MapWorlds.loadWorld(name)
            MapWorlds.applyEditRules(world)
            ensurePlatform(world, map)
            EditSession(map, world)
        }

        player.teleport(map.spawn.toLocation(session.world))
        player.gameMode = GameMode.CREATIVE
        giveTools(player)
        player.sendMessage(Component.translatable("rushcraft.setup.editing", NamedTextColor.GREEN, Component.text(map.name)))
        SetupMenu.openMain(player, session)
    }

    /** 新規作成した空のマップで足場が無い場合にだけ岩盤を置く */
    private fun ensurePlatform(world: World, map: MapDefinition) {
        val below = map.spawn.toLocation(world).block.getRelative(0, -1, 0)
        val hasLevelData = File(map.directory, "level.dat").exists()
        if (!hasLevelData && below.type.isAir) {
            below.type = Material.BEDROCK
        }
    }

    fun save(player: Player) {
        val session = session(player) ?: return notEditing(player)
        if (save(session)) {
            player.sendMessage(Component.translatable("rushcraft.setup.saved", NamedTextColor.GREEN, Component.text(session.map.name)))
            val problems = session.map.validate()
            if (problems.isNotEmpty()) {
                player.sendMessage(Component.translatable("rushcraft.setup.problems", NamedTextColor.YELLOW, Component.text(problems.size)))
                problems.forEach { player.sendMessage(Component.text(" - ").append(it.message.colorIfAbsent(MapDefinition.problemColor(it)))) }
            }
        } else {
            player.sendMessage(Component.translatable("rushcraft.setup.save-failed", NamedTextColor.RED))
        }
    }

    /**
     * map.toml を書き出し、ワールドを一度アンロードしてテンプレートへコピーしてから読み込み直す。
     * (アンロードしてリージョンファイルを確実にディスクへ書き出すため)
     */
    private fun save(session: EditSession): Boolean {
        val map = session.map
        val world = session.world
        val fallback = Bukkit.getWorlds().first().spawnLocation

        val editors = world.players.associateWith { it.location.clone() }
        editors.keys.forEach { it.teleport(fallback) }

        val folder = world.worldFolder
        if (!Bukkit.unloadWorld(world, true)) {
            editors.forEach { (player, location) -> player.teleport(location) }
            return false
        }

        try {
            MapWorlds.clearTemplate(map.directory)
            MapWorlds.copyWorld(folder, map.directory)
            map.save()
            session.dirty = false
        } catch (exception: Exception) {
            RushCraft.plugin.logger.severe("Failed to save map '${map.id}': ${exception.message}")
            exception.printStackTrace()
            return false
        } finally {
            session.world = MapWorlds.loadWorld(folder.name)
            MapWorlds.applyEditRules(session.world)
            editors.forEach { (player, location) ->
                if (player.isOnline) {
                    location.world = session.world
                    player.teleport(location)
                }
            }
        }
        return true
    }

    fun leave(player: Player) {
        val session = session(player) ?: return notEditing(player)
        player.teleport(Bukkit.getWorlds().first().spawnLocation)
        if (session.world.players.isEmpty()) {
            // 未保存の変更も編集用ワールドには残す
            Bukkit.unloadWorld(session.world, true)
            sessions.remove(session.map.id)
        }
        if (session.dirty) {
            player.sendMessage(Component.translatable("rushcraft.setup.left-unsaved", NamedTextColor.YELLOW))
        }
    }

    /**
     * 編集用ワールドを破棄し、テンプレートの状態に戻す。
     */
    fun discard(player: Player, id: String) {
        val map = MapRegistry.get(id) ?: run {
            player.sendMessage(Component.translatable("rushcraft.setup.not-found", NamedTextColor.RED, Component.text(id)))
            return
        }
        val fallback = Bukkit.getWorlds().first().spawnLocation
        sessions.remove(id)?.let { session ->
            session.world.players.forEach { it.teleport(fallback) }
            Bukkit.unloadWorld(session.world, false)
        }
        File(MapWorlds.container, MapWorlds.editWorldName(id)).deleteRecursively()

        // map.toml も読み込み直して未保存の変更を捨てる
        try {
            MapRegistry.register(MapDefinition.load(map.directory))
        } catch (exception: Exception) {
            player.sendMessage(Component.text(exception.message ?: exception.toString(), NamedTextColor.RED))
        }
        player.sendMessage(Component.translatable("rushcraft.setup.discarded", NamedTextColor.GREEN, Component.text(id)))
    }

    fun list(player: Player) {
        player.sendMessage(Component.translatable("rushcraft.setup.list", NamedTextColor.GOLD))
        for (map in MapRegistry.all()) {
            val errors = map.validate().count { it.error }
            val status = if (errors == 0) {
                Component.text("✔", NamedTextColor.GREEN)
            } else {
                Component.text("✘ $errors", NamedTextColor.RED)
            }
            player.sendMessage(
                Component.text()
                    .append(Component.text(" - ", NamedTextColor.GRAY))
                    .append(Component.text(map.id, NamedTextColor.WHITE))
                    .append(Component.text(" (${map.name}, ${map.type.id}) ", NamedTextColor.GRAY))
                    .append(status)
                    .build()
            )
        }
    }

    private fun notEditing(player: Player) {
        player.sendMessage(Component.translatable("rushcraft.setup.not-editing", NamedTextColor.RED))
    }

    // ------------------------------------------------------------------
    // ツール
    // ------------------------------------------------------------------

    fun giveTools(player: Player) {
        val inventory = player.inventory
        for (tool in Tool.entries) {
            if (inventory.contents.any { it != null && toolOf(it) == tool }) continue
            inventory.addItem(createTool(tool))
        }
    }

    private fun createTool(tool: Tool): ItemStack {
        val stack = ItemStack(tool.material)
        stack.editMeta { meta ->
            meta.itemName(Component.translatable(tool.nameKey, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false))
            meta.lore(
                listOf(
                    Component.translatable("${tool.nameKey}-lore", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                )
            )
            meta.setEnchantmentGlintOverride(true)
            meta.persistentDataContainer.set(toolKey, PersistentDataType.STRING, tool.name)
        }
        return stack
    }

    fun toolOf(stack: ItemStack?): Tool? {
        if (stack == null || stack.isEmpty) return null
        val value = stack.persistentDataContainer.get(toolKey, PersistentDataType.STRING) ?: return null
        return Tool.entries.find { it.name == value }
    }

    fun setPosition(player: Player, first: Boolean, pos: BlockPos) {
        val selection = selection(player)
        if (first) selection.pos1 = pos else selection.pos2 = pos
        player.sendActionBar(
            Component.translatable(
                if (first) "rushcraft.setup.wand.pos1" else "rushcraft.setup.wand.pos2",
                NamedTextColor.LIGHT_PURPLE,
                Component.text(pos.toString()),
            )
        )
    }

    // ------------------------------------------------------------------
    // 可視化
    // ------------------------------------------------------------------

    private var t = 0

    override fun tick() {
        t++
        if (t % 10 != 0) return
        for (session in sessions.values) {
            for (player in session.world.players) {
                visualize(player, session)
            }
        }
    }

    private fun visualize(player: Player, session: EditSession) {
        val map = session.map
        val world = session.world

        dust(player, map.spawn.toLocation(world), Color.WHITE, 1.5F)
        for (team in map.teams) {
            val color = team.color.dye.color
            team.spawnRooms.forEach { outline(player, it, world, color) }
            for (spawn in team.spawns) {
                val location = spawn.toLocation(world)
                for (i in 0..4) {
                    dust(player, location.clone().add(0.0, i * 0.5, 0.0), color, 1.2F)
                }
            }
        }

        val settings = map.settings
        if (settings is CTWSettings) {
            for (wool in settings.wools) {
                wool.room?.let { outline(player, it, world, wool.color.color) }
                wool.spawners.forEach { outline(player, it, world, wool.color.color) }
                val monument = wool.monument ?: continue
                outline(player, Region(monument, monument), world, wool.color.color)
            }
        }

        for (region in map.noBuildRegions) {
            outline(player, region, world, Color.RED)
        }

        val selection = selection(player)
        selection.pos1?.let { outline(player, Region(it, it), world, Color.FUCHSIA) }
        selection.pos2?.let { outline(player, Region(it, it), world, Color.FUCHSIA) }
        selection.region()?.let { outline(player, it, world, Color.FUCHSIA) }
    }

    private fun dust(player: Player, location: Location, color: Color, size: Float) {
        if (location.distanceSquared(player.location) > 96.0 * 96.0) return
        player.spawnParticle(Particle.DUST, location, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(color, size))
    }

    /** 範囲の 12 本の辺をパーティクルで描く */
    private fun outline(player: Player, region: Region, world: World, color: Color) {
        val minX = region.min.x.toDouble()
        val minY = region.min.y.toDouble()
        val minZ = region.min.z.toDouble()
        val maxX = region.max.x + 1.0
        val maxY = region.max.y + 1.0
        val maxZ = region.max.z + 1.0

        val longest = maxOf(maxX - minX, maxY - minY, maxZ - minZ)
        val step = (longest / 48.0).coerceAtLeast(0.5)

        fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double) {
            val length = maxOf(x2 - x1, y2 - y1, z2 - z1)
            var d = 0.0
            while (d <= length) {
                val ratio = if (length == 0.0) 0.0 else d / length
                dust(
                    player,
                    Location(world, x1 + (x2 - x1) * ratio, y1 + (y2 - y1) * ratio, z1 + (z2 - z1) * ratio),
                    color,
                    0.8F,
                )
                d += step
            }
        }

        for (y in listOf(minY, maxY)) {
            line(minX, y, minZ, maxX, y, minZ)
            line(minX, y, maxZ, maxX, y, maxZ)
            line(minX, y, minZ, minX, y, maxZ)
            line(maxX, y, minZ, maxX, y, maxZ)
        }
        for (x in listOf(minX, maxX)) {
            for (z in listOf(minZ, maxZ)) {
                line(x, minY, z, x, maxY, z)
            }
        }
    }

    override fun isAlive(): Boolean = true
}
