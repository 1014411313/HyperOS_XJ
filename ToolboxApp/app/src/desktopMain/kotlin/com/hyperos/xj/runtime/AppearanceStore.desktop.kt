package com.hyperos.xj.runtime

import com.hyperos.xj.ui.AppearanceSettings
import java.io.File
import java.util.Properties

actual object AppearanceStore {
    private val file = File(System.getProperty("user.home"), ".hyperos_xj_appearance.properties")

    actual fun load(): AppearanceSettings {
        if (!file.isFile) return AppearanceSettings()
        val values = runCatching {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            props.mapNotNull { (key, value) ->
                value?.let { key.toString() to it.toString() }
            }.toMap()
        }.getOrNull() ?: return AppearanceSettings()
        return appearanceFromMap(values)
    }

    actual fun save(settings: AppearanceSettings) {
        val props = Properties()
        for ((key, value) in settings.toPersistentMap()) {
            props.setProperty(key, value)
        }
        runCatching {
            file.outputStream().use { props.store(it, null) }
        }
    }
}
