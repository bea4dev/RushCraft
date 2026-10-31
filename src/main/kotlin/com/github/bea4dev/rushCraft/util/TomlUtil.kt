package com.github.bea4dev.rushCraft.util

import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.math.BigDecimal
import java.math.RoundingMode

class TomlFormatException(message: String) : Exception(message)

// tomlj の getXxx(String) はキーをドット区切りとして解釈し、型違いで例外を投げるため、
// ここでは単一キーで取得して数値型の違い (Long / Double) も吸収する。

private fun TomlTable.raw(key: String): Any? = get(listOf(key))

fun TomlTable.optString(key: String): String? = raw(key) as? String

fun TomlTable.string(key: String, default: String): String = optString(key) ?: default

fun TomlTable.requireString(key: String): String {
    return optString(key) ?: throw TomlFormatException("'$key' (string) is required")
}

fun TomlTable.optInt(key: String): Int? = (raw(key) as? Number)?.toInt()

fun TomlTable.int(key: String, default: Int): Int = optInt(key) ?: default

fun TomlTable.requireInt(key: String): Int {
    return optInt(key) ?: throw TomlFormatException("'$key' (integer) is required")
}

fun TomlTable.optLong(key: String): Long? = (raw(key) as? Number)?.toLong()

fun TomlTable.optDouble(key: String): Double? = (raw(key) as? Number)?.toDouble()

fun TomlTable.requireDouble(key: String): Double {
    return optDouble(key) ?: throw TomlFormatException("'$key' (number) is required")
}

fun TomlTable.bool(key: String, default: Boolean): Boolean = raw(key) as? Boolean ?: default

fun TomlTable.optTable(key: String): TomlTable? = raw(key) as? TomlTable

fun TomlTable.requireTable(key: String): TomlTable {
    return optTable(key) ?: throw TomlFormatException("'$key' (table) is required")
}

fun TomlTable.tables(key: String): List<TomlTable> {
    val array = raw(key) as? TomlArray ?: return emptyList()
    return (0 until array.size()).mapNotNull { array.get(it) as? TomlTable }
}

fun TomlTable.strings(key: String): List<String> {
    val array = raw(key) as? TomlArray ?: return emptyList()
    return (0 until array.size()).mapNotNull { array.get(it) as? String }
}

/**
 * RushCraft のファイルを書き出すための最小限の TOML ライター。
 *
 * 値には String / Boolean / Int / Long / Double / Float / List / Map (インラインテーブル) を使える。
 */
class TomlWriter {
    private val builder = StringBuilder()

    fun comment(text: String) = apply {
        builder.append("# ").append(text).append('\n')
    }

    fun blank() = apply {
        builder.append('\n')
    }

    fun table(name: String) = apply {
        separate()
        builder.append('[').append(name).append("]\n")
    }

    fun arrayTable(name: String) = apply {
        separate()
        builder.append("[[").append(name).append("]]\n")
    }

    fun value(key: String, value: Any) = apply {
        builder.append(key(key)).append(" = ").append(format(value, multiline = true)).append('\n')
    }

    private fun separate() {
        if (builder.isNotEmpty() && !builder.endsWith("\n\n")) {
            builder.append('\n')
        }
    }

    override fun toString(): String = builder.toString()

    companion object {
        private val BARE_KEY = Regex("[A-Za-z0-9_-]+")

        fun key(key: String): String = if (BARE_KEY.matches(key)) key else quote(key)

        fun format(value: Any?, multiline: Boolean = false): String {
            return when (value) {
                is String -> quote(value)
                is Boolean, is Int, is Long -> value.toString()
                is Float -> formatDouble(value.toDouble())
                is Double -> formatDouble(value)
                is Map<*, *> -> value.entries.joinToString(", ", "{ ", " }") { (k, v) ->
                    "${key(k.toString())} = ${format(v)}"
                }

                is List<*> -> if (multiline && value.any { it is Map<*, *> }) {
                    value.joinToString("", "[\n", "]") { "    ${format(it)},\n" }
                } else {
                    value.joinToString(", ", "[", "]") { format(it) }
                }

                else -> throw IllegalArgumentException("Unsupported TOML value: $value")
            }
        }

        private fun formatDouble(value: Double): String {
            val text = BigDecimal(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            val normalized = if (text == "-0") "0" else text
            return if ('.' in normalized) normalized else "$normalized.0"
        }

        private fun quote(value: String): String {
            val escaped = StringBuilder()
            for (char in value) {
                when (char) {
                    '"' -> escaped.append("\\\"")
                    '\\' -> escaped.append("\\\\")
                    '\n' -> escaped.append("\\n")
                    '\r' -> escaped.append("\\r")
                    '\t' -> escaped.append("\\t")
                    else -> if (char.code < 0x20) {
                        escaped.append("\\u%04x".format(char.code))
                    } else {
                        escaped.append(char)
                    }
                }
            }
            return "\"$escaped\""
        }
    }
}
