package com.github.bea4dev.rushCraft.setup

import com.github.bea4dev.rushCraft.map.BlockPos
import com.github.bea4dev.rushCraft.map.MapDefinition
import com.github.bea4dev.rushCraft.map.SpawnPoint
import com.github.bea4dev.rushCraft.map.TeamDefinition
import com.github.bea4dev.rushCraft.match.TeamColor
import com.github.bea4dev.rushCraft.match.mode.CTWSettings
import com.github.bea4dev.rushCraft.match.mode.WoolDefinition
import com.github.bea4dev.rushCraft.util.Dialogs
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.DyeColor
import org.bukkit.entity.Player

/**
 * Dialog を使ったセットアップ GUI。
 */
object SetupMenu {
    private const val INPUT_WIDTH = Dialogs.INPUT_WIDTH

    private fun tr(key: String, vararg args: Component): Component =
        Component.translatable("rushcraft.setup.menu.$key", *args)

    private fun button(
        label: Component,
        tooltip: Component? = null,
        action: (DialogResponseView, Player) -> Unit,
    ): ActionButton = Dialogs.button(label, tooltip, action = action)

    private fun closeButton(): ActionButton = Dialogs.closeButton(tr("close"))

    private fun show(
        player: Player,
        title: Component,
        body: List<Component>,
        inputs: List<DialogInput> = emptyList(),
        buttons: List<ActionButton>,
        exit: ActionButton? = null,
    ) = Dialogs.show(player, title, body, inputs, buttons, exit)

    /** セッションがまだ有効か (保存などでワールドが入れ替わっても map は同じ) */
    private fun valid(player: Player, session: SetupManager.EditSession): Boolean {
        return SetupManager.session(player) === session
    }

    private fun changed(session: SetupManager.EditSession) {
        session.dirty = true
    }

    private fun text(key: String, label: Component, initial: String, maxLength: Int = 64): DialogInput {
        return DialogInput.text(key, label).width(INPUT_WIDTH).initial(initial).maxLength(maxLength).build()
    }

    // ------------------------------------------------------------------
    // メイン
    // ------------------------------------------------------------------

    fun openMain(player: Player, session: SetupManager.EditSession) {
        val map = session.map
        val body = mutableListOf<Component>()
        body += tr(
            "main.summary",
            Component.text(map.id, NamedTextColor.WHITE),
            Component.translatable(map.type.nameKey, NamedTextColor.AQUA),
            Component.text(map.teams.size, NamedTextColor.WHITE),
        )
        if (session.dirty) {
            body += tr("main.unsaved").color(NamedTextColor.YELLOW)
        }
        val problems = map.validate()
        if (problems.isEmpty()) {
            body += tr("main.valid").color(NamedTextColor.GREEN)
        } else {
            body += problems.map { Component.text("• ").append(it.message).color(MapDefinition.problemColor(it)) }
        }

        val buttons = mutableListOf<ActionButton>()
        buttons += button(tr("main.info")) { _, p -> if (valid(p, session)) openInfo(p, session) }
        buttons += button(tr("main.spawn"), tr("main.spawn-tooltip")) { _, p ->
            if (!valid(p, session)) return@button
            map.spawn = SpawnPoint.of(p.location)
            changed(session)
            p.sendMessage(tr("spawn.set", Component.text(map.spawn.toString())).color(NamedTextColor.GREEN))
            openMain(p, session)
        }
        buttons += button(tr("main.teams")) { _, p -> if (valid(p, session)) openTeams(p, session) }
        if (map.settings is CTWSettings) {
            buttons += button(tr("main.wools")) { _, p -> if (valid(p, session)) openWools(p, session) }
        }
        buttons += button(tr("main.regions")) { _, p -> if (valid(p, session)) openRegions(p, session) }
        buttons += button(tr("main.kit")) { _, p -> if (valid(p, session)) openKit(p, session) }
        buttons += button(tr("main.save").color(NamedTextColor.GREEN), tr("main.save-tooltip")) { _, p ->
            if (!valid(p, session)) return@button
            SetupManager.save(p)
            SetupManager.session(p)?.let { openMain(p, it) }
        }
        buttons += button(tr("main.leave"), tr("main.leave-tooltip")) { _, p ->
            if (valid(p, session)) SetupManager.leave(p)
        }

        show(player, tr("main.title", Component.text(map.name)), body, buttons = buttons, exit = closeButton())
    }

