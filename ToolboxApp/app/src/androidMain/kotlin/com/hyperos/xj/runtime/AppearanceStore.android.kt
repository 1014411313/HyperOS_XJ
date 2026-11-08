package com.hyperos.xj.runtime

import android.content.Context
import com.hyperos.xj.ui.AppearanceSettings

actual object AppearanceStore {
    // androidApp 入口不可改（无法注册 Application），经 ActivityThread 反射取 Application Context；取不到时退回内存默认值
    private val appContext: Context? by lazy {
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()
    }

    actual fun load(): AppearanceSettings {
        val prefs = appContext
            ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: return AppearanceSettings()
        val values = prefs.all.mapNotNull { (key, value) ->
            value?.let { key to it.toString() }
        }.toMap()
        return appearanceFromMap(values)
    }

    actual fun save(settings: AppearanceSettings) {
        val prefs = appContext
            ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: return
        val editor = prefs.edit()
        for ((key, value) in settings.toPersistentMap()) {
            editor.putString(key, value)
        }
        editor.apply()
    }

    private const val PREFS_NAME = "xj_appearance"
}
