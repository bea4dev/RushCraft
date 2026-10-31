package com.github.bea4dev.rushCraft.match

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.event.Listener

interface MatchGameMode<G : MatchGameMode<G>> {
    fun createObjective(): MatchObjective<G>
    fun getNameKey(): String

    /**
     * サイドバーに表示するルール固有の試合状況。
     * 待機中・試合中・試合後のすべてで表示される。
     */
    fun getScoreboardLines(match: Match<G>): List<Component>

    /** ワールドとチームの準備ができたとき (待機開始前) */
    fun onLoad(match: Match<G>) {}

    fun onStart(match: Match<G>) {}

    fun onTick(match: Match<G>) {}

    fun onEnd(match: Match<G>) {}

    /** 試合中にチームの参加者がその位置に入れるか (スポーン部屋の判定はこれとは別に行われる) */
    fun canEnter(match: Match<G>, team: MatchTeam, location: Location): Boolean = true

    /** 試合が存在する間だけ登録されるリスナー */
    fun createListener(match: Match<G>): Listener? = null
}