    // ------------------------------------------------------------------
    // 基本情報
    // ------------------------------------------------------------------

    fun openInfo(player: Player, session: SetupManager.EditSession) {
        val map = session.map
        val inputs = listOf(
            text("name", tr("info.name"), map.name),
            text("version", tr("info.version"), map.version, 16),
            text("authors", tr("info.authors"), map.authors.joinToString(", "), 256),
            text("time_limit", tr("info.time-limit"), map.timeLimit.toString(), 8),
            text("max_height", tr("info.max-height"), map.maxBuildHeight?.toString() ?: "", 8),
            text("time", tr("info.time"), map.time.toString(), 8),
        )

        val save = button(tr("save").color(NamedTextColor.GREEN)) { view, p ->
            if (!valid(p, session)) return@button
            val timeLimit = view.getText("time_limit")?.trim()?.toIntOrNull()
            val maxHeightText = view.getText("max_height")?.trim().orEmpty()
            val maxHeight = maxHeightText.toIntOrNull()
            val time = view.getText("time")?.trim()?.toLongOrNull()
            if (timeLimit == null || timeLimit < 0 || time == null || (maxHeightText.isNotEmpty() && maxHeight == null)) {
                p.sendMessage(tr("invalid-number").color(NamedTextColor.RED))
                openInfo(p, session)
                return@button
            }

            view.getText("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { map.name = it }
            view.getText("version")?.trim()?.takeIf { it.isNotEmpty() }?.let { map.version = it }
            map.authors.clear()
            map.authors += view.getText("authors").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            map.timeLimit = timeLimit
            map.maxBuildHeight = maxHeight
            map.time = time
            session.world.time = time
            changed(session)
            openMain(p, session)
        }
        val back = button(tr("back")) { _, p -> if (valid(p, session)) openMain(p, session) }

        show(player, tr("info.title"), listOf(tr("info.description")), inputs, listOf(save, back))
    }

    // ------------------------------------------------------------------
    // チーム
    // ------------------------------------------------------------------

    fun openTeams(player: Player, session: SetupManager.EditSession) {
        val map = session.map
        val buttons = mutableListOf<ActionButton>()
        for (team in map.teams) {
            val label = Component.text()
                .append(team.displayName())
                .append(Component.text(" (${team.spawns.size})", NamedTextColor.GRAY))
                .build()
            buttons += button(label, tr("teams.tooltip")) { _, p -> if (valid(p, session)) openTeam(p, session, team) }
        }
        buttons += button(tr("teams.add").color(NamedTextColor.GREEN)) { _, p ->
            if (!valid(p, session)) return@button
            val color = TeamColor.entries.firstOrNull { c -> map.teams.none { it.color == c } }
            if (color == null) {
                p.sendMessage(tr("teams.no-color").color(NamedTextColor.RED))
                openTeams(p, session)
                return@button
            }
            var id = color.name.lowercase()
            var suffix = 2
            while (map.team(id) != null) id = "${color.name.lowercase()}${suffix++}"
            val team = TeamDefinition(id, color, null, 0, mutableListOf())
            map.teams += team
            changed(session)
            openTeam(p, session, team)
        }
        buttons += button(tr("back")) { _, p -> if (valid(p, session)) openMain(p, session) }

        show(player, tr("teams.title"), listOf(tr("teams.description")), buttons = buttons)
    }

    fun openTeam(player: Player, session: SetupManager.EditSession, team: TeamDefinition) {
        val map = session.map
        val body = mutableListOf<Component>()
        body += tr("team.spawns", Component.text(team.spawns.size))
        body += tr("team.spawn-rooms", Component.text(team.spawnRooms.joinToString().ifEmpty { "-" }))
        team.spawns.forEachIndexed { index, spawn ->
            body += Component.text("#${index + 1}: $spawn", NamedTextColor.GRAY)
        }

        val inputs = listOf(
            text("id", tr("team.id"), team.id, 32),
            DialogInput.singleOption(
                "color",
                tr("team.color"),
                TeamColor.entries.map { SingleOptionDialogInput.OptionEntry.create(it.name, it.displayName(), it == team.color) },
            ).width(INPUT_WIDTH).build(),
            text("name", tr("team.name"), team.name ?: "", 32),
            text("max_players", tr("team.max-players"), team.maxPlayers.toString(), 4),
        )

        // フォームの内容を反映する。失敗したらメッセージを出して false
        fun apply(view: DialogResponseView, p: Player): Boolean {
            val id = view.getText("id")?.trim()?.lowercase().orEmpty()
            val maxPlayers = view.getText("max_players")?.trim()?.toIntOrNull()
            if (!MapDefinition.ID_PATTERN.matches(id) || (id != team.id && map.team(id) != null)) {
                p.sendMessage(tr("team.invalid-id", Component.text(id)).color(NamedTextColor.RED))
                return false
            }
            if (maxPlayers == null || maxPlayers < 0) {
                p.sendMessage(tr("invalid-number").color(NamedTextColor.RED))
                return false
            }
            if (id != team.id) {
                // 羊毛の参照も追従させる
                (map.settings as? CTWSettings)?.wools?.filter { it.team == team.id }?.forEach { it.team = id }
                team.id = id
            }
            view.getText("color")?.let { name -> TeamColor.parse(name)?.let { team.color = it } }
            team.name = view.getText("name")?.trim()?.takeIf { it.isNotEmpty() }
            team.maxPlayers = maxPlayers
            changed(session)
            return true
        }

        val buttons = listOf(
            button(tr("save").color(NamedTextColor.GREEN)) { view, p ->
                if (!valid(p, session)) return@button
                if (apply(view, p)) openTeams(p, session) else openTeam(p, session, team)
            },
            button(tr("team.add-spawn"), tr("team.add-spawn-tooltip")) { view, p ->
                if (!valid(p, session)) return@button
                if (apply(view, p)) {
                    team.spawns += SpawnPoint.of(p.location)
                    p.sendMessage(tr("team.spawn-added", team.displayName(), Component.text(team.spawns.size)).color(NamedTextColor.GREEN))
                }
                openTeam(p, session, team)
            },
            button(tr("team.remove-spawn"), tr("team.remove-spawn-tooltip")) { view, p ->
                if (!valid(p, session)) return@button
                if (apply(view, p)) {
                    team.spawns.minByOrNull { it.distanceSquared(p.location) }?.let { team.spawns.remove(it) }
                }
                openTeam(p, session, team)
            },
            button(tr("team.add-room"), tr("team.add-room-tooltip")) { view, p ->
                if (!valid(p, session)) return@button
                if (apply(view, p)) {
                    val region = SetupManager.selection(p).region()
                    if (region == null) {
                        p.sendMessage(tr("regions.no-selection").color(NamedTextColor.RED))
                    } else {
                        team.spawnRooms += region
                    }
                }
                openTeam(p, session, team)
            },
            button(tr("team.clear-rooms")) { view, p ->
                if (!valid(p, session)) return@button
                if (apply(view, p)) team.spawnRooms.clear()
                openTeam(p, session, team)
            },
            button(tr("team.teleport")) { _, p ->
                if (!valid(p, session)) return@button
                team.spawns.firstOrNull()?.let { p.teleport(it.toLocation(session.world)) }
            },
            button(tr("team.delete").color(NamedTextColor.RED)) { _, p ->
                if (!valid(p, session)) return@button
                map.teams.remove(team)
                (map.settings as? CTWSettings)?.wools?.removeIf { it.team == team.id }
                changed(session)
                openTeams(p, session)
            },
            button(tr("back")) { _, p -> if (valid(p, session)) openTeams(p, session) },
        )

        show(player, tr("team.title", team.displayName()), body, inputs, buttons)
    }

    // ------------------------------------------------------------------
    // 羊毛 (CTW)
    // ------------------------------------------------------------------

    fun openWools(player: Player, session: SetupManager.EditSession) {
        val map = session.map
        val settings = map.settings as? CTWSettings ?: return openMain(player, session)

        val buttons = mutableListOf<ActionButton>()
        for (wool in settings.wools) {
            val team = map.team(wool.team)
            val label = Component.text()
                .append(Component.text("■ ", WoolDefinition.woolColor(wool.color)))
                .append(team?.displayName() ?: Component.text(wool.team, NamedTextColor.GRAY))
                .build()
            val tooltip = Component.text()
                .append(wool.displayName())
                .append(Component.newline())
                .append(
                    wool.monument?.let { tr("wools.monument", Component.text(it.toString())) }
                        ?: tr("wools.no-monument").color(NamedTextColor.RED)
                )
                .build()
            buttons += button(label, tooltip) { _, p -> if (valid(p, session)) openWool(p, session, wool) }
        }
        buttons += button(tr("wools.add").color(NamedTextColor.GREEN)) { _, p ->
            if (!valid(p, session)) return@button
            val team = map.teams.firstOrNull()
            if (team == null) {
                p.sendMessage(tr("wools.no-team").color(NamedTextColor.RED))
                openWools(p, session)
                return@button
            }
            val color = DyeColor.entries.firstOrNull { c -> settings.wools.none { it.color == c } } ?: DyeColor.WHITE
            val wool = WoolDefinition(team.id, color, null)
            settings.wools += wool
            changed(session)
            openWool(p, session, wool)
        }
        buttons += button(tr("back")) { _, p -> if (valid(p, session)) openMain(p, session) }

        show(player, tr("wools.title"), listOf(tr("wools.description")), buttons = buttons)
    }

    fun openWool(player: Player, session: SetupManager.EditSession, wool: WoolDefinition) {
        val map = session.map
        val settings = map.settings as? CTWSettings ?: return openMain(player, session)

        val body = listOf(
            tr("wool.description"),
            tr("wool.room", Component.text(wool.room?.toString() ?: "-")),
            tr("wool.spawners", Component.text(wool.spawners.joinToString().ifEmpty { "-" })),
            wool.monument?.let { tr("wools.monument", Component.text(it.toString())) }
                ?: tr("wools.no-monument").color(NamedTextColor.RED),
        )

        val inputs = listOf(
            DialogInput.singleOption(
                "team",
                tr("wool.team"),
                map.teams.map { SingleOptionDialogInput.OptionEntry.create(it.id, it.displayName(), it.id == wool.team) },
            ).width(INPUT_WIDTH).build(),
            DialogInput.singleOption(
                "color",
                tr("wool.color"),
                DyeColor.entries.map {
                    SingleOptionDialogInput.OptionEntry.create(it.name, WoolDefinition.woolName(it), it == wool.color)
                },
            ).width(INPUT_WIDTH).build(),
        )

        fun apply(view: DialogResponseView) {
            view.getText("team")?.let { id -> if (map.team(id) != null) wool.team = id }
            view.getText("color")?.let { name -> WoolDefinition.parseColor(name)?.let { wool.color = it } }
            changed(session)
        }

        fun setMonument(p: Player, adjacent: Boolean) {
            val result = p.rayTraceBlocks(8.0)
            val hit = result?.hitBlock
            if (hit == null) {
                p.sendMessage(tr("wool.no-target").color(NamedTextColor.RED))
                return
            }
            val block = if (adjacent) hit.getRelative(result.hitBlockFace ?: org.bukkit.block.BlockFace.UP) else hit
            wool.monument = BlockPos.of(block)
            p.sendMessage(tr("wool.monument-set", Component.text(wool.monument.toString())).color(NamedTextColor.GREEN))
        }

        val buttons = listOf(
            button(tr("save").color(NamedTextColor.GREEN)) { view, p ->
                if (!valid(p, session)) return@button
                apply(view)
                openWools(p, session)
            },
            button(tr("wool.monument-adjacent"), tr("wool.monument-adjacent-tooltip")) { view, p ->
                if (!valid(p, session)) return@button
                apply(view)
                setMonument(p, adjacent = true)
                openWool(p, session, wool)
            },
            button(tr("wool.monument-target"), tr("wool.monument-target-tooltip")) { view, p ->
                if (!valid(p, session)) return@button
                apply(view)
                setMonument(p, adjacent = false)
                openWool(p, session, wool)
            },
            button(tr("wool.set-room"), tr("wool.set-room-tooltip")) { view, p ->
                if (!valid(p, session)) return@button
                apply(view)
                val region = SetupManager.selection(p).region()
                if (region == null) {
                    p.sendMessage(tr("regions.no-selection").color(NamedTextColor.RED))
                } else {
                    wool.room = region
                }
                openWool(p, session, wool)
            },
            button(tr("wool.clear-room")) { view, p ->
                if (!valid(p, session)) return@button
                apply(view)
                wool.room = null
                openWool(p, session, wool)
            },
            button(tr("wool.add-spawner"), tr("wool.add-spawner-tooltip")) { view, p ->
                if (!valid(p, session)) return@button
                apply(view)
                val region = SetupManager.selection(p).region()
                if (region == null) {
                    p.sendMessage(tr("regions.no-selection").color(NamedTextColor.RED))
                } else {
                    wool.spawners += region
                }
                openWool(p, session, wool)
            },
            button(tr("wool.clear-spawners")) { view, p ->
                if (!valid(p, session)) return@button
                apply(view)
                wool.spawners.clear()
                openWool(p, session, wool)
            },
            button(tr("wool.teleport")) { _, p ->
                if (!valid(p, session)) return@button
                wool.monument?.let { p.teleport(it.toCenter(session.world).add(0.0, 1.0, 0.0)) }
            },
            button(tr("wool.delete").color(NamedTextColor.RED)) { _, p ->
                if (!valid(p, session)) return@button
                settings.wools.remove(wool)
                changed(session)
                openWools(p, session)
            },
            button(tr("back")) { _, p -> if (valid(p, session)) openWools(p, session) },
        )

        show(player, tr("wool.title", wool.displayName()), body, inputs, buttons)
    }

    // ------------------------------------------------------------------
    // 建築禁止エリア
    // ------------------------------------------------------------------

    fun openRegions(player: Player, session: SetupManager.EditSession) {
        val map = session.map
        val selection = SetupManager.selection(player)

        val body = mutableListOf<Component>()
        body += tr("regions.description")
        body += tr(
            "regions.selection",
            Component.text(selection.pos1?.toString() ?: "-"),
            Component.text(selection.pos2?.toString() ?: "-"),
        ).color(NamedTextColor.LIGHT_PURPLE)

        val buttons = mutableListOf<ActionButton>()
        buttons += button(tr("regions.add").color(NamedTextColor.GREEN)) { _, p ->
            if (!valid(p, session)) return@button
            val region = SetupManager.selection(p).region()
            if (region == null) {
                p.sendMessage(tr("regions.no-selection").color(NamedTextColor.RED))
            } else {
                map.noBuildRegions += region
                changed(session)
            }
            openRegions(p, session)
        }
        buttons += button(tr("regions.wand")) { _, p ->
            if (!valid(p, session)) return@button
            SetupManager.giveTools(p)
        }
        map.noBuildRegions.forEachIndexed { index, region ->
            buttons += button(
                tr("regions.delete", Component.text(index + 1)).color(NamedTextColor.RED),
                Component.text(region.toString()),
            ) { _, p ->
                if (!valid(p, session)) return@button
                map.noBuildRegions.remove(region)
                changed(session)
                openRegions(p, session)
            }
        }
        buttons += button(tr("back")) { _, p -> if (valid(p, session)) openMain(p, session) }

        show(player, tr("regions.title"), body, buttons = buttons)
    }

    // ------------------------------------------------------------------
    // キット
    // ------------------------------------------------------------------

    fun openKit(player: Player, session: SetupManager.EditSession) {
        val kit = session.map.kit
        val body = mutableListOf<Component>()
        body += tr("kit.description")
        body += tr("kit.count", Component.text(kit.items.size))

        val inputs = listOf(
            DialogInput.bool("colored_armor", tr("kit.colored-armor")).initial(kit.teamColoredArmor).build(),
        )

        val buttons = listOf(
            button(tr("kit.copy").color(NamedTextColor.GREEN), tr("kit.copy-tooltip")) { view, p ->
                if (!valid(p, session)) return@button
                kit.copyFrom(p) { SetupManager.toolOf(it) == null }
                kit.teamColoredArmor = view.getBoolean("colored_armor") ?: kit.teamColoredArmor
                changed(session)
                p.sendMessage(tr("kit.copied", Component.text(kit.items.size)).color(NamedTextColor.GREEN))
                openKit(p, session)
            },
            button(tr("kit.give")) { view, p ->
                if (!valid(p, session)) return@button
                kit.teamColoredArmor = view.getBoolean("colored_armor") ?: kit.teamColoredArmor
                kit.apply(p, session.map.teams.firstOrNull()?.color)
                SetupManager.giveTools(p)
                openKit(p, session)
            },
            button(tr("save").color(NamedTextColor.GREEN)) { view, p ->
                if (!valid(p, session)) return@button
                val colored = view.getBoolean("colored_armor") ?: kit.teamColoredArmor
                if (colored != kit.teamColoredArmor) {
                    kit.teamColoredArmor = colored
                    changed(session)
                }
                openMain(p, session)
            },
            button(tr("back")) { _, p -> if (valid(p, session)) openMain(p, session) },
        )

        show(player, tr("kit.title"), body, inputs, buttons)
    }
}
