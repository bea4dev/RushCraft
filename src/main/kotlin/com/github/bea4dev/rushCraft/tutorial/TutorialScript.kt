package com.github.bea4dev.rushCraft.tutorial

/**
 * チュートリアルの台本。Kotlin の sequence を使い、待ちを yield しながら上から順に書く。
 *
 * ```
 * step("intro") {
 *     session.say("...")
 *     wait(20)
 *     waitUntil { session.count("kill") >= 3 }
 * }
 * ```
 */
typealias Script = suspend SequenceScope<TutorialWait>.() -> Unit

/** 台本を先へ進めてよいかを毎 tick 調べる */
fun interface TutorialWait {
    fun isDone(): Boolean
}

private class TicksWait(private var remaining: Int) : TutorialWait {
    override fun isDone(): Boolean = --remaining <= 0
}

/** ticks だけ待つ */
suspend fun SequenceScope<TutorialWait>.wait(ticks: Int) {
    if (ticks > 0) yield(TicksWait(ticks))
}

/** condition を満たすまで待つ */
suspend fun SequenceScope<TutorialWait>.waitUntil(condition: () -> Boolean) {
    if (!condition()) yield(TutorialWait(condition))
}

/**
 * チュートリアルの 1 段階。
 *
 * @param id やり直しの戻り先の指定に使う
 * @param retryFrom やられたときにやり直す段階の id (null ならこの段階)
 */
class TutorialStep(val id: String, val retryFrom: String? = null, val script: Script)

/** 台本を 1 tick ずつ進める */
class ScriptRunner(script: Script) {
    private val iterator = iterator(script)
    private var current: TutorialWait? = null

    var isFinished = false
        private set

    fun tick() {
        if (isFinished) return
        val wait = current
        if (wait != null && !wait.isDone()) return
        // 次の待ちまで台本を進める (新しい待ちは次の tick から調べる)
        if (iterator.hasNext()) {
            current = iterator.next()
        } else {
            isFinished = true
            current = null
        }
    }
}
