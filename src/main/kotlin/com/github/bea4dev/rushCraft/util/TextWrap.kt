package com.github.bea4dev.rushCraft.util

/**
 * アイテムの説明 (lore) 用の折り返し。全角文字は半角 2 文字分として数える。
 */
object TextWrap {
    /** 行頭に置かない文字 */
    private const val NO_LINE_START = "、。，．,.)）」』!?！？ー"

    fun wrap(text: String, width: Int): List<String> {
        return text.split('\n').flatMap { wrapLine(it, width) }
    }

    private fun wrapLine(text: String, width: Int): List<String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var lineWidth = 0

        for (token in tokens(text)) {
            val tokenWidth = token.sumOf(::charWidth)
            val fits = lineWidth + tokenWidth <= width
            val allowedAtStart = token.firstOrNull()?.let { it !in NO_LINE_START } ?: true
            if (!fits && line.isNotEmpty() && allowedAtStart) {
                lines += line.toString().trimEnd()
                line.clear()
                lineWidth = 0
                if (token.isBlank()) continue
            }
            line.append(token)
            lineWidth += tokenWidth
        }
        if (line.isNotEmpty() || lines.isEmpty()) lines += line.toString().trimEnd()
        return lines
    }

    /** 半角の英単語はまとめ、全角文字は 1 文字ずつ区切る */
    private fun tokens(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val word = StringBuilder()
        for (char in text) {
            when {
                char == ' ' -> {
                    if (word.isNotEmpty()) tokens += word.toString().also { word.clear() }
                    tokens += " "
                }

                charWidth(char) == 1 -> word.append(char)
                else -> {
                    if (word.isNotEmpty()) tokens += word.toString().also { word.clear() }
                    tokens += char.toString()
                }
            }
        }
        if (word.isNotEmpty()) tokens += word.toString()
        return tokens
    }

    private fun charWidth(char: Char): Int = if (char.code < 0x1100 || char in '｡'..'ￜ') 1 else 2
}
