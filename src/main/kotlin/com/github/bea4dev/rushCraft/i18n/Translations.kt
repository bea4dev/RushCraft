package com.github.bea4dev.rushCraft.i18n

import net.kyori.adventure.key.Key
import net.kyori.adventure.translation.GlobalTranslator
import net.kyori.adventure.translation.TranslationStore
import org.bukkit.plugin.java.JavaPlugin
import org.tomlj.Toml
import java.text.MessageFormat
import java.util.Locale

object Translations {
    const val NAMESPACE = "rushcraft"

    private const val LANG_DIR = "lang"
    private val DEFAULT_LOCALE: Locale = Locale.US

    private val SUPPORTED_LOCALES = listOf(
        Locale.US,
        Locale.JAPAN,
    )

    private var store: TranslationStore.StringBased<MessageFormat>? = null

    fun register(plugin: JavaPlugin) {
        unregister()

        val newStore = TranslationStore.messageFormat(Key.key(NAMESPACE, "main"))
        newStore.defaultLocale(DEFAULT_LOCALE)

        for (locale in SUPPORTED_LOCALES) {
            loadLocale(plugin, locale, newStore)
        }

        GlobalTranslator.translator().addSource(newStore)
        store = newStore
    }

    fun unregister() {
        store?.let { GlobalTranslator.translator().removeSource(it) }
        store = null
    }

    private fun loadLocale(plugin: JavaPlugin, locale: Locale, store: TranslationStore.StringBased<MessageFormat>) {
        val tag = locale.toLanguageTag().replace('-', '_')
        val resourcePath = "$LANG_DIR/$tag.toml"
        val stream = plugin.getResource(resourcePath)
        if (stream == null) {
            plugin.logger.warning("Translation file not found: $resourcePath")
            return
        }

        val result = stream.use { Toml.parse(it) }
        if (result.hasErrors()) {
            result.errors().forEach { plugin.logger.warning("[$resourcePath] $it") }
            return
        }

        val translations = mutableMapOf<String, MessageFormat>()
        for (dottedKey in result.dottedKeySet()) {
            val value = result.getString(dottedKey)
            if (value == null) {
                plugin.logger.warning("[$resourcePath] non-string value at '$dottedKey' was skipped")
                continue
            }
            translations["$NAMESPACE.$dottedKey"] = MessageFormat(value, locale)
        }
        store.registerAll(locale, translations)
    }
}
