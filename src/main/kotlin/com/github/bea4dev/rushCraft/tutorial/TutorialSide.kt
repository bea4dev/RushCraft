package com.github.bea4dev.rushCraft.tutorial

import com.github.bea4dev.rushCraft.match.TeamColor

/**
 * チュートリアルの陣営。プレイヤーとお手本が味方 (赤)、ダミーが敵 (青)。
 * 敵味方の判定は同じセッションの中だけで行う (Combatants.isEnemy)。
 */
class TutorialSide(val session: TutorialSession, val enemy: Boolean) {
    val color: TeamColor
        get() = if (enemy) ENEMY_COLOR else ALLY_COLOR

    companion object {
        val ALLY_COLOR = TeamColor.RED
        val ENEMY_COLOR = TeamColor.BLUE
    }
}
