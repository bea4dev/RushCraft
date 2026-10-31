package com.github.bea4dev.rushCraft.util

import com.github.bea4dev.rushCraft.RushCraft
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.body.ItemDialogBody
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody
import io.papermc.paper.registry.data.dialog.input.BooleanDialogInput
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.input.TextDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.translation.GlobalTranslator
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.Duration

/**
 * Paper の Dialog を GUI として使うための共通処理。
 */
object Dialogs {
    private val CALLBACK_OPTIONS = ClickCallback.Options.builder()
        .uses(1)
        .lifetime(Duration.ofMinutes(30))
        .build()

    const val BODY_WIDTH = 320
    const val BUTTON_WIDTH = 150
    const val INPUT_WIDTH = 300

    /**
     * 押されたときにメインスレッドで action を実行するボタン。
     */
    fun button(
        label: Component,
        tooltip: Component? = null,
        width: Int = BUTTON_WIDTH,
        action: (DialogResponseView, Player) -> Unit,
    ): ActionButton {
        val callback = DialogAction.customClick({ view, audience ->
            val player = audience as? Player ?: return@customClick
            val run = Runnable { if (player.isOnline) action(view, player) }
            if (Bukkit.isPrimaryThread()) run.run() else Bukkit.getScheduler().runTask(RushCraft.plugin, run)
        }, CALLBACK_OPTIONS)
        return ActionButton.builder(label).tooltip(tooltip).width(width).action(callback).build()
    }

    /** 何もせずに閉じるボタン */
    fun closeButton(label: Component): ActionButton = ActionButton.builder(label).width(BUTTON_WIDTH).build()

    fun show(
        player: Player,
        title: Component,
        body: List<Component>,
        inputs: List<DialogInput> = emptyList(),
        buttons: List<ActionButton>,
        exit: ActionButton? = null,
        columns: Int = 2,
    ) {
        showBodies(player, title, body.map { DialogBody.plainMessage(it, BODY_WIDTH) }, inputs, buttons, exit, columns)
    }

    /**
     * Dialog 内の Component は GlobalTranslator で翻訳されないため、
     * プレイヤーの言語でここで描画してから送る。
     * (アイテムの名前は描画しないので、必要なら呼び出し側で描画しておく)
     */
    fun showBodies(
        player: Player,
        title: Component,
        body: List<DialogBody>,
        inputs: List<DialogInput> = emptyList(),
        buttons: List<ActionButton>,
        exit: ActionButton? = null,
        columns: Int = 2,
    ) {
        val locale = player.locale()
        fun render(component: Component): Component = GlobalTranslator.render(component, locale)

        fun render(message: PlainMessageDialogBody): PlainMessageDialogBody {
            return DialogBody.plainMessage(render(message.contents()), message.width())
        }

        fun render(body: DialogBody): DialogBody {
            return when (body) {
                is PlainMessageDialogBody -> render(body)
                is ItemDialogBody -> DialogBody.item(body.item())
                    .description(body.description()?.let(::render))
                    .showDecorations(body.showDecorations())
                    .showTooltip(body.showTooltip())
                    .width(body.width())
                    .height(body.height())
                    .build()
            }
        }

        fun render(button: ActionButton): ActionButton {
            return ActionButton.builder(render(button.label()))
                .tooltip(button.tooltip()?.let(::render))
                .width(button.width())
                .action(button.action())
                .build()
        }

        fun render(input: DialogInput): DialogInput {
            return when (input) {
                is TextDialogInput -> DialogInput.text(input.key(), render(input.label()))
                    .width(input.width())
                    .labelVisible(input.labelVisible())
                    .initial(input.initial())
                    .maxLength(input.maxLength())
                    .multiline(input.multiline())
                    .build()

                is BooleanDialogInput -> DialogInput.bool(input.key(), render(input.label()))
                    .initial(input.initial())
                    .onTrue(input.onTrue())
                    .onFalse(input.onFalse())
                    .build()

                is SingleOptionDialogInput -> DialogInput.singleOption(
                    input.key(),
                    render(input.label()),
                    input.entries().map {
                        SingleOptionDialogInput.OptionEntry.create(it.id(), it.display()?.let(::render), it.initial())
                    },
                ).width(input.width()).labelVisible(input.labelVisible()).build()

                else -> input
            }
        }

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(render(title))
                        .canCloseWithEscape(true)
                        .body(body.map(::render))
                        .inputs(inputs.map(::render))
                        .build()
                )
                .type(
                    DialogType.multiAction(buttons.map(::render))
                        .columns(columns)
                        .exitAction(exit?.let(::render))
                        .build()
                )
        }
        player.showDialog(dialog)
    }
}
