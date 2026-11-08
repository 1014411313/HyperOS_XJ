package com.hyperos.xj.runtime

import com.hyperos.xj.ui.AppearanceSettings
import com.hyperos.xj.ui.GlassMode

expect object AppearanceStore {
    fun load(): AppearanceSettings
    fun save(settings: AppearanceSettings)
}

internal fun AppearanceSettings.toPersistentMap(): Map<String, String> = mapOf(
    "themeIndex" to themeIndex.toString(),
    "glassMode" to glassMode.name,
    "fontScale" to fontScale.toString(),
    "cardScale" to cardScale.toString(),
    "cardCorner" to cardCorner.toString(),
    "cardAlpha" to cardAlpha.toString(),
    "cardBlur" to cardBlur.toString(),
    "cardDistortion" to cardDistortion.toString(),
    "cardTexture" to cardTexture.toString(),
    "smoothMode" to smoothMode.toString(),
    "hideBarText" to hideBarText.toString(),
    "barWidth" to barWidth.toString(),
    "barHeight" to barHeight.toString(),
    "barAlpha" to barAlpha.toString(),
    "barSolid" to barSolid.toString(),
    "barColorIndex" to barColorIndex.toString(),
    "barBlur" to barBlur.toString(),
    "wallpaperIndex" to wallpaperIndex.toString(),
    "bgBlur" to bgBlur.toString(),
    "customBg" to customBg.toString(),
    // bgImage（ImageBitmap）不持久化：位图不可序列化，重启后需重新选择
)

internal fun appearanceFromMap(map: Map<String, String>): AppearanceSettings =
    AppearanceSettings(
        themeIndex = map["themeIndex"]?.toIntOrNull() ?: 0,
        glassMode = map["glassMode"]?.let { name ->
            GlassMode.entries.firstOrNull { it.name == name }
        } ?: GlassMode.Liquid,
        fontScale = map["fontScale"]?.toFloatOrNull() ?: 1f,
        cardScale = map["cardScale"]?.toFloatOrNull() ?: 1f,
        cardCorner = map["cardCorner"]?.toFloatOrNull() ?: 25f,
        cardAlpha = map["cardAlpha"]?.toFloatOrNull() ?: 0.06f,
        cardBlur = map["cardBlur"]?.toFloatOrNull() ?: 0f,
        cardDistortion = map["cardDistortion"]?.toFloatOrNull() ?: 36f,
        cardTexture = map["cardTexture"]?.toBooleanStrictOrNull() ?: true,
        smoothMode = map["smoothMode"]?.toBooleanStrictOrNull() ?: false,
        hideBarText = map["hideBarText"]?.toBooleanStrictOrNull() ?: false,
        barWidth = map["barWidth"]?.toFloatOrNull() ?: 84f,
        barHeight = map["barHeight"]?.toFloatOrNull() ?: 60f,
        barAlpha = map["barAlpha"]?.toFloatOrNull() ?: 0.25f,
        barSolid = map["barSolid"]?.toFloatOrNull() ?: 0f,
        barColorIndex = map["barColorIndex"]?.toIntOrNull() ?: 0,
        barBlur = map["barBlur"]?.toFloatOrNull() ?: 3f,
        wallpaperIndex = map["wallpaperIndex"]?.toIntOrNull() ?: 0,
        bgBlur = map["bgBlur"]?.toFloatOrNull() ?: 2f,
        customBg = map["customBg"]?.toBooleanStrictOrNull() ?: false,
        bgImage = null,
    )
